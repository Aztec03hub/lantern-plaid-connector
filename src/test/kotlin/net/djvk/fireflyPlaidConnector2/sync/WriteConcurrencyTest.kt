package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Transaction
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSingle
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionStore
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.lib.FireflyMock
import net.djvk.fireflyPlaidConnector2.lib.createFireflyResponse
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.whenever
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** Bounded concurrency of creates (fireflyPlaidConnector2.firefly.writeConcurrency). */
internal class WriteConcurrencyTest {
    @Suppress("unused")
    private val warmMockUtil = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE

    @TempDir
    lateinit var dir: Path

    private val firefly = FireflyMock()
    private val inFlight = AtomicInteger(0)
    private val maxInFlight = AtomicInteger(0)
    private val createLatencyMs = 50L

    private fun stored(store: TransactionStore) = createFireflyResponse(
        TransactionSingle(TransactionRead("transactions", "1", Transaction(transactions = store.transactions), ObjectLink()))
    )

    private fun helper(concurrency: Int) = SyncHelper(
        AccountConfigs(emptyList()), "token", firefly.aboutApi, firefly.transactionsApi, firefly.accountsApi, firefly.plaidLinksApi,
        concurrency,
    )

    /** Firefly answers each create after [createLatencyMs], like a real one under load. */
    private fun slowFirefly(failFor: (String) -> HttpStatusCode? = { null }) = runBlocking {
        whenever(firefly.transactionsApi.storeTransaction(any())).doSuspendableAnswer {
            val store = it.getArgument<TransactionStore>(0)
            val now = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(now, ::maxOf)
            try {
                delay(createLatencyMs)
                val status = failFor(store.transactions.first().plaidLinks!!.first().plaidTransactionId)
                if (status != null) throw statusError(status)
                stored(store)
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private fun statusError(status: HttpStatusCode): ClientRequestException = runBlocking {
        val client = HttpClient(MockEngine { respond("{}", status, headersOf(HttpHeaders.ContentType, "application/json")) }) {
            expectSuccess = true
        }
        try {
            client.get("https://x.test/y"); throw AssertionError("expected an error")
        } catch (e: ClientRequestException) {
            e
        }
    }

    private fun dto(plaidId: String) = FireflyTransactionDto(
        null,
        FireflyFixtures.getTransaction(
            amount = "10.00", plaidLinks = listOf(PlaidLink(plaidId, PlaidLinkLeg.single, "acct")),
        ).transactions.first(),
    )

    private fun timed(concurrency: Int, count: Int): Pair<Int, Long> = runBlocking {
        val start = System.nanoTime()
        val created = helper(concurrency).optimisticInsertBatchIntoFirefly((1..count).map { dto("tx$it") })
        created to (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun eightAtATimeIsMuchFasterThanOneAtATimeAndNeverExceedsTheLimit() {
        slowFirefly()
        val (serialCreated, serialMs) = timed(1, 40)
        val serialMax = maxInFlight.get()
        maxInFlight.set(0)
        val (parallelCreated, parallelMs) = timed(8, 40)
        println("MEASURED 40 creates x ${createLatencyMs} ms: concurrency 1 = $serialMs ms, concurrency 8 = $parallelMs ms")

        assertThat(serialCreated).isEqualTo(40)
        assertThat(parallelCreated).isEqualTo(40)
        assertThat(serialMax).isEqualTo(1)
        assertThat(maxInFlight.get()).isGreaterThan(1).isLessThanOrEqualTo(8)
        assertThat(parallelMs).isLessThan(serialMs / 3)
    }

    @Test
    fun aServerErrorFailsTheBatchAndStopsTheRest() = runBlocking<Unit> {
        slowFirefly { if (it == "tx2") HttpStatusCode.InternalServerError else null }
        val failure = runCatching { helper(4).optimisticInsertBatchIntoFirefly((1..40).map { dto("tx$it") }) }.exceptionOrNull()
        assertThat(failure).isNotNull
    }

    @Test
    fun concurrentDeadLettersAreAllKept() = runBlocking<Unit> {
        slowFirefly { HttpStatusCode.BadRequest }
        val store = DeadLetterStore(dir.toString())
        val service = FireflyTransactionService(
            firefly.transactionsApi, helper(8), 5, "UTC", firefly.plaidLinksApi, store,
        )
        // A 400 is a permanent rejection of one write: each create is dead-lettered, all through the one file
        val creates = (1..30).map { dto("tx$it") }
        val notCreated = service.processFireflyTransactionUpdates(creates, listOf(), listOf())

        assertThat(notCreated).isEqualTo(30)
        assertThat(store.read().map { it.key }).containsExactlyInAnyOrderElementsOf((1..30).map { "tx$it" })
    }
}
