package net.djvk.fireflyPlaidConnector2.pairing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

internal class PairEngineTest {
    // Old Second checking, DCU Imagine card / checking / savings, a Chase card, SoFi checking and its vault
    private val o2 = PairAccount(1, "Phil s Checking", "Old Second")
    private val imagine = PairAccount(7, "IMAGINE REWARDS", "DCU", "0836", setOf("card"))
    private val dcuChecking = PairAccount(6, "CASH BACK CHECKING", "DCU", "0021")
    private val dcuSavings = PairAccount(5, "MEMBERSHIP SAVINGS", "DCU", "0000")
    private val chase = PairAccount(8, "CREDIT CARD ..0325", "Chase", "0325", setOf("card"))
    private val sofi = PairAccount(9, "SoFi Checking", "SoFi")
    private val vault = PairAccount(10, "Emergency Fund", "SoFi", null, setOf("vault"))
    private val all = listOf(o2, imagine, dcuChecking, dcuSavings, chase, sofi, vault)

    private val day = LocalDate.of(2025, 8, 18)
    private var n = 0
    private fun out(account: PairAccount, text: String, cents: Long = 10_000, date: LocalDate = day, id: String = "o${n++}") =
        Leg(id, account.id, Dir.OUT, date, cents, text)

    private fun inn(account: PairAccount, text: String, cents: Long = 10_000, date: LocalDate = day, id: String = "i${n++}") =
        Leg(id, account.id, Dir.IN, date, cents, text)

    private fun engine(config: PairingConfig = PairingConfig()) = PairEngine(all, config)

    // region the reported false positive

    @Test
    fun aZellePaymentToAPersonNeverPairsWithACardPaymentReceived() {
        val result = engine().decide(
            listOf(
                out(sofi, "Zelle Payment to Mayte Lopez", date = LocalDate.of(2026, 8, 5)),
                inn(imagine, "Credit Card Payment Received", date = LocalDate.of(2026, 8, 8)),
            )
        )
        assertThat(result.proposals).isEmpty()
        assertThat(result.vetoed.single().veto).isEqualTo("p2p")
    }

    // endregion

    // region markers and the two-family rule

    @Test
    fun anAchPullWithAnOutMarkerAndACardPaymentReceivedIsMarkedAndAutoMerged() {
        val o = out(o2, "AC DUPAGECU VSA PMT 123")
        val i = inn(imagine, "Credit Card Payment Received")
        val result = engine().decide(listOf(o, i))
        val p = result.proposals.single()
        assertThat(p.edge.layer).isEqualTo(2)
        assertThat(p.edge.points.map { it.rule }).contains("out-marker", "in-marker", "date-gap")
        // two markers are ONE family: 2 + 2 + 2 for the same day is 6 points but it is not enough on its own
        assertThat(p.edge.families).containsExactly("marker")
        assertThat(p.auto).isFalse()
    }

    @Test
    fun aMarkerAndAnExactDestinationAreTwoFamiliesAndAutoMerge() {
        val result = engine().decide(listOf(out(dcuSavings, "To Loan 0836"), inn(imagine, "Credit Card Payment Received")))
        val p = result.proposals.single()
        assertThat(p.edge.families).containsExactlyInAnyOrder("marker", "destination")
        assertThat(p.edge.points.first { it.rule == "destination-exact" }.points).isEqualTo(4)
        assertThat(p.auto).isTrue()
    }

    @Test
    fun aDestinationRuleThatNamesTheWrongAccountIsAVetoWhateverElseMatches() {
        val result = engine().decide(listOf(out(dcuSavings, "To Loan 0836"), inn(dcuChecking, "From Share 0000")))
        assertThat(result.proposals).isEmpty()
        assertThat(result.vetoed.single().veto).isEqualTo("destination-contradiction")
    }

