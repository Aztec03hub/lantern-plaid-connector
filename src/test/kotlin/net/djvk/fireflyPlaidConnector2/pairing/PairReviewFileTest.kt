package net.djvk.fireflyPlaidConnector2.pairing

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

internal class PairReviewFileTest {
    private val at = Instant.parse("2026-10-22T03:20:11Z")

    private fun leg(id: String, out: Boolean, day: LocalDate, text: String = "TRANSFER") =
        Leg(id, if (out) 2 else 5, if (out) Dir.OUT else Dir.IN, day, 12_500, text, currency = "USD")

    private fun proposal(n: Int, points: Int, auto: Boolean = false, day: LocalDate = LocalDate.of(2026, 10, 19), text: String = "TRANSFER") =
        Proposal(Edge(leg("out$n", true, day, text), leg("in$n", false, day.plusDays(1)), 1, 1, listOf(RulePoints("marker", points, "markers", "n"))), auto)

    private fun tree(p: List<Proposal>) = jacksonObjectMapper().readTree(PairReviewFile.render(p, at))

    @Test
    fun aReviewPairIsWrittenWithBothLegsAndItsPoints() {
        val item = tree(listOf(proposal(1, 3))).path("items").single()
        assertThat(item.path("id").asText()).isEqualTo("out1>in1")
        assertThat(item.path("score").asInt()).isEqualTo(3)
        assertThat(item.path("gapDays").asInt()).isEqualTo(1)
        assertThat(item.path("outLeg").path("account").asInt()).isEqualTo(2)
        assertThat(item.path("inLeg").path("cents").asLong()).isEqualTo(12_500)
        assertThat(item.path("points").single().path("rule").asText()).isEqualTo("marker")
        assertThat(item.path("points").single().path("family").asText()).isEqualTo("markers")
    }

    @Test
    fun anAutoPairIsNotInTheQueue() {
        assertThat(tree(listOf(proposal(1, 4, auto = true))).path("items")).isEmpty()
    }

    @Test
    fun datesAreIsoTextNotArrays() {
        val n = tree(listOf(proposal(1, 3)))
        assertThat(n.path("items").single().path("outLeg").path("date").isTextual).isTrue()
        assertThat(n.path("items").single().path("outLeg").path("date").asText()).isEqualTo("2026-10-19")
        assertThat(n.path("generatedAt").asText()).isEqualTo("2026-10-22T03:20:11Z")
    }

    @Test
    fun moreThanOneHundredPairsAreCappedBestScoreFirst() {
        val many = (1..120).map { proposal(it, if (it == 120) 9 else 3) }
        val items = tree(many).path("items")
        assertThat(items).hasSize(100)
        assertThat(items[0].path("id").asText()).isEqualTo("out120>in120")
    }

    @Test
    fun theIdIsTheSameOnEveryRender() {
        assertThat(PairReviewFile.render(listOf(proposal(1, 3)), at)).isEqualTo(PairReviewFile.render(listOf(proposal(1, 3)), at))
    }

    @Test
    fun textLosesControlCharactersAndIsCutToEightyCodePoints() {
        val text = "A\u0000B\n" + "😀".repeat(100)
        val out = tree(listOf(proposal(1, 3, text = text))).path("items").single().path("outLeg").path("text").asText()
        assertThat(out.codePointCount(0, out.length)).isEqualTo(80)
        assertThat(out).startsWith("AB😀").doesNotContain("\u0000", "\n")
    }

    @Test
    fun aRuleNameOverFortyCharactersIsCutSoTheReaderKeepsThePair() {
        val p = proposal(1, 3)
        val e = p.edge.copy(points = listOf(RulePoints("r".repeat(60), 3)))
        val rule = tree(listOf(p.copy(edge = e))).path("items").single().path("points").single().path("rule").asText()
        assertThat(rule).hasSize(40)
    }
}
