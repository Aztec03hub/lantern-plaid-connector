package net.djvk.fireflyPlaidConnector2.pairing

import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PairMergeOutcome
import net.djvk.fireflyPlaidConnector2.transactions.AccountKind
import net.djvk.fireflyPlaidConnector2.transactions.pairedTransactionType

/** The text of a pass: the dry run prints it, a real pass prints it too (design 7). */
object PairReportRenderer {
    private fun amount(cents: Long) = "%d.%02d".format(cents / 100, cents % 100)

    private fun legLine(l: Leg, names: Map<Int, String>) =
        "${l.date} ${amount(l.cents).padStart(9)} ${(names[l.account] ?: "account ${l.account}").take(18).padEnd(18)} [${l.text.take(44)}] group ${l.groupId ?: "-"}"

    private fun points(e: Edge) = e.points.joinToString(", ") { "${it.rule}${if (it.points >= 0) "+" else ""}${it.points}${it.note?.let { n -> " ($n)" } ?: ""}" }

    fun render(
        result: PairResult,
        config: PairingConfig,
        dryRun: Boolean,
        merged: List<MergeRecord>,
        rejected: List<Pair<Edge, PairMergeOutcome.Rejected>>,
        needsHuman: List<Pair<Leg, String>>,
        late: List<Pair<Edge, Edge>>,
        awaiting: List<AwaitingRecord>,
        legsRead: Int,
        kinds: Map<Int, AccountKind>,
        accounts: List<PairAccount>,
    ): String {
        val names = accounts.associate { it.id to it.name }
        val b = StringBuilder()
        val auto = result.proposals.filter { it.auto }
        val review = result.proposals.filter { !it.auto }
        b.appendLine(if (dryRun) "=== TRANSFER PAIRING: DRY RUN (nothing is written) ===" else "=== TRANSFER PAIRING: REAL PASS ===")
        b.appendLine("rule_version ${config.ruleVersion}  autoMin ${config.autoMin}  reviewMin ${config.reviewMin}  markerDays ${config.markerDays}  fallbackDays ${config.fallbackDays}")
        b.appendLine("single legs read: $legsRead; candidate pairs scored: ${result.candidates}")
        b.appendLine("proposed (auto-merge): ${auto.size}; review band: ${review.size}; ambiguous components: ${result.ambiguous.size}; " +
                "waiting on pending or an unsettled bank: ${result.waiting.size}; vetoed: ${result.vetoed.size}; " +
                "unmatched (under reviewMin): ${result.unmatched.size}; legs not settled or outside the window: ${result.tooNew.size}; needs a human: ${needsHuman.size}")
        if (!dryRun) b.appendLine("merged: ${merged.size}; refused by core: ${rejected.size}")
        val ages = auto.map { java.time.temporal.ChronoUnit.DAYS.between(it.edge.out.date, java.time.LocalDate.now()) }
        if (ages.isNotEmpty()) b.appendLine("age of the proposed pairs (days): min ${ages.min()}, median ${ages.sorted()[ages.size / 2]}, max ${ages.max()}")

        fun block(title: String, ps: List<Proposal>) {
            b.appendLine().appendLine("== $title")
            ps.sortedBy { it.edge.out.date }.forEach { p ->
                val e = p.edge
                val type = pairedTransactionType(kinds[e.out.account] ?: AccountKind.ASSET, kinds[e.inn.account] ?: AccountKind.ASSET)
                b.appendLine("score ${e.score} layer ${e.layer} gap ${e.gap}d  type ${type.value}  families ${e.families}  ${if (e.flags.isEmpty()) "" else e.flags}")
                b.appendLine("   out ${legLine(e.out, names)}")
                b.appendLine("   in  ${legLine(e.inn, names)}")
                b.appendLine("   points: ${points(e)}")
            }
        }
        block("Proposed pairs (auto-merge)", auto)
        block("Review band (a person confirms; never merged on its own)", review)

        b.appendLine().appendLine("== Ambiguous components (no pair made; a hint resolves one)")
        result.ambiguous.forEach { a ->
            b.appendLine(a.reason)
            a.edges.forEach { e -> b.appendLine("   score ${e.score}  ${legLine(e.out, names)}  ->  ${legLine(e.inn, names)}") }
        }
        b.appendLine().appendLine("== Waiting (the partner is pending, or a bank has not synced past the window)")
        result.waiting.forEach { e -> b.appendLine("   score ${e.score}  ${legLine(e.out, names)}  ->  ${legLine(e.inn, names)}") }
        b.appendLine().appendLine("== Vetoed: matched on amount, direction and date but can never pair")
        result.vetoed.sortedBy { it.out.date }.forEach { e -> b.appendLine("   [${e.veto}]  ${legLine(e.out, names)}  ->  ${legLine(e.inn, names)}") }
        b.appendLine().appendLine("== Unmatched: matched on amount and date but scored under reviewMin (${config.reviewMin})")
        result.unmatched.sortedBy { it.out.date }.forEach { e -> b.appendLine("   score ${e.score}  ${legLine(e.out, names)}  ->  ${legLine(e.inn, names)}   points: ${points(e)}") }
        b.appendLine().appendLine("== Needs a human (never sent to core)")
        needsHuman.forEach { (l, why) -> b.appendLine("   [$why]  ${legLine(l, names)}") }
        if (late.isNotEmpty()) {
            b.appendLine().appendLine("== Late competitors: a leg arrived that would have competed for a merged pair (flagged, not undone)")
            late.forEach { (m, r) -> b.appendLine("   merged ${legLine(m.out, names)} -> ${legLine(m.inn, names)}  vs  ${legLine(r.out, names)} -> ${legLine(r.inn, names)}") }
        }
        if (rejected.isNotEmpty()) {
            b.appendLine().appendLine("== Refused by core (both journals unchanged)")
            rejected.forEach { (e, o) -> b.appendLine("   HTTP ${o.status} ${o.reason}  ${legLine(e.out, names)} -> ${legLine(e.inn, names)}") }
        }
        if (merged.isNotEmpty()) {
            b.appendLine().appendLine("== Merged (undo one with pair.unmerge=<id>)")
            merged.forEach { m -> b.appendLine("   pair_merge_id ${m.pairMergeId}  ${m.edge.out.id} + ${m.edge.inn.id}") }
        }
        b.appendLine().appendLine("== Awaiting a partner: ${awaiting.size} legs")
        awaiting.sortedBy { it.date }.forEach {
            b.appendLine("   ${it.state}  ${it.date} account ${it.account} leg ${it.legId.take(12)} expects ${it.expectedPartner ?: "?"} by ${it.expectedPairBy ?: "-"}  " +
                    "waiting on ${it.waitingOn}  evidence ${it.scoreSoFar.map { r -> "${r.rule}+${r.points}" }}")
        }
        return b.toString()
    }
}
