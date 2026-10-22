package net.djvk.fireflyPlaidConnector2.pairing

import com.fasterxml.jackson.databind.ObjectMapper
import net.djvk.fireflyPlaidConnector2.names.CounterpartyNamer
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Counts only, on the real fixtures (lead-run; skips without LANTERN_PAIRING_FIXTURES like [PairAcceptanceGateTest]).
 * Prints lines starting "PROBE " with numbers; never a row text, id, account name or amount. Asserts nothing.
 */
internal class FixtureCountsProbeTest {
    private val dir = File(
        System.getProperty("lantern.pairing.fixtures") ?: System.getenv("LANTERN_PAIRING_FIXTURES") ?: "/nonexistent-set-LANTERN_PAIRING_FIXTURES",
    )

    @Test
    fun printCounts() {
        val f = File(dir, "universe-v2.json")
        assumeTrue(f.exists(), "SKIPPED: no fixtures (set LANTERN_PAIRING_FIXTURES)")
        val rows = ObjectMapper().readTree(f).filter { !it.path("pending").asBoolean(false) }.map {
            val text = it["original_description"]?.takeIf { v -> !v.isNull }?.asText()?.takeIf { v -> v.isNotEmpty() } ?: it["name"].asText()
            val plaid = (it["counterparties"]?.firstOrNull()?.get("name") ?: it["merchant_name"])?.takeIf { v -> !v.isNull }?.asText()
            plaid to text
        }
        for (w in listOf("tax(es)?", "ides", "att", "comed", "bilt", "affirm")) {
            val edge = Regex("(?i)(?<![a-z])$w(?![a-z])")
            val bound = Regex("(?i)\\b$w\\b")
            println("PROBE glued $w=" + rows.count { (_, t) -> edge.containsMatchIn(t) && !bound.containsMatchIn(t) })
        }
        println("PROBE special carryOver=" + rows.count { (_, t) -> PairDefaults.carryOver.containsMatchIn(t) } +
            " consolidation=" + rows.count { (_, t) -> PairDefaults.consolidation.containsMatchIn(t) })
        val namer = CounterpartyNamer()
        for (stem in listOf("kroger", "costco", "target")) {
            val hit = rows.filter { (_, t) -> t.contains(stem, ignoreCase = true) }
            println("PROBE chain $stem rawRows=${hit.size} distinctNames=${hit.map { (p, t) -> namer.canonical(p, t) }.toSet().size}")
        }
    }
}
