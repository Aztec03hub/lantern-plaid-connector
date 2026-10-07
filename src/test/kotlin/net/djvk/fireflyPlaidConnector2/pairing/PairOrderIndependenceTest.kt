package net.djvk.fireflyPlaidConnector2.pairing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.config.AccountConfig
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.sync.ItemStatusStore
import net.djvk.fireflyPlaidConnector2.sync.SyncHelper
import net.djvk.fireflyPlaidConnector2.transactions.AccountKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Random

/**
 * Design 10, connector test 5: split a generated population into 1..N runs, run them in every order, and run two passes
 * concurrently, against a fake core that implements the merge endpoint contract (through the real [PairApi]). The final
 * merged set must equal the one-shot outcome. A "run" is one set of banks imported and synced, then one pass; the
 * watermark (4.4) is what makes a pass wait for the banks that could hold a competitor.
 */
internal class PairOrderIndependenceTest {
    @TempDir
    lateinit var dir: Path

    private val today = LocalDate.of(2026, 3, 1)
    private val now: Instant = today.atStartOfDay(ZoneId.of("UTC")).toInstant()
    private val d = LocalDate.of(2026, 1, 10)

    // 1 Old Second, 2 Chase card, 3 Imagine (DCU card), 4 SoFi checking
    private val configs = listOf(
        AccountConfig(1, "tok1", "p1", institutionName = "Old Second", displayName = "Old Second"),
        AccountConfig(2, "tok2", "p2", institutionName = "Chase", mask = "0325", roles = listOf("card"), displayName = "Chase card"),
        AccountConfig(3, "tok3", "p3", institutionName = "DCU", mask = "0836", roles = listOf("card"), displayName = "Imagine"),
        AccountConfig(4, "tok4", "p4", institutionName = "SoFi", displayName = "SoFi checking"),
    )

    /**
     * A population from a seed: payments with named destinations (some with the same amount, so they compete), same-day
     * twins, an ambiguous out with two equally good inflows on different banks, an inflow of the same amount that the
     * destination vetoes, and noise on every bank. Each leg belongs to the bank (account) it is on.
     */
    private fun populate(core: FakePairCore, seed: Long) {
        val rnd = Random(seed)
        val amounts = listOf(10_000L, 10_000L, 25_000L, 4_500L, 7_777L)
        for (n in 1..8) {
            val day = d.plusDays(rnd.nextInt(30).toLong())
            val cents = amounts[rnd.nextInt(amounts.size)]
            core.add("pay-out$n", 1, true, day, cents, "AC CHASE CREDIT CRD AUTOPAY")
            core.add("pay-in$n", 2, false, day.plusDays(rnd.nextInt(3).toLong()), cents, "Payment Thank You")
            if (rnd.nextBoolean()) core.add("rival$n", 3, false, day, cents, "Credit Card Payment Received") // vetoed: the outflow names Chase
            core.add("coffee$n", 1, true, day, 450L + n, "Coffee shop $n")
            core.add("refund$n", 4, false, day, cents, "Refund coffee shop") // income veto
        }
        // twins: the same payment twice on one day
        core.add("twin-out-a", 1, true, d.plusDays(35), 12_000, "AC CHASE CREDIT CRD AUTOPAY")
        core.add("twin-out-b", 1, true, d.plusDays(35), 12_000, "AC CHASE CREDIT CRD AUTOPAY")
        core.add("twin-in-a", 2, false, d.plusDays(36), 12_000, "Payment Thank You")
        core.add("twin-in-b", 2, false, d.plusDays(36), 12_000, "Payment Thank You")
        // not a twin: an out with two equally good inflows on different banks pairs with neither
        // (a hint on the outflow's wording gives it a second rule family, so whichever inflow arrives first would auto-merge
        // if the pass did not wait for the other bank)
        core.add("amb-out", 1, true, d.plusDays(20), 5_050, "AC AMB TRANSFER OUT")
        core.add("amb-in-chase", 2, false, d.plusDays(21), 5_050, "Credit Card Payment Received")
        core.add("amb-in-dcu", 3, false, d.plusDays(21), 5_050, "Credit Card Payment Received")
    }

    private val configFile by lazy {
        Path.of(dir.toString(), "pair-config.json").also { java.nio.file.Files.writeString(it, """{"hints": [{"account": 1, "regex": "AMB TRANSFER"}]}""") }.toString()
    }

    /** One pass over the world, with the banks in [synced] having reported through [clock]. Returns nothing: the core holds the result. */
    private fun pass(core: FakePairCore, tag: String, synced: Collection<Int>, store: ItemStatusStore, clock: Instant) = runBlocking {
        val helper = mock<SyncHelper>()
        whenever(helper.fetchAccountKinds()).thenReturn(
            mapOf("1" to AccountKind.ASSET, "2" to AccountKind.LIABILITY, "3" to AccountKind.LIABILITY, "4" to AccountKind.ASSET),
        )
        for (a in synced) {
            val cfg = configs.first { it.fireflyAccountId == a }
            store.record(clock, listOf(store.ref(cfg.plaidItemAccessToken, cfg.institutionName!!)), listOf())
        }
        val settings = PairSettings(dryRun = false, useWatermark = true, directory = Path.of(dir.toString(), tag).toString(), timeZone = "UTC", pendingTag = "pending", configFile = configFile)
        PairPass(helper, core.service(), core.api(), AccountConfigs(configs), store, settings).run(d.minusDays(5), today, now = clock.plusSeconds(3 * 86_400))
    }

