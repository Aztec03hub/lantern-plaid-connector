package net.djvk.fireflyPlaidConnector2.pairing

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import net.djvk.fireflyPlaidConnector2.api.plaid.models.PersonalFinanceCategory
import net.djvk.fireflyPlaidConnector2.lib.PlaidFixtures
import net.djvk.fireflyPlaidConnector2.transactions.PersonalFinanceCategoryEnum
import net.djvk.fireflyPlaidConnector2.transactions.PlaidFireflyTransaction
import net.djvk.fireflyPlaidConnector2.transactions.TransferMatcher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * U46 dry-run harness (READ-ONLY, deleted by the removal commit): replays the OLD greedy matcher and the pair engine over
 * the same settled rows and prints, for every pair the old matcher made, whether the engine finds it.
 * Needs the same fixtures as [PairAcceptanceGateTest] (universe-v2.json, pair-config.json beside it) and skips loudly without them.
 *
 *   LANTERN_PAIRING_FIXTURES=<dir> nice -n 19 ./gradlew --offline test --tests '*MatcherVsPairerReplayTest*' -i
 *   -Dlantern.replay.from=<date>  keep only legs dated on or after it (default: all)
 *   -Dlantern.replay.out=<file>   the table (default build/matcher-vs-pairer.txt)
 *
 * Rows with no personal_finance_category are still offered to the old matcher (a superset of what it paired).
 * Ids are printed as account index and the first 8 characters of the Plaid id, never amounts joined to names.
 */
internal class MatcherVsPairerReplayTest {
    private val mapper = ObjectMapper()

    private val dir = File(
        System.getProperty("lantern.pairing.fixtures") ?: System.getenv("LANTERN_PAIRING_FIXTURES")
            ?: "/nonexistent-set-LANTERN_PAIRING_FIXTURES"
    )

    private data class Row(
        val id: String, val accountId: String, val accountName: String, val amount: Double, val date: LocalDate, val text: String,
        val primary: String?, val detailed: String?,
    )

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

    private fun rows(node: JsonNode, from: LocalDate?) = node.filter { !it.path("pending").asBoolean(false) }.map {
        val cat = it.path("personal_finance_category")
        Row(
            it["transaction_id"].asText(), it["account_id"].asText(), it["_account"]?.asText() ?: it["account_id"].asText(),
            it["amount"].asDouble(), LocalDate.parse(it["date"].asText()),
            (it["original_description"]?.takeIf { v -> !v.isNull }?.asText()?.takeIf { v -> v.isNotEmpty() }) ?: it["name"].asText(),
            cat.path("primary").takeIf { v -> v.isTextual }?.asText(), cat.path("detailed").takeIf { v -> v.isTextual }?.asText(),
        )
    }.filter { from == null || !it.date.isBefore(from) }

