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
        store.record(ago(1), listOf(kept, old), emptyList())

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

    private fun status(ref: ItemStatusStore.ItemRef, ok: String?, fail: String?, code: String?) =
        ItemStatus(ref.id, ref.institution, ref.redactedToken, ok, fail, code)

    private fun seed(store: ItemStatusStore, vararg e: ItemStatus) {
        Files.writeString(store.path, com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(e.toList()))
    }

    @Test
    fun relinkedBankDropsTheOldAlarmBecauseTheNewLinkSyncedLater(@TempDir dir: Path) = runBlocking<Unit> {
        val store = ItemStatusStore(dir.toString())
        val a = store.ref("access-fake-a", "Same Bank")
        val b = store.ref("access-fake-b", "Same Bank")
        seed(store, status(a, null, ago(2).toString(), "ITEM_LOGIN_REQUIRED"))

        store.poll(now, listOf(b))

        assertEquals(setOf(b.id), store.read().keys)
    }

    @Test
    fun olderOrEqualSiblingSuccessKeepsTheAlarm(@TempDir dir: Path) = runBlocking<Unit> {
        // The poll stamps the sibling at `now`; the alarm's failure time is made later or equal to it
        for (failureAt in listOf(now, now.plus(Duration.ofHours(1)))) {
            val store = ItemStatusStore(dir.resolve(failureAt.toString().replace(":", "")).also { Files.createDirectories(it) }.toString())
            val a = store.ref("access-fake-a", "Same Bank")
            val b = store.ref("access-fake-b", "Same Bank")
            seed(store, status(a, null, failureAt.toString(), "ITEM_LOGIN_REQUIRED"), status(b, ago(5).toString(), null, null))

            store.poll(now, listOf(b))

            assertEquals(setOf(a.id, b.id), store.read().keys, "failure at $failureAt")
        }
    }

    @Test
    fun theKeptWarnIsLoggedOnlyWhenTheCountChanges(@TempDir dir: Path) = runBlocking<Unit> {
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().also { it.start() }
        val log = org.slf4j.LoggerFactory.getLogger(ItemStatusStore::class.java) as ch.qos.logback.classic.Logger
        log.addAppender(appender)
        try {
            val store = ItemStatusStore(dir.toString())
            val live = store.ref("access-fake-live", "Live Bank")
            val kept = store.ref("access-fake-kept", "Kept Bank")
            store.record(ago(1), listOf(kept), emptyList())

            store.poll(now, listOf(live))
            store.poll(now, listOf(live))

            assertEquals(1, appender.list.count { it.level == ch.qos.logback.classic.Level.WARN })
        } finally {
            log.detachAppender(appender)
        }
    }

    @Test
    fun sameBankEntryWithoutCodeIsStillReplaced(@TempDir dir: Path) = runBlocking<Unit> {
        val store = ItemStatusStore(dir.toString())
        val a = store.ref("access-fake-a", "Same Bank")
        val b = store.ref("access-fake-b", "Same Bank")
        store.record(ago(1), listOf(a), emptyList())

        store.poll(now, listOf(b))

        assertEquals(setOf(b.id), store.read().keys)
    }

    @Test
    fun oldFailureWithCodeIsReplacedByTheInstitutionRule(@TempDir dir: Path) = runBlocking<Unit> {
        val store = ItemStatusStore(dir.toString())
        val a = store.ref("access-fake-a", "Same Bank")
        val b = store.ref("access-fake-b", "Same Bank")
        store.record(hoursAgo(31 * 24), emptyList(), listOf(a to "ITEM_LOGIN_REQUIRED"))

        store.poll(now, listOf(b))

        assertEquals(setOf(b.id), store.read().keys)
    }

    @Test
    fun blankAndUnnamedInstitutionsNeverCountAsAMatch(@TempDir dir: Path) = runBlocking<Unit> {
        val store = ItemStatusStore(dir.toString())
        for (name in listOf("unnamed Item", "")) {
            val a = store.ref("access-fake-a-$name", name)
            val b = store.ref("access-fake-b-$name", name)
            store.record(ago(1), listOf(a), emptyList())

            store.poll(now, listOf(b))

            assertEquals(setOf(a.id, b.id), store.read().keys, "name '$name'")
            Files.delete(store.path)
        }
    }

    @Test
    fun futureTimestampsAreKept(@TempDir dir: Path) = runBlocking<Unit> {
        val store = ItemStatusStore(dir.toString())
        val live = store.ref("access-fake-live", "Live Bank")
        val skew = store.ref("access-fake-skew", "Skew Bank")
        seed(store, status(skew, now.plus(Duration.ofDays(400)).toString(), null, null))

        store.poll(now, listOf(live))

        assertEquals(setOf(live.id, skew.id), store.read().keys)
    }

    @Test
    fun unusableTimesDropOnlyWithoutAnErrorCode(@TempDir dir: Path) = runBlocking<Unit> {
        val store = ItemStatusStore(dir.toString())
        val live = store.ref("access-fake-live", "Live Bank")
        val junk = store.ref("access-fake-junk", "Junk Bank")
        val coded = store.ref("access-fake-coded", "Coded Bank")
        val empty = store.ref("access-fake-empty", "Empty Bank")
        seed(store,
            status(junk, "not a time", "also not", null),
            status(coded, null, "garbage", "ITEM_LOGIN_REQUIRED"),
            status(empty, null, null, null),
        )

        store.poll(now, listOf(live))

        assertEquals(setOf(live.id, coded.id), store.read().keys)
    }

    @Test
    fun unusableEntriesSurviveAnEmptyPoll(@TempDir dir: Path) = runBlocking<Unit> {
        val store = ItemStatusStore(dir.toString())
        val empty = store.ref("access-fake-empty", "Empty Bank")
        seed(store, status(empty, null, null, null))

        store.poll(now, emptyList())

        assertEquals(setOf(empty.id), store.read().keys)
    }

    @Test
    fun outageOverThirtyOneDaysDropsAnOldAlarmWhenAbsent(@TempDir dir: Path) = runBlocking<Unit> {
        // Documents the accepted limit: the dashboard then shows the Item as unsynced rather than needing a log in
        val store = ItemStatusStore(dir.toString())
        val live = store.ref("access-fake-live", "Live Bank")
        val gone = store.ref("access-fake-gone", "Gone Bank")
        store.record(hoursAgo(31 * 24), emptyList(), listOf(gone to "ITEM_LOGIN_REQUIRED"))

        store.poll(now, listOf(live))

        assertEquals(setOf(live.id), store.read().keys)
    }

    @Test
    fun codeWithoutAFailureTimeIsComparedByItsOwnSuccessAgainstASibling(@TempDir dir: Path) = runBlocking<Unit> {
        val store = ItemStatusStore(dir.toString())
        val live = store.ref("access-fake-live", "Same Bank")
        val odd = store.ref("access-fake-odd", "Same Bank")
        seed(store, status(odd, hoursAgo(90 * 24).toString(), null, "ITEM_LOGIN_REQUIRED"))

        store.poll(now, listOf(live))

        assertEquals(setOf(live.id), store.read().keys)
    }

    @Test
    fun codeWithoutAFailureTimeIsKeptWhenNoSiblingExists(@TempDir dir: Path) = runBlocking<Unit> {
        val store = ItemStatusStore(dir.toString())
        val live = store.ref("access-fake-live", "Live Bank")
        val odd = store.ref("access-fake-odd", "Odd Bank")
        seed(store, status(odd, hoursAgo(90 * 24).toString(), null, "ITEM_LOGIN_REQUIRED"))

        store.poll(now, listOf(live))

        assertEquals(setOf(live.id, odd.id), store.read().keys)
    }

    @Test
    fun codeLessSameBankEntryWithAFutureSuccessIsStillReplaced(@TempDir dir: Path) = runBlocking<Unit> {
        val store = ItemStatusStore(dir.toString())
        val live = store.ref("access-fake-live", "Same Bank")
        val skew = store.ref("access-fake-skew", "Same Bank")
        seed(store, status(skew, now.plus(Duration.ofDays(2)).toString(), null, null))

        store.poll(now, listOf(live))

        assertEquals(setOf(live.id), store.read().keys)
    }
}
