package net.djvk.fireflyPlaidConnector2.pairing

import net.djvk.fireflyPlaidConnector2.transactions.dayOfMonthWithin
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Round-1 review fixes in the engine (T1, T5, T7, L1, L2, H1): thresholds pinned without the private gate, the guards
 * that only the connector enforces, and the rejected-pair exclusion.
 */
internal class PairEngineGuardsTest {
    private val o2 = PairAccount(1, "Checking", "Old Second")
    private val sofi = PairAccount(9, "SoFi Checking", "SoFi")
    private val vault = PairAccount(10, "Emergency Fund", "SoFi", null, setOf("vault"))
    private val chase = PairAccount(8, "CREDIT CARD ..0325", "Chase", "0325", setOf("card"))
    private val imagine = PairAccount(7, "IMAGINE REWARDS", "DCU", "0836", setOf("card"))
    private val all = listOf(o2, sofi, vault, chase, imagine)
    private val day = LocalDate.of(2025, 8, 18)

    private fun out(account: PairAccount, text: String, cents: Long = 10_000, date: LocalDate = day, id: String = "o") =
        Leg(id, account.id, Dir.OUT, date, cents, text, currency = "USD")

    private fun inn(account: PairAccount, text: String, cents: Long = 10_000, date: LocalDate = day, id: String = "i") =
        Leg(id, account.id, Dir.IN, date, cents, text, currency = "USD")

    private fun engine(config: PairingConfig = PairingConfig(), rejected: Set<Pair<String, String>> = setOf()) = PairEngine(all, config, rejected)

    // region T1: the boundaries of autoMin and reviewMin

    /** Marker plus date only: one family, so never auto. Score 6 (out-marker 2, in-marker 2, date-gap 2). */
    private val onlyMarkers = out(o2, "AC ACH PULL 123") to inn(chase, "Payment Thank You")

    /** Marker plus named destination: two families. */
    private val twoFamilies = out(o2, "AC CHASE CREDIT CRD AUTOPAY") to inn(chase, "Payment Thank You")

    @Test
    fun theDefaultsAreTheCalibratedAutoMin4AndReviewMin3() {
        assertThat(PairingConfig().autoMin).isEqualTo(4)
        assertThat(PairingConfig().reviewMin).isEqualTo(3)
    }

    @Test
    fun aPairIsAutoExactlyAtAutoMinAndNotOnePointBelow() {
        val score = engine().evaluate(twoFamilies.first, twoFamilies.second)!!.score
        val edge = engine().evaluate(twoFamilies.first, twoFamilies.second)!!
        assertThat(edge.families).hasSizeGreaterThanOrEqualTo(2)
        assertThat(PairEngine(all, PairingConfig(autoMin = score)).isAuto(edge)).isTrue()
        assertThat(PairEngine(all, PairingConfig(autoMin = score + 1)).isAuto(edge)).isFalse()
    }

    @Test
    fun aPairWithOneFamilyIsNeverAutoHoweverHighItScores() {
        val edge = engine().evaluate(onlyMarkers.first, onlyMarkers.second)!!
        assertThat(edge.families).hasSize(1)
        assertThat(PairEngine(all, PairingConfig(autoMin = 0)).isAuto(edge)).isFalse()
    }

    @Test
    fun aPairIsReviewedExactlyAtReviewMinAndUnmatchedOnePointBelow() {
        val edge = engine().evaluate(onlyMarkers.first, onlyMarkers.second)!!
        val at = PairEngine(all, PairingConfig(autoMin = 100, reviewMin = edge.score)).decide(listOf(onlyMarkers.first, onlyMarkers.second))
        assertThat(at.proposals.single().auto).isFalse()
        assertThat(at.unmatched).isEmpty()
        val below = PairEngine(all, PairingConfig(autoMin = 100, reviewMin = edge.score + 1)).decide(listOf(onlyMarkers.first, onlyMarkers.second))
        assertThat(below.proposals).isEmpty()
        assertThat(below.unmatched).hasSize(1)
    }