    /** Banks arrive in [runs] order (each run is a set of banks); the world holds only what has arrived. */
    private fun importRuns(seed: Long, runs: List<Set<Int>>, tag: String): Set<Pair<String, String>> {
        val full = FakePairCore().also { populate(it, seed) }
        val core = FakePairCore()
        val store = ItemStatusStore(Path.of(dir.toString(), "$tag-store").toString())
        val arrived = mutableSetOf<Int>()
        var clock = now
        for ((i, run) in runs.withIndex()) {
            // the arriving banks' journals are added exactly as they were generated, ids included
            full.snapshot().forEach { (id, j) ->
                val s = j.attributes.transactions.single()
                val out = s.type == TransactionTypeProperty.withdrawal
                val account = (if (out) s.sourceId else s.destinationId)!!.toInt()
                if (account in run) core.add("", account, out, s.date.toLocalDate(), s.amount.replace(".", "").toLong(), s.description, links = s.plaidLinks, groupId = id)
            }
            arrived.addAll(run)
            pass(core, "$tag-$i", arrived, store, clock)
            clock = clock.plusSeconds(1)
        }
        return core.mergedPlaidPairs().toSet()
    }

    private fun oneShot(seed: Long) = importRuns(seed, listOf(setOf(1, 2, 3, 4)), "one-$seed")

    private fun <T> List<T>.permutations(): List<List<T>> =
        if (size <= 1) listOf(this) else flatMap { x -> (this - x).permutations().map { listOf(x) + it } }

    /** Every way to split [items] into non-empty groups. */
    private fun <T> partitions(items: List<T>): List<List<Set<T>>> =
        if (items.isEmpty()) listOf(listOf()) else {
            val first = items.first()
            partitions(items.drop(1)).flatMap { p ->
                listOf(listOf(setOf(first)) + p) + p.indices.map { i -> p.mapIndexed { j, g -> if (i == j) g + first else g } }
            }
        }

    @Test
    fun theOneShotOutcomeIsWhatTheGeneratorIntends() {
        val merged = oneShot(1)
        // 8 payments + 2 twins pair; the ambiguous out pairs with neither inflow; no vetoed rival or refund is ever used
        assertThat(merged.map { it.first }).doesNotContain("amb-out")
        assertThat(merged.map { it.second }).doesNotContain("amb-in-chase", "amb-in-dcu")
        assertThat(merged.filter { it.first.startsWith("pay-out") || it.first.startsWith("twin-out") }).hasSizeGreaterThanOrEqualTo(8)
        assertThat(merged.none { it.second.startsWith("rival") || it.second.startsWith("refund") }).isTrue()
    }

    @Test
    fun everySplitOfTheBanksIntoRunsInEveryOrderEndsWithTheOneShotPairs() {
        for (seed in 1L..2L) {
            val expected = oneShot(seed)
            var combos = 0
            for (split in partitions(listOf(1, 2, 3, 4))) for (order in split.permutations()) {
                assertThat(importRuns(seed, order, "s$seed-${combos++}")).describedAs("seed $seed, runs $order").isEqualTo(expected)
            }
            assertThat(combos).isEqualTo(75) // 1 + 7*2 + 6*6 + 24
        }
    }

    @Test
    fun twoConcurrentPassesOverTheSameWorldMergeEachPairExactlyOnce() {
        val expected = oneShot(3)
        repeat(10) { round ->
            val core = FakePairCore().also { populate(it, 3) }
            val store = ItemStatusStore(Path.of(dir.toString(), "conc-$round").toString())
            runBlocking {
                val helperKinds = mapOf("1" to AccountKind.ASSET, "2" to AccountKind.LIABILITY, "3" to AccountKind.LIABILITY, "4" to AccountKind.ASSET)
                for (a in 1..4) store.record(now, listOf(store.ref(configs[a - 1].plaidItemAccessToken, configs[a - 1].institutionName!!)), listOf())
                (0..1).map { w ->
                    async(Dispatchers.Default) {
                        val helper = mock<SyncHelper>()
                        whenever(helper.fetchAccountKinds()).thenReturn(helperKinds)
                        val settings = PairSettings(dryRun = false, useWatermark = true, directory = Path.of(dir.toString(), "conc-$round-$w").toString(), timeZone = "UTC", pendingTag = "pending", configFile = configFile)
                        PairPass(helper, core.service(), core.api(), AccountConfigs(configs), store, settings).run(d.minusDays(5), today, now = now.plusSeconds(3 * 86_400))
                    }
                }.awaitAll()
            }
            assertThat(core.mergedPlaidPairs().toSet()).describedAs("round $round").isEqualTo(expected)
            assertThat(core.liveMergeCount()).describedAs("one live merge per pair, round $round").isEqualTo(expected.size)
        }
    }
}
