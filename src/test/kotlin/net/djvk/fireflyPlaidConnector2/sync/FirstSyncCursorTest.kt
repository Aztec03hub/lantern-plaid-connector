package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.plaid.models.RemovedTransaction
import net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction as PlaidTransaction
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsSyncRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsSyncResponse
import net.djvk.fireflyPlaidConnector2.lib.PlaidFixtures
import net.djvk.fireflyPlaidConnector2.lib.PlaidMock
import net.djvk.fireflyPlaidConnector2.lib.createPlaidResponse
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Path

/**
 * U01 (LAN-56, finding E2): a first-sync fast-forward that fails part way must leave no cursor, and a poll must not
 * sync an Item that has none (that would import its whole history). Every id, token and cursor here is invented.
 */
internal class FirstSyncCursorTest {
    /** Touch MockUtil first: its file-level mocks must not be created in the middle of a whenever().thenReturn(). */
    @Suppress("unused")
    private val warmMockUtil = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE

    @TempDir
    lateinit var dir: Path

    private val tok = "tok-12345678"
    private val seq = sequenceOf(Pair(tok, listOf("acct")))

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

    private fun plaidError(code: String) =
        statusError(HttpStatusCode.BadRequest, """{"error_type":"ITEM_ERROR","error_code":"$code"}""")

    private fun mutationError() = plaidError(MUTATION_DURING_PAGINATION)

    private fun page(txs: List<PlaidTransaction>, cursor: String, hasMore: Boolean): TransactionsSyncResponse =
        mock {
            on { added } doReturn txs
            on { modified } doReturn listOf()
            on { removed } doReturn listOf<RemovedTransaction>()
            on { nextCursor } doReturn cursor
            on { this.hasMore } doReturn hasMore
        }

    /** Answers the n-th transactionsSync call with the n-th step; a Throwable step is thrown. */
    private fun PlaidMock.script(vararg steps: Any) {
        var call = 0
        runBlocking {
            whenever(api.transactionsSync(any())).doSuspendableAnswer {
                val step = steps[minOf(call++, steps.size - 1)]
                if (step is Throwable) throw step
                createPlaidResponse(step as TransactionsSyncResponse)
            }
        }
    }

    private fun requestCursors(plaid: PlaidMock): List<String?> = runBlocking {
        val captor = argumentCaptor<TransactionsSyncRequest>()
        verify(plaid.api, org.mockito.kotlin.atLeast(0)).transactionsSync(captor.capture())
        captor.allValues.map { it.cursor }
    }

    private fun tolerant(plaid: PlaidMock) = PlaidSyncService(plaid.wrapper, 100, true)

    @Test
    fun aFirstSyncThatFailsOnPageTwoLeavesNoCursor() = runBlocking<Unit> {
        val plaid = PlaidMock()
        plaid.script(page(listOf(), "c1", true), plaidError("INSTITUTION_DOWN"))
        val map = mutableMapOf<String, String>()

        val failures = tolerant(plaid).initializeCursors(seq, map)

        assertThat(map).isEmpty()
        val f = failures.getValue(tok)
        assertThat(f.errorCode).isEqualTo("INSTITUTION_DOWN")
        assertThat(f.toString()).doesNotContain(tok).doesNotContain("error_type")
    }

    @Test
    fun aFirstSyncThatFailsOnPageThreeLeavesNoCursor() = runBlocking<Unit> {
        val plaid = PlaidMock()
        plaid.script(page(listOf(), "c1", true), page(listOf(), "c2", true), plaidError("INSTITUTION_DOWN"))
        val map = mutableMapOf<String, String>()

        tolerant(plaid).initializeCursors(seq, map)

        assertThat(map).isEmpty()
    }

    @Test
    fun aFirstSyncThatSucceedsKeepsOnlyTheLastCursor() = runBlocking<Unit> {
        val plaid = PlaidMock()
        plaid.script(page(listOf(), "c1", true), page(listOf(), "c2", true), page(listOf(), "c3", false))
        val map = mutableMapOf<String, String>()

        val failures = tolerant(plaid).initializeCursors(seq, map)

        assertThat(failures).isEmpty()
        assertThat(map).isEqualTo(mapOf(tok to "c3"))
        assertThat(requestCursors(plaid)).containsExactly(null, "c1", "c2")
    }

