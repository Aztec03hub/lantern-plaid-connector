package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.content.TextContent
import net.djvk.fireflyPlaidConnector2.api.plaid.models.SandboxPublicTokenCreateRequestOptions
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsSyncRequest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.ApiConfiguration
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapper
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountsGetRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountType
import net.djvk.fireflyPlaidConnector2.api.plaid.models.InvestmentsTransactionsGetRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.InvestmentsTransactionsGetRequestOptions
import net.djvk.fireflyPlaidConnector2.api.plaid.models.ItemPublicTokenExchangeRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.ItemRemoveRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.Products
import net.djvk.fireflyPlaidConnector2.api.plaid.models.SandboxPublicTokenCreateRequest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LAN-102 AC 3 (U58): the connector's real Plaid calls, run once against the Plaid SANDBOX (no production call, no cost).
 * Environment: LANTERN_PLAID_SANDBOX_CLIENT_ID and LANTERN_PLAID_SANDBOX_SECRET. Skipped without both.
 * The wrapper and sync service are the production classes; Items are created here and removed in [removeItems].
 * Test 4 uses the sandbox user user_transactions_dynamic (password pass_good) on ins_109508: a /sandbox/transactions/create
 * between two pages of /transactions/sync makes Plaid answer 400 TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION.
 * Never put the client id, the secret or an access token into a message, a log line or a test name.
 */