    // endregion

    // region T5 and T7: guards only the connector enforces

    @Test
    fun aScheduledFlowToAnotherAccountVetoesASameAmountPair() {
        val flow = ScheduledFlow(from = o2.id, to = imagine.id, cents = 10_000, dayOfMonth = 18)
        val e = engine(PairingConfig(scheduled = listOf(flow)))
        val toOther = e.evaluate(out(o2, "AC X"), inn(chase, "Payment Thank You"))!!
        assertThat(toOther.veto).isEqualTo("schedule-contradiction")
        val toTheScheduled = e.evaluate(out(o2, "AC X"), inn(imagine, "Payment Thank You"))!!
        assertThat(toTheScheduled.veto).isNull()
        assertThat(toTheScheduled.points.map { it.rule }).contains("scheduled")
    }

    @Test
    fun aScheduledFlowOnlyBindsTheSameAccountTheSameAmountAndTheRightDay() {
        fun veto(flow: ScheduledFlow, o: Leg) = engine(PairingConfig(scheduled = listOf(flow))).evaluate(o, inn(chase, "Payment Thank You", o.cents, o.date))!!.veto
        val base = ScheduledFlow(from = o2.id, to = imagine.id, cents = 10_000, dayOfMonth = 18)
        assertThat(veto(base, out(o2, "AC X"))).isEqualTo("schedule-contradiction")
        assertThat(veto(base.copy(from = sofi.id), out(o2, "AC X"))).isNull() // another account's flow
        assertThat(veto(base.copy(cents = 20_000), out(o2, "AC X"))).isNull() // another amount
        assertThat(veto(base, out(o2, "AC X", date = day.plusDays(10)))).isNull() // far from the day
        assertThat(veto(base.copy(dayOfMonth = null), out(o2, "AC X"))).isEqualTo("schedule-contradiction") // any day
    }

    @Test
    fun aScheduledFlowOnTheThirtiethMeetsAnOutflowOnTheSecondOfTheNextMonth() {
        // L2: the day tolerance wraps the month, as routing does
        val flow = ScheduledFlow(from = o2.id, to = imagine.id, cents = 10_000, dayOfMonth = 30, toleranceDays = 3)
        val o = out(o2, "AC X", date = LocalDate.of(2025, 9, 2))
        val e = engine(PairingConfig(scheduled = listOf(flow)))
        assertThat(e.evaluate(o, inn(chase, "Payment Thank You", date = o.date))!!.veto).isEqualTo("schedule-contradiction")
        assertThat(e.oneSided(o).map { it.rule }).contains("scheduled")
    }

    @Test
    fun theDayToleranceWrapsMonthsAndClipsToTheLastDay() {
        assertThat(dayOfMonthWithin(LocalDate.of(2025, 9, 2), 30, 3)).isTrue()
        assertThat(dayOfMonthWithin(LocalDate.of(2025, 9, 3), 30, 3)).isFalse()
        assertThat(dayOfMonthWithin(LocalDate.of(2025, 2, 28), 31, 0)).isTrue() // the 31st is the 28th in February
        assertThat(dayOfMonthWithin(LocalDate.of(2025, 8, 29), 1, 3)).isTrue() // the 1st of next month
    }

    @Test
    fun aCarryOverOnEitherLegIsNeverACandidate() {
        val text = "LAST STATEMENT BAL FROM ACCT ENDING 1234"
        assertThat(engine().evaluate(out(o2, text), inn(chase, "Payment Thank You"))).isNull()
        assertThat(engine().evaluate(out(o2, "AC X"), inn(chase, text))).isNull()
    }

    @Test
    fun aRoundUpInflowAgainstAPlainOutflowIsVetoedAndAMatchingTextPairs() {
        assertThat(engine().evaluate(out(sofi, "Coffee Shop"), inn(vault, "Roundup *Coffee Shop"))!!.veto).startsWith("round-up")
        assertThat(engine().evaluate(out(sofi, "Roundup *Coffee Shop"), inn(vault, "Coffee Shop"))!!.veto).startsWith("round-up")
        assertThat(engine().evaluate(out(sofi, "Roundup *Coffee Shop"), inn(vault, "Roundup *Coffee Shop"))!!.veto).isNull()
    }

