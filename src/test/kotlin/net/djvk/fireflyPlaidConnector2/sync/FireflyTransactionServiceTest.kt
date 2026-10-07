package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PlaidLinksApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLookupResponse
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLookupRow
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSingle
import net.djvk.fireflyPlaidConnector2.lib.createFireflyResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.net.URI
import java.time.LocalDate
import java.time.OffsetDateTime

class FireflyTransactionServiceTest {
    /** Touch MockUtil first: its file-level mocks must not be created in the middle of a whenever().thenReturn(). */
    @Suppress("unused")
    private val warmMockUtil = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE


    private val fireflyTxApi: TransactionsApi = mock()
    private val syncHelper: SyncHelper = mock { onBlocking { optimisticInsertBatchIntoFirefly(any()) } doReturn 1 }
    private val plaidLinksApi: PlaidLinksApi = mock()
    private val existingFireflyPullWindowDays = 30

    private val fireflyTransactionService = FireflyTransactionService(
        fireflyTxApi,
        syncHelper,
        existingFireflyPullWindowDays,
        "UTC",
        plaidLinksApi,
    )

    @TempDir
    lateinit var dir: Path

    private fun statusError(status: HttpStatusCode, body: String = "{}"): ClientRequestException = runBlocking {
        val client = HttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }) {
            expectSuccess = true
        }
        try {
            client.get("https://x.test/y"); throw AssertionError("expected an error")
        } catch (e: ClientRequestException) {
            e
        }
    }

    private fun linked(plaidId: String) = FireflyTransactionDto(
        null,
        FireflyFixtures.getTransaction(plaidLinks = listOf(PlaidLink(plaidId, PlaidLinkLeg.single, "acct"))).transactions.first(),
    )

    private fun row(plaidId: String, group: String) = PlaidLinkLookupRow(plaidId, "j$group", group, PlaidLinkLeg.single, "acct")

    private fun read(group: String, vararg plaidIds: String) = TransactionRead(
        type = "transactions", id = group,
        attributes = FireflyFixtures.getTransaction(plaidLinks = plaidIds.map { PlaidLink(it, PlaidLinkLeg.single, "acct") }),
        links = ObjectLink(self = URI("http://example.com/transactions/$group")),
    )

    private fun serviceWithStore() = DeadLetterStore(dir.toString()).let {
        it to FireflyTransactionService(fireflyTxApi, syncHelper, existingFireflyPullWindowDays, "UTC", plaidLinksApi, it)
    }

    // region 409 / 422 handling

    @Test
    fun aCreateThatFireflyAnswers409IsSkippedNotDeadLetteredAndNotCountedAsCreated() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        whenever(syncHelper.optimisticInsertBatchIntoFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.Conflict) }

        val notCreated = service.processFireflyTransactionUpdates(listOf(linked("tx1")), listOf(), listOf())

        assertEquals(1, notCreated)
        assertThat(store.read()).isEmpty()
    }

    @Test
    fun aCreateThatWasCreatedIsNotCountedAsNotCreated() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        whenever(syncHelper.optimisticInsertBatchIntoFirefly(any())).thenReturn(1)

        assertEquals(0, service.processFireflyTransactionUpdates(listOf(linked("tx1")), listOf(), listOf()))
        assertThat(store.read()).isEmpty()
    }

    @Test
    fun aCreateTheHelperSkippedAsZeroAmountIsNotCountedAsCreated() = runBlocking<Unit> {
        val (_, service) = serviceWithStore()
        whenever(syncHelper.optimisticInsertBatchIntoFirefly(any())).thenReturn(0)

        assertEquals(1, service.processFireflyTransactionUpdates(listOf(linked("tx1")), listOf(), listOf()))
    }

    @Test
    fun a409AlsoDropsTheExistingLetterOfThatCreate() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        store.add(DeadLetter("create", "tx1", null, linked("tx1").tx, false, "old"))
        whenever(syncHelper.optimisticInsertBatchIntoFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.Conflict) }

        service.retryDeadLetters()

        assertThat(store.read()).describedAs("a retried create that already landed is dropped").isEmpty()
    }

    @Test
    fun anUpdateThatFireflyAnswers409IsDroppedNotDeadLettered() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        store.add(DeadLetter("update", "ff1", "ff1", linked("tx1").tx, false, "old"))
        whenever(syncHelper.updateBatchInFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.Conflict) }

        service.processFireflyTransactionUpdates(listOf(), listOf(FireflyTransactionDto("ff1", linked("tx1").tx)), listOf())

        assertThat(store.read()).isEmpty()
    }

    @Test
    fun a409OnAFreshUpdateIsNotKept() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        whenever(syncHelper.updateBatchInFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.Conflict) }

        service.processFireflyTransactionUpdates(listOf(), listOf(FireflyTransactionDto("ff2", linked("tx2").tx)), listOf())

        assertThat(store.read()).isEmpty()
    }

    @Test
    fun a409OnADeleteIsDroppedNotDeadLettered() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        whenever(syncHelper.deleteBatchInFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.Conflict) }

        service.processFireflyTransactionUpdates(listOf(), listOf(), listOf("ff3"))

        assertThat(store.read()).isEmpty()
    }

    @Test
    fun a422OnACreateIsStillDeadLetteredUnderTheFirstPlaidId() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        whenever(syncHelper.optimisticInsertBatchIntoFirefly(any()))
            .doSuspendableAnswer { throw statusError(HttpStatusCode.UnprocessableEntity, """{"message":"no such account"}""") }

        val notCreated = service.processFireflyTransactionUpdates(listOf(linked("tx1")), listOf(), listOf())

        assertEquals(1, notCreated)
        val letter = store.read().single()
        assertEquals("tx1", letter.key)
        assertThat(letter.message).contains("no such account")
    }

    @Test
    fun anUpdateWithAFallbackCreateThatGets422CreatesTheFallbackAndKeepsNoLetter() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        val fallback = linked("leg2")
        val update = FireflyTransactionDto("ff1", linked("leg1").tx, fallbackCreate = fallback)
        whenever(syncHelper.updateBatchInFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.UnprocessableEntity) }

        service.processFireflyTransactionUpdates(listOf(), listOf(update), listOf())

        verify(syncHelper).optimisticInsertBatchIntoFirefly(eq(listOf(fallback)))
        assertThat(store.read()).isEmpty()
    }

    @Test
    fun anUpdateWithAFallbackCreateThatGets404AlsoCreatesTheFallback() = runBlocking<Unit> {
        val (_, service) = serviceWithStore()
        val fallback = linked("leg2")
        whenever(syncHelper.updateBatchInFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.NotFound) }

        service.processFireflyTransactionUpdates(listOf(), listOf(FireflyTransactionDto("ff1", linked("leg1").tx, fallbackCreate = fallback)), listOf())

        verify(syncHelper).optimisticInsertBatchIntoFirefly(eq(listOf(fallback)))
    }

    @Test
    fun aCreateLetterWithNoLinkIsAbandonedAtRetryAndNotSent() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        store.add(DeadLetter("create", "old-key", null, FireflyFixtures.getTransaction().transactions.first(), false, "x"))

        service.retryDeadLetters()

        assertThat(store.read().single().abandoned).isTrue()
        verify(syncHelper, never()).optimisticInsertBatchIntoFirefly(any())
    }

    @Test
    fun theLetterKeyOfATransferCreateIsItsFirstLinksRawId() {
        val split = FireflyFixtures.getTransaction(
            type = TransactionTypeProperty.transfer,
            plaidLinks = listOf(PlaidLink("dep", PlaidLinkLeg.destination, "b"), PlaidLink("wd", PlaidLinkLeg.source, "a")),
        ).transactions.first()

        assertEquals("dep", FireflyTransactionService.letterKey(split))
    }

    // endregion

    // region link lookups

    @Test
    fun fetchMissingByPlaidIdMakesOneLookupPerChunkOfIdsAndOneReadPerDistinctGroup() = runBlocking<Unit> {
        val max = PlaidLinksApi.MAX_IDS
        val ids = (1..max * 2 + max / 2).map { "id$it" }
        // id1 and id2 are the two legs of one transfer (group g1); the first id of the second chunk is group g2; of the third g3
        val second = "id${max + 1}"
        val third = "id${2 * max + 1}"
        val rowsByFirst = mapOf(
            "id1" to listOf(row("id1", "g1"), row("id2", "g1")),
            second to listOf(row(second, "g2")),
            third to listOf(row(third, "g3")),
        )
        whenever(plaidLinksApi.lookupPlaidLinks(any())).doSuspendableAnswer { call ->
            @Suppress("UNCHECKED_CAST")
            val chunk = call.arguments[0] as List<String>
            createFireflyResponse(PlaidLinkLookupResponse(rowsByFirst[chunk.first()].orEmpty()))
        }
        for (g in listOf("g1", "g2", "g3")) {
            createFireflyResponse(TransactionSingle(read(g))).let { r -> whenever(fireflyTxApi.getTransaction(g)).thenReturn(r) }
        }

        val found = fireflyTransactionService.fetchMissingByPlaidId(ids, listOf())

        assertEquals(listOf("g1", "g2", "g3"), found.map { it.id })
        val chunks = argumentCaptor<List<String>>()
        verify(plaidLinksApi, times(3)).lookupPlaidLinks(chunks.capture())
        assertEquals(listOf(max, max, max / 2), chunks.allValues.map { it.size })
        verify(fireflyTxApi, times(3)).getTransaction(any())
        verify(fireflyTxApi, times(1)).getTransaction("g1")
    }

    @Test
    fun fetchMissingByPlaidIdSkipsIdsTheWindowAlreadyHoldsAndDuplicates() = runBlocking<Unit> {
        createFireflyResponse(PlaidLinkLookupResponse(listOf(row("far", "g9")))).let { r -> whenever(plaidLinksApi.lookupPlaidLinks(any())).thenReturn(r) }
        createFireflyResponse(TransactionSingle(read("g9", "far"))).let { r -> whenever(fireflyTxApi.getTransaction("g9")).thenReturn(r) }

        val found = fireflyTransactionService.fetchMissingByPlaidId(listOf("near", "far", "far"), listOf(read("g1", "near")))

        assertEquals(listOf("g9"), found.map { it.id })
        verify(plaidLinksApi).lookupPlaidLinks(eq(listOf("far")))
    }

    @Test
    fun fetchMissingByPlaidIdMakesNoCallWhenEverythingIsInTheWindow() = runBlocking<Unit> {
        val found = fireflyTransactionService.fetchMissingByPlaidId(listOf("near"), listOf(read("g1", "near")))

        assertThat(found).isEmpty()
        verifyNoInteractions(plaidLinksApi)
        verifyNoInteractions(fireflyTxApi)
    }

    @Test
    fun aFailedLinkLookupPropagates() = runBlocking<Unit> {
        whenever(plaidLinksApi.lookupPlaidLinks(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.InternalServerError) }

        val e = runCatching { fireflyTransactionService.fetchMissingByPlaidId(listOf("x"), listOf()) }.exceptionOrNull()

        assertThat(e).isInstanceOf(io.ktor.client.plugins.ResponseException::class.java)
    }

    @Test
    fun heldPlaidIdsChunksAtTheLookupLimitAndReturnsOnlyTheHeldOnes() = runBlocking<Unit> {
        val max = PlaidLinksApi.MAX_IDS
        val ids = (1..2 * max + 1).map { "id$it" } + "id1" // one duplicate
        whenever(plaidLinksApi.lookupPlaidLinks(any())).doSuspendableAnswer { call ->
            @Suppress("UNCHECKED_CAST")
            val chunk = call.arguments[0] as List<String>
            createFireflyResponse(PlaidLinkLookupResponse(chunk.filter { it.endsWith("7") }.map { row(it, "g$it") }))
        }

        val held = fireflyTransactionService.heldPlaidIds(ids)

        assertEquals(ids.distinct().filter { it.endsWith("7") }.toSet(), held)
        val chunks = argumentCaptor<List<String>>()
        verify(plaidLinksApi, times(3)).lookupPlaidLinks(chunks.capture())
        assertEquals(listOf(max, max, 1), chunks.allValues.map { it.size })
        verifyNoInteractions(fireflyTxApi)
    }

    @Test
    fun heldPlaidIdsOfNothingMakesNoCall() = runBlocking<Unit> {
        assertThat(fireflyTransactionService.heldPlaidIds(listOf())).isEmpty()
        verifyNoInteractions(plaidLinksApi)
    }

    // endregion

    @Test
    fun testProcessFireflyTransactionUpdates() {
        runBlocking {
            // Setup
            val createTransaction = FireflyFixtures.getTransaction()
            val createTxSplit = createTransaction.transactions.first()
            val creates = listOf(FireflyTransactionDto(null, createTxSplit))

            // Create a transfer update
            val transferTransaction = FireflyFixtures.getTransaction(type = TransactionTypeProperty.transfer)
            val transferTxSplit = transferTransaction.transactions.first()
            val transferUpdate = FireflyTransactionDto("transfer-update-id", transferTxSplit)

            // Create a non-transfer update
            val nonTransferTransaction = FireflyFixtures.getTransaction(type = TransactionTypeProperty.withdrawal)
            val nonTransferTxSplit = nonTransferTransaction.transactions.first()
            val nonTransferUpdate = FireflyTransactionDto("non-transfer-update-id", nonTransferTxSplit)

            val updates = listOf(transferUpdate, nonTransferUpdate)
            val deletes = listOf("1", "2")

            // Execute
            fireflyTransactionService.processFireflyTransactionUpdates(creates, updates, deletes)

            // Verify
            // M3-R2: each write is made on its own, so one rejected transaction can be set aside without the rest
            verify(syncHelper).optimisticInsertBatchIntoFirefly(eq(creates))

            // H1: a transfer conversion is an in-place update like any other, never a delete plus create
            verify(syncHelper).updateBatchInFirefly(eq(listOf(transferUpdate)))
            verify(syncHelper).updateBatchInFirefly(eq(listOf(nonTransferUpdate)))
            verify(syncHelper, never()).deleteBatchInFirefly(eq(listOf("transfer-update-id")))

            // Verify deletes
            verify(syncHelper).deleteBatchInFirefly(eq(listOf("1")))
            verify(syncHelper).deleteBatchInFirefly(eq(listOf("2")))
        }
    }

    @Test
    fun aFailedTransferConversionPropagatesAndNothingIsDeleted() {
        runBlocking {
            val transferTxSplit = FireflyFixtures.getTransaction(type = TransactionTypeProperty.transfer).transactions.first()
            val transferUpdate = FireflyTransactionDto("transfer-update-id", transferTxSplit)
            whenever(syncHelper.updateBatchInFirefly(any())).thenThrow(RuntimeException("Update failed"))

            // H1(a): this used to be caught and logged, and the caller then committed its Plaid cursor
            val e = runCatching {
                fireflyTransactionService.processFireflyTransactionUpdates(emptyList(), listOf(transferUpdate), listOf("9"))
            }.exceptionOrNull()

            assertEquals("Update failed", e?.message)
            verify(syncHelper, never()).deleteBatchInFirefly(any())
        }
    }

    @Test
    fun testProcessFireflyNonTransferUpdates() {
        runBlocking {
            // Setup non-transfer updates
            val nonTransferTransaction = FireflyFixtures.getTransaction(type = TransactionTypeProperty.withdrawal)
            val nonTransferTxSplit = nonTransferTransaction.transactions.first()
            val nonTransferUpdate = FireflyTransactionDto("non-transfer-update-id", nonTransferTxSplit)

            // Execute
            fireflyTransactionService.processFireflyTransactionUpdates(
                emptyList(),
                listOf(nonTransferUpdate),
                emptyList()
            )

            // Verify that updateBatchInFirefly is called with the non-transfer update
            verify(syncHelper).updateBatchInFirefly(eq(listOf(nonTransferUpdate)))
            // Verify that deleteBatchInFirefly is not called
            verify(syncHelper, never()).deleteBatchInFirefly(eq(listOf("non-transfer-update-id")))
        }
    }

    @Test
    fun testFetchExistingFireflyTransactions() {
        runBlocking {
            // Setup - simplified approach
            val transaction1 = FireflyFixtures.getTransaction(description = "Test Transaction 1")
            val transaction2 = FireflyFixtures.getTransaction(description = "Test Transaction 2")
            val transactions = listOf(
                TransactionRead(
                    type = "transactions",
                    id = "1",
                    attributes = transaction1,
                    links = ObjectLink(self = URI("http://example.com/transactions/1"))
                ),
                TransactionRead(
                    type = "transactions",
                    id = "2",
                    attributes = transaction2,
                    links = ObjectLink(self = URI("http://example.com/transactions/2"))
                )
            )

            // Create mocks outside the coroutine
            val mockResponse = mock<net.djvk.fireflyPlaidConnector2.api.firefly.infrastructure.HttpResponse<net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionArray>>()
            val mockArray = mock<net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionArray>()
            val mockMeta = mock<net.djvk.fireflyPlaidConnector2.api.firefly.models.Meta>()

            // Setup basic mocks
            whenever(mockArray.data).thenReturn(transactions)
            whenever(mockArray.meta).thenReturn(mockMeta)
            whenever(mockMeta.pagination).thenReturn(null) // No pagination = single page
            whenever(mockResponse.body()).thenReturn(mockArray)

            // Mock API call
            whenever(fireflyTxApi.listTransaction(any(), any(), any(), any())).thenReturn(mockResponse)

            // Execute
            val result = fireflyTransactionService.fetchExistingFireflyTransactions()

            // Verify
            assertEquals(transactions, result)
            verify(fireflyTxApi).listTransaction(
                eq(1),
                any(),
                any(),
                any()
            )
        }
    }
}
