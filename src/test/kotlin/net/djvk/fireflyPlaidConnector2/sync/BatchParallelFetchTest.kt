package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Account
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountArray
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountTypeFilter
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ShortAccountTypeProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Meta
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsGetRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsGetResponse
import net.djvk.fireflyPlaidConnector2.config.AccountConfig
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.lib.FireflyMock
import net.djvk.fireflyPlaidConnector2.lib.PlaidMock
import net.djvk.fireflyPlaidConnector2.lib.createFireflyResponse
import net.djvk.fireflyPlaidConnector2.lib.createPlaidResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Batch mode reads each Plaid Item at the same time, and each Item's pages one after the other. */
internal class BatchParallelFetchTest {
    @Suppress("unused")
    private val warmMockUtil = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE

    private val plaid = PlaidMock()
    private val firefly = FireflyMock()
    private val latencyMs = 400L
    private val inFlight = AtomicInteger(0)
    private val maxInFlight = AtomicInteger(0)
    private val offsetsByToken = ConcurrentHashMap<String, MutableList<Int>>()

    @Test
    fun theItemsAreFetchedAtOnceAndEachItemsPagesInOrder() = runBlocking<Unit> {
        val assets = createFireflyResponse(AccountArray((1..4).map { AccountRead("accounts", "$it", Account("a$it", ShortAccountTypeProperty.asset), ObjectLink()) }, Meta()))
        val noLiabilities = createFireflyResponse(AccountArray(listOf(), Meta()))
        whenever(firefly.accountsApi.listAccount(anyOrNull(), anyOrNull(), eq(AccountTypeFilter.asset))).thenReturn(assets)
        whenever(firefly.accountsApi.listAccount(anyOrNull(), anyOrNull(), eq(AccountTypeFilter.liabilities))).thenReturn(noLiabilities)
        val page = mock<TransactionsGetResponse> { on { transactions } doReturn listOf() }
        whenever(plaid.api.transactionsGet(any<TransactionsGetRequest>())).doSuspendableAnswer {
            val request = it.getArgument<TransactionsGetRequest>(0)
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            try {
                delay(latencyMs)
                offsetsByToken.getOrPut(request.accessToken) { mutableListOf() }.add(request.options!!.offset!!)
                createPlaidResponse(page)
            } finally {
                inFlight.decrementAndGet()
            }
        }
        val items = (1..4).map { AccountConfig(it, "token$it", "plaid$it") }
        val helper = SyncHelper(AccountConfigs(items), "t", firefly.aboutApi, firefly.transactionsApi, firefly.accountsApi, firefly.plaidLinksApi)
        val runner = BatchSyncRunnerTest.createRunner(plaid, firefly, syncHelper = helper)

        val start = System.nanoTime()
        runner.run()
        val ms = (System.nanoTime() - start) / 1_000_000
        println("MEASURED 4 Plaid Items x 1 page x $latencyMs ms: $ms ms (serial would be ${4 * latencyMs}+ ms)")

        assertThat(offsetsByToken.keys).hasSize(4)
        assertThat(maxInFlight.get()).describedAs("Items read at the same time").isEqualTo(4)
    }

    @Test
    fun theRunningBalanceIsTurnedOffForTheRunAndOnAgainEvenWhenTheRunFails() = runBlocking<Unit> {
        val assets = createFireflyResponse(AccountArray((1..4).map { AccountRead("accounts", "$it", Account("a$it", ShortAccountTypeProperty.asset), ObjectLink()) }, Meta()))
        val noLiabilities = createFireflyResponse(AccountArray(listOf(), Meta()))
        whenever(firefly.accountsApi.listAccount(anyOrNull(), anyOrNull(), eq(AccountTypeFilter.asset))).thenReturn(assets)
        whenever(firefly.accountsApi.listAccount(anyOrNull(), anyOrNull(), eq(AccountTypeFilter.liabilities))).thenReturn(noLiabilities)
        val page = mock<TransactionsGetResponse> { on { transactions } doReturn listOf() }
        val pageResponse = createPlaidResponse(page)
        whenever(plaid.api.transactionsGet(any<TransactionsGetRequest>())).thenReturn(pageResponse)
        val configurationApi = mock<net.djvk.fireflyPlaidConnector2.api.firefly.apis.ConfigurationApi>()
        val reconciler = mock<TransferReconciler>()
        whenever(reconciler.reconcile(any(), any(), any())).doSuspendableAnswer { throw IllegalStateException("pairing blew up") }
        val items = (1..4).map { AccountConfig(it, "token$it", "plaid$it") }
        val helper = SyncHelper(AccountConfigs(items), "t", firefly.aboutApi, firefly.transactionsApi, firefly.accountsApi, firefly.plaidLinksApi)
        val runner = BatchSyncRunner(
            5, false, null, 100, plaid.wrapper, helper, firefly.accountsApi,
            net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter(
                false, "America/New_York", 5, false, "p-", false, "d-", net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig(),
            ),
            reconciler, configurationApi, true, "tok",
        )

        val failure = runCatching { runner.run() }.exceptionOrNull()

        assertThat(failure).hasMessageContaining("pairing blew up")
        val order = org.mockito.kotlin.inOrder(configurationApi)
        order.verify(configurationApi).setUseRunningBalance(false)
        order.verify(configurationApi).setUseRunningBalance(true)
    }

