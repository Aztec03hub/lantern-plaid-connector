package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.network.sockets.ConnectTimeoutException
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.pairing.PairPass
import net.djvk.fireflyPlaidConnector2.pairing.PairSettings
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.net.NoRouteToHostException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `polled.maxIterations` (the nightly's one-shot mode): the exit code follows the poll, and a down network fails fast. */
internal class OneShotModeTest {
    private val syncHelper: SyncHelper = mock()
    private val cursorManager: CursorManager = mock()
    private val plaidSyncService: PlaidSyncService = mock()
    private val fireflyTransactionService: FireflyTransactionService = mock()
    private val converter: TransactionConverter = mock()
    private val exits = mutableListOf<Int>()

    private fun orchestrator(max: Int, pairPass: PairPass? = null, pairSettings: PairSettings? = null) =
        PolledSyncOrchestrator(
            30, syncHelper, cursorManager, plaidSyncService, fireflyTransactionService, converter,
            pairPass = pairPass, pairSettings = pairSettings, exit = { exits.add(it) },
        ).also { it.maxIterations = max }

    private val accountMap = mapOf("account1" to 1)
    private val sequence = sequenceOf(Pair("token1", listOf("account1")))

    /** A poll with nothing to do, like NetworkResilienceTest.cursorIsCommittedAfterSuccess. */
    private fun emptyPoll() = runBlocking {
        whenever(fireflyTransactionService.fetchExistingFireflyTransactions()).thenReturn(emptyList())
        whenever(plaidSyncService.processPlaidTransactions(any(), any())).doSuspendableAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.arguments[1] as MutableMap<String, String>)["token1"] = "cursor-new"
            PlaidTransactionResult(emptyList(), emptyList(), emptyList())
        }
        whenever(converter.convertPollSync(any(), any(), any(), any(), any()))
            .thenReturn(TransactionConverter.ConvertPollSyncResult(emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun aGoodPollReturnsTrueAndExitsZero() = runBlocking<Unit> {
        emptyPoll()
        val o = orchestrator(1)
        val ok = o.pollOnce(accountMap, sequence, mutableMapOf("token1" to "cursor-old"))
        assertTrue(ok)
        assertTrue(o.stopIfDone(ok))
        assertEquals(listOf(0), exits)
    }

    @Test
    fun aFailedPollReturnsFalseAndExitsOne() = runBlocking<Unit> {
        whenever(syncHelper.validatePlaidLinksEndpoint()).doSuspendableAnswer { throw IllegalStateException("not the fork") }
        val o = orchestrator(1)
        val ok = o.pollOnce(accountMap, sequence, mutableMapOf("token1" to "cursor-old"))
        assertFalse(ok)
        assertTrue(o.stopIfDone(ok))
        assertEquals(listOf(1), exits)
    }

    @Test
    fun itOnlyStopsAfterMaxIterationsAndNeverWhenZero() {
        val twice = orchestrator(2)
        assertFalse(twice.stopIfDone(true))
        assertTrue(exits.isEmpty())
        assertTrue(twice.stopIfDone(true))
        assertEquals(listOf(0), exits)
        val service = orchestrator(0)
        repeat(3) { assertFalse(service.stopIfDone(false)) }
        assertEquals(listOf(0), exits)
    }

    @Test
    fun aOneShotStartupWithTheNetworkDownFailsAtOnceInsteadOfRetryingForever() = runBlocking<Unit> {
        var attempts = 0
        assertThrows<NoRouteToHostException> {
            runBlocking { orchestrator(1).startup { attempts++; throw NoRouteToHostException("down") } }
        }
        assertEquals(1, attempts)
        // the long-running service still waits: a start that works on the first try is returned unchanged
        assertEquals("up", orchestrator(0).startup { "up" })
    }

    @Test
    fun aOneShotThatCannotStartExitsOneAndNeverPolls() {  // nit2: the startup catch in run()
        runBlocking { whenever(syncHelper.setApiCreds()).doSuspendableAnswer { throw IllegalStateException("no credentials") } }
        orchestrator(1).run()
        assertEquals(listOf(1), exits)
        runBlocking { verify(plaidSyncService, org.mockito.kotlin.never()).processPlaidTransactions(any(), any()) }
    }

    @Test
    fun aThrowingPairPassIsLoggedAndTheCursorsStillCommit() = runBlocking<Unit> {
        emptyPoll()
        // an empty poll skips Firefly entirely (and the pair hook with it), so give it one deletion to work on
        whenever(plaidSyncService.processPlaidTransactions(any(), any())).doSuspendableAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.arguments[1] as MutableMap<String, String>)["token1"] = "cursor-new"
            PlaidTransactionResult(emptyList(), emptyList(), listOf("gone"))
        }
        whenever(fireflyTransactionService.fetchMissingByPlaidId(any(), any())).thenReturn(emptyList())
        whenever(fireflyTransactionService.windowStart()).thenReturn(java.time.LocalDate.now().minusDays(5))
        whenever(fireflyTransactionService.processFireflyTransactionUpdates(any(), any(), any())).thenReturn(0)
        val pass: PairPass = mock()
        whenever(pass.run(any(), any(), any(), any())).doSuspendableAnswer { throw ConnectTimeoutException("core down") }
        val o = orchestrator(1, pass, PairSettings(afterRun = true))
        val committed = mutableMapOf("token1" to "cursor-old")
        val ok = o.pollOnce(accountMap, sequence, committed)
        assertTrue(ok, "a failed pair hook must not fail the poll")
        verify(pass).run(any(), any(), any(), any())
        verify(cursorManager).writeCursorMap(mapOf("token1" to "cursor-new"))
    }
}
