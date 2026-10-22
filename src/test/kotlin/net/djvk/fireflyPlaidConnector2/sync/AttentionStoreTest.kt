package net.djvk.fireflyPlaidConnector2.sync

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant

class AttentionStoreTest {
    @TempDir
    lateinit var dir: File

    private val t1 = Instant.parse("2026-03-01T06:00:00Z")
    private val abandoned = setOf("dead_letter_abandoned")

    private fun store() = AttentionStore(dir.path)

    @Test
    fun theFileHasTheDocumentedShapeAndIsOwnerOnly() = runBlocking<Unit> {
        store().record(t1, listOf(Attention("dead_letter_abandoned", "plaid-tx-aaa", "update HTTP 422, 10 attempts")), abandoned)

        val file = File(dir, "needs_attention.json")
        val root = ObjectMapper().readTree(file)
        assertEquals(listOf("version", "updatedAt", "count", "items"), root.fieldNames().asSequence().toList())
        assertEquals(1, root["count"].asInt())
        assertEquals(listOf("kind", "ref", "detail", "since"), root["items"][0].fieldNames().asSequence().toList())
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
    }

    @Test
    fun sinceIsCarriedOverAndOtherKindsSurviveAReplace() = runBlocking<Unit> {
        val s = store()
        s.record(t1, listOf(Attention("pair_leg_needs_human", "L1", "reconciled")), setOf("pair_leg_needs_human"))
        val t2 = t1.plusSeconds(3600)
        s.record(t2, listOf(), abandoned)
        assertEquals(listOf(Attention("pair_leg_needs_human", "L1", "reconciled", t1.toString())), s.read())
        s.record(t2, listOf(Attention("pair_leg_needs_human", "L1", "reconciled")), setOf("pair_leg_needs_human"))
        assertEquals(t1.toString(), s.read().single().since)
        s.record(t2, listOf(), setOf("pair_leg_needs_human"))
        assertEquals(listOf<Attention>(), s.read())
    }

    @Test
    fun anUpdateConflictAgesOutAfterThirtyDaysButAnAbandonedLetterDoesNot() = runBlocking<Unit> {
        val s = store()
        s.record(t1, listOf(Attention("update_conflict", "ff7"), Attention("dead_letter_abandoned", "k")), setOf())
        s.record(t1.plus(Duration.ofDays(29)), listOf(), setOf())
        assertEquals(2, s.read().size)
        s.record(t1.plus(Duration.ofDays(31)), listOf(), setOf())
        assertEquals(listOf("dead_letter_abandoned"), s.read().map { it.kind })
    }

    @Test
    fun anUnreadableOldFileIsReplacedNotFatal() = runBlocking<Unit> {
        File(dir, "needs_attention.json").writeText("\u0000garbage{")
        store().record(t1, listOf(Attention("dead_letter_abandoned", "k")), abandoned)
        assertEquals(listOf("k"), store().read().map { it.ref })
    }

    @Test
    fun moreThanTwoHundredItemsAreCappedAndCounted() = runBlocking<Unit> {
        store().record(t1, List(250) { Attention("dead_letter_abandoned", "k%03d".format(it)) }, abandoned)
        val root = ObjectMapper().readTree(File(dir, "needs_attention.json"))
        assertEquals(200, root["items"].size())
        assertEquals(250, root["count"].asInt())
    }

    @Test
    fun unreadableFilesStayListedAfterTheCountResets() = runBlocking<Unit> {
        val letters = DeadLetterStore(dir.path)
        File(dir, "plaid_dead_letters.json").writeText("not json")
        File(dir, "notes.json").writeText("{}")
        letters.read()
        assertEquals(1, letters.takeUnreadableCount())
        assertEquals(0, letters.takeUnreadableCount())
        val listed = letters.unreadableFiles()
        assertEquals(1, listed.size)
        assertFalse(listed.single() == "notes.json")
    }
}
