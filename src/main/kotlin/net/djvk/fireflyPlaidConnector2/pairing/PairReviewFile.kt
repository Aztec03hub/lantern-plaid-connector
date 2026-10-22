package net.djvk.fireflyPlaidConnector2.pairing

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant

/** The review queue Lantern reads (pair-review.json): mid-score pairs with both legs and each rule's points. Dates are ISO text. */
object PairReviewFile {
    const val MAX_ITEMS = 100
    /** The app reader drops a whole pair whose rule name is longer than this, so the writer cuts it. */
    const val RULE_MAX = 40

    /** The same pair always gets the same id: Plaid ids do not change between passes. */
    fun id(out: String, inn: String) = "$out>$inn"

    /** The JSON text for [proposals] (only those with auto == false are kept), best score first, oldest first on a tie. */
    fun render(proposals: List<Proposal>, generatedAt: Instant): String {
        val items = proposals.filter { !it.auto }
            .sortedWith(compareByDescending<Proposal> { it.edge.score }.thenBy { it.edge.out.date }.thenBy { id(it.edge.out.id, it.edge.inn.id) })
            .take(MAX_ITEMS)
            .map { item(it.edge) }
        return jacksonObjectMapper().writeValueAsString(linkedMapOf("version" to 1, "generatedAt" to generatedAt.toString(), "items" to items))
    }

    private fun item(e: Edge) = linkedMapOf(
        "id" to id(e.out.id, e.inn.id), "out" to e.out.id, "inn" to e.inn.id, "score" to e.score, "gapDays" to e.gap, "flags" to e.flags,
        "outLeg" to leg(e.out), "inLeg" to leg(e.inn),
        "points" to e.points.map { linkedMapOf("rule" to it.rule.take(RULE_MAX), "points" to it.points, "family" to it.family, "note" to it.note) },
    )

    private fun leg(l: Leg) = linkedMapOf(
        "account" to l.account, "date" to l.date.toString(), "cents" to l.cents, "currency" to l.currency, "text" to Awaiting.textOf(l.text),
    )
}
