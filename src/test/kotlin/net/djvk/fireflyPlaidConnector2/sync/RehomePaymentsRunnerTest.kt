package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Account
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountSingle
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountTypeProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ShortAccountTypeProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Meta
import net.djvk.fireflyPlaidConnector2.api.firefly.models.MetaPagination
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PageLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionArray
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSingle
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionUpdate
import net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.lib.createFireflyResponse
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.time.OffsetDateTime

/** `syncMode: rehome-payments` against mocked Firefly APIs. */
internal class RehomePaymentsRunnerTest {
    @Suppress("unused")
    private val warmMockUtil = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE

    @TempDir lateinit var dir: File

    private val accountsApi = mock<AccountsApi>()
    private val txApi = mock<TransactionsApi>()
    private val syncHelper = mock<SyncHelper>()
    private val text = "AC PHILLIP LAFAYETT ACH XFER XXXX7769WEB XXXX0198"

    private fun converter(match: String = "XXXX0198"): TransactionConverter {
        val f = File(dir, "pair.json").also {
            it.writeText("""{"scheduled":[{"from":1,"to":2,"amount":400,"day":7}],
                "ownAccountPayments":[{"fromAccount":1,"match":"$match","route":"scheduled","candidates":[2,3]}]}""")
        }
        return TransactionConverter(
            useNameForDestination = true, enablePrimaryCategorization = false, primaryCategoryPrefix = "p-",
            enableDetailedCategorization = false, detailedCategoryPrefix = "d-", timeZoneString = "America/Chicago",
            txStyle = TransactionStyleConfig(null), pairConfigFile = f.path,
        )
    }

    private fun journal(id: String, amount: String, desc: String = text, destType: AccountTypeProperty = AccountTypeProperty.expenseAccount, at: OffsetDateTime? = null) = TransactionRead(
        "transactions", id,
        FireflyFixtures.getTransaction(
            sourceId = "1", destinationId = "50", destinationType = destType, amount = amount, description = desc,
            date = at ?: OffsetDateTime.now().minusDays(2).withDayOfMonth(7).let { if (it.isAfter(OffsetDateTime.now())) it.minusMonths(1) else it },
            transactionJournalId = "j$id", plaidLinks = listOf(PlaidLink("p$id", PlaidLinkLeg.single, "plaid1")),
        ), ObjectLink(),
    )

