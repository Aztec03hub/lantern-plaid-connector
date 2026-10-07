package net.djvk.fireflyPlaidConnector2.pairing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Anonymised trap fixtures (design 10.0 and 10.1): hand-written rows that reproduce each SHAPE of the look-alike traps on
 * an invented bank (Harbor Bank, Pine CU). No real names, numbers or masks; the wording is invented but follows the
 * defaults' patterns (a leading `AC `, "From Share", "Payment Thank You", "Roundup *").
 * Also the hint tests (10.1): with and without `counterpartAccount`, and a hint that resolves a non-twin tie.
 */
internal class PairTrapFixturesTest {
    private val chk = PairAccount(1, "Harbor Checking", "Harbor Bank")
    private val sav = PairAccount(2, "Harbor Savings", "Harbor Bank", "1111")
    private val card = PairAccount(3, "Pine Card", "Pine CU", "2222", setOf("card"))
    private val vault = PairAccount(4, "Harbor Vault", "Harbor Bank", null, setOf("vault"))
    private val other = PairAccount(5, "Elm Checking", "Elm Bank")
    private val accounts = listOf(chk, sav, card, vault, other)

    private val day = LocalDate.of(2026, 2, 12)
    private var n = 0
    private fun out(a: PairAccount, text: String, cents: Long = 7_500, date: LocalDate = day, id: String = "o${n++}") = Leg(id, a.id, Dir.OUT, date, cents, text)
    private fun inn(a: PairAccount, text: String, cents: Long = 7_500, date: LocalDate = day, id: String = "i${n++}") = Leg(id, a.id, Dir.IN, date, cents, text)

    private val base = PairingConfig(
        destinations = listOf(
            DestRule(Regex("(?i)pine cu autopay"), DestTarget.Institution("Pine CU")),
            DestRule(Regex("(?i)to share (\\d{4})"), DestTarget.Mask("Harbor Bank", 1)),
            DestRule(Regex("(?i)acme utilities"), DestTarget.Unlinked),
        ),
        externalPayees = listOf(Regex("(?i)rent to landlord")),
    )

    private fun engine(config: PairingConfig = base) = PairEngine(accounts, config)

    // region the trap shapes: each must produce no pair, and the engine says why

    @Test
    fun aPaymentToAPersonIsNeverPaired() {
        val result = engine().decide(listOf(out(chk, "Zelle Payment to Jordan Rivera"), inn(card, "Payment Thank You")))
        assertThat(result.proposals).isEmpty()
        assertThat(result.vetoed.single().veto).isEqualTo("p2p")
    }

    @Test
    fun incomeIsNeverPaired() {
        val result = engine().decide(listOf(out(chk, "AC ACH PULL 4417"), inn(sav, "ACME CORP PAYROLL")))
        assertThat(result.proposals).isEmpty()
        assertThat(result.vetoed.single().veto).isEqualTo("income")
    }

    @Test
    fun aPaymentToAnExternalPayeeIsNeverPaired() {
        val result = engine().decide(listOf(out(chk, "AC RENT TO LANDLORD"), inn(sav, "AC ACH XFER")))
        assertThat(result.proposals).isEmpty()
        assertThat(result.vetoed.single().veto).isEqualTo("external-payee")
    }

    @Test
    fun aDestinationThatContradictsTheInflowsAccountIsNeverPaired() {
        // names share 1111 (Harbor Savings) but the money landed on the Pine card
        val result = engine().decide(listOf(out(chk, "To Share 1111"), inn(card, "Payment Thank You")))
        assertThat(result.proposals).isEmpty()
        assertThat(result.vetoed.single().veto).isEqualTo("destination-contradiction")
    }

    @Test
    fun aDestinationThatIsNotLinkedIsNeverPaired() {
        val result = engine().decide(listOf(out(chk, "ACME UTILITIES BILL PAY"), inn(sav, "AC ACH XFER")))
        assertThat(result.proposals).isEmpty()
        assertThat(result.vetoed.single().veto).isEqualTo("unlinked-destination")
    }

    @Test
    fun theRoundUpOfAnotherMerchantIsNeverPaired() {
        val result = engine().decide(listOf(out(chk, "Roundup *Coffee Shop"), inn(vault, "Roundup *Bakery")))
        assertThat(result.proposals).isEmpty()
        assertThat(result.vetoed.single().veto).startsWith("round-up")
    }