    @Test
    fun aFailedItemDoesNotStopTheNextItemFromBeingFastForwarded() = runBlocking<Unit> {
        val plaid = PlaidMock()
        // Item 1: page 1 ok, page 2 fails. Item 2: one good page.
        plaid.script(page(listOf(), "c1", true), plaidError("INSTITUTION_DOWN"), page(listOf(), "d1", false))
        val map = mutableMapOf<String, String>()

        val failures = tolerant(plaid).initializeCursors(
            sequenceOf(Pair("tok-aaaaaaaa", listOf("acct")), Pair("tok-bbbbbbbb", listOf("acct"))), map
        )

        assertThat(failures.keys).containsExactly("tok-aaaaaaaa")
        assertThat(map).isEqualTo(mapOf("tok-bbbbbbbb" to "d1"))
    }

    @Test
    fun anItemWithACursorIsNotCalled() = runBlocking<Unit> {
        val plaid = PlaidMock()
        val map = mutableMapOf(tok to "start")

        val failures = tolerant(plaid).initializeCursors(seq, map)

        verify(plaid.api, never()).transactionsSync(any())
        assertThat(failures).isEmpty()
        assertThat(map).isEqualTo(mapOf(tok to "start"))
    }

    @Test
    fun aBlankCursorIsTreatedAsMissing() = runBlocking<Unit> {
        val plaid = PlaidMock()
        plaid.script(page(listOf(), "c9", false))
        val map = mutableMapOf(tok to "")

        tolerant(plaid).initializeCursors(seq, map)

        assertThat(map).isEqualTo(mapOf(tok to "c9"))
    }

    @Test
    fun aStrictServiceRethrowsAndStoresNothing() = runBlocking<Unit> {
        val plaid = PlaidMock()
        plaid.script(page(listOf(), "c1", true), plaidError("INSTITUTION_DOWN"))
        val map = mutableMapOf<String, String>()

        assertThrows<ClientRequestException> {
            runBlocking { PlaidSyncService(plaid.wrapper, 100, false).initializeCursors(seq, map) }
        }

        assertThat(map).isEmpty()
    }

    @Test
    fun aMutationDuringPaginationRestartsTheFastForward() = runBlocking<Unit> {
        val plaid = PlaidMock()
        plaid.script(page(listOf(), "c1", true), mutationError(), page(listOf(), "e1", true), page(listOf(), "e2", false))
        val map = mutableMapOf<String, String>()

        val failures = tolerant(plaid).initializeCursors(seq, map)

        assertThat(failures).isEmpty()
        assertThat(map).isEqualTo(mapOf(tok to "e2"))
        assertThat(requestCursors(plaid)).containsExactly(null, "c1", null, "e1")
    }

    @Test
    fun aMutationThatNeverSettlesIsAFailureWhenTolerantAndAThrowWhenStrict() = runBlocking<Unit> {
        val plaid = PlaidMock()
        plaid.script(mutationError())
        val map = mutableMapOf<String, String>()

        val failures = tolerant(plaid).initializeCursors(seq, map)

        assertThat(map).isEmpty()
        assertThat(failures.getValue(tok).errorCode).isEqualTo(MUTATION_DURING_PAGINATION)
        // 1 first attempt + 3 restarts
        assertThat(requestCursors(plaid)).hasSize(4)

        assertThrows<SyncMutationDuringPaginationException> {
            runBlocking { PlaidSyncService(plaid.wrapper, 100, false).initializeCursors(seq, map) }
        }
        assertThat(map).isEmpty()
    }

    @Test
    fun anEmptyNextCursorStoresNothing() = runBlocking<Unit> {
        val plaid = PlaidMock()
        plaid.script(page(listOf(), "", false))
        val map = mutableMapOf<String, String>()

        val failures = tolerant(plaid).initializeCursors(seq, map)

        assertThat(map).isEmpty()
        assertThat(failures).isEmpty()
    }

    // region orchestrator

    private val syncHelper: SyncHelper = mock()
    private val fireflyTransactionService: FireflyTransactionService = mock()
    private val converter: TransactionConverter = mock()

    private fun orchestrator(service: PlaidSyncService, cursors: CursorManager, importHistory: Boolean = false) =
        PolledSyncOrchestrator(
            30, syncHelper, cursors, service, fireflyTransactionService, converter,
            importHistoryOnFirstSync = importHistory,
        )