@EnabledIfEnvironmentVariable(named = "LANTERN_PLAID_SANDBOX_CLIENT_ID", matches = ".+", disabledReason = "assumption: LANTERN_PLAID_SANDBOX_CLIENT_ID not set (needs the Plaid sandbox keys)")
@EnabledIfEnvironmentVariable(named = "LANTERN_PLAID_SANDBOX_SECRET", matches = ".+", disabledReason = "assumption: LANTERN_PLAID_SANDBOX_SECRET not set (needs the Plaid sandbox keys)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class PlaidSandboxLiveTest {
    private val wrapper = PlaidApiWrapper(
        "https://sandbox.plaid.com", 3,
        System.getenv("LANTERN_PLAID_SANDBOX_CLIENT_ID"), System.getenv("LANTERN_PLAID_SANDBOX_SECRET"),
        null, ApiConfiguration().getClientConfig(), 0, 3_000, // a stray sandbox 429 costs seconds, not the 60 s default
    )
    private val clientId = System.getenv("LANTERN_PLAID_SANDBOX_CLIENT_ID")
    private val secret = System.getenv("LANTERN_PLAID_SANDBOX_SECRET")
    private val service = PlaidSyncService(wrapper, 500, false)
    private val accessTokens = mutableListOf<String>()

    private fun newItem(product: Products, options: SandboxPublicTokenCreateRequestOptions? = null): String = runBlocking {
        val public = wrapper.executeRequest(
            { api -> api.sandboxPublicTokenCreate(SandboxPublicTokenCreateRequest("ins_109508", listOf(product), options = options)) },
            "sandbox public token create"
        ).body().publicToken
        val token = wrapper.executeRequest(
            { api -> api.itemPublicTokenExchange(ItemPublicTokenExchangeRequest(public)) },
            "sandbox public token exchange"
        ).body().accessToken
        accessTokens.add(token)
        token
    }

    private val investmentsToken by lazy { newItem(Products.investments) }
    private val transactionsToken by lazy { newItem(Products.transactions) }

    @AfterAll
    fun removeItems() = runBlocking {
        accessTokens.forEach { t ->
            // one failed remove must not leave the other Items behind
            runCatching { wrapper.executeRequest({ api -> api.itemRemove(ItemRemoveRequest(t)) }, "sandbox item remove") }
        }
    }

    /** Sandbox answers PRODUCT_NOT_READY right after an Item is created; retry that one code for at most 60 s. */
    private suspend fun <T> untilReady(call: suspend () -> T): T {
        val deadline = System.currentTimeMillis() + 60_000
        while (true) {
            try {
                return call()
            } catch (cre: ClientRequestException) {
                val code = wrapper.plaidErrorTypeAndCode(cre).second
                if (code != "PRODUCT_NOT_READY" || System.currentTimeMillis() > deadline) throw cre
                delay(3_000)
            }
        }
    }

    @Test
    fun investmentTransactionsPageThroughRealSandboxData() = runBlocking<Unit> {
        val token = investmentsToken
        val ids = wrapper.executeRequest({ api -> api.accountsGet(AccountsGetRequest(token)) }, "sandbox accounts get")
            .body().accounts.filter { it.type == AccountType.investment }.map { it.accountId }
        assertTrue(ids.isNotEmpty(), "the sandbox Item has no investment accounts")
        val end = LocalDate.now()
        val start = end.minusDays(730)
        val total = untilReady {
            wrapper.executeRequest(
                {
                    it.investmentsTransactionsGet(
                        InvestmentsTransactionsGetRequest(token, start, end, options = InvestmentsTransactionsGetRequestOptions(ids, 500, 0))
                    )
                },
                "sandbox investment transactions total"
            ).body().totalInvestmentTransactions
        }
        val batch = maxOf(1, (total + 2) / 3) // about three pages, few calls: the sandbox rate limits this endpoint
        assertTrue(total > batch, "need more than one page at batch size $batch, the sandbox returned $total")

        val txs = PlaidSyncService(wrapper, batch, false).fetchInvestmentTransactions(token, ids, start, end)

        assertEquals(total, txs.size)
        assertEquals(txs.size, txs.map { it.investmentTransactionId }.toSet().size, "duplicate investment_transaction_id across pages")
    }

    @Test
    fun refreshIsAcceptedForATransactionsItem() = runBlocking<Unit> {
        assertTrue(service.refreshTransactions(transactionsToken))
    }

    @Test
    fun refreshOnAnItemWithoutTransactionsReturnsFalseAndDoesNotThrow() = runBlocking<Unit> {
        assertFalse(service.refreshTransactions(investmentsToken))
    }

    @Test
    fun aRealMutationDuringPaginationRestartsThePagination() = runBlocking<Unit> {
        val token = newItem(
            Products.transactions,
            SandboxPublicTokenCreateRequestOptions(overrideUsername = "user_transactions_dynamic", overridePassword = "pass_good"),
        )
        val ids = wrapper.executeRequest({ api -> api.accountsGet(AccountsGetRequest(token)) }, "sandbox accounts get")
            .body().accounts.map { it.accountId }
        // wait (60 s at most) until the first sync has transactions; this cursor is not given to the connector
        var available = 0
        val deadline = System.currentTimeMillis() + 60_000
        while (available == 0) {
            available = untilReady {
                wrapper.executeRequest(
                    { api -> api.transactionsSync(TransactionsSyncRequest(token, null, null, null, 500)) }, "sandbox first sync"
                ).body().added.size
            }
            if (available == 0) {
                check(System.currentTimeMillis() < deadline) { "the sandbox Item never got transactions" }
                delay(1_000)
            }
        }
        assertTrue(available > 1, "need more than one transaction to page, the sandbox returned $available")
        val batch = maxOf(1, (available + 1) / 2) // two pages before the mutation, not a walk over every transaction

        val description = "U58 mutation ${System.currentTimeMillis()}"
        val raw = HttpClient(CIO)
        val syncs = AtomicInteger()
        val cursorless = AtomicInteger()
        val mutated = AtomicBoolean(false)
        val plugin = createClientPlugin("u58-mutate") {
            onRequest { request, content ->
                if (request.url.buildString().endsWith("/transactions/sync")) {
                    syncs.incrementAndGet()
                    val noCursor = when (content) {
                        is TransactionsSyncRequest -> content.cursor == null
                        is TextContent -> !content.text.contains("\"cursor\"")
                        else -> false
                    }
                    if (noCursor) cursorless.incrementAndGet()
                }
            }
            onResponse { response ->
                if (response.call.request.url.encodedPath.endsWith("/transactions/sync") && mutated.compareAndSet(false, true)) {
                    val today = LocalDate.now()
                    raw.post("https://sandbox.plaid.com/sandbox/transactions/create") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"client_id":"$clientId","secret":"$secret","access_token":"$token","transactions":[{"date_transacted":"$today","date_posted":"$today","amount":1.23,"description":"$description","iso_currency_code":"USD"}]}"""
                        )
                    }
                    delay(2_000) // let the mutation land before page 2
                }
            }
        }
        val connectorWrapper = PlaidApiWrapper(
            "https://sandbox.plaid.com", 3, clientId, secret, null,
            { c -> ApiConfiguration().getClientConfig().invoke(c); c.install(plugin) }, 0, 3_000,
        )

        val result = PlaidSyncService(connectorWrapper, batch, false).processPlaidTransactions(sequenceOf(token to ids), mutableMapOf())
        raw.close()

        assertTrue(mutated.get(), "the mutation was never sent")
        assertTrue(result.failedItems.isEmpty())
        assertTrue(cursorless.get() >= 2, "pagination began ${cursorless.get()} time(s) in ${syncs.get()} sync requests, expected a restart")
        val txIds = result.created.map { it.transactionId }
        assertEquals(txIds.size, txIds.toSet().size, "duplicate transaction ids after the restart")
        assertTrue(result.created.any { it.name == description || it.originalDescription == description }, "the new transaction is missing")
    }
}