    @Test
    fun aDestinationThatIsNotLinkedNeverPairs() {
        // DCU loans have no Plaid rows: "To Loan 0001" and an inflow from anywhere cannot be the same money
        val result = engine().decide(listOf(out(dcuSavings, "To Loan 0001"), inn(imagine, "Credit Card Payment Received")))
        assertThat(result.proposals).isEmpty()
        assertThat(result.vetoed.single().veto).isEqualTo("unlinked-destination")
        val unlisted = engine().decide(listOf(out(o2, "BEST BUY 00012345"), inn(chase, "Payment Thank You")))
        assertThat(unlisted.proposals).isEmpty()
    }

    @Test
    fun aByInstitutionDestinationWithSeveralAccountsGetsNoPointsAndIsFlagged() {
        // "PHILLIP LAFAYETT ACH XFER" names DCU, which has three linked accounts that could receive it
        val result = engine().decide(listOf(out(o2, "AC PHILLIP LAFAYETT ACH XFER"), inn(dcuChecking, "AC ACH XFER")))
        val edge = (result.proposals.singleOrNull()?.edge) ?: result.unmatched.single()
        assertThat(edge.flags).contains("ambiguous-destination")
        assertThat(edge.points.first { it.rule == "destination-ambiguous" }.points).isEqualTo(0)
        assertThat(edge.families).doesNotContain("destination")
    }

    @Test
    fun aCardPaymentInflowNarrowsAnInstitutionToTheOnlyCardAndIsAutoMerged() {
        val result = engine().decide(listOf(out(o2, "AC PHILLIP LAFAYETT ACH XFER"), inn(imagine, "Credit Card Payment Received")))
        val p = result.proposals.single()
        assertThat(p.edge.points.map { it.rule }).contains("destination-narrowed")
        assertThat(p.auto).isTrue()
        // a card payment landing on a non-card account is a contradiction of the narrowing, never narrowed
        val wrong = engine().decide(listOf(out(o2, "AC PHILLIP LAFAYETT ACH XFER"), inn(dcuChecking, "Credit Card Payment Received")))
        assertThat(wrong.proposals.flatMap { it.edge.points.map { r -> r.rule } }).doesNotContain("destination-narrowed")
    }

    @Test
    fun aByInstitutionDestinationWithOneQualifyingAccountScoresThree() {
        val result = engine().decide(listOf(out(o2, "CHASE CREDIT CRD AUTOPAY"), inn(chase, "Payment Thank You")))
        assertThat(result.proposals.single().edge.points.first { it.rule == "destination-institution" }.points).isEqualTo(3)
    }

    @Test
    fun theVaultRoleAndRoundUps() {
        val ok = engine().decide(listOf(out(sofi, "To Emergency Fund Vault"), inn(vault, "From checking balance")))
        assertThat(ok.proposals.single().auto).isTrue()
        val roundUp = engine().decide(listOf(out(sofi, "Roundup *Coffee Shop"), inn(vault, "Roundup *Coffee Shop")))
        assertThat(roundUp.proposals).hasSize(1)
        // a round-up pairs only with the round-up of the same text, even where P2P text is involved
        val otherText = engine().decide(listOf(out(sofi, "Roundup *Coffee Shop"), inn(vault, "Roundup *Bakery")))
        assertThat(otherText.proposals).isEmpty()
        val paypal = engine().decide(listOf(out(sofi, "Roundup *PAYPAL INST XFER"), inn(vault, "Roundup *PAYPAL INST XFER")))
        assertThat(paypal.proposals).hasSize(1)
    }

    // endregion

    // region vetoes

    @Test
    fun anIncomeInflowIsNeverAFallbackCandidate() {
        for (text in listOf("ACME PAYROLL", "Direct Deposit", "Interest Paid", "IRS TAX REFUND")) {
            val result = engine().decide(listOf(out(o2, "AC SOMETHING"), inn(sofi, text)))
            assertThat(result.proposals).describedAs(text).isEmpty()
        }
    }

