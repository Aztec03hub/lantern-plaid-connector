package net.djvk.fireflyPlaidConnector2.pairing

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * The acceptance gate of design 10.0, on Phil's real rows. The fixtures live in lantern/private/pairing/ and are read
 * from there, never copied into this repo; without them the test SKIPS, loudly.
 *
 *   universe-v2.json  the Plaid rows (transaction_id, account_id, _account, amount, date, original_description, name, pending)
 *   oracle.json       look-alike candidates labelled TRUE or FALSE (out, inn, label)
 *   oracle-v2.json    optional: statement-proven pairs; rows that name a transaction missing from the universe
 *                     (the O2 to loan legs) are routed by section 9 and are not pairing candidates
 *   scheduled.json    optional: [{"from": <_account regex>, "to": <_account regex>, "amount": 105.0, "day": 18, "tolerance": 3}]
 *
 * Run:  LANTERN_PAIRING_FIXTURES=/home/plafayette/claude_projects/lantern/private/pairing \\
 *         ./gradlew --offline test --tests '*PairAcceptanceGateTest*' -i
 * Without LANTERN_PAIRING_FIXTURES (or -Dlantern.pairing.fixtures) the gate skips loudly: the build never reads private data.
 */
internal class PairAcceptanceGateTest {
    private val mapper = ObjectMapper()

    private val dir = File(
        System.getProperty("lantern.pairing.fixtures") ?: System.getenv("LANTERN_PAIRING_FIXTURES")
            ?: "/nonexistent-set-LANTERN_PAIRING_FIXTURES"
    )

    private data class Row(
        val id: String, val accountId: String, val accountName: String, val amount: Double, val date: LocalDate, val text: String,
    )

    /** How Phil's accounts are described to the engine: the same institutions, masks and roles tools/pair_mock.py assumes. */
    private fun describe(name: String, index: Int): PairAccount = when {
        Regex("Phil s Checking").containsMatchIn(name) -> PairAccount(index, name, "Old Second")
        Regex("IMAGINE REWARDS").containsMatchIn(name) -> PairAccount(index, name, "DCU", "0836", setOf("card"))
        Regex("CASH BACK CHECKING").containsMatchIn(name) -> PairAccount(index, name, "DCU", "0021")
        Regex("MEMBERSHIP SAVINGS").containsMatchIn(name) -> PairAccount(index, name, "DCU", "0000")
        Regex("CREDIT CARD \\.\\.0325").containsMatchIn(name) -> PairAccount(index, name, "Chase", "0325", setOf("card"))
        Regex("Emergency Fund").containsMatchIn(name) -> PairAccount(index, name, "SoFi", null, setOf("vault"))
        Regex("SoFi").containsMatchIn(name) -> PairAccount(index, name, "SoFi")
        else -> PairAccount(index, name)
    }

    private fun rows(node: JsonNode) = node.filter { !it.path("pending").asBoolean(false) }.map {
        Row(
            it["transaction_id"].asText(), it["account_id"].asText(), it["_account"]?.asText() ?: it["account_id"].asText(),
            it["amount"].asDouble(), LocalDate.parse(it["date"].asText()),
            (it["original_description"]?.takeIf { v -> !v.isNull }?.asText()?.takeIf { v -> v.isNotEmpty() }) ?: it["name"].asText(),
        )
    }

