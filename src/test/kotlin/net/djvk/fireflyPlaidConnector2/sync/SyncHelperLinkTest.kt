package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.jackson.jackson
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Transaction
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSingle
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionStore
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLookupResponse
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.lib.FireflyMock
import net.djvk.fireflyPlaidConnector2.lib.createFireflyResponse
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** The link-table behaviour of [SyncHelper]: the startup probe and how creates treat 409 and 422. */
internal class SyncHelperLinkTest {
    /** Touch MockUtil first: its file-level mocks must not be created in the middle of a whenever().thenReturn(). */
    @Suppress("unused")
    private val warmMockUtil = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE

    private val firefly = FireflyMock()

    /** What a Lantern Firefly answers to a create: the transaction as stored, with the links that were sent. */
    private fun stored(store: TransactionStore) = createFireflyResponse(
        TransactionSingle(TransactionRead("transactions", "1", Transaction(transactions = store.transactions), ObjectLink()))
    )

    private fun conflictBody(vararg ids: String) =
        """{"message":"already linked","conflicts":[${ids.joinToString(",") { """{"plaid_transaction_id":"$it"}""" }}]}"""

    init {
        runBlocking {
            whenever(firefly.transactionsApi.storeTransaction(any())).doSuspendableAnswer { stored(it.getArgument(0)) }
        }
    }

    private fun helper() = SyncHelper(
        AccountConfigs(emptyList()), "token", firefly.aboutApi, firefly.transactionsApi, firefly.accountsApi, firefly.plaidLinksApi,
    )

