package net.djvk.fireflyPlaidConnector2.sync

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.ApiConfiguration
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapper
import net.djvk.fireflyPlaidConnector2.config.AccountConfig
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * U50a: the liabilities read against a real PlaidApiWrapper on a MockEngine (no network). Items: tokA (card plC + depository plA, billed
 * liabilities), tokB (card plD, billed transactions only), tokE (card plE, liabilities in `products` but NOT billed), tokC (fails).
 */
internal class LiabilitiesRunnerTest {
    @Suppress("unused")
    private val warm = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE

    @TempDir lateinit var dir: File

    private val syncHelper = mock<SyncHelper>()
    private val calls = mutableListOf<Triple<HttpMethod, String, String>>()
    private val json = HttpHeaders.ContentType to "application/json"
    private val secretMessage = "SECRET-BANK-MESSAGE"

    private fun cfg(fid: Int, tok: String, pid: String, name: String) = AccountConfig(fid, tok, pid, institutionName = "Bank $name", mask = "000$fid", displayName = "Card $name")
    private val configs = AccountConfigs(listOf(cfg(1, "tokA", "plA", "A"), cfg(2, "tokA", "plC", "C"), cfg(3, "tokB", "plD", "D"), cfg(4, "tokE", "plE", "E"), cfg(5, "tokC", "plF", "F")))

    private fun item(billed: String, products: String = billed) =
        """{"available_products":[],"billed_products":[$billed],"consent_expiration_time":null,"error":null,"institution_id":"ins_1","item_id":"zzz","products":[$products],"update_type":"background","webhook":""}"""
    private fun acct(id: String, type: String) = """{"account_id":"$id","balances":{"current":1.0,"iso_currency_code":"USD"},"name":"x","type":"$type"}"""
    private val nulls = """"is_overdue":null,"last_payment_amount":null,"last_payment_date":null,"last_statement_issue_date":null,"last_statement_balance":null,"minimum_payment_amount":null,"next_payment_due_date":null"""
    private fun liab(credit: String) = """{"accounts":[${acct("plA", "depository")},${acct("plC", "credit")}],"item":${item("\"liabilities\"")},"liabilities":{"credit":[$credit],"mortgage":null,"student":null},"request_id":"r"}"""
    private val fullCredit = """{"account_id":"plC","aprs":[{"apr_percentage":29.9,"apr_type":"cash_apr","balance_subject_to_apr":null,"interest_charge_amount":null},{"apr_percentage":24.99,"apr_type":"purchase_apr","balance_subject_to_apr":100.0,"interest_charge_amount":1.5}],"is_overdue":false,"last_payment_amount":10.0,"last_payment_date":"2026-09-20","last_statement_issue_date":"2026-10-01","last_statement_balance":123.456,"minimum_payment_amount":35.0,"next_payment_due_date":"2026-10-25"}"""
    private val nullCredit = """{"account_id":"plC","aprs":[],$nulls}"""
    private fun err(type: String, code: String) = """{"error_type":"$type","error_code":"$code","error_message":"$secretMessage","display_message":null,"request_id":"r"}"""

    private fun runner(credit: String = fullCredit, tokC: (String) -> Pair<HttpStatusCode, String> = { HttpStatusCode.BadRequest to err("INSTITUTION_ERROR", "INSTITUTION_NOT_RESPONDING") }, stateDir: String = dir.path): LiabilitiesRunner {
        val engine = MockEngine { req ->
            val b = String(req.body.toByteArray())
            calls.add(Triple(req.method, req.url.encodedPath, b))
            val (status, text) = when {
                "tokC" in b -> tokC(req.url.encodedPath)
                req.url.encodedPath == "/item/get" -> HttpStatusCode.OK to """{"item":${when {
                    "tokA" in b -> item("\"transactions\",\"liabilities\"")
                    "tokE" in b -> item("\"transactions\"", "\"transactions\",\"liabilities\"")
                    else -> item("\"transactions\"")
                }},"request_id":"r"}"""
                else -> HttpStatusCode.OK to liab(credit)
            }
            respond(text, status, headersOf(json.first, json.second))
        }
        val wrapper = PlaidApiWrapper("https://plaid.test", 3, "id", "secret", engine, ApiConfiguration().getClientConfig(), 0)
        whenever(syncHelper.getAllPlaidAccessTokenAccountIdSets()).thenReturn(
            Pair(configs.accounts.associate { it.plaidAccountId to it.fireflyAccountId },
                sequenceOf("tokA" to listOf("plA", "plX", "plC"), "tokB" to listOf("plD"), "tokE" to listOf("plE"), "tokC" to listOf("plF"))))
        return LiabilitiesRunner(syncHelper, wrapper, configs, stateDir)
    }

    private fun file(): JsonNode = ObjectMapper().readTree(File(dir, "liabilities.json"))
    private fun row(n: Int) = file()["accounts"].first { it["firefly_account"].asInt() == n }
    private fun runQuiet(r: LiabilitiesRunner): String {
        val out = ByteArrayOutputStream(); val old = System.out
        System.setOut(PrintStream(out))
        try { r.run() } finally { System.setOut(old) }
        return out.toString()
    }

