package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import net.djvk.fireflyPlaidConnector2.transactions.PlaidFireflyTransaction
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import net.djvk.fireflyPlaidConnector2.transactions.TransferMatcher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * Pairs the single legs of own-account transfers that are already in Firefly, whatever order or split they were
 * imported in (one combined run, one run per bank, runs in parallel): each transfer ends as ONE journal with TWO Plaid
 * links. It reads Firefly's state, not a run's in-memory set, and changing nothing when there is nothing to pair makes
 * it safe to run after every batch and poll, and on its own (syncMode=pair) to repair data imported before it existed.
 *
 * A pair is two single-link withdrawal/deposit journals on two different configured accounts with the same amount, in
 * opposite directions, within transferMatchWindowDays ([TransferMatcher]'s rules). Ambiguous candidates (another
 * journal could pair with either side) are left alone: pairing the wrong two would corrupt both.
 *
 * A merge is two writes: (a) the deposit-side journal is deleted, which frees its Plaid link; (b) the withdrawal-side
 * journal is updated to the pair type with both accounts and both links. A dead letter that re-creates the deleted leg
 * is written first and removed after (b), so a crash between the two leaves the leg to be re-created by the next poll's
 * dead letter retry (or the next import) and paired again by the next pass.
 *
 * Passes run one at a time: an OS file lock in the persistence directory (shared by every process that uses that
 * directory, which is where importers coordinate; a Firefly-side advisory lock would also cover importers on other
 * hosts, but needs an endpoint the Firefly fork does not have) plus a lock for passes inside one JVM. Each pass reads
 * Firefly again after taking the lock, so a pass never works from a view another pass has changed.
 */
