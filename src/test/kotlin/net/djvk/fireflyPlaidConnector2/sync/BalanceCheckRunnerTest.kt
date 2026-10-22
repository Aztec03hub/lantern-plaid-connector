package net.djvk.fireflyPlaidConnector2.sync

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.ApiConfiguration
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.time.LocalDate
import java.time.ZoneId

/** U34: the balance check against a real PlaidApiWrapper on a MockEngine. Items: tokA (asset 2, card 3), tokB (loan 4). Account 5 is an investment and in no Item. */
internal class BalanceCheckRunnerTest {
    @Suppress("unused")
    private val warm = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE

    @TempDir lateinit var dir: File

    private val zone = ZoneId.of("America/Chicago")
    private val today = LocalDate.of(2026, 10, 21)
    private val accountsApi = mock<AccountsApi>()
    private val syncHelper = mock<SyncHelper>()

    private fun acct(id: String, type: String, cur: Double) = """{"account_id":"$id","balances":{"current":$cur,"iso_currency_code":"USD"},"name":"x","type":"$type"}"""
    private fun body(vararg a: String) = """{"accounts":[${a.joinToString(",")}],"item":{"available_products":[],"billed_products":["transactions"],"consent_expiration_time":null,"error":null,"institution_id":"ins_1","item_id":"zzz","products":["transactions"],"update_type":"background","webhook":""},"request_id":"r"}"""
    private val down = """{"error_type":"INSTITUTION_ERROR","error_code":"INSTITUTION_NOT_RESPONDING","error_message":"m","display_message":null,"request_id":"r"}"""
    private val fatal = """{"error_type":"INVALID_INPUT","error_code":"INVALID_ACCESS_TOKEN","error_message":"m","display_message":null,"request_id":"r"}"""

    private fun runner(bFails: String? = null, pendingTag: String = "pending", fireflyFails: Boolean = false): BalanceCheckRunner {
        val engine = MockEngine { req ->
            val b = String(req.body.toByteArray())
            val json = HttpHeaders.ContentType to "application/json"
            when {
                "tokA" in b -> respond(body(acct("plA", "depository", 1200.00), acct("plC", "credit", 305.40)), HttpStatusCode.OK, headersOf(json.first, json.second))
                bFails != null -> respond(bFails, HttpStatusCode.BadRequest, headersOf(json.first, json.second))
                else -> respond(body(acct("plL", "loan", 900.00)), HttpStatusCode.OK, headersOf(json.first, json.second))
            }
        }
        val wrapper = PlaidApiWrapper("https://plaid.test", 3, "id", "secret", engine, ApiConfiguration().getClientConfig(), 0)
        runBlocking {
            whenever(syncHelper.getAllPlaidAccessTokenAccountIdSets()).thenReturn(
                Pair(mapOf("plA" to 2, "plC" to 3, "plL" to 4, "plI" to 5), sequenceOf("tokA" to listOf("plA", "plC"), "tokB" to listOf("plL"))))
            whenever(accountsApi.getAccount(any(), anyOrNull())).doSuspendableAnswer {
                if (fireflyFails) throw IllegalStateException("firefly down")
                val id = it.getArgument<String>(0).toInt()
                // card 3 is 5.00 further in debt than Plaid says, except for a 20.00 pending charge that is backed out below
                val bal = mapOf(2 to "1200.00", 3 to "-325.40", 4 to "-900.00")[id]!!
                createFireflyResponse(AccountSingle(AccountRead("accounts", "$id", Account("A $id", ShortAccountTypeProperty.asset, currentBalance = bal), ObjectLink())))
            }
            whenever(accountsApi.listTransactionByAccount(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).doSuspendableAnswer {
                val id = it.getArgument<String>(0)
                val rows = if (id != "3") listOf() else listOf(
                    split("3", "9", "20.00", listOf("pending"), today.minusDays(1)),
                    split("3", "9", "7.00", listOf("pending"), today.minusDays(40)),
                    split("3", "9", "3.00", listOf(), today.minusDays(1)),
                )
                createFireflyResponse(TransactionArray(rows.map { s -> TransactionRead("transactions", "t", FireflyFixtures.getTransaction(type = TransactionTypeProperty.withdrawal, sourceId = s.first, destinationId = s.second, amount = s.third, tags = s.fourth, date = s.fifth.atTime(12, 0).atZone(zone).toOffsetDateTime(), transactionJournalId = "j"), ObjectLink()) }, Meta(), PageLink()))
            }
        }
        return BalanceCheckRunner(syncHelper, wrapper, accountsApi, "America/Chicago", dir.path, 0.05, 21, pendingTag).also { it.today = { today } }
    }

    private data class Row5<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
    private fun split(s: String, d: String, a: String, t: List<String>, day: LocalDate) = Row5(s, d, a, t, day)

    private fun file() = ObjectMapper().readTree(File(dir, "balance_gap.json"))

    @Test
    fun oneLinePerNonInvestmentAccountAndPendingIsBackedOut() {
        val out = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(out))
        try { runner(bFails = null).run() } finally { System.setOut(old) }
        val accounts = file()["accounts"]
        assertThat(accounts.map { it["account"].asInt() }).containsExactly(2, 3, 4)
        assertThat(accounts.map { it["status"].asText() }).containsExactly("ok", "ok", "ok")
        // card 3: Firefly -325.40 includes a pending 20.00 charge, posted -305.40 = Plaid; the 7.00 (too old) and untagged 3.00 are ignored
        assertThat(accounts[1]["firefly"].asText()).isEqualTo("-305.40")
        assertThat(accounts[1]["gap"].asText()).isEqualTo("0.00")
        assertThat(out.toString()).doesNotContain("!! balance gap")
    }

    @Test
    fun aDifferentPendingTagBacksNothingOutAndShowsTheGap() {
        val out = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(out))
        try { runner(pendingTag = "other").run() } finally { System.setOut(old) }
        assertThat(file()["accounts"][1]["gap"].asText()).isEqualTo("-20.00")
        assertThat(out.toString()).contains("!! balance gap: account 3 credit gap -20.00 since 2026-10-21")
    }

    @Test
    fun aTransientErrorMakesOnlyThatItemsAccountsUnavailable() {
        runner(bFails = down).run()
        val accounts = file()["accounts"]
        assertThat(accounts.map { it["status"].asText() }).containsExactly("ok", "ok", "unavailable")
        assertThat(accounts[2]["error_code"].asText()).isEqualTo("INSTITUTION_NOT_RESPONDING")
    }

    @Test
    fun aNonTransientPlaidErrorOrAFireflyFailureFailsTheStepAndKeepsTheOldFile() {
        File(dir, "balance_gap.json").writeText("{\"keep\":1}")
        assertThrows<Exception> { runner(bFails = fatal).run() }
        assertThrows<Exception> { runner(fireflyFails = true).run() }
        assertThat(File(dir, "balance_gap.json").readText()).isEqualTo("{\"keep\":1}")
    }
}