    private fun runner(apply: Boolean, rows: List<TransactionRead>, match: String = "XXXX0198", reverse: Boolean = false, since: String = "2000-01-01"): RehomePaymentsRunner {
        runBlocking {
            whenever(syncHelper.getAllPlaidAccessTokenAccountIdSets()).thenReturn(Pair(emptyMap(), emptySequence()))
            whenever(accountsApi.listTransactionByAccount(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
                .doSuspendableAnswer { createFireflyResponse(TransactionArray(rows, Meta(), PageLink())) }
        }
        return RehomePaymentsRunner(syncHelper, accountsApi, txApi, converter(match), apply, since, dir.path, "America/Chicago", reverse)
    }

    // 400 matches the Lexus schedule only on the 7th; journals are dated on the 7th of a recent month
    private val rows get() = listOf(
        journal("a", "400.00"),
        journal("b", "110.00"),
        journal("c", "400.00", desc = "SOMETHING ELSE"),
        journal("d", "400.00", destType = AccountTypeProperty.loan),
    )

    @Test
    fun aDryRunChangesNothing() {
        runner(false, rows).run()
        runBlocking { verify(txApi, never()).updateTransaction(any(), any()) }
        assertThat(File(dir, "rehome_log.jsonl")).doesNotExist()
    }

    @Test
    fun applyLogsThenUpdatesOnlyTheMatchedRow() = runBlocking<Unit> {
        val r = runner(true, rows)
        val plan = r.readPlan()
        assertThat(plan.map { it.journalId to it.to }).containsExactlyInAnyOrder("a" to 2, "b" to null)
        r.applyPlan(plan)

        val id = argumentCaptor<String>()
        val upd = argumentCaptor<TransactionUpdate>()
        verify(txApi).updateTransaction(id.capture(), upd.capture())
        assertThat(id.firstValue).isEqualTo("a")
        val split = upd.firstValue.transactions!!.single()
        assertThat(split.destinationId).isEqualTo("2")
        assertThat(split.plaidLinks!!.single().leg).isEqualTo(PlaidLinkLeg.source)
        val lines = File(dir, "rehome_log.jsonl").readLines()
        assertThat(lines).hasSize(2)
        assertThat(lines[0]).contains("\"status\":\"intent\"").contains("\"journal\":\"a\"").contains("\"oldDestination\":\"50\"").contains("\"newDestination\":\"2\"").contains("\"oldLeg\":\"single\"")
        assertThat(lines[1]).contains("\"status\":\"done\"")
    }

    @Test
    fun twoMovesKeepBothLogEntriesTheLogIsAppendedNotOverwritten() = runBlocking<Unit> {  // T9
        val r = runner(true, listOf(journal("a", "400.00"), journal("e", "400.00")))
        r.applyPlan(r.readPlan())
        val lines = File(dir, "rehome_log.jsonl").readLines()
        assertThat(lines).hasSize(4)
        assertThat(lines.count { it.contains("\"journal\":\"a\"") }).isEqualTo(2)
        assertThat(lines.count { it.contains("\"journal\":\"e\"") }).isEqualTo(2)
    }

    @Test
    fun aFailedUpdateLeavesAnIntentLineButNoDoneLine() = runBlocking<Unit> {  // B4
        whenever(txApi.updateTransaction(any(), any())).doSuspendableAnswer { error("422") }
        val r = runner(true, listOf(journal("a", "400.00")))
        org.junit.jupiter.api.assertThrows<IllegalStateException> { r.applyPlan(r.readPlan()) }
        val lines = File(dir, "rehome_log.jsonl").readLines()
        assertThat(lines).hasSize(1)
        assertThat(lines[0]).contains("\"status\":\"intent\"")
    }

    @Test
    fun reverseModePutsDoneMovesBackWithTheirOldLeg() = runBlocking<Unit> {  // B4
        val r = runner(true, listOf(journal("a", "400.00")))
        r.applyPlan(r.readPlan())
        stubJournal("a", "2")  // the move landed
        runner(false, emptyList(), reverse = true).reverseLog()
        verify(txApi, org.mockito.kotlin.times(1)).updateTransaction(any(), any())  // dry run reverses nothing
        runner(true, emptyList(), reverse = true).reverseLog()
        val upd = argumentCaptor<TransactionUpdate>()
        verify(txApi, org.mockito.kotlin.times(2)).updateTransaction(any(), upd.capture())
        val back = upd.lastValue.transactions!!.single()
        assertThat(back.destinationId).isEqualTo("50")
        assertThat(back.plaidLinks!!.single().leg).isEqualTo(PlaidLinkLeg.single)
        assertThat(back.plaidLinks!!.single().plaidTransactionId).isEqualTo("pa")
        runner(true, emptyList(), reverse = true).reverseLog()  // a second reverse finds nothing left
        verify(txApi, org.mockito.kotlin.times(2)).updateTransaction(any(), any())
    }

    private fun done(old: String, new: String, at: String, journal: String = "a", split: String = "j$journal", oldLeg: String = "single", status: String = "done") =
        """{"status":"$status","journal":"$journal","split":"$split","oldDestination":"$old","newDestination":"$new","plaidTx":"pa","plaidAccount":"plaid1","oldLeg":"$oldLeg","at":"$at"}"""

    private fun intent(old: String, new: String, at: String) = done(old, new, at, status = "intent")

    /** Firefly's current view of journal [id]: its split [split] sits on destination [dest] with the one Plaid link on [leg]. */
    private fun stubJournal(id: String, dest: String, leg: PlaidLinkLeg = PlaidLinkLeg.source, split: String = "j$id") = runBlocking {
        val tx = FireflyFixtures.getTransaction(destinationId = dest, transactionJournalId = split, plaidLinks = listOf(PlaidLink("p$id", leg, "plaid1")))
        val resp = createFireflyResponse(TransactionSingle(TransactionRead("transactions", id, tx, ObjectLink())))
        whenever(txApi.getTransaction(id)).thenReturn(resp)
    }

    private fun captureOut(block: () -> Unit): String {
        val old = System.out
        val buf = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(buf, true))
        try { block() } finally { System.setOut(old) }
        return buf.toString()
    }

    @Test
    fun aJournalMovedTwiceIsReversedNewestFirstSoItEndsOnItsFirstDestination() = runBlocking<Unit> {  // N11
        File(dir, "rehome_log.jsonl").writeText(done("50", "60", "2026-10-01T01:00:00Z") + "\n" + done("60", "70", "2026-10-02T01:00:00Z", oldLeg = "source") + "\n")
        stubJournal("a", "70")
        runner(true, emptyList(), reverse = true).reverseLog()
        val upd = argumentCaptor<TransactionUpdate>()
        verify(txApi, org.mockito.kotlin.times(2)).updateTransaction(any(), upd.capture())
        assertThat(upd.allValues.map { it.transactions!!.single().destinationId }).containsExactly("60", "50")
    }

    @Test
    fun aMoveMadeAfterAReverseIsReversedByTheNextReverse() = runBlocking<Unit> {  // N11: keyed on the done line, not on the journal
        File(dir, "rehome_log.jsonl").writeText(done("50", "60", "2026-10-01T01:00:00Z") + "\n")
        stubJournal("a", "60")
        runner(true, emptyList(), reverse = true).reverseLog()
        File(dir, "rehome_log.jsonl").appendText(done("50", "60", "2026-10-05T01:00:00Z") + "\n") // moved again later
        runner(true, emptyList(), reverse = true).reverseLog()
        verify(txApi, org.mockito.kotlin.times(2)).updateTransaction(any(), any())
        runner(true, emptyList(), reverse = true).reverseLog() // nothing left
        verify(txApi, org.mockito.kotlin.times(2)).updateTransaction(any(), any())
    }

    @Test
    fun anOldReversedLineWithoutUndoesStillCoversTheEarlierDoneLinesOfItsJournal() = runBlocking<Unit> {  // W5: lines written by f32667e
        File(dir, "rehome_log.jsonl").writeText(
            done("50", "60", "2026-10-01T01:00:00Z") + "\n" +
                """{"status":"reversed","journal":"a","split":"ja","at":"2026-10-02T01:00:00Z"}""" + "\n" +
                done("50", "60", "2026-10-05T01:00:00Z") + "\n", // a later move, after the old reverse
        )
        stubJournal("a", "60")
        runner(true, emptyList(), reverse = true).reverseLog()
        val upd = argumentCaptor<TransactionUpdate>()
        verify(txApi, org.mockito.kotlin.times(1)).updateTransaction(any(), upd.capture())
        assertThat(upd.firstValue.transactions!!.single().destinationId).isEqualTo("50")
    }

    @Test
    fun anOldReversedLineCoversOnlyTheSameJournalAndSplit() = runBlocking<Unit> {  // L1: pins the journal and the split match
        File(dir, "rehome_log.jsonl").writeText(
            done("50", "60", "2026-10-01T01:00:00Z", "a") + "\n" +                    // covered by the old reverse below
                done("51", "61", "2026-10-01T02:00:00Z", "b", split = "ja") + "\n" +  // other journal, same split: not covered
                done("52", "62", "2026-10-01T03:00:00Z", "a", split = "jx") + "\n" +  // same journal, other split: not covered
                """{"status":"reversed","journal":"a","split":"ja","at":"2026-10-02T01:00:00Z"}""" + "\n",
        )
        stubJournal("a", "62", split = "jx")
        stubJournal("b", "61", split = "ja")
        runner(true, emptyList(), reverse = true).reverseLog()
        val upd = argumentCaptor<TransactionUpdate>()
        verify(txApi, org.mockito.kotlin.times(2)).updateTransaction(any(), upd.capture())
        assertThat(upd.allValues.map { it.transactions!!.single().destinationId }).containsExactly("52", "51")
    }

    @Test
    fun aNewStyleReversedLineCoversOnlyTheLineItUndid() = runBlocking<Unit> {  // L1: pins the undoes == null test
        File(dir, "rehome_log.jsonl").writeText(
            done("50", "60", "2026-10-01T01:00:00Z") + "\n" + done("60", "70", "2026-10-02T01:00:00Z") + "\n" +
                """{"status":"reversed","journal":"a","split":"ja","undoes":"2026-10-02T01:00:00Z","at":"2026-10-03T01:00:00Z"}""" + "\n", // crash before the earlier move was undone
        )
        stubJournal("a", "60")
        runner(true, emptyList(), reverse = true).reverseLog()
        val upd = argumentCaptor<TransactionUpdate>()
        verify(txApi, org.mockito.kotlin.times(1)).updateTransaction(any(), upd.capture())
        assertThat(upd.firstValue.transactions!!.single().destinationId).isEqualTo("50")
    }

    @Test
    fun aRuleAnchoredOnTheOriginalTextMatchesTheBackfillToo() = runBlocking<Unit> {  // L4: the stored text is "merchant: original text"
        val r = runner(false, listOf(journal("a", "400.00", desc = "PHIL: XXXX0198 ACH")), match = "^XXXX0198")
        assertThat(r.readPlan().map { it.journalId to it.to }).containsExactly("a" to 2)
    }

    @Test
    fun amountsRoundHalfUpLikeTheImportDoes() = runBlocking<Unit> {  // L4: 399.995 is 40000 cents at import, not 39999
        val r = runner(false, listOf(journal("a", "399.995")))
        assertThat(r.readPlan().single().to).isEqualTo(2)
    }

    @Test
    fun aRoutingTargetWithPlaidTransactionsOfItsOwnStopsTheRun() = runBlocking<Unit> {  // L5
        val r = runner(false, emptyList())
        fun account(type: ShortAccountTypeProperty) {
            val resp = createFireflyResponse(AccountSingle(AccountRead("accounts", "2", Account("n", type), ObjectLink())))
            runBlocking { whenever(accountsApi.getAccount(any(), anyOrNull())).thenReturn(resp) }
        }
        account(ShortAccountTypeProperty.asset)
        org.junit.jupiter.api.assertThrows<IllegalStateException> { r.checkTargets(listOf(2)) }
        r.checkTargets(listOf(9))  // not a target: fine
        account(ShortAccountTypeProperty.liabilities)
        r.checkTargets(listOf(2, 3))  // a Plaid-synced loan has a balance only
    }

    @Test
    fun anInterruptedApplyIsPutBackByReverse() = runBlocking<Unit> {  // E20: the update landed, the done line never got written
        File(dir, "rehome_log.jsonl").writeText(intent("50", "2", "2026-10-01T01:00:00Z") + "\n")
        stubJournal("a", "2")
        val out = captureOut { runBlocking { runner(true, emptyList(), reverse = true).reverseLog() } }
        val upd = argumentCaptor<TransactionUpdate>()
        verify(txApi).updateTransaction(any(), upd.capture())
        val back = upd.firstValue.transactions!!.single()
        assertThat(back.destinationId).isEqualTo("50")
        assertThat(back.plaidLinks!!.single().leg).isEqualTo(PlaidLinkLeg.single)
        assertThat(out).contains("recovered").contains("REVERSED 1 moves.")
        assertThat(File(dir, "rehome_log.jsonl").readLines().last()).contains("\"status\":\"reversed\"").contains("\"undoes\":\"2026-10-01T01:00:00Z\"")
    }

    @Test
    fun anIntentThatNeverMovedIsNotReversed() = runBlocking<Unit> {  // E20: the update failed
        File(dir, "rehome_log.jsonl").writeText(intent("50", "2", "2026-10-01T01:00:00Z") + "\n")
        stubJournal("a", "50", leg = PlaidLinkLeg.single)
        val out = captureOut { runBlocking { runner(true, emptyList(), reverse = true).reverseLog() } }
        verify(txApi, never()).updateTransaction(any(), any())
        assertThat(out).contains("not moved").contains("REVERSED 0 moves.")
    }

    @Test
    fun anIntentFollowedByItsDoneLineIsReversedOnceNotTwice() = runBlocking<Unit> {
        File(dir, "rehome_log.jsonl").writeText(intent("50", "2", "2026-10-01T01:00:00Z") + "\n" + done("50", "2", "2026-10-01T01:00:01Z") + "\n")
        stubJournal("a", "2")
        runner(true, emptyList(), reverse = true).reverseLog()
        verify(txApi, org.mockito.kotlin.times(1)).updateTransaction(any(), any())
    }

    @Test
    fun aJournalEditedAfterTheDoneLineIsNotReversed() = runBlocking<Unit> {  // B21
        File(dir, "rehome_log.jsonl").writeText(done("50", "2", "2026-10-01T01:00:00Z") + "\n")
        stubJournal("a", "77")
        val out = captureOut { runBlocking { runner(true, emptyList(), reverse = true).reverseLog() } }
        verify(txApi, never()).updateTransaction(any(), any())
        assertThat(out).contains("edited since").contains("destination is now 77")
        assertThat(File(dir, "rehome_log.jsonl").readLines()).hasSize(1)  // a skip writes no log line
    }

    @Test
    fun aJournalWhoseLegWasChangedIsNotReversed() = runBlocking<Unit> {  // B21
        File(dir, "rehome_log.jsonl").writeText(done("50", "2", "2026-10-01T01:00:00Z") + "\n")
        stubJournal("a", "2", leg = PlaidLinkLeg.single)
        val out = captureOut { runBlocking { runner(true, emptyList(), reverse = true).reverseLog() } }
        verify(txApi, never()).updateTransaction(any(), any())
        assertThat(out).contains("edited since")
    }

    @Test
    fun aDryRunReverseChecksStateButWritesNothing() = runBlocking<Unit> {
        File(dir, "rehome_log.jsonl").writeText(
            done("50", "2", "2026-10-01T01:00:00Z", "a") + "\n" + done("51", "2", "2026-10-01T02:00:00Z", "b") + "\n",
        )
        stubJournal("a", "2")
        stubJournal("b", "77")
        val out = captureOut { runBlocking { runner(false, emptyList(), reverse = true).reverseLog() } }
        verify(txApi, never()).updateTransaction(any(), any())
        assertThat(out).contains("journal a: would go back to destination 50").contains("journal b: destination is now 77").contains("DRY RUN")
        assertThat(File(dir, "rehome_log.jsonl").readLines()).hasSize(2)
    }

    @Test
    fun aJournalMovedTwiceChainsThroughTheStateCheck() = runBlocking<Unit> {  // N11 with the state check
        File(dir, "rehome_log.jsonl").writeText(done("50", "60", "2026-10-01T01:00:00Z") + "\n" + done("60", "70", "2026-10-02T01:00:00Z", oldLeg = "source") + "\n")
        stubJournal("a", "70")  // Firefly shows only the final state; the second check must use the simulated one
        val dry = captureOut { runBlocking { runner(false, emptyList(), reverse = true).reverseLog() } }
        assertThat(dry).contains("would go back to destination 60").contains("would go back to destination 50")
        verify(txApi, never()).updateTransaction(any(), any())
        runner(true, emptyList(), reverse = true).reverseLog()
        val upd = argumentCaptor<TransactionUpdate>()
        verify(txApi, org.mockito.kotlin.times(2)).updateTransaction(any(), upd.capture())
        assertThat(upd.allValues.map { it.transactions!!.single().destinationId }).containsExactly("60", "50")
    }

    @Test
    fun theLogIsAppendedDurably() {
        val f = File(dir, "deeper/new/log.jsonl")
        appendDurably(f, "one\n")
        appendDurably(f, "two\n")
        assertThat(f.readLines()).containsExactly("one", "two")
    }

    @Test
    fun aLateEveningMoveLandsOnTheLocalDay() = runBlocking<Unit> {  // A13: 03:30Z on the 8th is 22:30 on the 7th in Chicago
        val r = runner(false, listOf(journal("a", "400.00", at = OffsetDateTime.parse("2026-09-08T03:30:00Z"))))
        val m = r.readPlan().single()
        assertThat(m.date.dayOfMonth).isEqualTo(7)
        assertThat(m.to).isEqualTo(2)
    }

    // U55 (B30): guards of planSplit and applyPlan that no test pinned
    private val onTheSeventh = OffsetDateTime.parse("2026-09-07T17:00:00Z")

    private fun row(id: String, type: TransactionTypeProperty = TransactionTypeProperty.withdrawal, source: String = "1", leg: PlaidLinkLeg = PlaidLinkLeg.single, at: OffsetDateTime = onTheSeventh) = TransactionRead(
        "transactions", id,
        FireflyFixtures.getTransaction(
            type = type, sourceId = source, destinationId = "50", destinationType = AccountTypeProperty.expenseAccount, amount = "400.00",
            description = text, date = at, transactionJournalId = "j$id", plaidLinks = listOf(PlaidLink("p$id", leg, "plaid1")),
        ), ObjectLink(),
    )

    @Test
    fun aLinkAlreadyOnANonSingleLegStaysUnplanned() = runBlocking<Unit> {
        val r = runner(false, listOf(row("a", leg = PlaidLinkLeg.source), row("b")))
        assertThat(r.readPlan().map { it.journalId }).containsExactly("b")
    }

    @Test
    fun onlyWithdrawalsAreEverPlanned() = runBlocking<Unit> {
        val r = runner(false, listOf(row("a", type = TransactionTypeProperty.deposit), row("b", type = TransactionTypeProperty.transfer), row("c")))
        assertThat(r.readPlan().map { it.journalId }).containsExactly("c")
    }

    @Test
    fun aWithdrawalFromAnotherSourceAccountIsNeverPlanned() = runBlocking<Unit> {
        val r = runner(false, listOf(row("a", source = "9"), row("b")))
        assertThat(r.readPlan().map { it.journalId }).containsExactly("b")
    }

    @Test
    fun aRowOnTheSinceDateIsPlannedAndTheDayBeforeIsNot() = runBlocking<Unit> {  // KDoc names no bound; the code keeps `date >= since`
        val r = runner(false, listOf(row("on", at = onTheSeventh), row("before", at = onTheSeventh.minusDays(1))), since = "2026-09-07")
        assertThat(r.readPlan().map { it.journalId }).containsExactly("on")
    }

    @Test
    fun aSearchWithTwoPagesIsReadToTheEnd() = runBlocking<Unit> {
        val r = runner(false, emptyList())
        whenever(accountsApi.listTransactionByAccount(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).doSuspendableAnswer {
            val page = it.getArgument<Int?>(1) ?: 1
            createFireflyResponse(TransactionArray(listOf(row(if (page == 1) "p1" else "p2")), Meta(pagination = MetaPagination(currentPage = page, totalPages = 2)), PageLink()))
        }
        assertThat(r.readPlan().map { it.journalId }).containsExactly("p1", "p2")
    }

    @Test
    fun theUpdateCarriesNoRulesAndNoWebhooks() = runBlocking<Unit> {
        val r = runner(true, listOf(row("a")))
        r.applyPlan(r.readPlan())
        val upd = argumentCaptor<TransactionUpdate>()
        verify(txApi).updateTransaction(any(), upd.capture())
        assertThat(upd.firstValue.applyRules).isEqualTo(false)
        assertThat(upd.firstValue.fireWebhooks).isEqualTo(false)
    }

    @Test
    fun aLaterDoneLineOfAnotherJournalDoesNotCloseAnOpenIntent() = runBlocking<Unit> {
        File(dir, "rehome_log.jsonl").writeText(intent("50", "2", "2026-10-01T01:00:00Z") + "\n" + done("51", "2", "2026-10-01T02:00:00Z", "b") + "\n")
        stubJournal("a", "2")
        stubJournal("b", "2")
        runner(true, emptyList(), reverse = true).reverseLog()
        verify(txApi).updateTransaction(org.mockito.kotlin.eq("a"), any())
        verify(txApi).updateTransaction(org.mockito.kotlin.eq("b"), any())
    }
}