@Component
class TransferReconciler(
    private val syncHelper: SyncHelper,
    private val fireflyTransactionService: FireflyTransactionService,
    private val converter: TransactionConverter,
    private val deadLetterStore: DeadLetterStore,
    @Value("\${fireflyPlaidConnector2.timeZone}")
    timeZoneString: String,
    @Value("\${fireflyPlaidConnector2.transferMatchWindowDays}")
    val transferMatchWindowDays: Long,
    /** Off unless asked for: skips the pass after batch and poll runs. syncMode=pair always runs it. */
    @Value("\${fireflyPlaidConnector2.reconcileTransfers:false}")
    private val enabled: Boolean = false,
    @Value("\${fireflyPlaidConnector2.pendingTag:}")
    private val pendingTag: String = "",
    /** How far back syncMode=pair looks for unpaired legs. */
    @Value("\${fireflyPlaidConnector2.pair.lookbackDays:3650}")
    val lookbackDays: Long = 3650,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val zoneId = ZoneId.of(timeZoneString)
    private val matcher = TransferMatcher(timeZoneString, transferMatchWindowDays)
    private val windowSeconds = transferMatchWindowDays * 24 * 60 * 60

    /** Two single legs to merge: [keep] (the withdrawal side) survives, [drop] (the deposit side) is deleted. */
    data class Merge(val keep: FireflyTransactionDto, val drop: FireflyTransactionDto)

    companion object {
        private const val MAX_PAGES = 1000
        private val jvmLock = Mutex()
    }

    /** Pairs what is in Firefly between [start] and [end]; returns how many pairs it merged. */
    suspend fun reconcile(start: LocalDate, end: LocalDate, force: Boolean = false): Int {
        if (!enabled && !force) return 0
        return withPairingLock {
            // A leg whose pairing was cut short comes back first, so this pass pairs it again
            fireflyTransactionService.retryDeadLetters()
            val existing = fireflyTransactionService.fetchFireflyTransactionsBetween(start, end, MAX_PAGES)
            val configured = syncHelper.getAllPlaidAccessTokenAccountIdSets().first.values.toSet()
            val merges = plan(existing, configured)
            var merged = 0
            for (merge in merges) {
                if (execute(merge)) merged++
            }
            logger.info("Transfer pairing: {} pairs found, {} merged", merges.size, merged)
            merged
        }
    }

    /** The pass after a poll: the pull window, ending today. */
    suspend fun reconcileWindow(): Int =
        reconcile(fireflyTransactionService.windowStart(), LocalDate.now(zoneId))

    /** The merges to make in [existing]: see the class comment for what is a pair. */
    fun plan(existing: List<TransactionRead>, configuredAccountIds: Set<Int>): List<Merge> {
        val singles = existing
            .filter { it.attributes.transactions.size == 1 }
            .map { FireflyTransactionDto(it.id, it.attributes.transactions.first()) }
            .filter { dto ->
                val links = dto.tx.plaidLinks.orEmpty()
                links.size == 1 && links[0].leg == PlaidLinkLeg.single &&
                        (pendingTag.isBlank() || dto.tx.tags?.contains(pendingTag) != true) &&
                        ownAccount(dto) in configuredAccountIds
            }
            .map { PlaidFireflyTransaction.FireflyTransaction(it) }

        return matcher.match(singles)
            .filterIsInstance<PlaidFireflyTransaction.Transfer>()
            .filter { transfer -> !isAmbiguous(transfer, singles) }
            .map { transfer ->
                val members = listOf(transfer.deposit, transfer.withdrawal).map { it.fireflyTransaction!! }
                val keep = members.first { it.tx.type == TransactionTypeProperty.withdrawal }
                Merge(keep, members.first { it !== keep })
            }
            .sortedWith(compareBy({ it.keep.tx.date }, { it.keep.id }))
    }

    private fun ownAccount(dto: FireflyTransactionDto): Int? = when (dto.tx.type) {
        TransactionTypeProperty.withdrawal -> dto.tx.sourceId?.toIntOrNull()
        TransactionTypeProperty.deposit -> dto.tx.destinationId?.toIntOrNull()
        else -> null
    }

    /** True if a journal other than the pair could also pair with either side of it. */
    private fun isAmbiguous(
        transfer: PlaidFireflyTransaction.Transfer,
        all: List<PlaidFireflyTransaction.FireflyTransaction>,
    ): Boolean {
        val pair = listOf(transfer.deposit, transfer.withdrawal)
        return pair.any { side ->
            all.any { other ->
                other !in pair &&
                        other.amount == -side.amount &&
                        other.fireflyAccountId != side.fireflyAccountId &&
                        abs(other.getTimestamp(zoneId).toEpochSecond() - side.getTimestamp(zoneId).toEpochSecond()) < windowSeconds
            }
        }
    }

    /** @return true if the two journals are now one */
    private suspend fun execute(merge: Merge): Boolean {
        val keep = merge.keep
        val drop = merge.drop
        val dropPlaidId = drop.tx.plaidLinks.orEmpty().single().plaidTransactionId
        val recreate = converter.recreateOf(drop.tx)
        val update = converter.mergeSingles(keep, drop)

        deadLetterStore.add(DeadLetter("create", dropPlaidId, null, recreate, false, "pairing in progress"))
        syncHelper.deleteBatchInFirefly(listOf(drop.transactionId))
        try {
            syncHelper.updateBatchInFirefly(listOf(update))
        } catch (e: ClientRequestException) {
            // Firefly refused the pair for good: put the deleted leg back as it was and leave both singles alone
            logger.error(
                "Firefly refused to pair {} with {} (HTTP {}); putting the deleted leg back",
                keep.id, dropPlaidId, e.response.status.value,
            )
            syncHelper.optimisticInsertBatchIntoFirefly(listOf(FireflyTransactionDto(null, recreate)))
            deadLetterStore.remove("create", dropPlaidId)
            return false
        } catch (e: CancellationException) {
            throw e
        }
        deadLetterStore.remove("create", dropPlaidId)
        logger.info("Paired Firefly transaction {} with Plaid transaction {}", keep.id, dropPlaidId)
        return true
    }

    private suspend fun <T> withPairingLock(block: suspend () -> T): T = jvmLock.withLock {
        val path = deadLetterStore.path.resolveSibling("plaid_pairing.lock")
        val channel = withContext(Dispatchers.IO) {
            Files.createDirectories(path.toAbsolutePath().parent)
            FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        }
        try {
            val lock = withContext(Dispatchers.IO) { channel.lock() }
            try {
                block()
            } finally {
                lock.release()
            }
        } finally {
            channel.close()
        }
    }
}
