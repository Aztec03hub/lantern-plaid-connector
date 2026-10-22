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
import kotlin.io.path.Path

/** The transfer's source account when the mark was made, and the Plaid ids of the legs that flipped since. */
data class FlipMark(val sourceId: String?, val legs: Set<String>)

/**
 * Remembers, between polls, which leg of a Firefly transfer flipped direction while its partner had not, so that the
 * partner flipping in a LATER sync can still swap the transfer. `flipped_legs.json` holds only opaque ids (Firefly
 * transaction ids, account ids, Plaid ids): no token, amount or text.
 */
@Component
class FlippedLegStore(
    @Value("\${fireflyPlaidConnector2.polled.cursorFileDirectoryPath}") cursorFileDirectoryPath: String,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val mapper = jacksonObjectMapper()
    val path = Path("$cursorFileDirectoryPath/flipped_legs.json")

    /** A missing or unreadable file reads as empty: a lost mark costs one more review, never a wrong swap. */
    suspend fun read(): Map<String, FlipMark> = withContext(Dispatchers.IO) {
        if (!Files.exists(path)) return@withContext emptyMap()
        try {
            mapper.readValue<Map<String, FlipMark>>(path.toFile())
        } catch (e: Exception) {
            logger.error("Could not read {} ({}); treating it as empty", path.fileName, e::class.java.simpleName)
            emptyMap()
        }
    }

    /** Merges [changes] into the file (a null removes the key) and replaces it atomically. A write failure is logged, never thrown. */
    suspend fun apply(changes: Map<String, FlipMark?>) {
        if (changes.isEmpty()) return
        withContext(Dispatchers.IO) {
            val merged = read().toMutableMap()
            changes.forEach { (k, v) -> if (v == null) merged.remove(k) else merged[k] = v }
            val directory = path.toAbsolutePath().parent
            try {
                Files.createDirectories(directory)
                val temp = Files.createTempFile(directory, "flipped_legs", ".tmp")
                try {
                    writeDurably(temp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(merged))
                    try {
                        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    Files.deleteIfExists(temp)
                }
            } catch (e: Exception) {
                logger.error("Could not write {} ({}); the flip will be remembered again at the next flip", path.fileName, e::class.java.simpleName)
            }
        }
    }
}
