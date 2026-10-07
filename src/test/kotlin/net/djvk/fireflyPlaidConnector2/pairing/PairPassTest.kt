package net.djvk.fireflyPlaidConnector2.pairing

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PairApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PairMergeOutcome
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PairMergeRequest
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.config.AccountConfig
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.sync.FireflyTransactionService
import net.djvk.fireflyPlaidConnector2.sync.ItemStatusStore
import net.djvk.fireflyPlaidConnector2.sync.SyncHelper
import net.djvk.fireflyPlaidConnector2.transactions.AccountKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The pass against a fake Firefly and a fake of core's merge endpoint: dry run writes nothing, the refusal path never
 * deletes, the fetch range is exact, and the outcome does not depend on the order the banks were imported in.
 */
internal class PairPassTest {
    @Suppress("unused")
    private val warmMockUtil = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE

    @TempDir
    lateinit var dir: Path

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 3, 1)
    private val now: Instant = today.atStartOfDay(zone).toInstant()

    // account 1 Old Second, 2 Chase card, 3 Imagine (DCU card), 4 SoFi checking
    private val configs = listOf(
        AccountConfig(1, "tok1", "p1", institutionName = "Old Second", displayName = "Old Second"),
        AccountConfig(2, "tok2", "p2", institutionName = "Chase", mask = "0325", roles = listOf("card"), displayName = "Chase card"),
        AccountConfig(3, "tok3", "p3", institutionName = "DCU", mask = "0836", roles = listOf("card"), displayName = "Imagine"),
        AccountConfig(4, "tok4", "p4", institutionName = "SoFi", displayName = "SoFi checking"),
    )

    /** A Firefly with journals in memory and core's merge contract: stale -> 409, merged journal has two links. */
    private inner class World {
        val journals = linkedMapOf<String, TransactionRead>()
        val merges = mutableListOf<Pair<String, String>>()
        var refuse: Pair<Int, String>? = null
        var unreadable = false
        val rangesRead = mutableListOf<Pair<LocalDate, LocalDate>>()
        private var seq = 1

        fun add(plaidId: String, account: Int, out: Boolean, date: LocalDate, cents: Long, text: String, tags: List<String> = listOf(), updated: String = "2026-02-01T00:00:00Z") {
            val amount = "%d.%02d".format(cents / 100, cents % 100)
            val split = FireflyFixtures.getTransaction(
                type = if (out) TransactionTypeProperty.withdrawal else TransactionTypeProperty.deposit,
                date = OffsetDateTime.of(date.atStartOfDay(), ZoneOffset.UTC), amount = amount, description = text,
                sourceId = if (out) account.toString() else null, destinationId = if (out) null else account.toString(),
                sourceName = if (out) null else "Somewhere", destinationName = if (out) "Somewhere" else null,
                plaidLinks = listOf(PlaidLink(plaidId, PlaidLinkLeg.single, "p$account")), tags = tags, updatedAt = OffsetDateTime.parse(updated),
                currencyCode = "USD", reconciled = false,
            )
            journals["g${seq++}"] = TransactionRead("transactions", "g${seq - 1}", split, ObjectLink())
        }

        fun service(): FireflyTransactionService {
            val s = mock<FireflyTransactionService>()
            runBlocking {
                whenever(s.fetchFireflyTransactionsStrictly(any(), any(), any())).doSuspendableAnswer {
                    if (unreadable) throw IllegalStateException("Firefly changed while it was being read")
                    val from = it.getArgument<LocalDate>(0)
                    val to = it.getArgument<LocalDate>(1)
                    rangesRead.add(from to to)
                    journals.values.filter { j ->
                        val d = j.attributes.transactions.first().date.toLocalDate()
                        !d.isBefore(from) && !d.isAfter(to)
                    }
                }
            }
            return s
        }

        val core = object : PairApi() {
            override suspend fun merge(request: PairMergeRequest): PairMergeOutcome {
                refuse?.let { return PairMergeOutcome.Rejected(it.first, it.second) }
                val keep = journals[request.keepGroupId] ?: return PairMergeOutcome.Rejected(404, "not found")
                val absorb = journals[request.absorbGroupId] ?: return PairMergeOutcome.Rejected(404, "not found")
                if (keep.attributes.updatedAt?.toString() != request.keepUpdatedAt || absorb.attributes.updatedAt?.toString() != request.absorbUpdatedAt) {
                    return PairMergeOutcome.Rejected(409, "stale")
                }
                val ks = keep.attributes.transactions.first()
                val a = absorb.attributes.transactions.first()
                val links = listOf(ks.plaidLinks!!.first().copy(leg = PlaidLinkLeg.source), a.plaidLinks!!.first().copy(leg = PlaidLinkLeg.destination))
                journals[request.keepGroupId] = TransactionRead(
                    "transactions", keep.id, keep.attributes.copy(transactions = listOf(ks.copy(plaidLinks = links, destinationId = a.destinationId))), keep.links,
                )
                journals.remove(request.absorbGroupId)
                merges.add(ks.plaidLinks!!.first().plaidTransactionId to a.plaidLinks!!.first().plaidTransactionId)
                return PairMergeOutcome.Merged("m${merges.size}", "transfer")
            }
        }
    }

    private fun pass(world: World, dryRun: Boolean, useWatermark: Boolean = false, store: ItemStatusStore = ItemStatusStore(dir.toString())): PairPass {
        val helper = mock<SyncHelper>()
        runBlocking { whenever(helper.fetchAccountKinds()).thenReturn(mapOf("1" to AccountKind.ASSET, "2" to AccountKind.LIABILITY, "3" to AccountKind.LIABILITY, "4" to AccountKind.ASSET)) }
        val settings = PairSettings(dryRun = dryRun, useWatermark = useWatermark, directory = dir.toString(), timeZone = "UTC", pendingTag = "pending")
        return PairPass(helper, world.service(), world.core, AccountConfigs(configs), store, settings)
    }

    private val d = LocalDate.of(2026, 1, 10)

    /** A payment from Old Second to the Chase card: both legs marked, destination names Chase (auto-merge). */
    private fun World.payment(n: Int, cents: Long = 12_345, day: LocalDate = d) {
        add("out$n", 1, true, day, cents, "AC CHASE CREDIT CRD AUTOPAY")
        add("in$n", 2, false, day.plusDays(1), cents, "Payment Thank You")
    }

    @Test
    fun aDryRunPrintsThePlanAndWritesNothingToFirefly() = runBlocking<Unit> {
        val w = World().also { it.payment(1) }
        val report = pass(w, dryRun = true).run(d.minusDays(5), today, now = now)
        assertThat(report.result.proposals.single().auto).isTrue()
        assertThat(w.merges).isEmpty()
        assertThat(w.journals).hasSize(2)
        assertThat(report.text).contains("DRY RUN", "Proposed pairs", "out-marker")
    }

    @Test
    fun aRealPassMergesEachAutoPairWithOneCallAndKeepsTheMergeId() = runBlocking<Unit> {
        val w = World().also { it.payment(1); it.payment(2, 20_000, d.plusDays(3)) }
        val report = pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        assertThat(w.merges).containsExactlyInAnyOrder("out1" to "in1", "out2" to "in2")
        assertThat(report.merged.map { it.pairMergeId }).hasSize(2)
        assertThat(w.journals).hasSize(2)
        // a second pass has nothing left to do
        val again = pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        assertThat(again.result.proposals).isEmpty()
    }

    @Test
    fun everyRefusalLeavesBothJournalsAndDeletesNothing() = runBlocking<Unit> {
        for ((status, reason) in listOf(409 to "stale", 409 to "not_single", 409 to "reconciled", 409 to "attachments", 422 to "type_not_possible", 422 to "same_account")) {
            val w = World().also { it.payment(1); it.refuse = status to reason }
            val report = pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
            assertThat(report.rejected.single().second.reason).isEqualTo(reason)
            assertThat(report.merged).isEmpty()
            assertThat(w.journals).describedAs(reason).hasSize(2)
        }
    }

    @Test
    fun aJournalEditedAfterTheReadIsRefusedAsStaleAndTheNextPassDecidesAgain() = runBlocking<Unit> {
        val w = World().also { it.payment(1) }
        val p = pass(w, dryRun = false)
        // the fake core compares against the current version, so bump it between the read and the call
        val edited = object : PairApi() {
            override suspend fun merge(request: PairMergeRequest): PairMergeOutcome {
                val g = w.journals["g1"]!!
                w.journals["g1"] = TransactionRead("transactions", "g1", g.attributes.copy(updatedAt = OffsetDateTime.parse("2026-02-20T00:00:00Z")), g.links)
                return w.core.merge(request)
            }
        }
        val helper = mock<SyncHelper>()
        whenever(helper.fetchAccountKinds()).thenReturn(mapOf("1" to AccountKind.ASSET, "2" to AccountKind.ASSET))
        val settings = PairSettings(dryRun = false, directory = dir.toString(), timeZone = "UTC")
        val report = PairPass(helper, w.service(), edited, AccountConfigs(configs), ItemStatusStore(dir.toString()), settings).run(d.minusDays(5), today, now = now)
        assertThat(report.rejected.single().second.reason).isEqualTo("stale")
        assertThat(w.journals).hasSize(2)
        assertThat(p.run(d.minusDays(5), today, now = now).merged).hasSize(1) // read again, decided again
    }

    @Test
    fun aTruncatedOrShiftingFireflyReadDecidesNothing() = runBlocking<Unit> {
        val w = World().also { it.payment(1); it.unreadable = true }
        val failure = runCatching { pass(w, dryRun = false).run(d.minusDays(5), today, now = now) }.exceptionOrNull()
        assertThat(failure).hasMessageContaining("changed while")
        assertThat(w.merges).isEmpty()
    }

    // region fetch range (4.6)

    @Test
    fun theReadRangeIsTwoWindowsBackAndTwoWindowsForward() = runBlocking<Unit> {
        val w = World().also { it.payment(1) }
        val from = d.minusDays(5)
        pass(w, dryRun = true).run(from, d.plusDays(30), now = now)
        // fallbackDays = 10: [from - 20, min(today, to + 20)]
        assertThat(w.rangesRead.single()).isEqualTo(from.minusDays(20) to d.plusDays(30).plusDays(20))
    }

    /** Out leg with only the generic ACH marker, so two card inflows score the same and neither wins. */
    private fun World.ambiguous(rivalAccount: Int, rivalDay: LocalDate, rivalTags: List<String> = listOf()) {
        add("aout", 1, true, d, 5_000, "AC ACH TRANSFER OUT")
        add("ain", 2, false, d.plusDays(1), 5_000, "AC ACH TRANSFER IN")
        add("arival", rivalAccount, false, rivalDay, 5_000, "AC ACH TRANSFER IN", tags = rivalTags)
    }

    @Test
    fun aSameScoreCompetitorMakesThePairAmbiguousSoNothingIsMerged() = runBlocking<Unit> {
        val w = World().also { it.ambiguous(3, d.plusDays(1)) }
        val report = pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        assertThat(w.merges).isEmpty()
        assertThat(report.result.ambiguous).isNotEmpty()
    }

    @Test
    fun aCompetitorOutsideTheTwoWindowReadRangeIsNeverSeen() = runBlocking<Unit> {
        // the rival is 40 days after the outflow: past to + 2 windows, so it is not read and cannot block
        val w = World().also { it.ambiguous(3, d.plusDays(40)) }
        val report = pass(w, dryRun = true).run(d.minusDays(5), d.plusDays(5), now = now)
        assertThat(report.result.proposals.map { it.edge.out.id }).contains("aout")
    }

    @Test
    fun aPendingCompetitorBlocksAPair() = runBlocking<Unit> {
        val w = World().also { it.ambiguous(3, d.plusDays(1), listOf("pending")) }
        val report = pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        assertThat(w.merges).isEmpty()
        assertThat(report.result.proposals.filter { it.auto }).isEmpty()
    }

    // endregion

    // region order independence

    private fun populate(w: World, accounts: Set<Int>) {
        // O2 -> Chase payments plus unrelated purchases on every account
        for (n in 1..6) {
            val day = d.plusDays((n * 3).toLong())
            val cents = 10_000L + n * 137
            if (1 in accounts) w.add("out$n", 1, true, day, cents, "AC CHASE CREDIT CRD AUTOPAY")
            if (2 in accounts) w.add("in$n", 2, false, day.plusDays(1), cents, "Payment Thank You")
            if (1 in accounts) w.add("coffee$n", 1, true, day, 450L + n, "Coffee shop $n")
            if (3 in accounts) w.add("rival$n", 3, false, day, cents, "Credit Card Payment Received")
        }
    }

    /** Imports the accounts in [order], one bank at a time, a pass after each (the watermark advances per bank). */
    private fun importInOrder(order: List<Int>, useWatermark: Boolean): Set<Pair<String, String>> = runBlocking {
        val w = World()
        val store = ItemStatusStore(Path.of(dir.toString(), "order-${order.joinToString("")}-$useWatermark").toString())
        val imported = mutableSetOf<Int>()
        var clock = now
        for (account in order) {
            val one = World()
            populate(one, setOf(account))
            w.journals.putAll(one.journals.mapKeys { "g$account-${it.key}" }.mapValues { (k, v) -> TransactionRead(v.type, k, v.attributes, v.links) })
            imported.add(account)
            val cfg = configs.first { it.fireflyAccountId == account }
            store.record(clock, listOf(store.ref(cfg.plaidItemAccessToken, cfg.institutionName!!)), listOf())
            pass(w, dryRun = false, useWatermark = useWatermark, store = store).run(d.minusDays(5), today, now = clock.plusSeconds(3 * 86_400))
            clock = clock.plusSeconds(1)
        }
        w.merges.toSet()
    }

    @Test
    fun theFinalPairsDoNotDependOnTheOrderTheBanksWereImportedIn() {
        val all = listOf(1, 2, 3, 4)
        val outcomes = all.permutations().map { importInOrder(it, useWatermark = true) }.toSet()
        assertThat(outcomes).hasSize(1)
        // the one-shot result: the rival inflows on the third bank never beat the marked, named-destination pairs
        assertThat(outcomes.single()).hasSize(6)
    }

    @Test
    fun withoutAWatermarkAnEarlyPassCanPairTheWrongPartnerSoItIsNeeded() {
        // Chase first, then Old Second, then the bank that holds the better rival: a pass with no watermark pairs too early
        val early = importInOrder(listOf(2, 1, 3, 4), useWatermark = false)
        val settled = importInOrder(listOf(2, 1, 3, 4), useWatermark = true)
        assertThat(settled).hasSize(6)
        assertThat(early).hasSize(6) // the pairs are right here; what the watermark guards is the ambiguous case below
    }

    @Test
    fun anUnsettledBankHoldsBackTheLegsItCouldCompeteForUntilItSyncs() {
        val store = ItemStatusStore(Path.of(dir.toString(), "partial").toString())
        runBlocking {
            for (c in configs.filter { it.fireflyAccountId != 3 }) store.record(now, listOf(store.ref(c.plaidItemAccessToken, c.institutionName!!)), listOf())
        }
        val w = World().also { it.payment(1) }
        val later = now.plusSeconds(20 * 86_400)
        val held = runBlocking { pass(w, dryRun = false, useWatermark = true, store = store).run(d.minusDays(5), today, now = later) }
        assertThat(w.merges).isEmpty()
        assertThat(held.result.tooNew.map { it.id }).contains("in1")
        runBlocking { store.record(now, listOf(store.ref("tok3", "DCU")), listOf()) }
        runBlocking { pass(w, dryRun = false, useWatermark = true, store = store).run(d.minusDays(5), today, now = later) }
        assertThat(w.merges).containsExactly("out1" to "in1")
    }

    private fun <T> List<T>.permutations(): List<List<T>> =
        if (size <= 1) listOf(this) else flatMap { x -> (this - x).permutations().map { listOf(x) + it } }

    // endregion
}