    @Test
    fun theNextStartAfterAFailedFirstSyncStartsAgain() = runBlocking<Unit> {
        val cursors = CursorManager(dir.toString())
        whenever(syncHelper.getAllPlaidAccessTokenAccountIdSets()).thenReturn(Pair(mapOf("acct" to 1), seq))

        // Run 1: page 1 ok, page 2 fails
        val bad = PlaidMock()
        bad.script(page(listOf(), "c1", true), plaidError("INSTITUTION_DOWN"))
        orchestrator(tolerant(bad), cursors).initializeCursors()
        assertThat(cursors.readCursorMap()).doesNotContainKey(tok)
        assertThat(cursors.cursorFilePath.toFile().readText()).doesNotContain("$tok|")

        // Run 2: healthy Plaid, starts from scratch
        val good = PlaidMock()
        good.script(page(listOf(), "g1", true), page(listOf(), "g2", false))
        orchestrator(tolerant(good), cursors).initializeCursors()
        assertThat(requestCursors(good)).containsExactly(null, "g1")
        assertThat(cursors.cursorFilePath.toFile().readText().trim()).isEqualTo("$tok|g2")
    }

    @Test
    fun aCursorFileThatIsAbsentEmptyOrMalformedBehavesAsDocumented() = runBlocking<Unit> {
        val cursors = CursorManager(dir.toString())
        val file = cursors.cursorFilePath.toFile()

        assertThat(cursors.readCursorMap()).isEmpty()

        file.writeText("")
        assertThat(cursors.readCursorMap()).isEmpty()

        file.writeText("$tok|\n")
        val blank = cursors.readCursorMap()
        assertThat(blank).isEqualTo(mapOf(tok to ""))
        assertThat(hasCursor(blank, tok)).isFalse()
        assertThat(hasCursor(mapOf(tok to "c"), tok)).isTrue()

        file.writeText("tokenwithoutbar\n")
        val e = assertThrows<IllegalStateException> { runBlocking { cursors.readCursorMap() } }
        assertThat(e.message).doesNotContain("tokenwithoutbar")
    }

    @Test
    fun aPollDoesNotSyncAnItemWhoseFastForwardFailed() = runBlocking<Unit> {
        val plaid = PlaidMock()
        plaid.script(page(listOf(), "c1", true), plaidError("INSTITUTION_DOWN"))
        val cursorMap = mutableMapOf<String, String>()

        val result = orchestrator(tolerant(plaid), mock()).processTransactions(mapOf("acct" to 1), seq, cursorMap)

        // Two calls for the fast-forward; never a third with a null cursor (that would be the whole history)
        assertThat(requestCursors(plaid)).containsExactly(null, "c1")
        assertThat(cursorMap).isEmpty()
        assertThat(result.failedItems.single().errorCode).isEqualTo("INSTITUTION_DOWN")
    }

    @Test
    fun importHistoryOnFirstSyncStillPollsACursorlessItemFromTheStart() = runBlocking<Unit> {
        val plaid = PlaidMock()
        plaid.script(page(listOf(), "h1", false))
        val cursorMap = mutableMapOf<String, String>()

        orchestrator(tolerant(plaid), mock(), importHistory = true).processTransactions(mapOf("acct" to 1), seq, cursorMap)

        assertThat(requestCursors(plaid)).containsExactly(null)
        assertThat(cursorMap).isEqualTo(mapOf(tok to "h1"))
    }

    @Test
    fun anItemFastForwardedByAPollIsCommittedAndNotImportedOldHistory() = runBlocking<Unit> {
        val plaid = PlaidMock()
        val old = PlaidFixtures.getPaymentTransaction(accountId = "acct", transactionId = "old1")
        // Two pages of old history, then the poll's own (empty) page
        plaid.script(page(listOf(old), "c1", true), page(listOf(old), "c2", false), page(listOf(), "c3", false))
        val cursorMap = mutableMapOf<String, String>()

        orchestrator(tolerant(plaid), mock()).processTransactions(mapOf("acct" to 1), seq, cursorMap)

        assertThat(requestCursors(plaid)).containsExactly(null, "c1", "c2")
        assertThat(cursorMap).isEqualTo(mapOf(tok to "c3"))
        verify(fireflyTransactionService, never()).processFireflyTransactionUpdates(any(), any(), any())
        verify(converter, never()).convertPollSync(any(), any(), any(), any(), any())
    }

    // endregion
}
