package net.djvk.fireflyPlaidConnector2.sync

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.Path

/**
 * One Firefly write that Firefly permanently rejected (a 4xx other than an authentication or rate-limit failure), kept
 * so that one bad transaction doesn't stall every bank while it is never silently lost.
 *
 * @property operation "create", "update" or "delete"
 * @property key what identifies the transaction, so a newer write for it replaces this one: the Firefly id for an
 *  update or delete, the external id for a create
 * @property split what was to be written (null for a delete). This is transaction content, which is why the file is
 *  written owner-only
 * @property message Firefly's own error message
 */
data class DeadLetter(
    val operation: String,
    val key: String,
    val fireflyId: String? = null,
    val split: TransactionSplit? = null,
    val changesType: Boolean = false,
    val message: String = "",
)

/**
 * The durable list of [DeadLetter]s, `plaid_dead_letters.json` next to the cursor file, replaced atomically and
 * readable only by the owner. [FireflyTransactionService] retries them at the start of every poll and removes the ones
 * that go through; the count is reported in the result callback (status "partial").
 */
@Component
class DeadLetterStore(
    @Value("\${fireflyPlaidConnector2.polled.cursorFileDirectoryPath}")
    cursorFileDirectoryPath: String,
) {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    val path = Path("$cursorFileDirectoryPath/plaid_dead_letters.json")

    suspend fun read(): List<DeadLetter> = withContext(Dispatchers.IO) {
        val file = path.toFile()
        if (!file.exists()) listOf() else mapper.readValue(file)
    }

    /** Adds [letter], replacing any earlier one with the same operation and key. */
    suspend fun add(letter: DeadLetter) {
        write(read().filterNot { it.operation == letter.operation && it.key == letter.key } + letter)
    }

    /** Drops the letters for [operation] and [key] (the write finally went through). */
    suspend fun remove(operation: String, key: String) {
        val all = read()
        val remaining = all.filterNot { it.operation == operation && it.key == key }
        if (remaining.size != all.size) write(remaining)
    }

    private suspend fun write(letters: List<DeadLetter>) = withContext(Dispatchers.IO) {
        val directory = path.toAbsolutePath().parent
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, "plaid_dead_letters", ".tmp")
        try {
            try {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"))
            } catch (e: UnsupportedOperationException) {
                // file system without POSIX permissions: nothing to restrict
            }
            Files.writeString(temp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(letters))
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
