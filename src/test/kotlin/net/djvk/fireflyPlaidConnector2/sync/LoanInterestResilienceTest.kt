package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.ApiConfiguration
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.*
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapper
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.lib.createFireflyResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/** LAN-50: the loan interest step against a real PlaidApiWrapper on a MockEngine, one loan per institution (tokens tokA, tokB). */
internal class LoanInterestResilienceTest {
    @Suppress("unused")
    private val warm = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE

    @TempDir lateinit var dir: File

    private val zone = ZoneId.of("America/Chicago")
    private val owed = mutableMapOf(2 to BigDecimal("23417.37"), 3 to BigDecimal("23417.37"))
    private val calls = mutableMapOf("tokA" to 0, "tokB" to 0)
    private val stores = mutableListOf<TransactionStore>()
    /** token -> exception its request throws before any answer (a network failure, or a request-building bug) */
    /** when true tokA serves both loan accounts (plA and plB) */
    private var sharedItem = false
    private var requestFails = mapOf<String, Exception>()
    private val txApi = mock<TransactionsApi>()
    private val accountsApi = mock<AccountsApi>()
    private val syncHelper = mock<SyncHelper>()

    private fun errorBody(type: String, code: String) =
        """{"error_type":"$type","error_code":"$code","error_message":"private message","display_message":null,"request_id":"r"}"""

    /** one Plaid balance answer for [accs]; [current] null leaves the `current` field out */
    private fun balance(accs: List<String>, current: String? = "23491.66") =
        """{"accounts":[${accs.joinToString(",") { acc -> """{"account_id":"$acc","balances":{${if (current != null) "\"current\":$current," else ""}"iso_currency_code":"USD"},"name":"x","type":"loan"}""" }}],"item":{"available_products":["balance"],"billed_products":["transactions"],"consent_expiration_time":null,"error":null,"institution_id":"ins_56","item_id":"zzz","products":["transactions"],"update_type":"background","webhook":""},"request_id":"r"}"""

