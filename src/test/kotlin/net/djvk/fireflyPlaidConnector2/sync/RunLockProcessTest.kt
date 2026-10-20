package net.djvk.fireflyPlaidConnector2.sync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class RunLockProcessTest {
    private fun child(vararg args: String): Process =
        ProcessBuilder(
            System.getProperty("java.home") + "/bin/java", "-Xmx64m", "-cp", System.getProperty("java.class.path"),
            "net.djvk.fireflyPlaidConnector2.sync.RunLockChildMain", *args,
        ).start()

    /** Starts a holder and waits for HELD (15 s). */
    private fun holder(dir: Path, mode: String = "poll"): Process {
        val p = child("hold", dir.toString(), mode)
        val reader = p.inputStream.bufferedReader()
        val line = java.util.concurrent.CompletableFuture.supplyAsync { reader.readLine() }.get(15, TimeUnit.SECONDS)
        assertEquals("HELD", line, "holder said: $line, stderr: ${p.errorStream.bufferedReader().let { if (p.isAlive) "" else it.readText() }}")
        return p
    }

    private fun runTry(dir: Path, vararg extra: String): Triple<Int, String, String> {
        val p = child("try", dir.toString(), "pair", *extra)
        assertTrue(p.waitFor(20, TimeUnit.SECONDS), "try child hung")
        return Triple(p.exitValue(), p.inputStream.bufferedReader().readText(), p.errorStream.bufferedReader().readText())
    }

    @Test
    fun aSecondProcessExitsBusyAtOnceWhileTheFirstRuns(@TempDir dir: Path) {
        val h = holder(dir)
        try {
            val t0 = System.nanoTime()
            val (code, out, err) = runTry(dir)
            assertTrue((System.nanoTime() - t0) < 6_000_000_000L)
            assertEquals(75, code)
            assertTrue(err.contains("pid=${h.pid()}") && err.contains("mode=poll"), err)
            assertFalse(out.contains("RAN"))
        } finally {
            h.destroyForcibly()
        }
    }

    @Test
    fun aKilledHolderReleasesTheLockWithoutAnyCleanup(@TempDir dir: Path) {
        val h = holder(dir)
        h.destroyForcibly()
        h.waitFor()
        val (code, out, _) = runTry(dir)
        assertEquals(0, code)
        assertTrue(out.contains("RAN"))
        assertTrue(Files.exists(dir.resolve(RunLock.FILE_NAME)))
    }

    @Test
    fun exitingNormallyReleasesIt(@TempDir dir: Path) {
        val h = holder(dir)
        h.outputStream.close()
        assertTrue(h.waitFor(10, TimeUnit.SECONDS))
        val (code, out, _) = runTry(dir)
        assertEquals(0, code)
        assertTrue(out.contains("RAN"))
    }

    @Test
    fun aTryChildWithAWaitWindowWaitsForAHolder(@TempDir dir: Path) {
        val h = holder(dir)
        Thread { Thread.sleep(1000); h.outputStream.close() }.start()
        val (code, out, _) = runTry(dir, "10")
        assertEquals(0, code)
        assertTrue(out.contains("RAN"))
    }
}
