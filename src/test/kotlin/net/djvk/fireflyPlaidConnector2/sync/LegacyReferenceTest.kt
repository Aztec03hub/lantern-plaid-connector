package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.SearchApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionArray
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.lib.FireflyMock
import net.djvk.fireflyPlaidConnector2.lib.createFireflyResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicReference

/** [SyncHelper.heldByLegacyReference]: finds what an import from before the link table made for a Plaid id. */
internal class LegacyReferenceTest {
    /** Touch MockUtil first: its file-level mocks must not be created in the middle of a whenever().thenReturn(). */
    @Suppress("unused")
    private val warmMockUtil = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE

    private val firefly = FireflyMock()
    private val search: SearchApi = mock()

    private fun helper(api: SearchApi?) = SyncHelper(
        AccountConfigs(emptyList()), "token", firefly.aboutApi, firefly.transactionsApi, firefly.accountsApi, firefly.plaidLinksApi,
        8, api,
    )

    private fun answer(vararg found: TransactionRead) = createFireflyResponse(
        mock<TransactionArray> { on { data } doReturn found.toList() }
    )

    @Test
    fun aHitOnTheExternalIdOrTheInternalReferenceMeansTheOlderImportHoldsIt() = runBlocking<Unit> {
        val hit = mock<TransactionRead>()
        for (operator in listOf("external_id_is", "internal_reference_is")) {
            val api: SearchApi = mock()
            val none = answer()
            val found = answer(hit)
            whenever(api.searchTransactions(any(), org.mockito.kotlin.anyOrNull())).thenReturn(none)
            whenever(api.searchTransactions(org.mockito.kotlin.eq("$operator:\"plaid1\""), org.mockito.kotlin.anyOrNull())).thenReturn(found)

            assertThat(helper(api).heldByLegacyReference("plaid1")).describedAs(operator).isTrue()
        }
    }

    @Test
    fun noHitMeansNothingOlderHoldsIt() = runBlocking<Unit> {
        val none = answer()
        whenever(search.searchTransactions(any(), org.mockito.kotlin.anyOrNull())).thenReturn(none)

        assertThat(helper(search).heldByLegacyReference("plaid2")).isFalse()

        val queries = argumentCaptor<String>()
        verify(search, org.mockito.kotlin.times(2)).searchTransactions(queries.capture(), org.mockito.kotlin.anyOrNull())
        assertThat(queries.allValues).containsExactly("external_id_is:\"plaid2\"", "internal_reference_is:\"plaid2\"")
    }

    @Test
    fun anIdWithAQuoteOrBackslashIsNeverSearchedNorStrippedIntoAnotherId() = runBlocking<Unit> {
        assertThat(helper(search).heldByLegacyReference("ab\"c")).isFalse()
        assertThat(helper(search).heldByLegacyReference("ab\\c")).isFalse()

        org.mockito.kotlin.verifyNoInteractions(search)
    }

    @Test
    fun withoutASearchApiTheAnswerIsAnErrorNotAGuess() {
        val e = AtomicReference<Throwable?>()
        runBlocking { runCatching { helper(null).heldByLegacyReference("plaid3") }.onFailure { e.set(it) } }

        assertThat(e.get()).isInstanceOf(IllegalStateException::class.java)
    }
}
