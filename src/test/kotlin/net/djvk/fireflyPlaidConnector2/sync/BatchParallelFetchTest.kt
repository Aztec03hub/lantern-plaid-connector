package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Account
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountArray
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountTypeFilter
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
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
}