    /** [failA]/[failB]: given the call number for that token, the (status, body) to answer with, or null for the real balance. */
    private fun runner(failA: (Int) -> Pair<HttpStatusCode, String>?, failB: (Int) -> Pair<HttpStatusCode, String>? = { null }, lagDir: String = ""): LoanInterestRunner {
        val engine = MockEngine { req ->
            val body = String(req.body.toByteArray())
            val (tok, acc, fail) = if ("tokA" in body) Triple("tokA", "plA", failA) else Triple("tokB", "plB", failB)
            val n = calls.merge(tok, 1, Int::plus)!!
            requestFails[tok]?.let { throw it }
            val f = fail(n)
            if (f != null) respond(f.second, f.first, headersOf(HttpHeaders.ContentType, "application/json"))
            else respond(balance(if (sharedItem && tok == "tokA") listOf("plA", "plB") else listOf(acc)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val wrapper = PlaidApiWrapper("https://plaid.test", 3, "id", "secret", engine, ApiConfiguration().getClientConfig(), 0, 0)
        val cfg = File(dir, "pair.json").also {
            it.writeText("""{"loans":[{"account":2,"apr":7.59,"interestAccount":"Interest A"},{"account":3,"apr":7.59,"interestAccount":"Interest B"}]}""")
        }
        runBlocking {
            whenever(syncHelper.getAllPlaidAccessTokenAccountIdSets()).thenReturn(
                Pair(mapOf("plA" to 2, "plB" to 3), sequenceOf("tokA" to listOf("plA"), "tokB" to listOf("plB"))))
            whenever(accountsApi.getAccount(any(), anyOrNull())).doSuspendableAnswer {
                val id = it.getArgument<String>(0).toInt()
                val date = it.getArgument<LocalDate?>(1)
                val o = if (date == null) owed[id]!! else BigDecimal("23817.37")
                createFireflyResponse(AccountSingle(AccountRead("accounts", "$id", Account("Loan $id", ShortAccountTypeProperty.liabilities, currentBalance = "-$o"), ObjectLink())))
            }
            whenever(accountsApi.listTransactionByAccount(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).doSuspendableAnswer {
                val id = it.getArgument<String>(0)
                fun row(rid: String, amount: String, d: LocalDate, desc: String, interest: Boolean) = TransactionRead("transactions", rid,
                    FireflyFixtures.getTransaction(type = TransactionTypeProperty.withdrawal, sourceId = if (interest) id else "1",
                        destinationId = if (interest) "90" else id, amount = amount, description = desc,
                        date = d.atTime(12, 0).atZone(zone).toOffsetDateTime(), transactionJournalId = "j$rid$id", tags = listOf()), ObjectLink())
                val rows = listOf(row("a", "400.00", LocalDate.of(2026, 8, 17), "payment", false),
                    row("ia", "154.72", LocalDate.of(2026, 8, 17), "Interest on Loan $id payment of \$400.00", true),
                    row("b", "400.00", LocalDate.of(2026, 9, 1), "payment", false))
                createFireflyResponse(TransactionArray(rows, Meta(), PageLink()))
            }
            whenever(txApi.storeTransaction(any())).doSuspendableAnswer {
                val t = it.getArgument<TransactionStore>(0)
                val s = t.transactions.single()
                stores.add(t)
                owed.merge(s.sourceId!!.toInt(), BigDecimal(s.amount), BigDecimal::add)
                createFireflyResponse(TransactionSingle(TransactionRead("transactions", "n", FireflyFixtures.getTransaction(), ObjectLink())))
            }
        }
        return LoanInterestRunner(syncHelper, wrapper, accountsApi, txApi, "America/Chicago", cfg.path, 0.05, true, true, lagDir, 3)
    }

    private fun serverError(status: Int) = HttpStatusCode.fromValue(status) to "{}"

    /** Runs [block] and returns what it printed to stdout. */
    private fun printed(block: () -> Unit): String {
        val old = System.out
        val buf = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(buf, true))
        try { block() } finally { System.setOut(old) }
        return buf.toString()
    }

    private val instDown = HttpStatusCode.BadRequest to errorBody("INSTITUTION_ERROR", "INSTITUTION_NOT_RESPONDING")

    @Test
    fun aTransientInstitutionErrorIsRetriedAndInterestIsAppliedOnce() {
        runner(failA = { if (it <= 2) instDown else null }).run()
        assertThat(calls["tokA"]).isEqualTo(3 + 1)  // 2 failures, a success, and the apply re-read
        assertThat(stores.map { it.transactions.single().sourceId }).containsExactlyInAnyOrder("2", "3")
        assertThat(stores.map { it.transactions.single().amount }).containsOnly("74.29")
    }

    @Test
    fun aLoanWhoseInstitutionStaysDownIsSkippedTheOtherIsAppliedAndTheRunStillFails() {
        val e = assertThrows<IllegalStateException> { runner(failA = { instDown }).run() }
        assertThat(e.message).contains("SKIPPED").contains("2 (INSTITUTION_NOT_RESPONDING)").doesNotContain("private message")
        assertThat(calls["tokA"]).isEqualTo(3)  // the wrapper's retry policy: maxRetries attempts
        assertThat(stores.map { it.transactions.single().sourceId }).containsExactly("3")
        assertThat(owed[2]).isEqualByComparingTo("23417.37")
    }

    @Test
    fun theNextNightAppliesTheLoanThatWasSkipped() {
        assertThrows<IllegalStateException> { runner(failA = { instDown }).run() }
        stores.clear()
        calls.keys.forEach { calls[it] = 0 }
        runner(failA = { null }).run()
        assertThat(stores.map { it.transactions.single().sourceId }).containsExactly("2")  // loan 3 is covered now, never charged twice
    }

    @Test
    fun aNonTransientPlaidErrorStillAbortsAndNothingIsWritten() {
        val bad = HttpStatusCode.BadRequest to errorBody("INVALID_INPUT", "INVALID_ACCESS_TOKEN")
        assertThrows<ClientRequestException> { runner(failA = { bad }).run() }
        assertThat(calls["tokA"]).isEqualTo(1)
        assertThat(stores).isEmpty()
        calls.keys.forEach { calls[it] = 0 }
        assertThrows<ClientRequestException> { runner(failA = { HttpStatusCode.Unauthorized to "{}" }).run() }
        assertThat(calls["tokA"]).isEqualTo(1)
    }

    @Test
    fun aPermanentInstitutionErrorIsSkippedNotRetriedAndAloneDoesNotFailTheRun() {
        val gone = HttpStatusCode.BadRequest to errorBody("INSTITUTION_ERROR", "INSTITUTION_NOT_SUPPORTED")
        val out = printed { runner(failA = { gone }).run() }
        assertThat(out).contains("!! loan-interest: ").contains("SKIPPED (permanent)").contains("2 (INSTITUTION_NOT_SUPPORTED)")
        assertThat(calls["tokA"]).isEqualTo(1)
        assertThat(stores.map { it.transactions.single().sourceId }).containsExactly("3")
    }

    @Test
    fun aPermanentSkipPlusAnotherFailureStillFailsAndNamesOnlyTheOther() {
        val gone = HttpStatusCode.BadRequest to errorBody("INSTITUTION_ERROR", "INSTITUTION_NO_LONGER_SUPPORTED")
        var thrown: IllegalStateException? = null
        val out = printed { thrown = assertThrows<IllegalStateException> { runner(failA = { gone }, failB = { instDown }).run() } }
        assertThat(thrown!!.message).contains("3 (INSTITUTION_NOT_RESPONDING)").doesNotContain("2 (")
        assertThat(out).contains("SKIPPED (permanent)").contains("2 (INSTITUTION_NO_LONGER_SUPPORTED)")
    }

    @Test
    fun aRateLimitErrorIsSkippedAfterItsRetries() {
        val rl = HttpStatusCode.BadRequest to errorBody("RATE_LIMIT", "ACCOUNTS_LIMIT")
        val e = assertThrows<IllegalStateException> { runner(failA = { rl }).run() }
        assertThat(e.message).contains("2 (ACCOUNTS_LIMIT)")
        assertThat(calls["tokA"]).isEqualTo(3)
        assertThat(stores.map { it.transactions.single().sourceId }).containsExactly("3")
    }

    @Test
    fun aPersistent429IsSkippedNotLoopedForever() {
        val e = assertThrows<IllegalStateException> { runner(failA = { HttpStatusCode.TooManyRequests to errorBody("RATE_LIMIT", "RATE_LIMIT_EXCEEDED") }).run() }
        assertThat(e.message).contains("2 (RATE_LIMIT_EXCEEDED)")
        assertThat(calls["tokA"]).isEqualTo(3)
        assertThat(stores.map { it.transactions.single().sourceId }).containsExactly("3")
    }

    @Test
    fun aSharedItemFailingOnTheReReadMarksBothLoansUnchecked() {
        sharedItem = true
        val login = HttpStatusCode.BadRequest to errorBody("ITEM_ERROR", "ITEM_LOGIN_REQUIRED")
        val r = runner(failA = { if (it >= 3) login else null })
        runBlocking {
            whenever(syncHelper.getAllPlaidAccessTokenAccountIdSets()).thenReturn(
                Pair(mapOf("plA" to 2, "plB" to 3), sequenceOf("tokA" to listOf("plA", "plB"))))
        }
        val e = assertThrows<IllegalStateException> { r.run() }
        assertThat(stores).hasSize(2)
        assertThat(e.message).contains("WRITTEN but not re-checked").contains("2 (ITEM_LOGIN_REQUIRED)").contains("3 (ITEM_LOGIN_REQUIRED)")
    }

    @Test
    fun aReReadWithoutACurrentBalanceMarksThatLoanOnlyAndTracksLag() {
        val lag = File(dir, "lag").path
        val noCurrent = HttpStatusCode.OK to balance(listOf("plA"), null)
        var thrown: IllegalStateException? = null
        val out = printed { thrown = assertThrows<IllegalStateException> { runner(failA = { if (it >= 2) noCurrent else null }, lagDir = lag).run() } }
        assertThat(thrown!!.message).contains("WRITTEN but not re-checked").contains("2 (ERROR_IllegalStateException)").doesNotContain("3 (")
        assertThat(out).contains("loan 3: gap now")
        assertThat(File(lag, "loan-lag.json")).exists()
        assertThat(stores).hasSize(2)
    }

    @Test
    fun aGapLeftAfterWritingMarksThatLoanAndTheOtherIsStillChecked() {
        val lag = File(dir, "lag").path
        val farOff = HttpStatusCode.OK to balance(listOf("plA"), "99999.00")
        var thrown: IllegalStateException? = null
        val out = printed { thrown = assertThrows<IllegalStateException> { runner(failA = { if (it >= 2) farOff else null }, lagDir = lag).run() } }
        assertThat(thrown!!.message).contains("WRITTEN but not re-checked").contains("2 (GAP)").doesNotContain("3 (")
        assertThat(out).contains("loan 3: gap now")
        assertThat(File(lag, "loan-lag.json")).exists()
    }

    @Test
    fun aSkippedLoanDoesNotRaiseThePaymentProbablyMissingAlert() {
        val lag = File(dir, "lag")
        lag.mkdirs()
        File(lag, "loan-lag.json").writeText("""{"2":"2026-09-25"}""")
        val r = runner(failA = { instDown }, lagDir = lag.path).also { it.today = { LocalDate.of(2026, 10, 1) } }
        val e = assertThrows<IllegalStateException> { r.run() }
        assertThat(e.message).contains("SKIPPED").doesNotContain("Firefly lags")
        assertThat(File(lag, "loan-lag.json").readText()).isEqualTo("""{"2":"2026-09-25"}""")
    }

    @Test
    fun aBareIoExceptionFromTheBodyReadIsANetworkSkipButAJsonErrorAborts() {
        // ktor reads the body inside the call, so the mock cannot raise this after the wrapper returns; test the classifier itself
        val r = runner(failA = { null })
        runBlocking {
            assertThat(r.unavailableCode(java.io.IOException("cut"))).isEqualTo("NETWORK_IOException")
            assertThat(r.unavailableCode(com.fasterxml.jackson.core.JsonParseException(null, "bad"))).isNull()
        }
    }

    @Test
    fun aLoginRequiredItemIsSkippedAndTheOtherLoanIsApplied() {
        val login = HttpStatusCode.BadRequest to errorBody("ITEM_ERROR", "ITEM_LOGIN_REQUIRED")
        val e = assertThrows<IllegalStateException> { runner(failA = { login }).run() }
        assertThat(e.message).contains("2 (ITEM_LOGIN_REQUIRED)").doesNotContain("private message")
        assertThat(calls["tokA"]).isEqualTo(1)
        assertThat(stores.map { it.transactions.single().sourceId }).containsExactly("3")
    }

    @Test
    fun aServerErrorOnOneItemIsSkippedAndTheOtherIsApplied() {
        var thrown: IllegalStateException? = null
        val out = printed { thrown = assertThrows<IllegalStateException> { runner(failA = { serverError(502) }).run() } }
        assertThat(thrown!!.message).contains("2 (HTTP_502)")
        assertThat(out).contains("!! loan-interest: ").contains("2 (HTTP_502)")
        assertThat(calls["tokA"]).isEqualTo(3)
        assertThat(stores.map { it.transactions.single().sourceId }).containsExactly("3")
    }

    @Test
    fun aNetworkFailureOnOneItemIsSkippedAndTheOtherIsApplied() {
        requestFails = mapOf("tokA" to java.io.IOException("x"))
        val e = assertThrows<IllegalStateException> { runner(failA = { null }).run() }
        assertThat(e.message).contains("2 (NETWORK_IOException)")
        assertThat(calls["tokA"]).isEqualTo(3)
        assertThat(stores.map { it.transactions.single().sourceId }).containsExactly("3")
    }

    @Test
    fun theFirstLoanFailingDoesNotStopTheLastAndViceVersa() {
        val e1 = assertThrows<IllegalStateException> { runner(failA = { instDown }).run() }
        assertThat(e1.message).contains("2 (").doesNotContain("3 (")
        assertThat(stores.map { it.transactions.single().sourceId }).containsExactly("3")
        stores.clear(); calls.keys.forEach { calls[it] = 0 }; owed[2] = BigDecimal("23417.37"); owed[3] = BigDecimal("23417.37")
        val e2 = assertThrows<IllegalStateException> { runner(failA = { null }, failB = { instDown }).run() }
        assertThat(e2.message).contains("3 (").doesNotContain("2 (")
        assertThat(stores.map { it.transactions.single().sourceId }).containsExactly("2")
    }

    @Test
    fun whenEveryLoanFailsNothingIsWrittenAndTheRunFailsNamingAll() {
        val lag = File(dir, "lag").path
        val d1 = LocalDate.of(2026, 10, 1)
        owed[2] = BigDecimal("23600.00")
        runner(failA = { null }, lagDir = lag).also { it.today = { d1 } }.run()
        stores.clear()
        val r = runner(failA = { instDown }, failB = { instDown }, lagDir = lag).also { it.today = { d1.plusDays(1) } }
        val e = assertThrows<IllegalStateException> { r.run() }
        assertThat(e.message).contains("2 (INSTITUTION_NOT_RESPONDING)").contains("3 (INSTITUTION_NOT_RESPONDING)")
        assertThat(stores).isEmpty()
        assertThat(File(lag, "loan-lag.json").readText()).isEqualTo("""{"2":"2026-10-01"}""")
    }

    @Test
    fun twoLoansOnOneDownItemAreBothSkipped() {
        val r = runner(failA = { instDown })
        runBlocking {
            whenever(syncHelper.getAllPlaidAccessTokenAccountIdSets()).thenReturn(
                Pair(mapOf("plA" to 2, "plB" to 3), sequenceOf("tokA" to listOf("plA", "plB"))))
        }
        val e = assertThrows<IllegalStateException> { r.run() }
        assertThat(e.message).contains("2 (").contains("3 (")
        assertThat(stores).isEmpty()
    }

    @Test
    fun aRequestBuildingBugStillAborts() {
        requestFails = mapOf("tokA" to IllegalStateException("bug"))
        val e = assertThrows<RuntimeException> { runner(failA = { null }).run() }
        assertThat(e.message).contains("failed after 3 attempts")
        assertThat(stores).isEmpty()
    }

    @Test
    fun anUnparseableAnswerStillAbortsAndNothingIsWritten() {
        assertThrows<Exception> { runner(failA = { HttpStatusCode.OK to "not json" }).run() }
        assertThat(stores).isEmpty()
    }

    @Test
    fun aReReadLoginRequiredReportsThatLoanOnlyAndTracksLag() {
        val lag = File(dir, "lag").path
        val login = HttpStatusCode.BadRequest to errorBody("ITEM_ERROR", "ITEM_LOGIN_REQUIRED")
        var thrown: IllegalStateException? = null
        val out = printed { thrown = assertThrows<IllegalStateException> { runner(failA = { if (it >= 2) login else null }, lagDir = lag).run() } }
        assertThat(thrown!!.message).contains("WRITTEN but not re-checked").contains("2 (ITEM_LOGIN_REQUIRED)").doesNotContain("3 (")
        assertThat(out).contains("loan 3: gap now")
        assertThat(File(lag, "loan-lag.json")).exists()
        assertThat(stores).hasSize(2)
    }

    @Test
    fun aReReadInvalidAccessTokenStillTracksLagReportsAndFails() {
        val lag = File(dir, "lag").path
        val bad = HttpStatusCode.BadRequest to errorBody("INVALID_INPUT", "INVALID_ACCESS_TOKEN")
        owed[3] = BigDecimal("23600.00")
        val e = assertThrows<IllegalStateException> { runner(failA = { if (it >= 2) bad else null }, lagDir = lag).run() }
        assertThat(e.message).contains("WRITTEN but not re-checked").contains("ERROR_ClientRequestException")
        assertThat(stores.map { it.transactions.single().sourceId }).containsExactly("2")
        assertThat(File(lag, "loan-lag.json").readText()).contains("\"3\"")
    }

    @Test
    fun aReReadServerErrorTracksLagAndReports() {
        val lag = File(dir, "lag").path
        owed[3] = BigDecimal("23600.00")
        val e = assertThrows<IllegalStateException> { runner(failA = { if (it >= 2) serverError(500) else null }, lagDir = lag).run() }
        assertThat(e.message).contains("WRITTEN but not re-checked").contains("2 (HTTP_500)")
        assertThat(stores.map { it.transactions.single().sourceId }).containsExactly("2")
        assertThat(File(lag, "loan-lag.json").readText()).contains("\"3\"")
    }

    @Test
    fun theNightAfterAFailedReReadWritesNothingMore() {
        val login = HttpStatusCode.BadRequest to errorBody("ITEM_ERROR", "ITEM_LOGIN_REQUIRED")
        assertThrows<IllegalStateException> { runner(failA = { if (it >= 2) login else null }).run() }
        assertThat(stores).hasSize(2)
        stores.clear(); calls.keys.forEach { calls[it] = 0 }
        runner(failA = { null }).run()
        assertThat(stores).isEmpty()
    }

    @Test
    fun aRateLimitTypeErrorIsRetried() {
        val rl = HttpStatusCode.BadRequest to errorBody("RATE_LIMIT", "ACCOUNTS_LIMIT")
        runner(failA = { if (it <= 1) rl else null }).run()
        assertThat(calls["tokA"]).isEqualTo(2 + 1)  // failure, success, apply re-read
    }

    @Test
    fun aSkippedLoanKeepsItsLagCounter() {
        val lag = File(dir, "lag").path
        val d1 = LocalDate.of(2026, 10, 1)
        owed[2] = BigDecimal("23600.00")  // Plaid owes less than Firefly: "Firefly lags"
        runner(failA = { null }, lagDir = lag).also { it.today = { d1 } }.run()
        assertThat(File(lag, "loan-lag.json").readText()).isEqualTo("""{"2":"2026-10-01"}""")
        val r = runner(failA = { instDown }, lagDir = lag).also { it.today = { d1.plusDays(1) } }
        assertThrows<IllegalStateException> { r.run() }
        assertThat(File(lag, "loan-lag.json").readText()).isEqualTo("""{"2":"2026-10-01"}""")
    }

    @Test
    fun aFailedReReadStillTracksLagAndReportsTheWrittenLoan() {
        val lag = File(dir, "lag").path
        // night 1 per call: A = 1 balance (plan), 2 = re-read (fails persistently); B is fine
        owed[3] = BigDecimal("23600.00")
        val e = assertThrows<IllegalStateException> { runner(failA = { if (it >= 2) instDown else null }, lagDir = lag).run() }
        assertThat(e.message).contains("WRITTEN but not re-checked").contains("2 (INSTITUTION_NOT_RESPONDING)")
        assertThat(stores.map { it.transactions.single().sourceId }).containsExactly("2")
        assertThat(File(lag, "loan-lag.json").readText()).contains("\"3\"")  // trackLag ran
    }

    @Test
    fun aFireflyErrorStillAborts() {
        val r = runner(failA = { null })
        runBlocking { whenever(accountsApi.getAccount(any(), anyOrNull())).thenThrow(IllegalStateException("firefly down")) }
        assertThrows<IllegalStateException> { r.run() }
        assertThat(stores).isEmpty()
    }
}
