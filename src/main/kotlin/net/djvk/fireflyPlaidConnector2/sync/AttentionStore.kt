package net.djvk.fireflyPlaidConnector2.sync

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import kotlin.io.path.Path

/**
 * One thing a person should look at. Carries only a kind, an id and fixed words or status codes: no token, no
 * description, no amount and no message text of Firefly or Plaid.
 */
data class Attention(val kind: String, val ref: String, val detail: String = "", val since: String = "")

private data class AttentionFile(val version: Int = 1, val updatedAt: String = "", val count: Int = 0, val items: List<Attention> = listOf())

/**
 * `needs_attention.json` next to the cursor file: what the connector found this poll that it can't fix itself, for the
 * dashboard to read. Replaced atomically, owner-only. Best effort: a write failure is logged and never fails a poll that
 * already committed its cursors.
 */
@Component
class AttentionStore(
    @Value("\${fireflyPlaidConnector2.polled.cursorFileDirectoryPath}")
    cursorFileDirectoryPath: String,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    val path = Path("$cursorFileDirectoryPath/needs_attention.json")

    /** The items in the file; empty when there is none or it can't be read. */
    suspend fun read(): List<Attention> = withContext(Dispatchers.IO) {
        val file = path.toFile()
        if (!file.isFile) return@withContext listOf()
        try {
            mapper.readValue<AttentionFile>(file).items
        } catch (e: Exception) {
            logger.error("Ignoring unreadable needs_attention.json: ${e::class.simpleName}")
            listOf()
        }
    }

    /**
     * Items of the [replaces] kinds are replaced by [fresh]; every other kind is kept as it was. A `since` is carried
     * over from the old item of the same kind and ref, else [now]. Never throws except cancellation.
     */
    suspend fun record(now: Instant, fresh: List<Attention>, replaces: Set<String>) {
        try {
            val old = read()
            val oldSince = old.associate { (it.kind to it.ref) to it.since }
            val cutoff = now.minus(Duration.ofDays(CONFLICT_DAYS))
            val kept = old.filter { it.kind !in replaces }
                .filterNot { it.kind == CONFLICT && runCatching { Instant.parse(it.since).isBefore(cutoff) }.getOrDefault(false) }
            val stamped = fresh.map { it.copy(since = oldSince[it.kind to it.ref]?.takeIf { s -> s.isNotBlank() } ?: now.toString()) }
            val all = (kept + stamped).distinctBy { it.kind to it.ref }.sortedWith(compareBy({ it.kind }, { it.ref }))
            // ponytail: rewritten whole on every poll, fine for a few KB; the cap keeps it under the app reader's bound
            val text = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(AttentionFile(1, now.toString(), all.size, all.take(MAX_ITEMS)))
            withContext(Dispatchers.IO) { writeAtomically(path, text) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Could not write needs_attention.json: ${e::class.simpleName}")
        }
    }

    companion object {
        const val CONFLICT = "update_conflict"
        const val CONFLICT_DAYS = 30L
        const val MAX_ITEMS = 200
    }
}
