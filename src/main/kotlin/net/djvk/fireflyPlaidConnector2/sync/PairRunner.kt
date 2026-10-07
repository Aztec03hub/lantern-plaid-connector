package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PairApi
import net.djvk.fireflyPlaidConnector2.pairing.PairPass
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
            return@runBlocking
        }
        converter.accountKinds = syncHelper.fetchAccountKinds()
        val today = LocalDate.now()
        val report = pass.run(today.minusDays(settings.lookbackDays), today)
        logger.info("Pairing finished: {} proposed, {} merged", report.result.proposals.count { it.auto }, report.merged.size)
    }
}
