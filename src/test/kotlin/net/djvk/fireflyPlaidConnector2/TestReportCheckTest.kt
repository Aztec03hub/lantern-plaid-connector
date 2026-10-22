package net.djvk.fireflyPlaidConnector2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Proves the logic of build.gradle.kts verifyTestReport. Keep the regexes in step with that task. */
class TestReportCheckTest {
    private val annotated = Regex("""(?m)^\s*@(Test|ParameterizedTest|RepeatedTest|TestFactory)\b(?:\([^)]*\))?(?:\s*@\w+(?:\([^)]*\))?)*\s*(?:(?:internal|private|public|override)\s+)*fun\s+(`[^`]+`|\w+)""")
    private val testcase = Regex("""<testcase\s+name="([^"]*)"\s+classname="([^"]*)"[^>]*?(/>|>)""")
    private val skippedTag = Regex("""<skipped(?:\s+message="([^"]*)")?""")

    private fun methods(src: String) = annotated.findAll(src).map { it.groupValues[2].trim('`') }.toList()
    private fun reported(xml: String) = testcase.findAll(xml).map {
        it.groupValues[2].substringAfterLast('.').substringBefore('$') + "." +
            it.groupValues[1].replace(Regex("""\[\d+]$"""), "").substringBefore('(')
    }.toSet()
    private fun skips(xml: String) = skippedTag.findAll(xml).groupingBy { it.groupValues[1] }.eachCount()

    @Test
    fun aTestMethodReturningAValueIsReportedMissing() {
        val src = "@Test fun a() = 5\n@Test fun b() = runBlocking<Unit> { }".replace("@Test", "\n@Test")
        val xml = """<testcase name="b()" classname="p.X" time="0.1"/>"""
        val missing = methods(src).filter { "X.$it" !in reported(xml) }
        assertEquals(listOf("a"), missing)
    }

    @Test
    fun backtickedAndAnnotatedMethodsAreFound() {
        assertEquals(listOf("a name with spaces"), methods("\n@Test @Timeout(5) fun `a name with spaces`() {}"))
    }

    @Test
    fun parameterizedSuffixesAreStripped() {
        val xml = """<testcase name="x()[1]" classname="p.X"/><testcase name="x()[2]" classname="p.X${'$'}In"/>"""
        assertEquals(setOf("X.x"), reported(xml))
    }

    @Test
    fun skippedReasonsAreGrouped() {
        val xml = """<skipped message="m1"/><skipped message="m1"></skipped><skipped message="m2"/>"""
        assertEquals(mapOf("m1" to 2, "m2" to 1), skips(xml))
    }
}
