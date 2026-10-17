package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

internal class K5DurabilityTest {
    private fun mode(p: Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(p))
    private fun names(dir: Path) = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    @Test
    fun widePreexistingCursorFileBecomesOwnerOnly(@TempDir dir: Path) = runBlocking<Unit> {
        val manager = CursorManager(dir.toString())
        Files.writeString(manager.cursorFilePath, "access-fake-1|cur-0")
        Files.setPosixFilePermissions(manager.cursorFilePath, PosixFilePermissions.fromString("rw-r--r--"))

        manager.writeCursorMap(mapOf("access-fake-1" to "cur-1"))

        assertEquals("rw-------", mode(manager.cursorFilePath))
    }

    @Test
    fun oldFormatFileLoadsAndRewritesByteForByte(@TempDir dir: Path) = runBlocking<Unit> {
        val manager = CursorManager(dir.toString())
        val old = "access-fake-1|cur-1\naccess-fake-2|a|b"
        Files.writeString(manager.cursorFilePath, old)

        manager.writeCursorMap(manager.readCursorMap())

        assertEquals(old, Files.readString(manager.cursorFilePath))
    }

    @Test
    fun failedMoveKeepsTheOldContentAndLeavesNoTempFile(@TempDir dir: Path) = runBlocking<Unit> {
        val manager = CursorManager(dir.toString())
        // The target name is a non-empty directory, so the move onto it must throw
        Files.createDirectory(manager.cursorFilePath)
        Files.writeString(manager.cursorFilePath.resolve("keep"), "old")

        assertThrows<IOException> { runBlocking { manager.writeCursorMap(mapOf("access-fake-1" to "cur-1")) } }

        assertEquals("old", Files.readString(manager.cursorFilePath.resolve("keep")))
        assertEquals(listOf("plaid_sync_cursors.txt"), names(dir))
    }

    @Test
    fun itemStatusFileIsOwnerOnlyHoldsNoTokenAndClearsOnSuccess(@TempDir dir: Path) = runBlocking<Unit> {
        val store = ItemStatusStore(dir.toString())
        val ref = store.ref("access-fake-1", "Test Bank")

        store.record(Instant.parse("2026-01-02T03:04:05Z"), emptyList(), listOf(ref to "ITEM_LOGIN_REQUIRED"))

        assertEquals("rw-------", mode(store.path))
        assertFalse(Files.readString(store.path).contains("access-fake-1"))
        assertEquals("ITEM_LOGIN_REQUIRED", store.read()[ref.id]!!.lastErrorCode)
        assertEquals(listOf("item_status.json"), names(dir))

        store.record(Instant.parse("2026-01-03T03:04:05Z"), listOf(ref), emptyList())
        assertNull(store.read()[ref.id]!!.lastErrorCode)
    }
}
