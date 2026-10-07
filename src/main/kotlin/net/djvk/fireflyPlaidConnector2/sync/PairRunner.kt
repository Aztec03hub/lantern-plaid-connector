package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * `syncMode: pair`: reads no Plaid data, only pairs the unpaired own-account transfer legs already in Firefly (see
 * [TransferReconciler]) and exits. For repairing data imported before pairing was order independent.
 */
@ConditionalOnProperty(name = ["fireflyPlaidConnector2.syncMode"], havingValue = "pair")
@Component
class PairRunner(
    private val syncHelper: SyncHelper,
    private val converter: TransactionConverter,
    private val reconciler: TransferReconciler,
) : Runner {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun run() = runBlocking {
        syncHelper.setApiCreds()
        converter.accountKinds = syncHelper.fetchAccountKinds()
        val today = LocalDate.now()
        val merged = reconciler.reconcile(today.minusDays(reconciler.lookbackDays), today, force = true)
        logger.info("Pairing finished: {} transfers merged", merged)
    }
}