    @Test
    fun anExternalPayeeNeverPairs() {
        val config = PairingConfig(externalPayees = listOf(Regex("(?i)moms checking")))
        val result = engine(config).decide(listOf(out(o2, "AC Moms Checking"), inn(sofi, "AC TRANSFER")))
        assertThat(result.proposals).isEmpty()
        assertThat(result.vetoed.single().veto).isEqualTo("external-payee")
    }

    @Test
    fun aHintWithACounterpartAccountLiftsTheP2pVetoForThatAccountOnly() {
        val hint = Hint(account = sofi.id, regex = Regex("Zelle Payment to Phillip"), counterpartAccount = o2.id)
        val config = PairingConfig(hints = listOf(hint))
        val pair = listOf(out(sofi, "Zelle Payment to Phillip Lafayette"), inn(o2, "Zelle payment from Phillip Lafayette"))
        // the inflow also says zelle: its own account has no hint, so it stays vetoed
        assertThat(engine(config).decide(pair).proposals).isEmpty()
        val both = PairingConfig(hints = listOf(hint, Hint(o2.id, Regex("Zelle payment from Phillip"), sofi.id)))
        val result = engine(both).decide(pair)
        assertThat(result.proposals.single().edge.families).contains("hint")
    }

    @Test
    fun aStatementCarryOverIsNeverACandidate() {
        val result = engine().decide(listOf(out(dcuSavings, "LAST STATEMENT BAL FROM ACCT ENDING 1234"), inn(dcuChecking, "From Share 0000")))
        assertThat(result.candidates).isZero()
    }

    // endregion

    // region assignment

    @Test
    fun atEqualScoreALowerLayerBeatsACloserFallbackCandidate() {
        // marked pair two days apart: 2 + 2 + 0 = 4 (layer 2); unmarked inflow the same day: 2 + 2 = 4 (layer 3)
        val o = out(o2, "AC DUPAGECU VSA PMT", date = day)
        val marked = inn(imagine, "Credit Card Payment Received", date = day.plusDays(2))
        val near = inn(chase, "Some Transfer", date = day)
        val engine = engine()
        assertThat(engine.evaluate(o, marked)!!.score).isEqualTo(4)
        assertThat(engine.evaluate(o, near)!!.score).isEqualTo(4)
        val result = engine.decide(listOf(o, marked, near))
        assertThat(result.proposals.single().edge.inn.id).isEqualTo(marked.id)
    }

    @Test
    fun aHigherScoreBeatsALowerLayer() {
        // design 4.5 orders by score first: a same-day unmarked inflow with a destination beats a marked one three days off
        val o = out(o2, "CHASE CREDIT CRD AUTOPAY", date = day) // out is not marked; destination names Chase
        val chaseSameDay = inn(chase, "Payment Thank You", date = day)
        val result = engine().decide(listOf(o, chaseSameDay))
        assertThat(result.proposals.single().edge.score).isGreaterThan(4)
    }

    @Test
    fun twinsAssignEitherWayToTheSameLedger() {
        fun run(order: List<Leg>) = engine().decide(order).proposals.map { it.edge.signature }.sortedBy { it.toString() }
        val o1 = out(dcuSavings, "To Loan 0836", id = "o1")
        val o2b = out(dcuSavings, "To Loan 0836", id = "o2")
        val i1 = inn(imagine, "Credit Card Payment Received", id = "i1")
        val i2 = inn(imagine, "Credit Card Payment Received", id = "i2")
        assertThat(run(listOf(o1, o2b, i1, i2))).hasSize(2)
        assertThat(run(listOf(o1, o2b, i1, i2))).isEqualTo(run(listOf(i2, i1, o2b, o1)))
    }

    @Test
    fun aNonTwinTieProducesNoPairAndIsListed() {
        val o = out(o2, "AC SOMETHING")
        val result = engine().decide(listOf(o, inn(chase, "AC TRANSFER IN", id = "ia"), inn(imagine, "AC TRANSFER IN", id = "ib")))
        assertThat(result.proposals).isEmpty()
        assertThat(result.ambiguous).hasSize(1)
    }

