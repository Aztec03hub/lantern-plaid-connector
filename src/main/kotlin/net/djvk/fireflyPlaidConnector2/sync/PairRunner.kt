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
) : Runner {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun run() = runBlocking {
        syncHelper.setApiCreds()
        if (unmerge.isNotBlank()) {
            pairApi.setAccessToken(settings.fireflyAccessToken)
            pairApi.unmerge(unmerge.trim(), unmergeForce)
            logger.info("Unmerged pair_merge_id {}", unmerge.trim())
            // H1: remember that a person rejected this pair, or the next pass would merge it again
            val state = PairStateFile.read(settings.directory)
            val gone = state.merges.firstOrNull { it.pairMergeId == unmerge.trim() }
            if (gone == null) logger.warn("pair_merge_id {} is not in pair-state.json; the next pass records it when it sees both legs single again", unmerge.trim())
            else PairStateFile.write(
                settings.directory,
                state.copy(
                    merges = state.merges - gone,
                    rejected = (state.rejected + RejectedPair(gone.out.id, gone.inn.id, gone.pairMergeId, LocalDate.now().toString())).distinctBy { it.out to it.inn },
                ),
            )
            return@runBlocking
        }
        converter.accountKinds = syncHelper.fetchAccountKinds()
        val today = LocalDate.now()
        val report = pass.run(today.minusDays(settings.lookbackDays), today)
        logger.info("Pairing finished: {} proposed, {} merged", report.result.proposals.count { it.auto }, report.merged.size)
        // A2: the pass went on past a failed pair and saved what it merged; now say so with a non-zero exit
        check(report.failed.isEmpty()) { "${report.failed.size} pair(s) failed to merge: " + report.failed.joinToString("; ") { "${it.out}+${it.inn}: ${it.error}" } }
        failOnRefusals(report)
    }

    /** A1: a pair that core refused is a WARN in the log only; a non-zero exit makes the nightly say so. */
    internal fun failOnRefusals(report: PairPassReport) = check(report.rejected.isEmpty()) {
        "pairing: core refused ${report.rejected.size} pair(s): ${report.rejected.map { it.second.reason }.distinct()}"
    }
}
