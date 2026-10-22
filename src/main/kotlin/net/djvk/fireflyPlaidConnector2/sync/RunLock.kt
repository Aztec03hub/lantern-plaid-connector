package net.djvk.fireflyPlaidConnector2.sync

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class RunLockBusyException(message: String) : RuntimeException(message)

/**
 * One connector run per state directory: a kernel lock (`FileChannel.tryLock`) on `connector.lock`. The kernel drops it when the
 * holder exits or dies, so there is no stale state. The one-line content (pid, mode, start) only names the holder to a loser.
 */
class RunLock private constructor(
    private val key: Path,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
        HELD.remove(key)
    }

    companion object {
        const val FILE_NAME = "connector.lock"
        const val EXIT_BUSY = 75
        const val EXIT_NO_LOCK = 78
        private val logger = LoggerFactory.getLogger(RunLock::class.java)

        /** Lock paths held by this JVM: a second tryLock in one JVM throws, and closing any channel on the file drops the JVM's lock. */
        private val HELD = ConcurrentHashMap<Path, String>()
        private val STALE_TEMP = Regex("^((item_status|plaid_sync_cursors|plaid_dead_letters)\\d+\\.tmp|(pair-state|loan-lag)\\.json\\.(\\d+\\.)?tmp|(pair-state|loan-lag|item_status|balance_gap|refresh_times)\\.json\\.[A-Za-z0-9_-]+\\.tmp)$")

        /** Throws [RunLockBusyException] (message names the holder) or IOException. Waits up to [waitSeconds], polling every 500 ms. */
        fun acquire(dir: Path, mode: String, waitSeconds: Long = 0): RunLock {
            Files.createDirectories(dir)
            val file = dir.toRealPath().resolve(FILE_NAME)
            val deadline = System.nanoTime() + waitSeconds * 1_000_000_000L
            while (true) {
                tryOnce(file, mode)?.let { return it }
                if (System.nanoTime() >= deadline) throw RunLockBusyException(busyMessage(file, mode))
                Thread.sleep(500)
            }
        }

        private fun tryOnce(file: Path, mode: String): RunLock? {
            val line = "pid=${ProcessHandle.current().pid()} mode=$mode since=${Instant.now()}"
            if (HELD.putIfAbsent(file, line) != null) return null
            var channel: FileChannel? = null
            try {
                channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                val lock = channel.tryLock()
                if (lock == null) {
                    channel.close()
                    HELD.remove(file)
                    return null
                }
                channel.truncate(0)
                channel.write(ByteBuffer.wrap("$line\n".toByteArray()), 0)
                return RunLock(file, channel, lock)
            } catch (e: Throwable) {
                runCatching { channel?.close() }
                HELD.remove(file)
                throw e
            }
        }

        private fun busyMessage(file: Path, mode: String): String {
            // HELD first: opening the file in a JVM that holds it would risk dropping our own lock
            val holder = HELD[file] ?: runCatching { Files.readString(file).trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "an unknown process"
            return "$file is held by $holder ; this run (mode $mode) did not start and changed nothing."
        }

        /** Takes the lock, removes stale temp files, runs [block], releases. Busy: prints "!!" to stderr, [exit] 75. Cannot lock: [exit] 78. */
        fun guarded(
            dir: Path,
            mode: String,
            waitSeconds: Long = 0,
            exit: (Int) -> Unit = { kotlin.system.exitProcess(it) },
            block: () -> Unit,
        ) {
            val lock = try {
                acquire(dir, mode, waitSeconds)
            } catch (e: RunLockBusyException) {
                val msg = "!! connector run refused: ${e.message} Wait for it to finish, then run again."
                logger.error(msg)
                System.err.println(msg)
                exit(EXIT_BUSY)
                return
            } catch (e: IOException) {
                val msg = "!! cannot take the run lock ${dir.resolve(FILE_NAME)}: ${e::class.simpleName}; this run (mode $mode) did not start."
                logger.error(msg)
                System.err.println(msg)
                exit(EXIT_NO_LOCK)
                return
            }
            lock.use {
                cleanStaleTemps(dir)
                block()
            }
        }

        /** Deletes crashed-run temp files by exact name pattern; call only while holding the lock. */
        internal fun cleanStaleTemps(dir: Path) {
            var count = 0
            runCatching {
                Files.newDirectoryStream(dir).use { s ->
                    for (p in s) {
                        if (Files.isRegularFile(p) && STALE_TEMP.matches(p.fileName.toString())) {
                            if (runCatching { Files.deleteIfExists(p) }.getOrDefault(false)) count++
                            else logger.warn("Could not delete stale temp file ${p.fileName}")
                        }
                    }
                }
            }
            if (count > 0) logger.info("Removed $count stale temp file(s) from $dir")
        }
    }
}