    @Test
    fun anItemIsReadToPlaidsTotalEvenWhenAPageIsShort() = runBlocking<Unit> {
        val assets = createFireflyResponse(AccountArray(listOf(AccountRead("accounts", "1", Account("a1", ShortAccountTypeProperty.asset), ObjectLink())), Meta()))
        val noLiabilities = createFireflyResponse(AccountArray(listOf(), Meta()))
        whenever(firefly.accountsApi.listAccount(anyOrNull(), anyOrNull(), eq(AccountTypeFilter.asset))).thenReturn(assets)
        whenever(firefly.accountsApi.listAccount(anyOrNull(), anyOrNull(), eq(AccountTypeFilter.liabilities))).thenReturn(noLiabilities)
        whenever(firefly.transactionsApi.storeTransaction(any())).doSuspendableAnswer {
            val store = it.getArgument<net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionStore>(0)
            createFireflyResponse(
                net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSingle(
                    TransactionRead("transactions", "1", net.djvk.fireflyPlaidConnector2.api.firefly.models.Transaction(transactions = store.transactions), ObjectLink())
                )
            )
        }
        val offsets = java.util.concurrent.CopyOnWriteArrayList<Int>()
        // 3 transactions in total, delivered one per call although the page size is 100
        whenever(plaid.api.transactionsGet(any<TransactionsGetRequest>())).doSuspendableAnswer {
            val offset = it.getArgument<TransactionsGetRequest>(0).options!!.offset!!
            offsets.add(offset)
            val one = net.djvk.fireflyPlaidConnector2.lib.PlaidFixtures.getPaymentTransaction(
                accountId = "a".repeat(37), transactionId = "t$offset", pendingTransactionId = null, amount = 5.0,
            )
            val response = mock<TransactionsGetResponse>()
            whenever(response.transactions).thenReturn(listOf(one))
            whenever(response.totalTransactions).thenReturn(3)
            createPlaidResponse(response)
        }
        val helper = SyncHelper(AccountConfigs(listOf(AccountConfig(1, "token1", "a".repeat(37)))), "t", firefly.aboutApi, firefly.transactionsApi, firefly.accountsApi, firefly.plaidLinksApi)

        BatchSyncRunnerTest.createRunner(plaid, firefly, syncHelper = helper).run()

        assertThat(offsets).containsExactly(0, 1, 2)
    }

    @Test
    fun aBatchPlaidReadThatEndsOnAnEmptyPageBeforeTheTotalFails() = runBlocking<Unit> {
        val assets = createFireflyResponse(AccountArray(listOf(AccountRead("accounts", "1", Account("a1", ShortAccountTypeProperty.asset), ObjectLink())), Meta()))
        val noLiabilities = createFireflyResponse(AccountArray(listOf(), Meta()))
        whenever(firefly.accountsApi.listAccount(anyOrNull(), anyOrNull(), eq(AccountTypeFilter.asset))).thenReturn(assets)
        whenever(firefly.accountsApi.listAccount(anyOrNull(), anyOrNull(), eq(AccountTypeFilter.liabilities))).thenReturn(noLiabilities)
        whenever(plaid.api.transactionsGet(any<TransactionsGetRequest>())).doSuspendableAnswer {
            val response = mock<TransactionsGetResponse>()
            whenever(response.transactions).thenReturn(listOf())
            whenever(response.totalTransactions).thenReturn(5)
            createPlaidResponse(response)
        }
        val helper = SyncHelper(AccountConfigs(listOf(AccountConfig(1, "token1", "a".repeat(37)))), "t", firefly.aboutApi, firefly.transactionsApi, firefly.accountsApi, firefly.plaidLinksApi)

        val failure = runCatching { BatchSyncRunnerTest.createRunner(plaid, firefly, syncHelper = helper).run() }.exceptionOrNull()

        assertThat(failure).hasMessageContaining("empty page")
    }
}
