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
        val engine = PairEngine(accounts, PairingConfig(scheduled = scheduled))

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
        val truth = oracle.filter { it["label"].asText() == "TRUE" }.map { it["out"].asText() to it["inn"].asText() }
        val traps = oracle.filter { it["label"].asText() == "FALSE" }.map { it["out"].asText() to it["inn"].asText() }
        val truthSigs = truth.filter { it.first in legById && it.second in legById }.map { sig(legById.getValue(it.first), legById.getValue(it.second)) }
            .groupingBy { it }.eachCount().toMutableMap()
        val autoSigs = auto.groupingBy { sig(it.edge.out, it.edge.inn) }.eachCount()
        val reviewSigs = review.groupingBy { sig(it.edge.out, it.edge.inn) }.eachCount()

        var foundAuto = 0
        var foundReview = 0
        val missed = mutableListOf<Pair<String, String>>()
        val remainingAuto = autoSigs.toMutableMap()
        val remainingReview = reviewSigs.toMutableMap()
        for ((o, i) in truth) {
            val s = sig(legById.getValue(o), legById.getValue(i))
            when {
                (remainingAuto[s] ?: 0) > 0 -> { remainingAuto[s] = remainingAuto.getValue(s) - 1; foundAuto++ }
                (remainingReview[s] ?: 0) > 0 -> { remainingReview[s] = remainingReview.getValue(s) - 1; foundReview++ }
                else -> missed.add(o to i)
            }
        }
        val falseAuto = remainingAuto.filterValues { it > 0 }
        val falseReview = remainingReview.filterValues { it > 0 }

        // traps: no proposal may contain the exact (out, inn) of a trap
        val proposed = result.proposals.map { it.edge.out.id to it.edge.inn.id }.toSet()
        val trapsAccepted = traps.filter { it in proposed }
        val minTrue = truth.mapNotNull { (o, i) -> engine.evaluate(legById.getValue(o), legById.getValue(i))?.takeIf { it.veto == null }?.score }.minOrNull()
        val maxTrap = traps.mapNotNull { (o, i) -> engine.evaluate(legById.getValue(o), legById.getValue(i))?.takeIf { it.veto == null }?.score }.maxOrNull()
        val trapVetoed = traps.count { (o, i) -> engine.evaluate(legById.getValue(o), legById.getValue(i))?.veto != null }

        println("=== ACCEPTANCE GATE ===")
        println("universe: ${legs.size} settled legs, ${accounts.size} accounts; candidates scored: ${result.candidates}")
        println("TRUE pairs found: ${foundAuto + foundReview}/${truth.size}  (auto ${foundAuto}, review band $foundReview)")
        println("false positives: auto ${falseAuto.values.sum()}, review band ${falseReview.values.sum()}")
        println("traps rejected: ${traps.size - trapsAccepted.size}/${traps.size}  (of which hard-vetoed: $trapVetoed)")
        println("min TRUE score: $minTrue   max trap score: $maxTrap   autoMin=${PairingConfig().autoMin} reviewMin=${PairingConfig().reviewMin}")
        println("ambiguous components: ${result.ambiguous.size}; waiting: ${result.waiting.size}")
        missed.forEach { (o, i) ->
            val e = engine.evaluate(legById.getValue(o), legById.getValue(i))
            println("MISSED ${byId.getValue(o).date} ${byId.getValue(o).accountName.take(16)} [${byId.getValue(o).text.take(40)}] -> " +
                    "${byId.getValue(i).accountName.take(16)} [${byId.getValue(i).text.take(34)}] ${byId.getValue(i).date}  " +
                    "score=${e?.score} veto=${e?.veto} layer=${e?.layer} families=${e?.families} points=${e?.points?.map { "${it.rule}:${it.points}" }}")
        }
        trapsAccepted.forEach { (o, i) -> println("TRAP ACCEPTED ${byId.getValue(o).text.take(40)} -> ${byId.getValue(i).text.take(40)}") }
        falseAuto.keys.forEach { println("FALSE POSITIVE (auto) $it") }

        // the optional statement-proven set: anything it names that the universe lacks is a section 9 routing, not a pair
        File(dir, "oracle-v2.json").takeIf { it.exists() }?.let { f ->
            val v2 = mapper.readTree(f)
            val keys = v2.firstOrNull()?.fieldNames()?.asSequence()?.toList()
            val pairs = v2.filter { it.has("out") && it.has("inn") && (!it.has("label") || it["label"].asText() == "TRUE") }
            val inUniverse = pairs.count { it["out"].asText() in legById && it["inn"].asText() in legById }
            println("oracle-v2: ${v2.size()} rows (fields $keys), ${pairs.size} pair rows, $inUniverse with both legs in the universe, ${pairs.size - inUniverse} routed by section 9")
        }

        assertThat(missed).describedAs("TRUE pairs not found (auto or review)").isEmpty()
        assertThat(falseAuto).describedAs("false positives among auto-merges").isEmpty()
        assertThat(trapsAccepted).describedAs("traps that were proposed").isEmpty()
        assertThat(foundReview).describedAs("TRUE pairs that only reach the review band: autoMin is not calibrated").isZero()
        if (minTrue != null && maxTrap != null) {
            assertThat(PairingConfig().autoMin - maxTrap).describedAs("margin below autoMin over the highest trap").isGreaterThanOrEqualTo(2)
            assertThat(minTrue - PairingConfig().autoMin).describedAs("margin above autoMin under the lowest TRUE pair").isGreaterThanOrEqualTo(2)
        }
    }
}
