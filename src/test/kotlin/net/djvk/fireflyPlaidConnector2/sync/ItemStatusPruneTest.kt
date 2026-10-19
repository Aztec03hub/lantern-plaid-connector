package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

internal class ItemStatusPruneTest {
    private val now = Instant.parse("2026-06-01T00:00:00Z")
    private fun ago(days: Long) = now.minus(Duration.ofDays(days))
    private fun hoursAgo(h: Long) = now.minus(Duration.ofHours(h))

    private suspend fun ItemStatusStore.poll(t: Instant, ok: List<ItemStatusStore.ItemRef>, bad: List<Pair<ItemStatusStore.ItemRef, String>> = emptyList()) =
        record(t, ok, bad, pruneUnlisted = true)

    @Test
    fun replacedItemOfTheSameInstitutionIsPrunedOthersKept(@TempDir dir: Path) = runBlocking<Unit> {
        val store = ItemStatusStore(dir.toString())
        val old = store.ref("access-fake-old", "Test Bank")
        val kept = store.ref("access-fake-kept", "Other Bank")
        val fresh = store.ref("access-fake-new", "Test Bank")
        store.record(ago(1), listOf(kept), listOf(old to "ITEM_LOGIN_REQUIRED"))

        store.poll(now, listOf(kept, fresh))

        assertEquals(setOf(kept.id, fresh.id), store.read().keys)
    }

    @Test
    fun singleItemConfigKeepsThreeOtherInstitutions(@TempDir dir: Path) = runBlocking<Unit> {
        val store = ItemStatusStore(dir.toString())
        val a = store.ref("access-fake-a", "Bank A")
        val b = store.ref("access-fake-b", "Bank B")
        val c = store.ref("access-fake-c", "Bank C")
        val d = store.ref("access-fake-d", "Bank D")
        store.record(ago(1), listOf(a, b, d), listOf(c to "ITEM_LOGIN_REQUIRED"))

        store.poll(now, listOf(a))

        assertEquals(setOf(a.id, b.id, c.id, d.id), store.read().keys)
        assertEquals("ITEM_LOGIN_REQUIRED", store.read()[c.id]!!.lastErrorCode)
    }

    @Test
    fun silentOverThirtyDaysIsPrunedTwentyNineIsKept(@TempDir dir: Path) = runBlocking<Unit> {
        val store = ItemStatusStore(dir.toString())
        val live = store.ref("access-fake-live", "Live Bank")
        val old = store.ref("access-fake-old", "Old Bank")
        val recent = store.ref("access-fake-recent", "Recent Bank")
        val failedOld = store.ref("access-fake-fold", "Failing Bank")
        store.record(hoursAgo(30 * 24 + 1), listOf(old), listOf(failedOld to "ITEM_LOGIN_REQUIRED"))
        store.record(hoursAgo(30 * 24 - 1), listOf(recent), emptyList())

        store.poll(now, listOf(live))

        assertEquals(setOf(live.id, recent.id), store.read().keys)
    }

    @Test
    fun anEntryWithRecentFailureOnlyIsKept(@TempDir dir: Path) = runBlocking<Unit> {
        val store = ItemStatusStore(dir.toString())
        val live = store.ref("access-fake-live", "Live Bank")
        val bad = store.ref("access-fake-bad", "Bad Bank")
        store.record(ago(90), listOf(bad), emptyList())
        store.record(ago(2), emptyList(), listOf(bad to "ITEM_LOGIN_REQUIRED"))

        store.poll(now, listOf(live))

        assertEquals(setOf(live.id, bad.id), store.read().keys)
    }

    @Test
    fun emptyPollSetPrunesNothingEvenWhenSilent(@TempDir dir: Path) = runBlocking<Unit> {
        val store = ItemStatusStore(dir.toString())
        val a = store.ref("access-fake-a", "Bank A")
        store.record(ago(60), listOf(a), emptyList())

        store.poll(now, emptyList())

        assertEquals(setOf(a.id), store.read().keys)
    }

    @Test
    fun investmentOnlyItemRecordedEarlierIsKeptWhileRecent(@TempDir dir: Path) = runBlocking<Unit> {
        // An investment-only Item is never in the polled set; absence alone must not delete it
        val store = ItemStatusStore(dir.toString())
        val inv = store.ref("access-fake-inv", "Broker")
        val bank = store.ref("access-fake-bank", "Bank")
        store.record(ago(1), listOf(inv, bank), emptyList())

        store.poll(now, listOf(bank))

        assertEquals(setOf(inv.id, bank.id), store.read().keys)
    }

    @Test
    fun sameSuffixTokenCollisionCannotDeleteAnEntry(@TempDir dir: Path) = runBlocking<Unit> {
        // Two tokens ending in the same 4 characters, different institutions, one fails: the absent twin stays
        val store = ItemStatusStore(dir.toString())
        val a = store.ref("access-fake-aaaa-1234", "Bank A")
        val b = store.ref("access-fake-bbbb-1234", "Bank B")
        store.record(ago(1), listOf(a, b), emptyList())

        store.poll(now, emptyList(), listOf(a to "ITEM_LOGIN_REQUIRED"))

        assertEquals(setOf(a.id, b.id), store.read().keys)
    }

    @Test
    fun unreadableStatusFileDoesNotStallAndNothingIsPrunedOnTop(@TempDir dir: Path) = runBlocking<Unit> {
        val store = ItemStatusStore(dir.toString())
        val a = store.ref("access-fake-a", "Bank A")
        Files.writeString(store.path, "{ not json")

        store.poll(now, listOf(a))

        // Nothing could be read, so nothing is pruned; the file is rebuilt from the poll
        assertEquals(setOf(a.id), store.read().keys)
    }

    @Test
    fun cursorFileIsByteIdenticalAndStatusFileStaysOwnerOnly(@TempDir dir: Path) = runBlocking<Unit> {
        val manager = CursorManager(dir.toString())
        Files.writeString(manager.cursorFilePath, "access-fake-1|cur-1\naccess-fake-2|a|b")
        val before = Files.readAllBytes(manager.cursorFilePath)
        val store = ItemStatusStore(dir.toString())
        val old = store.ref("access-fake-old", "Test Bank")
        val fresh = store.ref("access-fake-new", "Test Bank")
        store.record(ago(1), listOf(old), emptyList())
        Files.setPosixFilePermissions(store.path, PosixFilePermissions.fromString("rw-r--r--"))

        store.poll(now, listOf(fresh))
        val once = Files.readString(store.path)
        store.poll(now, listOf(fresh))

        assertEquals(before.toList(), Files.readAllBytes(manager.cursorFilePath).toList())
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.path)))
        assertEquals(setOf(fresh.id), store.read().keys)
        assertEquals(once, Files.readString(store.path))
    }
}
