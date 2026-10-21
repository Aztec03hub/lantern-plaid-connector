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
    fun a409OnAnUpdateIsLoggedAtErrorWithFireflysConflictList() = runBlocking<Unit> {
        // An update 409 is a race the index could not see; after a pending to posted update it can leave the pending
        //  transaction as a second record of the money, so a person must be told
        val logger = org.slf4j.LoggerFactory.getLogger(FireflyTransactionService::class.java) as ch.qos.logback.classic.Logger
        val seen = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().also { it.start() }
        logger.addAppender(seen)
        try {
            whenever(syncHelper.updateBatchInFirefly(any())).doSuspendableAnswer {
                throw statusError(HttpStatusCode.Conflict, """{"conflicts":[{"plaid_transaction_id":"posted9"}]}""")
            }

            fireflyTransactionService.processFireflyTransactionUpdates(listOf(), listOf(FireflyTransactionDto("ff7", linked("posted9").tx)), listOf())

            val error = seen.list.single { it.level == ch.qos.logback.classic.Level.ERROR }
            assertThat(error.formattedMessage).contains("ff7").contains("posted9")
        } finally {
            logger.detachAppender(seen)
        }
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
    fun aPairingUpdateWithAFallbackCreateThatGets422CreatesTheFallbackAndKeepsNoLetter() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        val fallback = linked("leg2")
        val pairing = linked("leg1").tx.copy(type = TransactionTypeProperty.transfer)
        val update = FireflyTransactionDto("ff1", pairing, fallbackCreate = fallback)
        whenever(syncHelper.updateBatchInFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.UnprocessableEntity) }

        service.processFireflyTransactionUpdates(listOf(), listOf(update), listOf())

        verify(syncHelper).optimisticInsertBatchIntoFirefly(eq(listOf(fallback)))
        assertThat(store.read()).isEmpty()
    }

    @Test
    fun aPairingUpdateRejectedWithAnyOtherPermanent4xxAlsoCreatesTheFallbackAndKeepsNoLetter() = runBlocking<Unit> {
        // Review r2 (F6): kept as a group-keyed update letter, the new leg was hidden from reviseDeadLetteredCreates and
        //  lost for good when a later retry got 404 (the letter is dropped and has no fallback)
        for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.PayloadTooLarge)) {
            val (store, service) = serviceWithStore()
            val fallback = linked("leg2")
            val pairing = linked("leg1").tx.copy(type = TransactionTypeProperty.transfer)
            whenever(syncHelper.updateBatchInFirefly(any())).doSuspendableAnswer { throw statusError(status) }

            service.processFireflyTransactionUpdates(listOf(), listOf(FireflyTransactionDto("ff1", pairing, fallbackCreate = fallback)), listOf())

            verify(syncHelper, atLeastOnce()).optimisticInsertBatchIntoFirefly(eq(listOf(fallback)))
            assertThat(store.read()).describedAs("HTTP $status").isEmpty()
            clearInvocations(syncHelper)
        }
    }

    @Test
    fun aPairingUpdateRejectedWithATransientStatusIsNotReplacedByTheFallback() = runBlocking<Unit> {
        val (_, service) = serviceWithStore()
        val pairing = linked("leg1").tx.copy(type = TransactionTypeProperty.transfer)
        whenever(syncHelper.updateBatchInFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.TooManyRequests) }

        org.junit.jupiter.api.assertThrows<ClientRequestException> {
            runBlocking { service.processFireflyTransactionUpdates(listOf(), listOf(FireflyTransactionDto("ff1", pairing, fallbackCreate = linked("leg2"))), listOf()) }
        }
        verify(syncHelper, never()).optimisticInsertBatchIntoFirefly(any())
    }

    @Test
    fun aPendingToPostedUpdateThatGets422IsDeadLetteredAndTheFallbackIsNotCreated() = runBlocking<Unit> {
        // The pending transaction still holds the money and this sync's removal of the pending id was shielded:
        //  creating the posted one beside it would count the same money twice
        val (store, service) = serviceWithStore()
        val promotion = FireflyTransactionDto("ff1", linked("posted").tx, fallbackCreate = linked("posted"))
        whenever(syncHelper.updateBatchInFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.UnprocessableEntity) }

        service.processFireflyTransactionUpdates(listOf(), listOf(promotion), listOf())

        verify(syncHelper, never()).optimisticInsertBatchIntoFirefly(any())
        assertThat(store.read().single().operation).isEqualTo("update")
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

    // region U02: dead letters keep the money

    private fun promotion(flag: Boolean = false, type: TransactionTypeProperty? = null) = FireflyTransactionDto(
        "ff1", linked("posted").tx.let { if (type != null) it.copy(type = type) else it },
        fallbackCreate = linked("posted"), fallbackOnlyWhenGone = flag,
    )

    @Test
    fun aPromotionLetterRetriedAndGettingA404CreatesThePostedTransactionAndRemovesTheLetter() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        whenever(syncHelper.updateBatchInFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.UnprocessableEntity) }
        service.processFireflyTransactionUpdates(listOf(), listOf(promotion()), listOf())
        assertThat(store.read().single().fallback?.plaidLinks).isEqualTo(linked("posted").tx.plaidLinks)
        verify(syncHelper, never()).optimisticInsertBatchIntoFirefly(any())

        whenever(syncHelper.updateBatchInFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.NotFound) }
        service.retryDeadLetters()

        val sent = argumentCaptor<List<FireflyTransactionDto>>()
        verify(syncHelper).optimisticInsertBatchIntoFirefly(sent.capture())
        assertThat(sent.firstValue.single().tx.plaidLinks).isEqualTo(linked("posted").tx.plaidLinks)
        assertThat(store.read()).isEmpty()
    }

    @Test
    fun aTransferPromotionThatGets404CreatesThePostedTransactionOnFirstAttempt() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        whenever(syncHelper.updateBatchInFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.NotFound) }

        service.processFireflyTransactionUpdates(listOf(), listOf(promotion(true, TransactionTypeProperty.transfer)), listOf())

        verify(syncHelper).optimisticInsertBatchIntoFirefly(eq(listOf(linked("posted"))))
        assertThat(store.read()).isEmpty()
    }

    @Test
    fun aTransferPromotionThatGets422IsDeadLetteredAndNoFallbackIsCreated() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        whenever(syncHelper.updateBatchInFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.UnprocessableEntity) }

        service.processFireflyTransactionUpdates(listOf(), listOf(promotion(true, TransactionTypeProperty.transfer)), listOf())

        verify(syncHelper, never()).optimisticInsertBatchIntoFirefly(any())
        val letter = store.read().single()
        assertThat(letter.operation).isEqualTo("update")
        assertThat(letter.fallback?.plaidLinks).isEqualTo(linked("posted").tx.plaidLinks)
    }

    @Test
    fun aRetriedLetterWhoseFallbackCreateFailsKeepsTheLetterAndFailsTheIteration() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        store.add(DeadLetter("update", "ff1", "ff1", linked("posted").tx, false, "x", fallback = linked("posted").tx))
        whenever(syncHelper.updateBatchInFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.NotFound) }
        whenever(syncHelper.optimisticInsertBatchIntoFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.ServiceUnavailable) }

        org.junit.jupiter.api.assertThrows<io.ktor.client.plugins.ServerResponseException> { runBlocking { service.retryDeadLetters() } }

        assertThat(store.read().single().operation).isEqualTo("update")
    }

    @Test
    fun aRetriedLetterWhoseFallbackCreateAnswers409IsRemovedWithoutADuplicate() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        store.add(DeadLetter("update", "ff1", "ff1", linked("posted").tx, false, "x", fallback = linked("posted").tx))
        whenever(syncHelper.updateBatchInFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.NotFound) }
        whenever(syncHelper.optimisticInsertBatchIntoFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.Conflict) }

        service.retryDeadLetters()

        verify(syncHelper, times(1)).optimisticInsertBatchIntoFirefly(any())
        assertThat(store.read()).isEmpty()
    }

    private fun splitOn(plaidAccount: String, type: TransactionTypeProperty, leg: PlaidLinkLeg, source: String?, destination: String?) =
        FireflyFixtures.getTransaction(
            type = type, sourceId = source, destinationId = destination,
            plaidLinks = listOf(PlaidLink("tx9", leg, plaidAccount)),
        ).transactions.first()

    private suspend fun resentCreate(split: TransactionSplit, accountMap: Map<String, Int>): TransactionSplit {
        val (store, service) = serviceWithStore()
        store.add(DeadLetter("create", "tx9", null, split, false, "x"))
        service.retryDeadLetters(accountMap)
        val sent = argumentCaptor<List<FireflyTransactionDto>>()
        verify(syncHelper).optimisticInsertBatchIntoFirefly(sent.capture())
        return sent.firstValue.single().tx
    }

    @Test
    fun aLetterRetriedAfterTheMappingWasFixedIsSentWithTheCurrentAccountId() = runBlocking<Unit> {
        val sent = resentCreate(splitOn("plaidA", TransactionTypeProperty.withdrawal, PlaidLinkLeg.single, "11", null), mapOf("plaidA" to 12))

        assertThat(sent.sourceId).isEqualTo("12")
        assertThat(sent.destinationId).isNull()
    }

    @Test
    fun aDepositLetterIsReResolvedOnItsDestinationSide() = runBlocking<Unit> {
        val sent = resentCreate(splitOn("plaidA", TransactionTypeProperty.deposit, PlaidLinkLeg.single, null, "11"), mapOf("plaidA" to 12))

        assertThat(sent.destinationId).isEqualTo("12")
        assertThat(sent.sourceId).isNull()
    }

    @Test
    fun aTransferLetterReResolvesBothLegs() = runBlocking<Unit> {
        val split = FireflyFixtures.getTransaction(
            type = TransactionTypeProperty.transfer, sourceId = "11", destinationId = "21",
            plaidLinks = listOf(PlaidLink("tx9", PlaidLinkLeg.source, "plaidA"), PlaidLink("tx10", PlaidLinkLeg.destination, "plaidB")),
        ).transactions.first()

        val sent = resentCreate(split, mapOf("plaidA" to 12, "plaidB" to 22))

        assertThat(sent.sourceId).isEqualTo("12")
        assertThat(sent.destinationId).isEqualTo("22")
    }

    @Test
    fun aLetterWhosePlaidAccountIsNotInTheMapKeepsItsStoredAccountId() = runBlocking<Unit> {
        val sent = resentCreate(splitOn("plaidA", TransactionTypeProperty.withdrawal, PlaidLinkLeg.single, "11", null), mapOf("other" to 12))

        assertThat(sent.sourceId).isEqualTo("11")
    }

    @Test
    fun aLinklessUpdateLetterIsRetriedWithTheAccountOfItsStoredPlaidAccountId() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        val bare = FireflyFixtures.getTransaction(type = TransactionTypeProperty.withdrawal, sourceId = "11").transactions.first()
        store.add(DeadLetter("update", "ff1", "ff1", bare, false, "x", plaidAccountId = "plaidA"))

        service.retryDeadLetters(mapOf("plaidA" to 12))

        val sent = argumentCaptor<List<FireflyTransactionDto>>()
        verify(syncHelper).updateBatchInFirefly(sent.capture())
        assertThat(sent.firstValue.single().tx.sourceId).isEqualTo("12")
    }

    @Test
    fun aStillRejectedLetterIsWrittenBackWithTheCurrentAccounts() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        store.add(DeadLetter("create", "tx9", null, splitOn("plaidA", TransactionTypeProperty.withdrawal, PlaidLinkLeg.single, "11", null), false, "x"))
        whenever(syncHelper.optimisticInsertBatchIntoFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.UnprocessableEntity) }

        service.retryDeadLetters(mapOf("plaidA" to 12))

        val kept = store.read().single()
        assertThat(kept.split?.sourceId).isEqualTo("12")
        assertThat(kept.attempts).isEqualTo(1)
    }

    @Test
    fun anAbandonedLetterWithAFallbackIsNeitherRetriedNorCreated() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        store.add(DeadLetter("update", "ff1", "ff1", linked("posted").tx, false, "x", abandoned = true, fallback = linked("posted").tx))

        service.retryDeadLetters()

        verify(syncHelper, never()).updateBatchInFirefly(any())
        verify(syncHelper, never()).optimisticInsertBatchIntoFirefly(any())
        assertThat(store.read()).hasSize(1)
    }

    @Test
    fun aLetterFileWrittenByTheOldBuildStillReads() = runBlocking<Unit> {
        val store = DeadLetterStore(dir.toString())
        java.nio.file.Files.writeString(
            store.path,
            """[{"operation":"update","key":"ff1","fireflyId":"ff1","changesType":false,"message":"old","attempts":2,"abandoned":false}]""",
        )

        val letter = store.read().single()

        assertThat(letter.fallback).isNull()
        assertThat(letter.plaidAccountId).isNull()
        assertThat(letter.attempts).isEqualTo(2)
    }

    @Test
    fun aLetterWithoutAFallbackStillDropsOn404() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        store.add(DeadLetter("update", "ff1", "ff1", linked("posted").tx, false, "x"))
        whenever(syncHelper.updateBatchInFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.NotFound) }

        service.retryDeadLetters()

        verify(syncHelper, never()).optimisticInsertBatchIntoFirefly(any())
        assertThat(store.read()).isEmpty()
    }

    @Test
    fun aCreateStandingInForAnUnmatchedUpdateIsSkippedWhenAnOlderImportHoldsThePlaidId() = runBlocking<Unit> {
        val (_, service) = serviceWithStore()
        whenever(syncHelper.heldByLegacyReference("old1")).thenReturn(true)

        val notCreated = service.processFireflyTransactionUpdates(listOf(linked("old1").copy(checkLegacyReference = true)), listOf(), listOf())

        assertEquals(1, notCreated)
        verify(syncHelper, never()).optimisticInsertBatchIntoFirefly(any())
    }

    @Test
    fun aCreateStandingInForAnUnmatchedUpdateIsSentWhenNothingOlderHoldsThePlaidId() = runBlocking<Unit> {
        val (_, service) = serviceWithStore()
        whenever(syncHelper.heldByLegacyReference("new1")).thenReturn(false)

        val notCreated = service.processFireflyTransactionUpdates(listOf(linked("new1").copy(checkLegacyReference = true)), listOf(), listOf())

        assertEquals(0, notCreated)
        verify(syncHelper).heldByLegacyReference("new1")
        verify(syncHelper).optimisticInsertBatchIntoFirefly(any())
    }

    @Test
    fun aLegacySearchThatFailsFailsTheIterationInsteadOfCreating() = runBlocking<Unit> {
        val (_, service) = serviceWithStore()
        whenever(syncHelper.heldByLegacyReference(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.ServiceUnavailable) }

        org.junit.jupiter.api.assertThrows<io.ktor.client.plugins.ServerResponseException> {
            runBlocking { service.processFireflyTransactionUpdates(listOf(linked("x1").copy(checkLegacyReference = true)), listOf(), listOf()) }
        }
        verify(syncHelper, never()).optimisticInsertBatchIntoFirefly(any())
    }

    @Test
    fun anOrdinaryCreateNeverRunsTheLegacySearch() = runBlocking<Unit> {
        val (_, service) = serviceWithStore()

        service.processFireflyTransactionUpdates(listOf(linked("n1")), listOf(), listOf())

        verify(syncHelper, never()).heldByLegacyReference(any())
    }

    @Test
    fun anUnmatchedUpdateCreateIsLeftToTheLetterThatAlreadyHoldsThatPlaidId() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        store.add(DeadLetter("create", "w1", null, linked("w1").tx, false, "x"))

        val notCreated = service.processFireflyTransactionUpdates(listOf(linked("w1").copy(checkLegacyReference = true)), listOf(), listOf())

        assertEquals(1, notCreated)
        verify(syncHelper, never()).optimisticInsertBatchIntoFirefly(any())
        assertThat(store.read().single().key).isEqualTo("w1")
    }

    @Test
    fun aPairedUpdateLetterWithNoStoredAccountKeepsItsAccountsOnRetry() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        val leg = splitOn("plaidB", TransactionTypeProperty.withdrawal, PlaidLinkLeg.single, "11", "20").copy(plaidLinks = null)
        store.add(DeadLetter("update", "ff1", "ff1", leg, false, "x"))

        service.retryDeadLetters(mapOf("plaidA" to 11, "plaidB" to 20))

        val sent = argumentCaptor<List<FireflyTransactionDto>>()
        verify(syncHelper).updateBatchInFirefly(sent.capture())
        assertThat(sent.firstValue.single().tx.sourceId).isEqualTo("11")
        assertThat(sent.firstValue.single().tx.destinationId).isEqualTo("20")
    }

    @Test
    fun aRetriedLettersFallbackIsCreatedOnTheCurrentAccount() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        val fb = splitOn("plaidA", TransactionTypeProperty.withdrawal, PlaidLinkLeg.single, "11", null)
        store.add(DeadLetter("update", "ff1", "ff1", linked("posted").tx, false, "x", fallback = fb))
        whenever(syncHelper.updateBatchInFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.NotFound) }

        service.retryDeadLetters(mapOf("plaidA" to 12))

        val sent = argumentCaptor<List<FireflyTransactionDto>>()
        verify(syncHelper).optimisticInsertBatchIntoFirefly(sent.capture())
        assertThat(sent.firstValue.single().tx.sourceId).isEqualTo("12")
    }

    @Test
    fun aRetriedUpdateLetterRejectedAgainWith422DoesNotCreateItsFallback() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        store.add(DeadLetter("update", "ff1", "ff1", linked("posted").tx, false, "x", fallback = linked("posted").tx))
        whenever(syncHelper.updateBatchInFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.UnprocessableEntity) }

        service.retryDeadLetters()

        verify(syncHelper, never()).optimisticInsertBatchIntoFirefly(any())
        assertThat(store.read().single().attempts).isEqualTo(1)
    }

    @Test
    fun aPromotionUpdateLetterHoldingThePostedIdBlocksTheCreateStandingInForAModifiedEvent() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        store.add(DeadLetter("update", "ff1", "ff1", linked("posted").tx, false, "x"))

        val notCreated = service.processFireflyTransactionUpdates(listOf(linked("posted").copy(checkLegacyReference = true)), listOf(), listOf())

        assertEquals(1, notCreated)
        verify(syncHelper, never()).optimisticInsertBatchIntoFirefly(any())
        verify(syncHelper, never()).heldByLegacyReference(any())
    }

    @Test
    fun aLegacySearchRejectedForGoodKeepsTheCreateAsAnAbandonedLetterAndTheIterationGoesOn() = runBlocking<Unit> {
        val (store, service) = serviceWithStore()
        whenever(syncHelper.heldByLegacyReference(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.BadRequest) }

        val notCreated = service.processFireflyTransactionUpdates(listOf(linked("s1").copy(checkLegacyReference = true)), listOf(), listOf())

        assertEquals(1, notCreated)
        verify(syncHelper, never()).optimisticInsertBatchIntoFirefly(any())
        val letter = store.read().single()
        assertThat(letter.abandoned).isTrue()
        assertThat(letter.message).isEqualTo("legacy search failed: HTTP 400")
        assertThat(letter.key).isEqualTo("s1")
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
