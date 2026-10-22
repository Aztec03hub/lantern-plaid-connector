package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

internal class FlippedLegStoreTest {
    @TempDir
    lateinit var dir: Path

    private fun store() = FlippedLegStore(dir.toString())

    @Test
    fun aMissingFileReadsEmpty() = runBlocking<Unit> {
        assertThat(store().read()).isEmpty()
    }

    @Test
    fun applyMergesAndNullRemoves() = runBlocking<Unit> {
        val s = store()
        s.apply(mapOf("ff1" to FlipMark("1", setOf("wd"))))
        s.apply(mapOf("ff2" to FlipMark("2", setOf("dep"))))
        assertThat(s.read()).containsOnlyKeys("ff1", "ff2")
        s.apply(mapOf("ff1" to null))
        assertThat(s.read()).containsOnlyKeys("ff2")
        assertThat(s.read()["ff2"]).isEqualTo(FlipMark("2", setOf("dep")))
    }

    @Test
    fun theFileHoldsOnlyIdsNoTokenNoText() = runBlocking<Unit> {
        val s = store()
        s.apply(mapOf("ff1" to FlipMark("1", setOf("wd"))))
        val text = Files.readString(s.path)
        assertThat(text).contains("ff1", "wd").doesNotContain("amount", "token", "description")
        assertThat(Files.list(dir).use { it.map { p -> p.fileName.toString() }.toList() }).containsExactly("flipped_legs.json")
    }

    @Test
    fun aCorruptFileReadsEmptyAndIsReplacedByTheNextApply() = runBlocking<Unit> {
        val s = store()
        Files.writeString(s.path, "{not json")
        assertThat(s.read()).isEmpty()
        s.apply(mapOf("ff1" to FlipMark("1", setOf("wd"))))
        assertThat(s.read()).containsOnlyKeys("ff1")
    }
}
