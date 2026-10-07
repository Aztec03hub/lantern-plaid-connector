package net.djvk.fireflyPlaidConnector2.sync

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.Path

/**
 * One Firefly write that Firefly permanently rejected (a 4xx other than an authentication or rate-limit failure), kept
 * so that one bad transaction doesn't stall every bank while it is never silently lost.
 *
 * @property operation "create", "update" or "delete"
 * @property key what identifies the transaction, so a newer write for it replaces this one: the Firefly id for an
 *  update or delete, the first Plaid id of its links for a create (see FireflyTransactionService.letterKey)
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
    /** How many retries Firefly has rejected since this letter was written; see [DeadLetterStore.maxAttempts]. */
    val attempts: Int = 0,
    /** True once [attempts] reached the cap: no longer retried, still reported, and left for a person to resolve. */
    val abandoned: Boolean = false,
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
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    val path = Path("$cursorFileDirectoryPath/plaid_dead_letters.json")

    private val unreadable = AtomicInteger(0)

    /** [add] and [remove] read, change and rewrite the whole file, so concurrent creates must not interleave them. */
    private val writeLock = kotlinx.coroutines.sync.Mutex()

    /**
     * How many times the file was found unreadable (and moved aside) since this was last asked; resets to 0. The poll
     * reports it in the result callback, so a lost dead letter file is never silent.
     */
    fun takeUnreadableCount(): Int = unreadable.getAndSet(0)

    /**
     * The letters in the file. A file that can't be parsed (for example after an upgrade changed the generated
     * `TransactionSplit`) is moved aside to `plaid_dead_letters.unreadable-<epoch>.json` (owner-only, kept for a
     * person to read) and logged at ERROR; the poll goes on with no letters instead of stopping every bank.
     */
    suspend fun read(): List<DeadLetter> = withContext(Dispatchers.IO) {
        val file = path.toFile()
        if (!file.exists()) return@withContext listOf()
        try {
            mapper.readValue<List<DeadLetter>>(file)
        } catch (e: IOException) {
            val aside = path.resolveSibling("plaid_dead_letters.unreadable-${System.currentTimeMillis()}.json")
            Files.move(path, aside, StandardCopyOption.REPLACE_EXISTING)
            unreadable.incrementAndGet()
            logger.error(
                "The dead letter file could not be read (${e::class.simpleName}); moved it to $aside. " +
                        "The writes in it are NOT being retried: read the moved file and re-enter them by hand."
            )
            listOf()
        }
    }

    /** Adds [letter], replacing any earlier one with the same operation and key. */
    suspend fun add(letter: DeadLetter) {
        writeLock.withLock {
            write(read().filterNot { it.operation == letter.operation && it.key == letter.key } + letter)
        }
    }

    /**
     * Drops the letters for [operation] and [key] (the write finally went through). A delete also drops every other
     * letter for the same Firefly transaction: an update of something that no longer exists can never succeed.
     */
    suspend fun remove(operation: String, key: String) = writeLock.withLock {
        val all = read()
        val remaining = all.filterNot {
            (it.operation == operation && it.key == key) || (operation == "delete" && it.fireflyId == key)
        }
        if (remaining.size != all.size) write(remaining)
    }

    companion object {
        /** A letter Firefly keeps rejecting is retried this many times, then abandoned (reported, not retried). */
        const val maxAttempts = 10

        /** The message of a letter that a pairing pass wrote before deleting a leg; see TransferReconciler. */
        const val PAIRING_IN_PROGRESS = "pairing in progress"
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
