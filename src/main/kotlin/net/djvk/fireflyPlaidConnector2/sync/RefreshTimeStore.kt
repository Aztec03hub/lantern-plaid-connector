package net.djvk.fireflyPlaidConnector2.sync

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import kotlin.io.path.Path

/**
 * When each Item was last asked to refresh, as `refresh_times.json` next to the cursor file, keyed by the non-secret
 * [ItemStatusStore.itemId] (never the access token). Plaid bills each refresh, and the nightly one-shot restarts the
 * process, so without this file every run would refresh every Item again.
 *
 * Best effort both ways: an unreadable file reads as empty (one extra refresh at worst) and a write failure is logged
 * and swallowed, because a lost timestamp can only cost one extra bill, never data.
 */
@Component
class RefreshTimeStore(
    @Value("\${fireflyPlaidConnector2.polled.cursorFileDirectoryPath}")
    cursorFileDirectoryPath: String,
    private val itemStatusStore: ItemStatusStore,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val mapper = jacksonObjectMapper()
    val path = Path("$cursorFileDirectoryPath/refresh_times.json")

    fun itemId(accessToken: PlaidAccessToken): String = itemStatusStore.itemId(accessToken)

    /** Last refresh attempt per [itemId]; empty with no file or an unreadable one (class name logged, never the content). */
    suspend fun read(): Map<String, Instant> = withContext(Dispatchers.IO) {
        val file = path.toFile()
        if (!file.exists()) return@withContext emptyMap()
        try {
            mapper.readValue<Map<String, String>>(file).mapValues { Instant.parse(it.value) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("${path.fileName} could not be read (${e::class.simpleName}); treating every Item as not yet refreshed.")
            emptyMap()
        }
    }

    suspend fun write(times: Map<String, Instant>) {
        try {
            val text = mapper.writeValueAsString(times.toSortedMap().mapValues { it.value.toString() })
            withContext(Dispatchers.IO) { writeAtomically(path, text) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Could not write ${path.fileName}: ${e::class.simpleName}")
        }
    }
}