    @Test
    fun itemWithLiabilitiesGivesTheCreditRowAndNonCreditIsNoCreditLiability() {
        val out = runQuiet(runner())
        val c = row(2)
        assertThat(c["status"].asText()).isEqualTo("ok")
        assertThat(c["aprs"].map { it["type"].asText() to it["percentage"].asText() }).containsExactly("cash_apr" to "29.9", "purchase_apr" to "24.99")
        assertThat(c["minimum_payment"].asText()).isEqualTo("35.00")
        assertThat(c["last_statement_balance"].asText()).isEqualTo("123.46")
        assertThat(c["next_payment_due_date"].asText()).isEqualTo("2026-10-25")
        assertThat(row(1)["status"].asText()).isEqualTo("no_credit_liability")
        assertThat(out).contains("account 2 Card C mask 0002: ok purchase_apr 24.99% min 35.00 due 2026-10-25")
    }

    @Test
    fun anItemWithoutBilledLiabilitiesIsNotLinkedAndLiabilitiesIsNeverCalled() {
        runner().run()
        for (n in listOf(3, 4)) assertThat(row(n)["status"].asText()).isEqualTo("not_linked")
        assertThat(calls.filter { it.second == "/item/get" && ("tokB" in it.third || "tokE" in it.third) }).hasSize(2)
        assertThat(calls.filter { it.second == "/liabilities/get" && ("tokB" in it.third || "tokE" in it.third) }).isEmpty()
    }

    @Test
    fun optionsAccountIdsHoldsOnlyTheConfiguredIds() { // plX is on the Item but not configured
        runner().run()
        val body = ObjectMapper().readTree(calls.first { it.second == "/liabilities/get" && "tokA" in it.third }.third)
        assertThat(body["options"]["account_ids"].map { it.asText() }).containsExactly("plA", "plC")
    }

    @Test
    fun aTransientErrorMakesOnlyThatItemUnavailableAndTheOthersStillWrite() {
        runner().run()
        assertThat(row(5)["status"].asText()).isEqualTo("unavailable")
        assertThat(row(5)["error_code"].asText()).isEqualTo("INSTITUTION_NOT_RESPONDING")
        assertThat(row(2)["status"].asText()).isEqualTo("ok")
    }

    @Test
    fun aPermanentPlaidErrorIsAnErrorRowWithTheCodeAndNoMessage() {
        runner(tokC = { HttpStatusCode.BadRequest to err("ITEM_ERROR", "PRODUCTS_NOT_SUPPORTED") }).run()
        assertThat(row(5)["status"].asText()).isEqualTo("error")
        assertThat(row(5)["error_code"].asText()).isEqualTo("PRODUCTS_NOT_SUPPORTED")
        assertThat(File(dir, "liabilities.json").readText()).doesNotContain(secretMessage)
    }

    @Test
    fun nullHeavyLiabilityIsARowWithNullsNotACrash() {
        runner(credit = nullCredit).run()
        val c = row(2)
        assertThat(c["status"].asText()).isEqualTo("ok")
        assertThat(c["aprs"]).isEmpty()
        for (k in listOf("minimum_payment", "next_payment_due_date", "last_statement_balance", "last_statement_issue_date", "is_overdue")) assertThat(c[k].isNull).describedAs(k).isTrue()
    }

    @Test
    fun aBodyTheModelCannotDecodeIsADecodeErrorRowAndTheRestContinue() {
        runner(tokC = { HttpStatusCode.OK to """{"item":"garbage"}""" }).run()
        assertThat(row(5)["status"].asText()).isEqualTo("error")
        assertThat(row(5)["error_code"].asText()).isEqualTo("DECODE")
        assertThat(row(2)["status"].asText()).isEqualTo("ok")
    }

    @Test
    fun theRunnerIsReadOnly() {
        val out = runQuiet(runner())
        assertThat(calls.map { it.first to it.second }.toSet()).isSubsetOf(setOf(HttpMethod.Post to "/item/get", HttpMethod.Post to "/liabilities/get"))
        assertThat(dir.list()!!.toList()).containsExactly("liabilities.json")
        val f = File(dir, "liabilities.json")
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath()))).isEqualTo("rw-------")
        for (secret in listOf("tokA", "tokB", "tokC", "tokE", "plC", "plA", "plD", "plE", "plF")) {
            assertThat(f.readText()).describedAs("file has $secret").doesNotContain(secret)
            assertThat(out).describedAs("stdout has $secret").doesNotContain(secret)
        }
    }

    @Test
    fun aFailedWriteFailsTheStepAndKeepsTheOldFile() {
        // the target is a non-empty directory, so the atomic move fails; and a state dir under a regular file cannot be created
        val target = File(dir, "liabilities.json").also { it.mkdir() }
        File(target, "keep").writeText("old")
        assertThrows<Exception> { runner().run() }
        assertThat(File(target, "keep").readText()).isEqualTo("old")
        val plain = File(dir, "plain").also { it.writeText("x") }
        assertThrows<Exception> { runner(stateDir = File(plain, "sub").path).run() }
    }
}
