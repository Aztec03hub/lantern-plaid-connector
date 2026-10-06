package net.djvk.fireflyPlaidConnector2.sync

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import kotlin.io.path.Path

/**
 * What the connector last knew about one Item (bank login), for whoever monitors it (Lantern reads `item_status.json`).
 * Holds no secret: [item] is a short SHA-256 of the access token, [accessToken] is the redacted form.
 *
 * @property lastSuccessfulSync the last poll in which this Item was fetched and its changes were committed
 * @property lastErrorCode Plaid's `error_code` (or exception class) of the most recent failure, null once it syncs again
 */
data class ItemStatus(
    val item: String,
    val institution: String,
    val accessToken: String,
    val lastSuccessfulSync: String? = null,
    val lastFailure: String? = null,
    val lastErrorCode: String? = null,
)

/**
 * Persists one [ItemStatus] per Item next to the cursor file, as `item_status.json`, replaced atomically.
 *
 * Writing it is best effort for the sync (a failure is logged at ERROR but never fails a poll that already committed
 * its cursors), because refusing to commit cursors over a status file would turn a monitoring problem into stalled banks.
 */
@Component
class ItemStatusStore(
    @Value("\${fireflyPlaidConnector2.polled.cursorFileDirectoryPath}")
    cursorFileDirectoryPath: String,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val mapper = jacksonObjectMapper()
    val path = Path("$cursorFileDirectoryPath/item_status.json")

    /** Stable, non-secret id of an Item. */
    fun itemId(accessToken: PlaidAccessToken): String =
        MessageDigest.getInstance("SHA-256").digest(accessToken.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    /** The stored statuses by [ItemStatus.item]; empty when there is no file. A corrupt file is an error, not an empty map. */
    suspend fun read(): Map<String, ItemStatus> = withContext(Dispatchers.IO) {
        val file = path.toFile()
        if (!file.exists()) emptyMap() else mapper.readValue<List<ItemStatus>>(file).associateBy { it.item }
    }

    /**
     * Records the outcome of a poll: every Item in [succeeded] is stamped as synced at [now], every Item in [failed]
     * gets its failure recorded (and keeps its previous last success).
     *
     * @return the last successful sync of each failed Item (by [ItemStatus.item]), null if it never succeeded
     */
    suspend fun record(
        now: Instant,
        succeeded: List<ItemRef>,
        failed: List<Pair<ItemRef, String>>,
    ): Map<String, String?> {
        val lastSuccess = mutableMapOf<String, String?>()
        try {
            val statuses = try {
                read().toMutableMap()
            } catch (e: Exception) {
                // An unreadable status file must not stall the sync; it is rebuilt from this poll on
                logger.error("Ignoring unreadable item status file ${path.fileName}: ${e::class.simpleName}")
                mutableMapOf()
            }
            for (ref in succeeded) {
                statuses[ref.id] = ItemStatus(ref.id, ref.institution, ref.redactedToken, now.toString())
            }
            for ((ref, code) in failed) {
                val before = statuses[ref.id]
                lastSuccess[ref.id] = before?.lastSuccessfulSync
                statuses[ref.id] = ItemStatus(
                    ref.id, ref.institution, ref.redactedToken, before?.lastSuccessfulSync, now.toString(), code,
                )
            }
            write(statuses.values.sortedBy { it.item })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Could not write the item status file ${path.fileName}: ${e::class.simpleName}")
        }
        return lastSuccess
    }

    private suspend fun write(statuses: List<ItemStatus>) = withContext(Dispatchers.IO) {
        val directory = path.toAbsolutePath().parent
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, "item_status", ".tmp")
        try {
            Files.writeString(temp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(statuses))
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** An Item as the status store identifies it. */
    data class ItemRef(val id: String, val institution: String, val redactedToken: String)

    fun ref(accessToken: PlaidAccessToken, institution: String) =
        ItemRef(itemId(accessToken), institution, net.djvk.fireflyPlaidConnector2.util.Utilities.redactAccessToken(accessToken))
}
