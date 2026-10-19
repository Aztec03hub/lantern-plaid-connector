package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlin.test.assertEquals

internal class ItemStatusPruneTest {
    private val t1 = Instant.parse("2026-01-02T03:04:05Z")
    private val t2 = Instant.parse("2026-01-03T03:04:05Z")

    @Test
    fun replacedItemIsPrunedAndStillLinkedItemIsKept(@TempDir dir: Path) = runBlocking<Unit> {
        val store = ItemStatusStore(dir.toString())
        val old = store.ref("access-fake-old", "Test Bank")
        val kept = store.ref("access-fake-kept", "Other Bank")
        val fresh = store.ref("access-fake-new", "Test Bank")
        store.record(t1, listOf(kept), listOf(old to "ITEM_LOGIN_REQUIRED"), pruneUnlisted = true)

        store.record(t2, listOf(kept, fresh), emptyList(), pruneUnlisted = true)

        val after = store.read()
        assertEquals(setOf(kept.id, fresh.id), after.keys)
        assertEquals(t2.toString(), after[kept.id]!!.lastSuccessfulSync)
    }

    @Test
    fun failedStillLinkedItemKeepsItsEntry(@TempDir dir: Path) = runBlocking<Unit> {
        val store = ItemStatusStore(dir.toString())
        val a = store.ref("access-fake-a", "Bank A")
        val b = store.ref("access-fake-b", "Bank B")
        store.record(t1, listOf(a, b), emptyList(), pruneUnlisted = true)

        store.record(t2, listOf(a), listOf(b to "ITEM_LOGIN_REQUIRED"), pruneUnlisted = true)

        assertEquals(setOf(a.id, b.id), store.read().keys)
        assertEquals("ITEM_LOGIN_REQUIRED", store.read()[b.id]!!.lastErrorCode)
    }

    @Test
    fun emptyPollSetKeepsEverything(@TempDir dir: Path) = runBlocking<Unit> {
        val store = ItemStatusStore(dir.toString())
        val a = store.ref("access-fake-a", "Bank A")
        store.record(t1, listOf(a), emptyList(), pruneUnlisted = true)

        store.record(t2, emptyList(), emptyList(), pruneUnlisted = true)

        assertEquals(setOf(a.id), store.read().keys)
    }

    @Test
    fun pruneKeepsModeOwnerOnlyAndSecondRunIsIdempotent(@TempDir dir: Path) = runBlocking<Unit> {
        val store = ItemStatusStore(dir.toString())
        val old = store.ref("access-fake-old", "Test Bank")
        val fresh = store.ref("access-fake-new", "Test Bank")
        store.record(t1, listOf(old), emptyList(), pruneUnlisted = true)
        Files.setPosixFilePermissions(store.path, PosixFilePermissions.fromString("rw-r--r--"))

        store.record(t2, listOf(fresh), emptyList(), pruneUnlisted = true)
        val once = Files.readString(store.path)
        store.record(t2, listOf(fresh), emptyList(), pruneUnlisted = true)

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.path)))
        assertEquals(setOf(fresh.id), store.read().keys)
        assertEquals(once, Files.readString(store.path))
    }
}