    @Test
    fun aHintResolvesATie() {
        val o = out(o2, "AC SOMETHING")
        val config = PairingConfig(hints = listOf(Hint(imagine.id, Regex("TRANSFER IN"), o2.id)))
        val result = engine(config).decide(listOf(o, inn(chase, "AC TRANSFER IN", id = "ia"), inn(imagine, "AC TRANSFER IN", id = "ib")))
        assertThat(result.proposals.single().edge.inn.id).isEqualTo("ib")
    }

    @Test
    fun theOutcomeDoesNotDependOnTheOrderOfTheLegs() {
        val legs = listOf(
            out(o2, "AC DUPAGECU VSA PMT", id = "o1"), inn(imagine, "Credit Card Payment Received", id = "i1"),
            out(dcuSavings, "To Loan 0836", id = "o2", date = day.plusDays(1)), inn(imagine, "Credit Card Payment Received", id = "i2", date = day.plusDays(2)),
            out(sofi, "To Emergency Fund Vault", cents = 2_500, id = "o3"), inn(vault, "From checking balance", cents = 2_500, id = "i3"),
        )
        val expected = engine().decide(legs).proposals.map { it.edge.out.id to it.edge.inn.id }.toSet()
        repeat(20) { seed ->
            val shuffled = legs.shuffled(java.util.Random(seed.toLong()))
            assertThat(engine().decide(shuffled).proposals.map { it.edge.out.id to it.edge.inn.id }.toSet()).isEqualTo(expected)
        }
    }

    // endregion

    // region pending, settled, late competitors

    @Test
    fun aPendingCompetitorHoldsAPairInsteadOfLettingTheSettledLegTakeIt() {
        val o = out(dcuSavings, "To Loan 0836", id = "o1")
        val settled = inn(imagine, "Credit Card Payment Received", id = "i1", date = day.plusDays(1))
        val pending = inn(imagine, "Credit Card Payment Received", id = "i2", date = day).copy(pending = true)
        val result = engine().decide(listOf(o, settled, pending))
        assertThat(result.proposals).isEmpty()
        assertThat(result.waiting).hasSize(1)
    }

    @Test
    fun aLegThatIsNotSettledYetWaitsAndIsListed() {
        val o = out(dcuSavings, "To Loan 0836")
        val i = inn(imagine, "Credit Card Payment Received").copy(settled = false)
        val result = engine().decide(listOf(o, i))
        assertThat(result.proposals).isEmpty()
        assertThat(result.tooNew).containsExactly(i)
    }

    @Test
    fun aLateCompetitorIsFlaggedNotUnmerged() {
        val e = engine()
        val merged = e.evaluate(out(dcuSavings, "To Loan 0836", id = "o1"), inn(imagine, "Credit Card Payment Received", id = "i1", date = day.plusDays(3)))!!
        // a closer inflow with the same evidence that arrived after the merge
        val late = inn(imagine, "Credit Card Payment Received", id = "i9", date = day)
        assertThat(e.lateCompetitors(listOf(merged), listOf(late))).hasSize(1)
        val unrelated = inn(chase, "Payment Thank You", id = "i8", date = day.plusDays(9))
        assertThat(e.lateCompetitors(listOf(merged), listOf(unrelated))).isEmpty()
    }

    // endregion

    @Test
    fun theDateGapScoresTwoOneZeroThenMinusOnePerDay() {
        fun gapPoints(g: Long) = engine().evaluate(out(o2, "AC X"), inn(imagine, "Credit Card Payment Received", date = day.plusDays(g)))!!
            .points.first { it.rule == "date-gap" }.points
        assertThat((0L..5L).map(::gapPoints)).containsExactly(2, 1, 0, -1, -2, -3)
    }

    @Test
    fun aGapPastTheFallbackWindowIsNotACandidate() {
        assertThat(engine().evaluate(out(o2, "AC X"), inn(imagine, "Credit Card Payment Received", date = day.plusDays(11)))).isNull()
    }
}