    @Test
    fun theSameAccountADifferentAmountAndADifferentCurrencyAreNeverCandidates() {
        assertThat(engine().evaluate(out(o2, "AC X"), inn(o2, "AC X"))).isNull()
        assertThat(engine().evaluate(out(o2, "AC X"), inn(chase, "Payment Thank You", cents = 10_001))).isNull()
        assertThat(engine().evaluate(out(o2, "AC X"), inn(chase, "Payment Thank You").copy(currency = "EUR"))).isNull()
        assertThat(engine().evaluate(out(o2, "AC X"), inn(chase, "Payment Thank You"))).isNotNull()
    }

    @Test
    fun aGapOfExactlyTheFallbackWindowIsACandidateAndOneDayMoreIsNot() {
        assertThat(engine().evaluate(out(o2, "AC X"), inn(chase, "Payment Thank You", date = day.plusDays(10)))).isNotNull()
        assertThat(engine().evaluate(out(o2, "AC X"), inn(chase, "Payment Thank You", date = day.plusDays(11)))).isNull()
        // the marker window: two marked legs 5 days apart are layer 2, 6 days apart layer 3
        assertThat(engine().evaluate(out(o2, "AC X"), inn(chase, "Payment Thank You", date = day.plusDays(5)))!!.layer).isEqualTo(2)
        assertThat(engine().evaluate(out(o2, "AC X"), inn(chase, "Payment Thank You", date = day.plusDays(6)))!!.layer).isEqualTo(3)
    }

    // endregion

    // region L1: word boundaries on the vetoes

    @Test
    fun anIncomeWordInsideAnotherWordDoesNotVetoARealTransfer() {
        fun veto(inflow: String) = engine().evaluate(out(o2, "AC X"), inn(chase, inflow))!!.veto
        assertThat(veto("Payment Thank You Provides")).isNull() // "ides"
        assertThat(veto("Payment Thank You Taxi")).isNull() // "tax"
        assertThat(veto("TAX REFUND")).isEqualTo("income")
        assertThat(veto("STATE TAXES")).isEqualTo("income")
        assertThat(veto("UNEMP IDES")).isEqualTo("income")
    }

    @Test
    fun anUnlinkedDestinationWordInsideAnotherWordDoesNotVetoARealTransfer() {
        fun veto(outflow: String) = engine().evaluate(out(o2, outflow), inn(chase, "Payment Thank You"))!!.veto
        for (word in listOf("COMED", "BILT", "AFFIRM")) {
            assertThat(veto("AC $word PAYMENT")).describedAs(word).isEqualTo("unlinked-destination")
        }
        for (word in listOf("COMEDY CLUB", "BILTMORE", "AFFIRMATIONS")) {
            assertThat(veto("AC $word")).describedAs(word).isNull()
        }
    }

    // endregion

    // region H1: a rejected pair is never proposed again

    @Test
    fun aRejectedPairIsNotProposedAgainButAnotherPartnerOfTheSameLegStillIs() {
        val o = out(o2, "AC CHASE CREDIT CRD AUTOPAY", id = "o1")
        val bad = inn(chase, "Payment Thank You", id = "i1")
        val other = inn(chase, "Payment Thank You", id = "i2", date = day.plusDays(2))
        val e = engine(rejected = setOf("o1" to "i1"))
        assertThat(e.evaluate(o, bad)).isNull()
        assertThat(e.decide(listOf(o, bad)).proposals).isEmpty()
        assertThat(e.decide(listOf(o, bad)).candidates).isZero()
        val result = e.decide(listOf(o, bad, other))
        assertThat(result.proposals.single().edge.inn.id).isEqualTo("i2")
        assertThat(engine().decide(listOf(o, bad)).proposals).hasSize(1)
    }

    // endregion
}
