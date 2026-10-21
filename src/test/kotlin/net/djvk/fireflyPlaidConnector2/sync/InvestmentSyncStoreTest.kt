package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.time.LocalDate

class InvestmentSyncStoreTest {
    @TempDir
    lateinit var dir: java.io.File

    private fun store() = InvestmentSyncStore(dir.path, ItemStatusStore(dir.path))
    private val d1 = LocalDate.of(2026, 3, 1)
    private val d2 = LocalDate.of(2026, 3, 2)

    @Test
    fun readOfAMissingFileIsEmpty() = runBlocking<Unit> {
        assertThat(store().read()).isEmpty()
    }

    @Test
    fun recordMergesOverWhatIsThere() = runBlocking<Unit> {
        val s = store()
        s.record(mapOf("aaaaaaaaaaaa" to d1))
        s.record(mapOf("bbbbbbbbbbbb" to d2))
        assertThat(s.read()).isEqualTo(mapOf("aaaaaaaaaaaa" to d1, "bbbbbbbbbbbb" to d2))
    }

    @Test
    fun theFileHoldsNoTokenOnlyAnItemIdAndADate() = runBlocking<Unit> {
        val s = store()
        s.record(mapOf(s.itemId("access-tokBroker") to d1))
        val text = s.path.toFile().readText()
        assertThat(text).doesNotContain("tok").doesNotContain("access")
        assertThat(text).matches("""\{"[0-9a-f]{12}":"\d{4}-\d{2}-\d{2}"}""")
    }

    @Test
    fun aCorruptFileReadsAsEmptyAndIsReplacedByTheNextRecord() = runBlocking<Unit> {
        val s = store()
        s.path.toFile().writeText("{not json")
        assertThat(s.read()).isEmpty()
        s.record(mapOf("aaaaaaaaaaaa" to d1))
        assertThat(s.read()).isEqualTo(mapOf("aaaaaaaaaaaa" to d1))
    }
}
