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
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate

/** A read that sums journals must fail, not return a list with one missed or doubled, when Firefly changes under it. */
internal class StrictFireflyReadTest {
    @Suppress("unused")
    private val warmMockUtil = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE

    private val txApi = mock<TransactionsApi>()
    private val service = FireflyTransactionService(txApi, mock<SyncHelper>(), 30, "UTC", mock<PlaidLinksApi>())
    private val from = LocalDate.of(2000, 1, 1)
    private val to = LocalDate.of(2026, 1, 1)

    private fun group(id: String) = TransactionRead("transactions", id, FireflyFixtures.getTransaction(), ObjectLink())

    /** Two pages: [first] groups with total [total1], then [second] groups with total [total2]. */
    private fun twoPages(first: List<String>, total1: Int, second: List<String>, total2: Int) = runBlocking {
        whenever(txApi.listTransaction(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).doSuspendableAnswer {
            val page = it.getArgument<Int>(0)
            val ids = if (page == 1) first else second
            createFireflyResponse(
                TransactionArray(
                    ids.map(::group),
                    Meta(MetaPagination(if (page == 1) total1 else total2, ids.size, 2, page, 2)),
                    PageLink(),
                )
            )
        }
    }

    @Test
    fun aStableReadPasses() = runBlocking<Unit> {
        twoPages(listOf("1", "2"), 3, listOf("3"), 3)
        assertThat(service.fetchFireflyTransactionsStrictly(from, to, 10).map { it.id }).containsExactly("1", "2", "3")
    }

    @Test
    fun aTotalThatChangesBetweenPagesFails() = runBlocking<Unit> {
        twoPages(listOf("1", "2"), 3, listOf("3", "4"), 4)
        val failure = runCatching { service.fetchFireflyTransactionsStrictly(from, to, 10) }.exceptionOrNull()
        assertThat(failure).hasMessageContaining("changed while")
    }

    @Test
    fun aGroupReadTwiceBecauseThePagesShiftedFails() = runBlocking<Unit> {
        twoPages(listOf("1", "2"), 3, listOf("2"), 3) // "3" was missed and "2" doubled, the total never moved
        val failure = runCatching { service.fetchFireflyTransactionsStrictly(from, to, 10) }.exceptionOrNull()
        assertThat(failure).hasMessageContaining("changed while")
    }

    @Test
    fun theTolerantReadStillAcceptsAShiftedList() = runBlocking<Unit> {
        twoPages(listOf("1", "2"), 3, listOf("2"), 3)
        assertThat(service.fetchFireflyTransactionsBetween(from, to, 10)).hasSize(3)
    }
}
