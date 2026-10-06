package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.Path

/**
 * Manages the Plaid sync cursors used for transaction synchronization.
 *
 * The cursor file maps each Plaid access token to its current sync cursor, one `token|cursor` per line. Because it
 * contains live access tokens it is written owner-read/write only (0600) where the file system supports POSIX
 * permissions, and replaced atomically so that a crash mid-write can never leave a truncated file (which would
 * lose cursors, or make the next start fail).
 */
@Component
class CursorManager(
    @Value("\${fireflyPlaidConnector2.polled.cursorFileDirectoryPath}")
    private val cursorFileDirectoryPath: String,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    val cursorFilePath = Path("$cursorFileDirectoryPath/plaid_sync_cursors.txt")

    /**
     * Reads the cursor map from file storage, if it exists.
     * If it doesn't exist, returns an empty map.
     *
     * @throws IllegalStateException if a line is not in `token|cursor` form. The line content is deliberately not
     *  included in the message because it holds an access token.
     */
    suspend fun readCursorMap(): MutableMap<PlaidAccessToken, PlaidSyncCursor> {
        return withContext(Dispatchers.IO) {
            val file = cursorFilePath.toFile()
            logger.trace("Reading Plaid sync cursor map from $file")

            if (!file.exists()) {
                logger.trace("No existing Plaid sync cursor map found, starting from scratch")
                return@withContext mutableMapOf()
            }

            val cursors = mutableMapOf<PlaidAccessToken, PlaidSyncCursor>()
            file.readLines().forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed
                val parts = line.split("|", limit = 2)
                check(parts.size == 2 && parts[0].isNotBlank()) {
                    "Plaid sync cursor file $file is malformed at line ${index + 1}; expected 'token|cursor'. " +
                            "Fix or delete the file (deleting makes the connector re-initialize its cursors)."
                }
                cursors[parts[0]] = parts[1]
            }
            cursors
        }
    }

    /**
     * Writes the cursor map to file storage, atomically and with owner-only permissions.
     */
    suspend fun writeCursorMap(map: Map<PlaidAccessToken, PlaidSyncCursor>) {
        logger.trace("Writing ${map.size} Plaid sync cursors to map $cursorFilePath")
        return withContext(Dispatchers.IO) {
            val content = map.entries
                .filter { it.value != "" }
                .joinToString("\n") { (token, cursor) ->
                    "$token|$cursor"
                }

            val directory = cursorFilePath.toAbsolutePath().parent
            Files.createDirectories(directory)

            // Create the temp file already restricted, so the tokens are never briefly world-readable
            val temp = Files.createTempFile(directory, "plaid_sync_cursors", ".tmp")
            try {
                restrictToOwner(temp)
                Files.writeString(temp, content)
                try {
                    Files.move(temp, cursorFilePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: AtomicMoveNotSupportedException) {
                    // Some bind mounts and network file systems can't do atomic moves; fall back to a plain replace
                    Files.move(temp, cursorFilePath, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }

    private fun restrictToOwner(path: java.nio.file.Path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        } catch (e: UnsupportedOperationException) {
            logger.warn("File system does not support POSIX permissions; cursor file ${path.fileName} is not restricted")
        }
    }
}
