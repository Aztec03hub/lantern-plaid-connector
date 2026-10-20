package net.djvk.fireflyPlaidConnector2.sync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class StateFilesTest {
    private fun names(dir: Path) = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    @Test
    fun writeAtomicallyLeavesNoTempAndReplacesWhole(@TempDir dir: Path) {
        val target = dir.resolve("x.json")
        Files.writeString(target, "old")
        writeAtomically(target, "new")
        assertEquals("new", Files.readString(target))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(target)))
        assertEquals(listOf("x.json"), names(dir))
    }

    @Test
    fun concurrentWritersNeverInterleaveOrLeaveATemp(@TempDir dir: Path) {
        val target = dir.resolve("x.json")
        val errors = ConcurrentLinkedQueue<Throwable>()
        val values = (1..16).map { "v$it".repeat(5000) }
        val threads = values.map { v -> Thread { runCatching { repeat(100) { writeAtomically(target, v) } }.onFailure { errors.add(it) } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(errors.isEmpty(), errors.toString())
        assertTrue(Files.readString(target) in values)
        assertEquals(listOf("x.json"), names(dir))
    }

    @Test
    fun moveAsideKeepsBytesAndNeverOverwrites(@TempDir dir: Path) {
        val f = dir.resolve("a.json")
        Files.writeString(f, "one")
        val first = moveAside(f) { 1L }
        Files.writeString(f, "two")
        val second = moveAside(f) { 2L }
        assertEquals("one", Files.readString(first))
        assertEquals("two", Files.readString(second))
        assertEquals("a.unreadable-1.json", first.fileName.toString())

        Files.writeString(f, "three")
        assertThrows<java.io.IOException> { moveAside(f) { 2L } }
        assertEquals("three", Files.readString(f))
        assertEquals("two", Files.readString(second))
    }
}