    @Test
    fun everyPairTheOldMatcherMadeIsFoundHeldOrExplained() {
        val universeFile = File(dir, "universe-v2.json")
        if (!universeFile.exists()) {
            System.err.println("*** MATCHER-VS-PAIRER REPLAY SKIPPED: fixtures not found in $dir (need universe-v2.json) ***")
        }
        assumeTrue(universeFile.exists(), "MATCHER-VS-PAIRER REPLAY SKIPPED: fixtures not found in $dir")

        val from = System.getProperty("lantern.replay.from")?.let(LocalDate::parse)
        val universe = rows(mapper.readTree(universeFile), from)
        val accountNames = universe.map { it.accountId to it.accountName }.distinct()
        val accountIndex = accountNames.mapIndexed { i, (id, _) -> id to i + 1 }.toMap()
        val accounts = accountNames.map { (id, name) -> describe(name, accountIndex.getValue(id)) }
        val configFile = listOf(File(dir, "pair-config.json"), File(dir.parentFile, "connector/pair-config.json")).firstOrNull { it.exists() }
        val engine = PairEngine(accounts, PairConfigLoader.load(configFile?.path ?: "", PairingConfig()))

        fun leg(r: Row) = Leg(
            r.id, accountIndex.getValue(r.accountId), if (r.amount > 0) Dir.OUT else Dir.IN, r.date,
            (kotlin.math.abs(r.amount) * 100).roundToLong(), r.text,
        )
        val legs = universe.map(::leg)
        val legById = legs.associateBy { it.id }

        // the OLD path: one batch call, 3-day window (the production value), America/Chicago like the gate's zone
        val plaidTxs = universe.map { r ->
            val category = r.primary?.let { p ->
                r.detailed?.let { d -> PersonalFinanceCategory(p, d).takeIf { runCatching { PersonalFinanceCategoryEnum.from(it) }.isSuccess } }
            }
            PlaidFixtures.getPaymentTransaction(
                accountId = r.accountId, transactionId = r.id, pendingTransactionId = null, amount = r.amount, name = r.text,
                date = r.date, personalFinanceCategory = category,
            )
        }
        val accountMap = accountIndex
        val oldPairs = TransferMatcher("America/Chicago", 3L)
            .match(PlaidFireflyTransaction.normalizeByTransactionId(plaidTxs, listOf(), accountMap))
            .filterIsInstance<PlaidFireflyTransaction.Transfer>()
            // Transfer.deposit holds the Plaid-positive (outflow) leg, so name the pair by the sign, not the field
            .map { t -> listOf(t.deposit, t.withdrawal).sortedByDescending { it.amount }.let { it[0].transactionId to it[1].transactionId } }

        val result = engine.decide(legs)
        fun sig(o: Leg, i: Leg) = listOf(o.account, i.account, o.cents, o.date, i.date)
        val byIds = result.proposals.associateBy { it.edge.out.id to it.edge.inn.id }
        val bySig = result.proposals.groupBy { sig(it.edge.out, it.edge.inn) }
        fun short(l: Leg) = "acct${l.account}:${l.id.take(8)}"
        fun rp(e: Edge?) = e?.points?.joinToString(" ") { "${it.rule}:${it.points}" } ?: "none"

        val lines = mutableListOf("status\tout\tinn\tdetail")
        var auto = 0; var review = 0; var held = 0; var notProposed = 0
        for ((o, i) in oldPairs) {
            val out = legById.getValue(o)
            val inn = legById.getValue(i)
            val p = byIds[o to i] ?: bySig[sig(out, inn)]?.firstOrNull()
            val e = engine.evaluate(out, inn)
            val (status, detail) = when {
                p != null && p.heldBy != null -> "HELD" to "heldBy=${p.heldBy}"
                p != null && p.auto -> "FOUND-AUTO" to ""
                p != null -> "FOUND-REVIEW" to "score=${e?.score} [${rp(e)}]"
                e?.veto != null -> "HELD" to "veto=${e.veto}"
                else -> "NOT-PROPOSED" to "score=${e?.score} [${rp(e)}]"
            }
            when (status) { "FOUND-AUTO" -> auto++; "FOUND-REVIEW" -> review++; "HELD" -> held++; else -> notProposed++ }
            lines.add("$status\t${short(out)}\t${short(inn)}\t$detail")
        }
        val oldSigs = oldPairs.map { (o, i) -> sig(legById.getValue(o), legById.getValue(i)) }.toMutableList()
        val engineOnly = result.proposals.filter { oldSigs.remove(sig(it.edge.out, it.edge.inn)).not() }
        engineOnly.forEach { lines.add("ENGINE-ONLY\t${short(it.edge.out)}\t${short(it.edge.inn)}\tauto=${it.auto}") }
        val summary = "old pairs ${oldPairs.size}, found auto $auto, review $review, held $held, not proposed $notProposed, engine-only ${engineOnly.size}"

        val out = File(System.getProperty("lantern.replay.out") ?: "build/matcher-vs-pairer.txt")
        out.absoluteFile.parentFile.mkdirs()
        out.writeText((lines + summary).joinToString("\n") + "\n")
        println("=== MATCHER VS PAIRER ===\n$summary\ntable: ${out.absolutePath}")
        assertThat(out).exists()
    }
}