    @Test
    fun theSameDayDuplicateTransferPairsTwiceAndTheCrossedTwinIsTheSameLedger() {
        val o1 = out(chk, "To Share 1111", id = "o1")
        val o2 = out(chk, "To Share 1111", id = "o2")
        val i1 = inn(sav, "From Share 0000", id = "i1")
        val i2 = inn(sav, "From Share 0000", id = "i2")
        val e = engine()
        val result = e.decide(listOf(o1, o2, i1, i2))
        assertThat(result.proposals).hasSize(2)
        assertThat(result.proposals.all { it.auto }).isTrue()
        // the crossed pairing scores the same and has the signature of the true one: it is not a trap
        assertThat(e.evaluate(o1, i2)!!.score).isEqualTo(e.evaluate(o1, i1)!!.score)
        assertThat(e.evaluate(o1, i2)!!.signature).isEqualTo(e.evaluate(o1, i1)!!.signature)
        // either assignment, any order of the legs, one ledger
        val ledger = result.proposals.map { it.edge.signature }.sortedBy { it.toString() }
        assertThat(e.decide(listOf(i2, o2, i1, o1)).proposals.map { it.edge.signature }.sortedBy { it.toString() }).isEqualTo(ledger)
    }

    @Test
    fun theHighestScoringTrapStaysBelowTheReviewBandWhenOnlyDateAndOneMarkerMatch() {
        // an unmarked outflow and an inflow with a marker, four days apart: in-marker 2 + date-gap -2 = 0
        val edge = engine().evaluate(out(chk, "Online purchase 99"), inn(sav, "AC ACH XFER", date = day.plusDays(4)))!!
        assertThat(edge.score).isLessThan(PairingConfig().reviewMin)
        assertThat(engine().decide(listOf(out(chk, "Online purchase 99"), inn(sav, "AC ACH XFER", date = day.plusDays(4)))).proposals).isEmpty()
    }

    // endregion

    // region hints (4.3.4)

    @Test
    fun aHintWithoutACounterpartAddsThreePointsForAnyPartnerButNeverLiftsTheP2pVeto() {
        val hint = Hint(chk.id, Regex("Harbor internal move"))
        val e = engine(base.copy(hints = listOf(hint)))
        val toSav = e.evaluate(out(chk, "Harbor internal move 1"), inn(sav, "Money in"))!!
        val toOther = e.evaluate(out(chk, "Harbor internal move 1"), inn(other, "Money in"))!!
        assertThat(toSav.points.first { it.rule == "hint" }.points).isEqualTo(3)
        assertThat(toOther.points.first { it.rule == "hint" }.points).isEqualTo(3)
        // one family alone is review band at best; a second family (a card payment received) makes it auto
        assertThat(e.decide(listOf(out(chk, "Harbor internal move 1"), inn(sav, "Money in"))).proposals.single().auto).isFalse()
        assertThat(e.decide(listOf(out(chk, "Harbor internal move 1"), inn(card, "Credit Card Payment Received"))).proposals.single().auto).isTrue()
        // the hint does not turn a P2P text into a transfer
        val p2p = e.evaluate(out(chk, "Zelle Harbor internal move"), inn(sav, "Money in"))!!
        assertThat(p2p.veto).isEqualTo("p2p")
    }

    @Test
    fun aHintWithACounterpartOnlyCountsAgainstThatAccountAndLiftsTheP2pVeto() {
        val e = engine(base.copy(hints = listOf(Hint(chk.id, Regex("Zelle to Sam"), counterpartAccount = sav.id))))
        val own = e.evaluate(out(chk, "Zelle to Sam Lee"), inn(sav, "Zelle from Sam Lee"))!!
        // the inflow also says zelle and has no hint of its own, so the p2p veto stands on that side
        assertThat(own.veto).isEqualTo("p2p")
        val ownBoth = engine(base.copy(hints = listOf(Hint(chk.id, Regex("Zelle to Sam"), sav.id), Hint(sav.id, Regex("Zelle from Sam"), chk.id))))
            .evaluate(out(chk, "Zelle to Sam Lee"), inn(sav, "Zelle from Sam Lee"))!!
        assertThat(ownBoth.veto).isNull()
        assertThat(ownBoth.points.map { it.rule }).contains("hint")
        // the same wording towards another account: no hint, still a p2p veto
        val elsewhere = e.evaluate(out(chk, "Zelle to Sam Lee"), inn(other, "Money in"))!!
        assertThat(elsewhere.points.map { it.rule }).doesNotContain("hint")
        assertThat(elsewhere.veto).isEqualTo("p2p")
    }

    @Test
    fun aHintOnTheOutflowWithACounterpartResolvesANonTwinTie() {
        val o = out(chk, "AC SOMETHING")
        val ia = inn(card, "AC TRANSFER IN", id = "ia")
        val ib = inn(sav, "AC TRANSFER IN", id = "ib")
        assertThat(engine().decide(listOf(o, ia, ib)).ambiguous).hasSize(1)
        val hinted = engine(base.copy(hints = listOf(Hint(chk.id, Regex("AC SOMETHING"), counterpartAccount = sav.id))))
        val result = hinted.decide(listOf(o, ia, ib))
        assertThat(result.ambiguous).isEmpty()
        assertThat(result.proposals.single().edge.inn.id).isEqualTo("ib")
    }

    // endregion
}