    @Test
    fun theEngineFindsEveryProvenPairAndRejectsEveryTrap() {
        val universeFile = File(dir, "universe-v2.json")
        val oracleFile = File(dir, "oracle.json")
        if (!universeFile.exists() || !oracleFile.exists()) {
            System.err.println("*** ACCEPTANCE GATE SKIPPED: fixtures not found in $dir (need universe-v2.json and oracle.json) ***")
        }
        assumeTrue(universeFile.exists() && oracleFile.exists(), "ACCEPTANCE GATE SKIPPED: fixtures not found in $dir")

        val universe = rows(mapper.readTree(universeFile))
        val accountNames = universe.map { it.accountId to it.accountName }.distinct()
        val accountIndex = accountNames.mapIndexed { i, (id, _) -> id to i + 1 }.toMap()
        val accounts = accountNames.map { (id, name) -> describe(name, accountIndex.getValue(id)) }
        val scheduled = File(dir, "scheduled.json").takeIf { it.exists() }?.let { f ->
            mapper.readTree(f).mapNotNull { n ->
                val from = accounts.firstOrNull { Regex(n["from"].asText()).containsMatchIn(it.name) } ?: return@mapNotNull null
                val to = accounts.firstOrNull { Regex(n["to"].asText()).containsMatchIn(it.name) } ?: return@mapNotNull null
                ScheduledFlow(from.id, to.id, (n["amount"].asDouble() * 100).roundToLong(), n["day"]?.asInt(), n["tolerance"]?.asInt() ?: 3)
            }
        } ?: listOf()
        // the personal wording (destinations, payees, income) is in pair-config.json beside the fixtures, or in ../connector/
        val configFile = listOf(File(dir, "pair-config.json"), File(dir.parentFile, "connector/pair-config.json")).firstOrNull { it.exists() }
        println("pair config: ${configFile ?: "NONE FOUND (defaults only; destination rules naming a person or an account number are missing)"}")
        val engine = PairEngine(accounts, PairConfigLoader.load(configFile?.path ?: "", PairingConfig(scheduled = scheduled)))

        val byId = universe.associateBy { it.id }
        fun leg(r: Row) = Leg(
            r.id, accountIndex.getValue(r.accountId), if (r.amount > 0) Dir.OUT else Dir.IN, r.date,
            (kotlin.math.abs(r.amount) * 100).roundToLong(), r.text,
        )
        val legs = universe.map(::leg)
        val legById = legs.associateBy { it.id }
        val result = engine.decide(legs)

        // twins are compared by what a pair means, not by which twin's id it used
        fun sig(o: Leg, i: Leg) = listOf(o.account, i.account, o.cents, o.date, i.date)
        val auto = result.proposals.filter { it.auto }
        val review = result.proposals.filter { !it.auto }

        val oracle = mapper.readTree(oracleFile)
        // the TRUE set is oracle-v2 (statement-proven) when present, else oracle.json
        val v2File = File(dir, "oracle-v2.json")
        val v2 = if (v2File.exists()) mapper.readTree(v2File) else null
        val truthRows = (v2 ?: oracle).filter { it.has("out") && it.has("inn") && (!it.has("label") || it["label"].asText() == "TRUE") }
            .map { it["out"].asText() to it["inn"].asText() }
        val inUniverse = truthRows.filter { it.first in legById && it.second in legById }
        // a TRUE pair whose destination the engine vetoes as unlinked or contradicting has an inflow on an account that has no
        // Plaid rows (a statement loan): section 9 routes it, it is not a pairing candidate
        fun routed(p: Pair<String, String>) = engine.evaluate(legById.getValue(p.first), legById.getValue(p.second))?.veto
            ?.let { it == "unlinked-destination" || it == "destination-contradiction" } == true
        val truth = inUniverse.filterNot(::routed)
        val routedBy9 = truthRows.size - truth.size
        val traps = oracle.filter { it["label"].asText() == "FALSE" }.map { it["out"].asText() to it["inn"].asText() }
        // a FALSE row with the signature of a TRUE pair is the crossed twin of a same-day duplicate transfer (4.5, "Twins"):
        // either assignment gives the same ledger, so it is not a trap and does not count in max-trap
        val trueSigs = inUniverse.map { (o, i) -> sig(legById.getValue(o), legById.getValue(i)) }.toSet()
        val (twinCrossings, realTraps) = traps.partition { (o, i) -> o in legById && i in legById && sig(legById.getValue(o), legById.getValue(i)) in trueSigs }

        // every proposal is explained by a TRUE signature (consumed up to its count), else it is a trap or a false positive
        val remaining = truth.groupingBy { sig(legById.getValue(it.first), legById.getValue(it.second)) }.eachCount().toMutableMap()
        val trapSet = traps.toSet()
        var foundAuto = 0
        var foundReview = 0
        var falseAuto = 0
        var falseReview = 0
        val trapsAccepted = mutableListOf<Pair<String, String>>()
        val reviewHistogram = sortedMapOf<String, Int>()
        for (p in result.proposals.sortedByDescending { it.auto }) {
            val s = sig(p.edge.out, p.edge.inn)
            if ((remaining[s] ?: 0) > 0) {
                remaining[s] = remaining.getValue(s) - 1
                if (p.auto) foundAuto++ else {
                    foundReview++
                    reviewHistogram.merge(p.edge.points.filter { it.points != 0 }.joinToString(" ") { "${it.rule}:${it.points}" }, 1, Int::plus)
                }
            } else if ((p.edge.out.id to p.edge.inn.id) in trapSet) trapsAccepted.add(p.edge.out.id to p.edge.inn.id)
            else if (p.auto) falseAuto++ else falseReview++
        }
        val missed = mutableListOf<Pair<String, String>>()
        val stillWanted = remaining.toMutableMap()
        for ((o, i) in truth) {
            val s = sig(legById.getValue(o), legById.getValue(i))
            if ((stillWanted[s] ?: 0) > 0) { stillWanted[s] = stillWanted.getValue(s) - 1; missed.add(o to i) }
        }
        val minTrue = truth.mapNotNull { (o, i) -> engine.evaluate(legById.getValue(o), legById.getValue(i))?.takeIf { it.veto == null }?.score }.minOrNull()
        val maxTrapAny = realTraps.filter { it !in truth }.mapNotNull { (o, i) -> engine.evaluate(legById.getValue(o), legById.getValue(i))?.score }.maxOrNull()
        val maxTrap = realTraps.filter { it !in truth }.mapNotNull { (o, i) -> engine.evaluate(legById.getValue(o), legById.getValue(i))?.takeIf { it.veto == null }?.score }.maxOrNull()
        val trapVetoed = traps.count { (o, i) -> engine.evaluate(legById.getValue(o), legById.getValue(i))?.veto != null }

        println("=== ACCEPTANCE GATE ===")
        println("universe: ${legs.size} settled legs, ${accounts.size} accounts; candidates scored: ${result.candidates}")
        println("TRUE pairs found: ${foundAuto + foundReview}/${truth.size}  (auto ${foundAuto}, review band $foundReview); routed by 9: pending ($routedBy9 pairs: ${truthRows.size - inUniverse.size} with a leg outside the universe, ${inUniverse.size - truth.size} destination-vetoed)")
        println("false positives: auto $falseAuto, review band $falseReview")
        println("traps rejected: ${traps.size - trapsAccepted.size}/${traps.size}  (of which hard-vetoed: $trapVetoed)")
        println("twin crossings (FALSE rows with a TRUE signature, excluded from traps and max-trap): ${twinCrossings.size}; real traps: ${realTraps.size}")
        println("min TRUE score: $minTrue   max trap score (non-vetoed): $maxTrap  (including vetoed, meaningless: $maxTrapAny)   autoMin=${PairingConfig().autoMin} reviewMin=${PairingConfig().reviewMin}")
        println("ambiguous components: ${result.ambiguous.size}; waiting: ${result.waiting.size}")
        // every non-vetoed trap that scored at least reviewMin: its rules and the rules of the pair(s) that took its legs
        val proposalOfLeg = result.proposals.flatMap { p -> listOf(p.edge.out.id to p.edge, p.edge.inn.id to p.edge) }.toMap()
        fun rp(e: Edge?) = e?.points?.joinToString(" ") { "${it.rule}:${it.points}" } ?: "none"
        println("traps scoring >= reviewMin (not vetoed), with the pair that beat each:")
        realTraps.filter { it !in truth }.forEach { (o, i) ->
            val e = engine.evaluate(legById.getValue(o), legById.getValue(i))
            if (e != null && e.veto == null && e.score >= PairingConfig().reviewMin) {
                println("  TRAP score=${e.score} gap=${e.gap} [${rp(e)}]")
                println("     beaten on out leg by gap=${proposalOfLeg[o]?.gap} [${rp(proposalOfLeg[o])}]")
                println("     beaten on in leg  by gap=${proposalOfLeg[i]?.gap} [${rp(proposalOfLeg[i])}]")
            }
        }
        // distribution of TRUE pair scores; every pair below autoMin + 2 is listed by rule:points only (never descriptions)
        val trueEvals = truth.mapNotNull { (o, i) -> engine.evaluate(legById.getValue(o), legById.getValue(i))?.takeIf { it.veto == null } }
        println("TRUE pair score histogram:")
        trueEvals.groupingBy { it.score }.eachCount().toSortedMap().forEach { (score, n) -> println("  score $score: $n") }
        println("TRUE pairs below autoMin + 2 (${PairingConfig().autoMin + 2}), rule:points:")
        trueEvals.filter { it.score < PairingConfig().autoMin + 2 }.forEach { println("  score=${it.score} [${rp(it)}]") }
        println("review-band TRUE pairs by rule set (rule:points only):")
        reviewHistogram.forEach { (k, n) -> println("  $n x $k") }
        missed.forEach { (o, i) ->
            val e = engine.evaluate(legById.getValue(o), legById.getValue(i))
            println("MISSED score=${e?.score} veto=${e?.veto} layer=${e?.layer} families=${e?.families} points=${e?.points?.map { "${it.rule}:${it.points}" }}")
        }
        trapsAccepted.forEach { (o, i) ->
            println("TRAP ACCEPTED points=${engine.evaluate(legById.getValue(o), legById.getValue(i))?.points?.map { "${it.rule}:${it.points}" }}")
        }

        assertThat(missed).describedAs("TRUE pairs not found (auto or review)").isEmpty()
        assertThat(falseAuto + falseReview).describedAs("false positives").isZero()
        assertThat(trapsAccepted).describedAs("traps that were proposed").isEmpty()
        assertThat(foundReview).describedAs("TRUE pairs that only reach the review band: autoMin is not calibrated").isZero()
        if (minTrue != null && maxTrap != null) {
            assertThat(PairingConfig().autoMin - maxTrap).describedAs("margin below autoMin over the highest trap").isGreaterThanOrEqualTo(2)
            assertThat(minTrue - PairingConfig().autoMin).describedAs("margin above autoMin under the lowest TRUE pair").isGreaterThanOrEqualTo(2)
        }
    }
}
