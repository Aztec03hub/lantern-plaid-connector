package net.djvk.fireflyPlaidConnector2.sync

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import kotlin.io.path.Path

/**
 * The date of each investment Item's last good investment sync, as `investment_sync.json` next to the cursor file,
 * keyed by the non-secret [ItemStatusStore.itemId]. Plaid has no cursor for investment transactions, so this date is
 * what lets a poll after a long outage read back to the day the Item last worked. Holds no token and no figures.
 *
 * Both directions are best effort for the poll: an unreadable file reads as "no record" (the fixed window is used) and
 * a write failure never fails the poll.
 */
@Component
class InvestmentSyncStore(
    @Value("\${fireflyPlaidConnector2.polled.cursorFileDirectoryPath}")
    cursorFileDirectoryPath: String,
    private val itemStatusStore: ItemStatusStore,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val mapper = jacksonObjectMapper()
    val path = Path("$cursorFileDirectoryPath/investment_sync.json")

    fun itemId(accessToken: PlaidAccessToken): String = itemStatusStore.itemId(accessToken)

    /** Last good sync date per [itemId]; empty when there is no file or it is unreadable (WARN, class name only). */
    suspend fun read(): Map<String, LocalDate> = withContext(Dispatchers.IO) {
        val file = path.toFile()
        if (!file.exists()) return@withContext emptyMap()
        try {
            mapper.readValue<Map<String, String>>(file).mapValues { LocalDate.parse(it.value) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Ignoring unreadable ${path.fileName}, using the fixed lookback window: ${e::class.simpleName}")
            emptyMap()
        }
    }

    /**
     * Merges [synced] over the stored dates and replaces the file atomically (temp file, fsync, rename).
     * Best effort: a failure is logged at ERROR and never fails the poll. A no-op for an empty map.
     */
    suspend fun record(synced: Map<String, LocalDate>) {
        if (synced.isEmpty()) return
        try {
            val merged = (read() + synced).toSortedMap().mapValues { it.value.toString() }
            withContext(Dispatchers.IO) {
                val directory = path.toAbsolutePath().parent
                Files.createDirectories(directory)
                val temp = Files.createTempFile(directory, "investment_sync", ".tmp")
                try {
                    writeDurably(temp, mapper.writeValueAsString(merged))
                    try {
                        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    Files.deleteIfExists(temp)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Could not write ${path.fileName}: ${e::class.simpleName}")
        }
    }
}
