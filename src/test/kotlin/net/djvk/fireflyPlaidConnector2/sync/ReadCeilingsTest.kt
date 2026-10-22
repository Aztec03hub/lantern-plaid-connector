package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PlaidLinksApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Meta
import net.djvk.fireflyPlaidConnector2.api.firefly.models.MetaPagination
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PageLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionArray
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.lib.createFireflyResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** The Firefly read ceilings are properties: the defaults read far more than the old 20 pages, and both can be lowered. */
internal class ReadCeilingsTest {
    @Suppress("unused")
    private val warmMockUtil = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE

    private val txApi = mock<TransactionsApi>()

    private fun service(vararg ceilings: Int): FireflyTransactionService = when (ceilings.size) {
        0 -> FireflyTransactionService(txApi, mock<SyncHelper>(), 30, "UTC", mock<PlaidLinksApi>())
        else -> FireflyTransactionService(
            txApi, mock<SyncHelper>(), 30, "UTC", mock<PlaidLinksApi>(), null, ceilings[0], ceilings[1],
        )
    }

    /** [pages] pages of one group each; the reported total is [total]. */
    private fun pages(pages: Int, total: Int) = runBlocking {
        whenever(txApi.listTransaction(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).doSuspendableAnswer {
            val page = it.getArgument<Int>(0)
            createFireflyResponse(
                TransactionArray(
                    listOf(TransactionRead("transactions", "$page", FireflyFixtures.getTransaction(), ObjectLink())),
                    Meta(MetaPagination(total, 1, 1, page, pages)),
                    PageLink(),
                )
            )
        }
    }

    @Test
    fun aWindowOf25PagesIsReadWithTheDefault() = runBlocking<Unit> {
        pages(25, 25)
        assertThat(service().fetchExistingFireflyTransactions()).hasSize(25)
    }

    @Test
    fun maxReadPagesIsHonoured() = runBlocking<Unit> {
        pages(3, 3)
        val failure = runCatching { service(2, 200_000).fetchExistingFireflyTransactions() }.exceptionOrNull()
        assertThat(failure).hasMessageContaining("Exceeded Firefly failsafe max page count 2")
    }

    @Test
    fun maxRangeTransactionsIsHonoured() = runBlocking<Unit> {
        pages(1, 11)
        val failure = runCatching { service(1000, 10).fetchExistingFireflyTransactions() }.exceptionOrNull()
        assertThat(failure).hasMessageContaining("exceeds the failsafe 10")
    }
}
