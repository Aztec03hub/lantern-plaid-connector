package net.djvk.fireflyPlaidConnector2.sync

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
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

    /** Last good sync date per [itemId]; empty when there is no file or it is unreadable (see [load]). */
    suspend fun read(): Map<String, LocalDate> = load() ?: emptyMap()

    /**
     * The stored dates, empty with no file. An unreadable file is logged at ERROR (class name only) and moved aside
     * (kept for a person), then read as empty so the fixed window applies. Null only when the move itself failed, so
     * the bad file is still in place and [record] must not overwrite it.
     */
    private suspend fun load(): Map<String, LocalDate>? = withContext(Dispatchers.IO) {
        val file = path.toFile()
        if (!file.exists()) return@withContext emptyMap()
        try {
            mapper.readValue<Map<String, String>>(file).mapValues { LocalDate.parse(it.value) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            try {
                val aside = moveAside(path)
                logger.error("${path.fileName} could not be read (${e::class.simpleName}); moved it to ${aside.fileName}. Using the fixed lookback window.")
                emptyMap()
            } catch (m: Exception) {
                logger.error("${path.fileName} could not be read (${e::class.simpleName}) and could not be moved aside (${m::class.simpleName}); leaving it in place.")
                null
            }
        }
    }

    /**
     * Merges [synced] over the stored dates and replaces the file atomically ([writeAtomically]).
     * Best effort: a failure is logged at ERROR and never fails the poll. A no-op for an empty map.
     */
    suspend fun record(synced: Map<String, LocalDate>) {
        if (synced.isEmpty()) return
        try {
            val current = load() ?: return
            val merged = (current + synced).toSortedMap().mapValues { it.value.toString() }
            withContext(Dispatchers.IO) { writeAtomically(path, mapper.writeValueAsString(merged)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Could not write ${path.fileName}: ${e::class.simpleName}")
        }
    }
}