    private fun statusError(status: HttpStatusCode, body: String = "{}"): ClientRequestException = runBlocking {
        // The helper reads the 409 and 422 bodies, so the client needs the same JSON handling as the real one
        val client = HttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }) {
            expectSuccess = true
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                jackson { net.djvk.fireflyPlaidConnector2.api.firefly.infrastructure.ApiClient.JSON_DEFAULT(this) }
            }
        }
        try {
            client.get("https://x.test/y"); throw AssertionError("expected an error")
        } catch (e: ClientRequestException) {
            e
        }
    }

    private fun dto(plaidId: String, amount: String = "10.00") = FireflyTransactionDto(
        null,
        FireflyFixtures.getTransaction(
            amount = amount, plaidLinks = listOf(PlaidLink(plaidId, PlaidLinkLeg.single, "acct")),
        ).transactions.first(),
    )

    // region startup probe

    @Test
    fun setApiCredsPassesWhenThePlaidLinksProbeAnswers200() = runBlocking<Unit> {
        helper().setApiCreds()

        verify(firefly.plaidLinksApi).setAccessToken("token")
        verify(firefly.plaidLinksApi).lookupPlaidLinks(eq(listOf("lantern-startup-probe")))
    }

    @Test
    fun setApiCredsThrowsIllegalStateWhenThePlaidLinksProbeAnswers404() = runBlocking<Unit> {
        whenever(firefly.plaidLinksApi.lookupPlaidLinks(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.NotFound) }

        val e = runCatching { helper().setApiCreds() }.exceptionOrNull()

        assertThat(e).isInstanceOf(IllegalStateException::class.java).hasMessageContaining("/api/v1/plaid-links")
    }

    @Test
    fun setApiCredsThrowsIllegalStateWhenThePlaidLinksProbeAnswers405() = runBlocking<Unit> {
        whenever(firefly.plaidLinksApi.lookupPlaidLinks(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.MethodNotAllowed) }

        val e = runCatching { helper().setApiCreds() }.exceptionOrNull()

        assertThat(e).isInstanceOf(IllegalStateException::class.java)
    }

    /** A real lookup client whose server answers every request with [body] as [contentType] and status 200. */
    private fun probeAnswering(body: String, contentType: String) = SyncHelper(
        AccountConfigs(emptyList()), "token", firefly.aboutApi, firefly.transactionsApi, firefly.accountsApi,
        net.djvk.fireflyPlaidConnector2.api.firefly.apis.PlaidLinksApi(
            "https://firefly.test",
            MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, contentType)) },
        ),
    )

    @Test
    fun setApiCredsThrowsIllegalStateWhenThePlaidLinksProbeAnswersAnHtmlPage() = runBlocking<Unit> {
        // a login page or proxy page after a redirect: Ktor has no JSON converter for text/html
        val e = runCatching { probeAnswering("<html>login</html>", "text/html").setApiCreds() }.exceptionOrNull()

        assertThat(e).isInstanceOf(IllegalStateException::class.java).hasMessageContaining("/api/v1/plaid-links")
    }

    @Test
    fun setApiCredsThrowsIllegalStateWhenThePlaidLinksProbeAnswersJsonWithoutData() = runBlocking<Unit> {
        val e = runCatching { probeAnswering("""{"message":"hello"}""", "application/json").setApiCreds() }.exceptionOrNull()

        assertThat(e).isInstanceOf(IllegalStateException::class.java).hasMessageContaining("/api/v1/plaid-links")
    }

    @Test
    fun theProbePassesOnARealEmptyLookupAnswer() = runBlocking<Unit> {
        probeAnswering("""{"data":[]}""", "application/json").setApiCreds()
    }

    @Test
    fun otherProbeFailuresPropagateAsTheyAre() = runBlocking<Unit> {
        whenever(firefly.plaidLinksApi.lookupPlaidLinks(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.Unauthorized) }

        val e = runCatching { helper().setApiCreds() }.exceptionOrNull()

        assertThat(e).isInstanceOf(ClientRequestException::class.java)
    }

    @Test
    fun theProbeIsNotAnswerableByAStockFireflyThatIgnoresLinksOnWrite() = runBlocking<Unit> {
        // a Firefly that answers 200 but drops plaid_links stores nothing the link table could dedupe
        whenever(firefly.transactionsApi.storeTransaction(any())).doSuspendableAnswer {
            val sent = it.getArgument<TransactionStore>(0)
            stored(sent.copy(transactions = sent.transactions.map { s -> s.copy(plaidLinks = null) }))
        }

        val e = runCatching { helper().optimisticInsertBatchIntoFirefly(listOf(dto("a"))) }.exceptionOrNull()

        assertThat(e).isInstanceOf(IllegalStateException::class.java)
    }

    // endregion

    // region optimistic insert

    @Test
    fun optimisticInsertReturnsHowManyWereActuallyCreated() = runBlocking<Unit> {
        val created = helper().optimisticInsertBatchIntoFirefly(listOf(dto("a"), dto("b"), dto("zero", amount = "0.00")))

        assertThat(created).describedAs("the zero amount is skipped").isEqualTo(2)
        verify(firefly.transactionsApi, times(2)).storeTransaction(any())
    }

    @Test
    fun aConflictIsSkippedAndTheRestOfTheBatchStillGoesThrough() = runBlocking<Unit> {
        var call = 0
        whenever(firefly.transactionsApi.storeTransaction(any())).doSuspendableAnswer {
            if (++call == 2) throw statusError(HttpStatusCode.Conflict, conflictBody("b"))
            stored(it.getArgument(0))
        }

        val created = helper().optimisticInsertBatchIntoFirefly(listOf(dto("a"), dto("b"), dto("c")))

        assertThat(created).isEqualTo(2)
        verify(firefly.transactionsApi, times(3)).storeTransaction(any())
    }

    @Test
    fun a422OnALinkedTransactionIsRethrownNotSkippedAndNothingAfterItIsSent() = runBlocking<Unit> {
        whenever(firefly.transactionsApi.storeTransaction(any())).doSuspendableAnswer {
            throw statusError(HttpStatusCode.UnprocessableEntity, """{"message":"duplicate of transaction #5","exception":"x","errors":{}}""")
        }

        val e = runCatching { helper().optimisticInsertBatchIntoFirefly(listOf(dto("a"), dto("b"))) }.exceptionOrNull()

        assertThat(e).describedAs("a linked transaction is never skipped on a 422").isInstanceOf(ClientRequestException::class.java)
        verify(firefly.transactionsApi, times(1)).storeTransaction(any())
    }

    @Test
    fun aTransferWithOneConflictingLegCreatesTheOtherLegAlone() = runBlocking<Unit> {
        val transfer = FireflyTransactionDto(
            null,
            FireflyFixtures.getTransaction(
                type = net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty.transfer,
                sourceId = "1", destinationId = "2",
                plaidLinks = listOf(PlaidLink("wd", PlaidLinkLeg.source, "a"), PlaidLink("dep", PlaidLinkLeg.destination, "b")),
            ).transactions.first(),
        )
        var call = 0
        whenever(firefly.transactionsApi.storeTransaction(any())).doSuspendableAnswer {
            if (++call == 1) throw statusError(HttpStatusCode.Conflict, conflictBody("dep")) else stored(it.getArgument(0))
        }
        val sent = org.mockito.kotlin.argumentCaptor<TransactionStore>()

        val created = helper().optimisticInsertBatchIntoFirefly(listOf(transfer))

        assertThat(created).isEqualTo(1)
        verify(firefly.transactionsApi, times(2)).storeTransaction(sent.capture())
        val alone = sent.secondValue.transactions.single()
        assertThat(alone.plaidLinks).containsExactly(PlaidLink("wd", PlaidLinkLeg.single, "a"))
        assertThat(alone.type).isEqualTo(net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty.withdrawal)
    }

    @Test
    fun aLinkLessTransactionIsSentWithTheHashCheckAndItsDuplicate422IsSkipped() = runBlocking<Unit> {
        val noLinks = FireflyTransactionDto(null, FireflyFixtures.getTransaction().transactions.first())
        whenever(firefly.transactionsApi.storeTransaction(any())).doSuspendableAnswer {
            throw statusError(HttpStatusCode.UnprocessableEntity, """{"message":"Duplicate of transaction #7.","exception":"x","errors":{}}""")
        }
        val sent = org.mockito.kotlin.argumentCaptor<TransactionStore>()

        val created = helper().optimisticInsertBatchIntoFirefly(listOf(noLinks))

        assertThat(created).isEqualTo(0)
        verify(firefly.transactionsApi).storeTransaction(sent.capture())
        assertThat(sent.firstValue.errorIfDuplicateHash).isTrue()
    }

    @Test
    fun theCreateCarriesItsLinksAndNoExternalId() = runBlocking<Unit> {
        val store = org.mockito.kotlin.argumentCaptor<net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionStore>()

        helper().optimisticInsertBatchIntoFirefly(listOf(dto("raw-id")))

        verify(firefly.transactionsApi).storeTransaction(store.capture())
        val split = store.firstValue.transactions.single()
        assertThat(split.plaidLinks).containsExactly(PlaidLink("raw-id", PlaidLinkLeg.single, "acct"))
        assertThat(split.externalId).isNull()
        assertThat(store.firstValue.errorIfDuplicateHash).describedAs("linked: the link table dedupes").isFalse()
        verify(firefly.plaidLinksApi, never()).lookupPlaidLinks(any())
    }

    // endregion

}
