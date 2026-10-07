package net.djvk.fireflyPlaidConnector2.names

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class CounterpartyNamerTest {
    private val namer = CounterpartyNamer()

    @Test
    fun theThreeShapesOfOnePaycheckBecomeOneName() {
        val shapes = listOf(
            "EVOLV CONSULTING",
            "Evolv Consulting Type: Payroll ID: XX2465 CO: Evolv Consulting",
            "AC EVOLV CONSULTING PAYROLL 11100001234567 PP",
        )
        assertThat(shapes.map { namer.canonical(null, it) }.toSet()).containsExactly("Evolv Consulting")
    }

    @Test
    fun plaidsOwnNamePreferredWhenPresent() {
        assertThat(namer.canonical("Starbucks", "STARBUCKS STORE 12345 CHICAGO IL")).isEqualTo("Starbucks")
        assertThat(namer.canonical("  ", "STARBUCKS STORE 12345 CHICAGO IL")).isEqualTo("Starbucks Store")
    }

    @Test
    fun storeNumbersTraceIdsAndLocationsAreStripped() {
        assertThat(namer.canonical(null, "TARGET #1234 CHICAGO IL")).isEqualTo("Target")
        assertThat(namer.canonical(null, "COMED ONLINE PMT WEB 8800123456")).isEqualTo("Comed Online")
        assertThat(namer.canonical(null, "Home Depot Naperville IL")).isEqualTo("Home Depot")
        assertThat(namer.canonical(null, "ACH PPD AMAZON 1234567890")).isEqualTo("Amazon")
    }

    @Test
    fun aShortNameIsNeverEmptiedByTheCleaning() {
        assertThat(namer.canonical(null, "AC 12345678")).isNotBlank()
        assertThat(namer.canonical(null, "IL")).isEqualTo("Il")
    }

    @Test
    fun theAliasTableHasTheLastWord() {
        val aliased = CounterpartyNamer(mapOf("EVOLV CONSULTING" to "Evolv (employer)"))
        assertThat(aliased.canonical(null, "AC EVOLV CONSULTING PAYROLL 1110000123")).isEqualTo("Evolv (employer)")
        assertThat(aliased.canonical("Evolv Consulting", "x")).isEqualTo("Evolv (employer)")
    }

    @Test
    fun keysIgnoreCasePunctuationAndSpacing() {
        assertThat(namer.key("Evolv  Consulting, Inc.")).isEqualTo(namer.key("EVOLV CONSULTING INC"))
    }
}
