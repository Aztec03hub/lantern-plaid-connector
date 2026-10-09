package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PairApi
import net.djvk.fireflyPlaidConnector2.pairing.PairPass
import net.djvk.fireflyPlaidConnector2.pairing.PairPassReport
import net.djvk.fireflyPlaidConnector2.pairing.PairStateFile
import net.djvk.fireflyPlaidConnector2.pairing.RejectedPair
import net.djvk.fireflyPlaidConnector2.pairing.PairSettings
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * `syncMode: pair`: reads no Plaid data, only pairs the unpaired own-account transfer legs already in Firefly and exits
 * (see [PairPass] and docs/design/transfer-pairer.md). A dry run unless `fireflyPlaidConnector2.pair.dryRun=false`.
 * `fireflyPlaidConnector2.pair.unmerge=<pair_merge_id>` undoes one merge instead.
 */
@ConditionalOnProperty(name = ["fireflyPlaidConnector2.syncMode"], havingValue = "pair")
@Component
class PairRunner(
    private val syncHelper: SyncHelper,
    private val converter: TransactionConverter,
    private val pass: PairPass,
    private val settings: PairSettings,
    private val pairApi: PairApi,
    @Value("\${fireflyPlaidConnector2.pair.unmerge:}")
    private val unmerge: String = "",
    @Value("\${fireflyPlaidConnector2.pair.unmergeForce:false}")
    private val unmergeForce: Boolean = false,
    /** N1: the Plaid ids of the pair, needed only when the merge is not in pair-state.json. */
    @Value("\${fireflyPlaidConnector2.pair.unmergeOut:}")
    private val unmergeOut: String = "",
    @Value("\${fireflyPlaidConnector2.pair.unmergeInn:}")
    private val unmergeInn: String = "",
    /** nit1: `pair.forgive=<out plaid id>,<inn plaid id>` removes one entry from the rejected list so the pair can be proposed again. */
    @Value("\${fireflyPlaidConnector2.pair.forgive:}")
    private val forgive: String = "",
) : Runner {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun run() = runBlocking {
        syncHelper.setApiCreds()
        if (forgive.isNotBlank()) {
            val (out, inn) = forgive.split(",").map { it.trim() }.also { require(it.size == 2) { "pair.forgive takes <out plaid id>,<inn plaid id>" } }
            val state = PairStateFile.read(settings.directory)
            check(state.rejected.any { it.out == out && it.inn == inn }) { "$out,$inn is not in the rejected list of pair-state.json" }
            PairStateFile.write(settings.directory, state.copy(rejected = state.rejected.filterNot { it.out == out && it.inn == inn }))
            logger.info("Forgave {} and {}; the next pass may propose them again", out, inn)
            return@runBlocking
        }
        if (unmerge.isNotBlank()) {
            val id = unmerge.trim()
            // N1: read the state BEFORE core is touched, so an unmerge that cannot be remembered stops before it is done
            val state = PairStateFile.read(settings.directory)
            val gone = state.merges.firstOrNull { it.pairMergeId == id }
            val rejection = gone?.let { RejectedPair(it.out.id, it.inn.id, id, LocalDate.now().toString()) }
                ?: if (unmergeOut.isNotBlank() && unmergeInn.isNotBlank()) {
                    val out = unmergeOut.trim()
                    val inn = unmergeInn.trim()
                    // W1: a typo or swapped ids would record a rejection that never matches; only ids that really are the two legs of one merged journal are accepted
                    val today = LocalDate.now()
                    check(pass.isMergedInFirefly(out, inn, today.minusDays(settings.lookbackDays), today)) {
                        "No merged journal in Firefly (last ${settings.lookbackDays} days) has outflow $out and inflow $inn as its two legs. Nothing was unmerged. Check the ids and their order: <outflow plaid id> then <inflow plaid id>."
                    }
                    RejectedPair(out, inn, id, LocalDate.now().toString())
                } else error(
                    "pair_merge_id $id is not in pair-state.json, so the next pass would merge the pair again. Nothing was unmerged. " +
                        "Pass fireflyPlaidConnector2.pair.unmergeOut=<outflow plaid id> and pair.unmergeInn=<inflow plaid id> as well.",
                )
            pairApi.setAccessToken(settings.fireflyAccessToken)
            pairApi.unmerge(id, unmergeForce)
            logger.info("Unmerged pair_merge_id {}", id)
            // H1: remember that a person rejected this pair, or the next pass would merge it again
            PairStateFile.write(
                settings.directory,
                state.copy(merges = state.merges - listOfNotNull(gone).toSet(), rejected = (state.rejected + rejection).distinctBy { it.out to it.inn }),
            )
            return@runBlocking
        }
        converter.accountKinds = syncHelper.fetchAccountKinds()
        val today = LocalDate.now()
        val report = pass.run(today.minusDays(settings.lookbackDays), today)
        logger.info("Pairing finished: {} proposed, {} merged", report.result.proposals.count { it.auto }, report.merged.size)
        failOnRefusals(report)
    }

    /**
     * A2 + A1: a failed pair or a pair that core refused makes the pair step exit non-zero, in ONE message. A 409 `stale` (someone
     * edited a journal since it was read) is a normal event that the next pass decides again, so it never fails the step (N7).
     */
    internal fun failOnRefusals(report: PairPassReport) {
        val refused = report.rejected.filter { it.second.reason != "stale" }
        val parts = listOfNotNull(
            report.failed.takeIf { it.isNotEmpty() }?.let { f -> "${f.size} pair(s) failed to merge: " + f.joinToString("; ") { "${it.out}+${it.inn}: ${it.error}" } },
            refused.takeIf { it.isNotEmpty() }?.let { "core refused ${it.size} pair(s): ${it.map { r -> r.second.reason }.distinct()}" },
        )
        check(parts.isEmpty()) { "pairing: " + parts.joinToString(" | ") }
    }
}
