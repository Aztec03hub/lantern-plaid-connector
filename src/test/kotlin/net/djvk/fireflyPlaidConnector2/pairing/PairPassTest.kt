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
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountTypeProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.config.AccountConfig
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.sync.FireflyTransactionService
import net.djvk.fireflyPlaidConnector2.sync.PairRunner
import net.djvk.fireflyPlaidConnector2.sync.ItemStatusStore
import net.djvk.fireflyPlaidConnector2.sync.SyncHelper
import net.djvk.fireflyPlaidConnector2.transactions.AccountKind
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files
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
        /** The Plaid id of an outflow whose merge makes core answer HTTP 500 (an exception, not a refusal). */
        var throwFor: String? = null
        private val originals = mutableMapOf<String, Pair<TransactionRead, TransactionRead>>()

        /** A person unmerges: both journals are plain singles again, as core's unmerge restores them. */
        fun unmerge(mergeId: String) {
            val (keep, absorb) = originals.getValue(mergeId)
            journals[keep.id] = keep
            journals[absorb.id] = absorb
        }
        val rangesRead = mutableListOf<Pair<LocalDate, LocalDate>>()
        private var seq = 1

        fun add(plaidId: String, account: Int, out: Boolean, date: LocalDate, cents: Long, text: String, tags: List<String> = listOf(), updated: String = "2026-02-01T00:00:00Z",
                linkLeg: PlaidLinkLeg = PlaidLinkLeg.single, destType: AccountTypeProperty? = null, amountText: String? = null,
                reconciled: Boolean = false, foreignAmount: String? = null) {
            val amount = amountText ?: "%d.%02d".format(cents / 100, cents % 100)
            val split = FireflyFixtures.getTransaction(
                type = if (out) TransactionTypeProperty.withdrawal else TransactionTypeProperty.deposit,
                date = OffsetDateTime.of(date.atStartOfDay(), ZoneOffset.UTC), amount = amount, description = text,
                sourceId = if (out) account.toString() else null, destinationId = if (out) null else account.toString(),
                sourceName = if (out) null else "Somewhere", destinationName = if (out) "Somewhere" else null,
                plaidLinks = listOf(PlaidLink(plaidId, linkLeg, "p$account")), tags = tags, updatedAt = OffsetDateTime.parse(updated),
                currencyCode = "USD", reconciled = reconciled, destinationType = destType, foreignAmount = foreignAmount,
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
                if (journals[request.keepGroupId]?.attributes?.transactions?.first()?.plaidLinks?.first()?.plaidTransactionId == throwFor) error("core answered HTTP 500")
                val keep = journals[request.keepGroupId] ?: return PairMergeOutcome.Rejected(404, "not found")
                val absorb = journals[request.absorbGroupId] ?: return PairMergeOutcome.Rejected(404, "not found")
                val keepStamp = parseCoreStamp(request.keepUpdatedAt)
                val absorbStamp = parseCoreStamp(request.absorbUpdatedAt)
                if (keepStamp == null || absorbStamp == null) return PairMergeOutcome.Rejected(422, "invalid_request")
                if (keep.attributes.updatedAt?.toInstant() != keepStamp || absorb.attributes.updatedAt?.toInstant() != absorbStamp) {
                    return PairMergeOutcome.Rejected(409, "stale")
                }
                val ks = keep.attributes.transactions.first()
                val a = absorb.attributes.transactions.first()
                val links = listOf(ks.plaidLinks!!.first().copy(leg = PlaidLinkLeg.source), a.plaidLinks!!.first().copy(leg = PlaidLinkLeg.destination))
                originals["m${merges.size + 1}"] = keep to absorb
                journals[request.keepGroupId] = TransactionRead(
                    "transactions", keep.id, keep.attributes.copy(transactions = listOf(ks.copy(plaidLinks = links, destinationId = a.destinationId))), keep.links,
                )
                journals.remove(request.absorbGroupId)
                merges.add(ks.plaidLinks!!.first().plaidTransactionId to a.plaidLinks!!.first().plaidTransactionId)
                return PairMergeOutcome.Merged("m${merges.size}", "transfer")
            }
        }
    }

    private fun pass(world: World, dryRun: Boolean, useWatermark: Boolean = false, store: ItemStatusStore = ItemStatusStore(dir.toString()), stateDir: String = dir.toString()): PairPass {
        val helper = mock<SyncHelper>()
        runBlocking { whenever(helper.fetchAccountKinds()).thenReturn(mapOf("1" to AccountKind.ASSET, "2" to AccountKind.LIABILITY, "3" to AccountKind.LIABILITY, "4" to AccountKind.ASSET)) }
        val settings = PairSettings(dryRun = dryRun, useWatermark = useWatermark, directory = stateDir, timeZone = "UTC", pendingTag = "pending")
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

    @Test
    fun aPendingLegThatIsTheBetterPartnerHoldsThePairInsteadOfTheLaterSettledOne() = runBlocking<Unit> {
        // T3: the pending inflow is one day after the outflow (the better partner), the settled one three days after.
        // Only the pending tag makes the pass wait; without it the pending leg would be merged.
        val w = World().also {
            it.add("out1", 1, true, d, 12_345, "AC CHASE CREDIT CRD AUTOPAY")
            it.add("pend", 2, false, d.plusDays(1), 12_345, "Payment Thank You", tags = listOf("pending"))
            it.add("late", 2, false, d.plusDays(3), 12_345, "Payment Thank You")
        }
        val report = pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        assertThat(w.merges).isEmpty()
        assertThat(report.result.waiting).isNotEmpty()
        assertThat(report.result.waiting.single().inn.id).isEqualTo("pend")
        // once the tag is gone the same leg is merged
        val settled = World().also {
            it.add("out1", 1, true, d, 12_345, "AC CHASE CREDIT CRD AUTOPAY")
            it.add("pend", 2, false, d.plusDays(1), 12_345, "Payment Thank You")
            it.add("late", 2, false, d.plusDays(3), 12_345, "Payment Thank You")
        }
        pass(settled, dryRun = false).run(d.minusDays(5), today, now = now)
        assertThat(settled.merges).containsExactly("out1" to "pend")
    }

    // endregion

    // region T2: the settle window

    @Test
    fun aLegIsNotMergedBeforeItsFallbackWindowHasPassedAndIsMergedExactlyOnTheLastDay() = runBlocking<Unit> {
        // default settings: every watermark is today, so "date + fallbackDays <= today" is the whole early-pairing guard
        val tooNew = World().also { it.payment(1, day = today.minusDays(3)) }
        val early = pass(tooNew, dryRun = false).run(today.minusDays(40), today, now = now)
        assertThat(tooNew.merges).isEmpty()
        assertThat(early.result.tooNew.map { it.id }).containsExactlyInAnyOrder("out1", "in1")

        // the inflow is a day after the outflow: out on today-11 is settled, in on today-10 is settled on its last day
        val boundary = World().also { it.payment(1, day = today.minusDays(11)) }
        pass(boundary, dryRun = false).run(today.minusDays(40), today, now = now)
        assertThat(boundary.merges).containsExactly("out1" to "in1")

        val oneDayShort = World().also { it.payment(1, day = today.minusDays(10)) }
        val report = pass(oneDayShort, dryRun = false).run(today.minusDays(40), today, now = now)
        assertThat(oneDayShort.merges).isEmpty()
        assertThat(report.result.tooNew.map { it.id }).containsExactly("in1")
    }

    @Test
    fun aLegBeforeTheDecisionWindowIsReadAsACompetitorButNeverDecided() = runBlocking<Unit> {
        val w = World().also { it.payment(1) }
        val report = pass(w, dryRun = false).run(d.plusDays(3), today, now = now)
        assertThat(report.result.proposals).isEmpty()
        assertThat(w.merges).isEmpty()
    }

    // endregion

    // region T4 and L3: a routed payment is never a candidate

    @Test
    fun aRoutedPaymentWithASourceLinkIsNeverPairedWithASameAmountInflow() = runBlocking<Unit> {
        val w = World().also {
            it.add("routed", 1, true, d, 12_345, "AC CHASE CREDIT CRD AUTOPAY", linkLeg = PlaidLinkLeg.source)
            it.add("in1", 2, false, d.plusDays(1), 12_345, "Payment Thank You")
        }
        val report = pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        assertThat(report.result.proposals).isEmpty()
        assertThat(w.merges).isEmpty()
        assertThat(report.legsRead).isEqualTo(1)
    }

    @Test
    fun aSingleLinkWithdrawalWhoseDestinationIsNowAnOwnAccountIsNotACandidate() = runBlocking<Unit> {
        for (type in listOf(AccountTypeProperty.loan, AccountTypeProperty.debt, AccountTypeProperty.mortgage, AccountTypeProperty.assetAccount)) {
            val w = World().also {
                it.add("out1", 1, true, d, 12_345, "AC CHASE CREDIT CRD AUTOPAY", destType = type)
                it.add("in1", 2, false, d.plusDays(1), 12_345, "Payment Thank You")
            }
            val report = pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
            assertThat(w.merges).describedAs(type.toString()).isEmpty()
            assertThat(report.legsRead).isEqualTo(1)
        }
        val expense = World().also {
            it.add("out1", 1, true, d, 12_345, "AC CHASE CREDIT CRD AUTOPAY", destType = AccountTypeProperty.expenseAccount)
            it.add("in1", 2, false, d.plusDays(1), 12_345, "Payment Thank You")
        }
        pass(expense, dryRun = false).run(d.minusDays(5), today, now = now)
        assertThat(expense.merges).hasSize(1)
    }

    // endregion

    // region T7: guards core also enforces, and the amount extraction

    @Test
    fun aReconciledOrForeignAmountLegGoesToNeedsHumanAndIsNotPaired() = runBlocking<Unit> {
        val w = World().also {
            it.add("out1", 1, true, d, 12_345, "AC CHASE CREDIT CRD AUTOPAY", reconciled = true)
            it.add("in1", 2, false, d.plusDays(1), 12_345, "Payment Thank You")
            it.add("out2", 1, true, d.plusDays(20), 7_700, "AC CHASE CREDIT CRD AUTOPAY", foreignAmount = "70.00")
            it.add("in2", 2, false, d.plusDays(21), 7_700, "Payment Thank You")
        }
        val report = pass(w, dryRun = false).run(d.minusDays(5), d.plusDays(40), now = now)
        assertThat(report.needsHuman.map { it.first.id to it.second }).containsExactlyInAnyOrder("out1" to "reconciled", "out2" to "foreign amount")
        assertThat(w.merges).isEmpty()
    }

    @Test
    fun anAmountWithAThirdDecimalRoundsHalfUpToTheCent() = runBlocking<Unit> {
        val w = World().also {
            it.add("out1", 1, true, d, 0, "AC CHASE CREDIT CRD AUTOPAY", amountText = "12.345")
            it.add("in1", 2, false, d.plusDays(1), 1_235, "Payment Thank You")
        }
        pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        assertThat(w.merges).containsExactly("out1" to "in1")
    }

    // endregion

    // region H1: a human unmerge sticks

    private fun stateFile() = dir.resolve("pair-state.json")

    @Test
    fun aPairAPersonUnmergedIsNeverMergedAgainByTheNextPass() = runBlocking<Unit> {
        val w = World().also { it.payment(1) }
        pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        assertThat(w.merges).hasSize(1)
        w.unmerge("m1") // the person unmerges in core; the connector is not told
        val next = pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        assertThat(next.result.proposals).isEmpty()
        assertThat(w.merges).hasSize(1) // not merged again
        assertThat(w.journals).hasSize(2)
        // and again, the night after
        assertThat(pass(w, dryRun = false).run(d.minusDays(5), today, now = now).result.proposals).isEmpty()
        val state = PairStateFile.read(dir.toString())
        assertThat(state.rejected.map { it.out to it.inn }).containsExactly("out1" to "in1")
        assertThat(state.merges).isEmpty()
        // a different pair is still merged
        w.payment(2, 20_000, d.plusDays(3))
        pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        assertThat(w.merges).hasSize(2)
    }

    @Test
    fun aDryRunAfterAnUnmergeDoesNotProposeTheRejectedPairEither() = runBlocking<Unit> {
        val w = World().also { it.payment(1) }
        pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        w.unmerge("m1")
        assertThat(pass(w, dryRun = true).run(d.minusDays(5), today, now = now).result.proposals).isEmpty()
    }

    @Test
    fun aVersionOneStateFileStillLoadsAndIsUpgradedOnTheNextRealPass() = runBlocking<Unit> {
        Files.writeString(stateFile(), """{"lags":{"1:2":[1,1]},"pendingFirstSeen":{},"pendingDurations":[3],"merges":[]}""")
        val w = World().also { it.payment(1) }
        pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        assertThat(w.merges).hasSize(1)
        val state = PairStateFile.read(dir.toString())
        assertThat(state.version).isEqualTo(PairState.CURRENT_VERSION)
        assertThat(state.pendingDurations).containsExactly(3)
        assertThat(state.lags["1:2"]).contains(1, 1)
        assertThat(Files.readString(stateFile())).contains("\"version\"", "\"rejected\"")
        // N4: the v1 file is kept the first time it is upgraded, and a second real pass does not overwrite that copy
        val bak = dir.resolve("pair-state.json.v1.bak")
        assertThat(Files.readString(bak)).isEqualTo("""{"lags":{"1:2":[1,1]},"pendingFirstSeen":{},"pendingDurations":[3],"merges":[]}""")
        pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        assertThat(Files.readString(bak)).contains("\"pendingDurations\":[3]").doesNotContain("version")
    }

    @Test
    fun aRunnerUnmergeRecordsTheRejectionAndForgetsTheMerge() = runBlocking<Unit> {
        val w = World().also { it.payment(1) }
        val settings = PairSettings(dryRun = false, directory = dir.toString(), timeZone = "UTC", pendingTag = "pending")
        pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        w.unmerge("m1")
        val helper = mock<SyncHelper>()
        val api = object : PairApi() {
            override suspend fun unmerge(pairMergeId: String, force: Boolean) {}
        }
        PairRunner(helper, mock<TransactionConverter>(), pass(w, dryRun = false), settings, api, unmerge = "m1").run()
        val state = PairStateFile.read(dir.toString())
        assertThat(state.merges).isEmpty()
        assertThat(state.rejected.single().pairMergeId).isEqualTo("m1")
        assertThat(state.rejected.single().out).isEqualTo("out1")
        // a runner pass now (past the rejection) merges nothing
        PairRunner(helper, mock<TransactionConverter>(), pass(w, dryRun = false), settings, api).run()
        assertThat(w.merges).hasSize(1)
    }

    @Test
    fun aStampWithZeroSecondsIsSentWithSecondsAndAnOffset() = runBlocking<Unit> {
        val w = World().also {
            it.add("out1", 1, true, d, 12_345, "AC CHASE CREDIT CRD AUTOPAY", updated = "2026-02-01T12:00:00-05:00")
            it.add("in1", 2, false, d.plusDays(1), 12_345, "Payment Thank You", updated = "2026-02-01T12:00:30-05:00")
        }
        val seen = mutableListOf<PairMergeRequest>()
        val spy = object : PairApi() {
            override suspend fun merge(request: PairMergeRequest): PairMergeOutcome { seen.add(request); return w.core.merge(request) }
        }
        passWith(w, spy).run(d.minusDays(5), today, now = now)
        assertThat(seen.single().keepUpdatedAt).isEqualTo("2026-02-01T12:00:00-05:00")
        assertThat(seen.single().absorbUpdatedAt).isEqualTo("2026-02-01T12:00:30-05:00")
        assertThat(w.merges).hasSize(1) // and the strict fake core accepted both stamps
    }

    @Test
    fun theRealMapperNormalisesToUtcAndTheStampStillHasSecondsAndNoZ() {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper().apply(net.djvk.fireflyPlaidConnector2.api.firefly.infrastructure.ApiClient.JSON_DEFAULT)
        fun read(s: String) = mapper.readValue("\"$s\"", OffsetDateTime::class.java)
        assertThat(PairPass.stamp(read("2026-10-08T12:00:00-05:00"))).isEqualTo("2026-10-08T17:00:00+00:00")
        assertThat(PairPass.stamp(read("2026-10-08T12:00:30-05:00"))).isEqualTo("2026-10-08T17:00:30+00:00")
        assertThat(PairPass.stamp(read("2026-10-08T12:00:00Z"))).isEqualTo("2026-10-08T12:00:00+00:00")
        assertThat(parseCoreStamp(PairPass.stamp(read("2026-10-08T12:00:00Z")))).isNotNull()
        assertThat(parseCoreStamp("2026-10-08T12:00Z")).isNull() // what toString() printed: core would refuse it
    }

    @Test
    fun aMergeFromACorrectedStampFormatIsRefusedByTheStrictFakeWhenTheFormatIsWrong() = runBlocking<Unit> {
        val w = World().also { it.payment(1) }
        val toStringApi = object : PairApi() {
            override suspend fun merge(request: PairMergeRequest) =
                w.core.merge(request.copy(keepUpdatedAt = "2026-02-01T00:00Z", absorbUpdatedAt = "2026-02-01T00:00Z"))
        }
        val report = passWith(w, toStringApi).run(d.minusDays(5), today, now = now)
        assertThat(report.rejected.single().second.reason).isEqualTo("invalid_request")
        assertThat(w.merges).isEmpty()
    }

    @Test
    fun earlierMergesSurviveQuietPassesSoALaterUnmergeSticks() = runBlocking<Unit> {
        val w = World().also { it.payment(1) }
        pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        pass(w, dryRun = false).run(d.minusDays(5), today, now = now) // a quiet night
        assertThat(PairStateFile.read(dir.toString()).merges.map { it.out.id }).containsExactly("out1")
        w.unmerge("m1")
        pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        assertThat(w.merges).hasSize(1)
        assertThat(PairStateFile.read(dir.toString()).rejected.map { it.out to it.inn }).containsExactly("out1" to "in1")
    }

    @Test
    fun forgivingARejectedPairLetsTheNextPassMergeItAgain() = runBlocking<Unit> {  // nit1
        val w = World().also { it.payment(1) }
        pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        w.unmerge("m1")
        pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        assertThat(w.merges).hasSize(1)
        val settings = PairSettings(dryRun = false, directory = dir.toString(), timeZone = "UTC", pendingTag = "pending")
        PairRunner(mock<SyncHelper>(), mock<TransactionConverter>(), pass(w, dryRun = false), settings, PairApi(), forgive = "out1,in1").run()
        assertThat(PairStateFile.read(dir.toString()).rejected).isEmpty()
        pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        assertThat(w.merges).hasSize(2)
    }

    private fun runnerFor(w: World, api: PairApi, unmerge: String, out: String = "", inn: String = "") =
        PairRunner(
            mock<SyncHelper>(), mock<TransactionConverter>(), pass(w, dryRun = false),
            PairSettings(dryRun = false, directory = dir.toString(), timeZone = "UTC", pendingTag = "pending"), api, unmerge = unmerge, unmergeOut = out, unmergeInn = inn,
        )

    private fun passWith(w: World, api: PairApi): PairPass {
        val helper = mock<SyncHelper>()
        runBlocking { whenever(helper.fetchAccountKinds()).thenReturn(mapOf("1" to AccountKind.ASSET, "2" to AccountKind.LIABILITY, "3" to AccountKind.LIABILITY, "4" to AccountKind.ASSET)) }
        val settings = PairSettings(dryRun = false, directory = dir.toString(), timeZone = "UTC", pendingTag = "pending")
        return PairPass(helper, w.service(), api, AccountConfigs(configs), ItemStatusStore(dir.toString()), settings)
    }

    @Test
    fun anUnmergeOfAMergeMissingFromStateStopsBeforeCoreUnlessThePlaidIdsAreGiven() = runBlocking<Unit> {
        val w = World().also { it.payment(1) }
        pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        Files.delete(stateFile()) // the merge was never recorded
        w.unmerge("m1")
        val calls = mutableListOf<String>()
        val api = object : PairApi() { override suspend fun unmerge(pairMergeId: String, force: Boolean) { calls.add(pairMergeId) } }
        org.assertj.core.api.Assertions.assertThatThrownBy { runnerFor(w, api, "m1").run() }.hasMessageContaining("not in pair-state.json").hasMessageContaining("unmergeOut")
        assertThat(calls).isEmpty() // core was not touched
        runnerFor(w, api, "m1", "out1", "in1").run()
        assertThat(calls).containsExactly("m1")
        pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        assertThat(w.merges).hasSize(1) // not merged again
        assertThat(PairStateFile.read(dir.toString()).rejected.single().out).isEqualTo("out1")
    }

    @Test
    fun aMergeCoreCommittedDespiteATimeoutIsRecoveredSoALaterUnmergeSticks() = runBlocking<Unit> {
        val w = World().also { it.payment(1) }
        val timeoutAfterCommit = object : PairApi() {
            override suspend fun merge(request: PairMergeRequest): PairMergeOutcome { w.core.merge(request); error("read timeout") }
        }
        val first = passWith(w, timeoutAfterCommit).run(d.minusDays(5), today, now = now)
        assertThat(first.failed).hasSize(1)
        assertThat(w.merges).hasSize(1)
        assertThat(PairStateFile.read(dir.toString()).merges).isEmpty() // not recorded yet
        pass(w, dryRun = false).run(d.minusDays(5), today, now = now) // next night sees two merged journals
        assertThat(PairStateFile.read(dir.toString()).merges.map { it.out.id to it.inn.id }).containsExactly("out1" to "in1")
        w.unmerge("m1") // a person unmerges
        pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        assertThat(w.merges).hasSize(1) // no second merge
    }

    // endregion

    // region A2: one bad pair does not stall the pass

    @Test
    fun aPairThatFailsIsRecordedAndTheOtherPairsAreStillMergedAndSaved() = runBlocking<Unit> {
        val w = World().also {
            it.payment(1); it.payment(2, 20_000, d.plusDays(3)); it.payment(3, 30_000, d.plusDays(6))
            it.throwFor = "out2"
        }
        val report = pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        assertThat(w.merges.map { it.first }).containsExactlyInAnyOrder("out1", "out3")
        assertThat(report.failed.map { it.out }).containsExactly("out2")
        assertThat(report.failed.single().error).contains("HTTP 500")
        val state = PairStateFile.read(dir.toString())
        assertThat(state.merges.map { it.out.id }).containsExactlyInAnyOrder("out1", "out3")
        assertThat(state.failures.map { it.out to it.inn }).containsExactly("out2" to "in2")
        assertThat(state.lags).isNotEmpty()
        // the next night, with core healthy again, the failed pair merges and the failure list is cleared
        w.throwFor = null
        val again = pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        assertThat(again.failed).isEmpty()
        assertThat(w.merges).hasSize(3)
        assertThat(PairStateFile.read(dir.toString()).failures).isEmpty()
    }

    @Test
    fun theRunnerExitsNonZeroWhenAPairFailedAfterTheOthersWereMerged() = runBlocking<Unit> {
        val w = World().also { it.payment(1); it.payment(2, 20_000, d.plusDays(3)); it.throwFor = "out1" }
        val settings = PairSettings(dryRun = false, directory = dir.toString(), timeZone = "UTC", pendingTag = "pending")
        val failure = runCatching { PairRunner(mock<SyncHelper>(), mock<TransactionConverter>(), pass(w, dryRun = false), settings, w.core).run() }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java).hasMessageContaining("1 pair(s) failed").hasMessageContaining("out1")
        assertThat(w.merges.map { it.first }).containsExactly("out2")
    }

    // endregion

    // region L6, A4, B3

    @Test
    fun aCorruptStateFileStopsThePassAndIsLeftAsItIs() = runBlocking<Unit> {
        Files.writeString(stateFile(), """{"lags": {"1:2": [1,""")
        val w = World().also { it.payment(1) }
        val failure = runCatching { pass(w, dryRun = false).run(d.minusDays(5), today, now = now) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java).hasMessageContaining("pair-state.json")
        assertThat(Files.readString(stateFile())).isEqualTo("""{"lags": {"1:2": [1,""")
        assertThat(w.merges).isEmpty()
    }

    @Test
    fun aStateFileFromANewerConnectorStopsThePass() = runBlocking<Unit> {
        Files.writeString(stateFile(), """{"version": 99}""")
        val failure = runCatching { pass(World().also { it.payment(1) }, dryRun = false).run(d.minusDays(5), today, now = now) }.exceptionOrNull()
        assertThat(failure).hasMessageContaining("newer")
    }

    @Test
    fun theStateFileIsWrittenWholeThroughATempFile() = runBlocking<Unit> {
        pass(World().also { it.payment(1) }, dryRun = false).run(d.minusDays(5), today, now = now)
        assertThat(Files.exists(dir.resolve("pair-state.json.tmp"))).isFalse()
        assertThat(PairStateFile.read(dir.toString()).merges).hasSize(1)
    }

    @Test
    fun everyRunPrintsTheSettingsInEffectAndTheDefaultsAreTheNightlyOnes() = runBlocking<Unit> {
        val captured = java.io.ByteArrayOutputStream()
        val old = System.out
        System.setOut(java.io.PrintStream(captured, true))
        try {
            pass(World().also { it.payment(1) }, dryRun = true).run(d.minusDays(5), today, now = now)
        } finally {
            System.setOut(old)
        }
        assertThat(captured.toString()).contains("Pair settings in effect: dryRun=true autoMin=4 reviewMin=3 markerDays=5 fallbackDays=10")
        assertThat(PairSettings().autoMin).isEqualTo(PairingConfig().autoMin).isEqualTo(4)
        assertThat(PairSettings().reviewMin).isEqualTo(PairingConfig().reviewMin).isEqualTo(3)
        assertThat(PairSettings().markerDays).isEqualTo(PairingConfig().markerDays)
        assertThat(PairSettings().fallbackDays).isEqualTo(PairingConfig().fallbackDays)
    }

    @Test
    fun theBundledYmlDoesNotOverrideThePairSettingsTheNightlyUses() {
        @Suppress("UNCHECKED_CAST")
        val root = org.yaml.snakeyaml.Yaml().load<Map<String, Any?>>(Files.readString(Path.of("src/main/resources/application.yml")))
        @Suppress("UNCHECKED_CAST")
        val pair = ((root["fireflyPlaidConnector2"] as Map<String, Any?>)["pair"] as Map<String, Any?>?).orEmpty()
        assertThat(pair.keys).doesNotContain("autoMin", "reviewMin", "markerDays", "fallbackDays", "lookbackDays", "useWatermark")
    }

    @Test
    fun aDryRunLeavesTheAwaitingFileOfTheLastRealPassAlone() = runBlocking<Unit> {
        val awaiting = dir.resolve("pair-awaiting.json")
        Files.writeString(awaiting, "SENTINEL")
        val w = World().also { it.payment(1) }
        pass(w, dryRun = true).run(d.minusDays(5), today, now = now)
        assertThat(Files.readString(awaiting)).isEqualTo("SENTINEL")
        assertThat(Files.exists(dir.resolve("pair-awaiting-dryrun.json"))).isTrue()
        pass(w, dryRun = false).run(d.minusDays(5), today, now = now)
        assertThat(Files.readString(awaiting)).isNotEqualTo("SENTINEL")
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
            pass(w, dryRun = false, useWatermark = useWatermark, store = store, stateDir = Path.of(dir.toString(), "order-${order.joinToString("")}-$useWatermark").toString()).run(d.minusDays(5), today, now = clock.plusSeconds(3 * 86_400))
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
    fun withoutAWatermarkTheSamePairsResultWhenNoLegIsEverTooNew() {
        // Names what it asserts: here the watermark changes nothing. The settle window itself is pinned by the T2 tests below.
        val early = importInOrder(listOf(2, 1, 3, 4), useWatermark = false)
        val settled = importInOrder(listOf(2, 1, 3, 4), useWatermark = true)
        assertThat(settled).hasSize(6)
        assertThat(early).isEqualTo(settled)
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
