package net.djvk.fireflyPlaidConnector2.pairing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

internal class PairDecisionFileTest {
    @TempDir
    lateinit var dir: Path

    private fun put(text: String) = Files.writeString(dir.resolve("pair-decisions.json"), text)

    @Test
    fun anAbsentFileIsEmptyWithNoProblem() {
        assertThat(PairDecisionFile.read(dir.toString())).isEqualTo(listOf<PairDecision>() to null)
    }

    @Test
    fun decisionsWithBadFieldsAreSkipped() {
        val long = "x".repeat(300)
        put("""{"decisions":[
            {"out":"a","inn":"b","action":"confirm"},
            {"out":"c","inn":"d","action":"delete"},
            {"out":" ","inn":"d","action":"reject"},
            {"out":"$long","inn":"d","action":"reject"},
            {"out":"e","inn":"f","action":"reject","extra":1}]}""")
        val (list, problem) = PairDecisionFile.read(dir.toString())
        assertThat(problem).isNull()
        assertThat(list).containsExactly(PairDecision("a", "b", "confirm"), PairDecision("e", "f", "reject"))
    }

    @Test
    fun anOversizedOrWrongShapedFileIsAProblemAndEmpty() {
        put(" ".repeat(300 * 1024) + """{"decisions":[{"out":"a","inn":"b","action":"confirm"}]}""")
        assertThat(PairDecisionFile.read(dir.toString())).isEqualTo(listOf<PairDecision>() to "unreadable")
        put("""{"decisions":"nope"}""")
        assertThat(PairDecisionFile.read(dir.toString())).isEqualTo(listOf<PairDecision>() to "unreadable")
    }
}
