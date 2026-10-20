package net.djvk.fireflyPlaidConnector2.sync

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class RunLockTest {
    @Test
    fun aSecondAcquireInTheSameJvmIsBusyAndTheFirstKeepsItsLock(@TempDir dir: Path) {
        val a = RunLock.acquire(dir, "poll")
        repeat(2) {
            val e = assertThrows<RunLockBusyException> { RunLock.acquire(dir, "pair") }
            assertTrue(e.message!!.contains("mode=poll"), e.message)
        }
        a.close()
        RunLock.acquire(dir, "pair").close()
    }

    @Test
    fun twoThreadsExactlyOneProceeds(@TempDir dir: Path) {
        val start = CountDownLatch(1)
        val inside = AtomicInteger(0)
        val maxInside = AtomicInteger(0)
        val winners = AtomicInteger(0)
        val losers = AtomicInteger(0)
        val threads = (1..8).map {
            Thread {
                start.await()
                try {
                    RunLock.acquire(dir, "poll").use {
                        winners.incrementAndGet()
                        maxInside.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                        Thread.sleep(200)
                        inside.decrementAndGet()
                    }
                } catch (e: RunLockBusyException) {
                    losers.incrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(8, winners.get() + losers.get())
        assertTrue(winners.get() >= 1)
        assertEquals(1, maxInside.get())
    }

    @Test
    fun waitingAcquiresAfterTheHolderReleases(@TempDir dir: Path) {
        val holder = RunLock.acquire(dir, "poll")
        Thread { Thread.sleep(1000); holder.close() }.start()
        val t0 = System.nanoTime()
        RunLock.acquire(dir, "pair", waitSeconds = 5).close()
        val s = (System.nanoTime() - t0) / 1e9
        assertTrue(s in 0.8..4.0, "$s")
    }

    @Test
    fun waitingGivesUpWithBusy(@TempDir dir: Path) {
        RunLock.acquire(dir, "poll").use {
            val t0 = System.nanoTime()
            assertThrows<RunLockBusyException> { RunLock.acquire(dir, "pair", waitSeconds = 1) }
            assertTrue((System.nanoTime() - t0) / 1e9 >= 1.0)
        }
    }

    @Test
    fun missingDirectoryIsCreated(@TempDir dir: Path) {
        RunLock.acquire(dir.resolve("a/b"), "poll").close()
        assertTrue(Files.exists(dir.resolve("a/b/connector.lock")))
    }

    @Test
    fun guardedRunsTheBlockReleasesAndCleansStaleTemps(@TempDir dir: Path) {
        val stale = listOf("item_status123.tmp", "plaid_sync_cursors9.tmp", "plaid_dead_letters1.tmp", "pair-state.json.tmp", "pair-state.json.55.tmp", "loan-lag.json.tmp", "item_status.json.77.tmp")
        val keep = listOf("plaid_sync_cursors.txt", "item_status.json", "pair-state.json", "pair-state.json.v1.bak", "plaid_dead_letters.unreadable-1.json", "notes.tmp")
        (stale + keep).forEach { Files.writeString(dir.resolve(it), "b-$it") }
        var ran = 0
        RunLock.guarded(dir, "poll") { ran++ }
        assertEquals(1, ran)
        val left = Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList().toSet() }
        assertEquals((keep + RunLock.FILE_NAME).toSet(), left)
        keep.forEach { assertEquals("b-$it", Files.readString(dir.resolve(it))) }
        RunLock.acquire(dir, "pair").close()
    }

    private fun captureErr(f: () -> Unit): String {
        val old = System.err
        val buf = ByteArrayOutputStream()
        System.setErr(PrintStream(buf, true))
        try { f() } finally { System.setErr(old) }
        return buf.toString()
    }

    @Test
    fun guardedWhenBusyPrintsAndExits75WithoutRunningTheBlock(@TempDir dir: Path) {
        var code: Int? = null
        var ran = false
        RunLock.acquire(dir, "poll").use {
            val err = captureErr { RunLock.guarded(dir, "pair", exit = { code = it }) { ran = true } }
            assertTrue(err.contains("connector.lock") && err.contains("did not start") && err.contains("mode=poll") &&
                err.contains("pid=${ProcessHandle.current().pid()}"), err)
        }
        assertEquals(75, code)
        assertFalse(ran)
    }

    @Test
    fun anUncreatableLockFileExits78(@TempDir dir: Path) {
        assumeFalse(System.getProperty("user.name") == "root")
        var code: Int? = null
        var ran = false
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"))
        try {
            assertThrows<IOException> { RunLock.acquire(dir, "poll") }
            captureErr { RunLock.guarded(dir, "poll", exit = { code = it }) { ran = true } }
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
        }
        assertEquals(78, code)
        assertFalse(ran)
    }
}
