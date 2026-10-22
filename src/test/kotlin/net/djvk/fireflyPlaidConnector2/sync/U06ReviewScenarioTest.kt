package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.plaid.models.InvestmentTransaction
import net.djvk.fireflyPlaidConnector2.api.plaid.models.InvestmentTransactionSubtype
import net.djvk.fireflyPlaidConnector2.api.plaid.models.InvestmentTransactionType
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import net.djvk.fireflyPlaidConnector2.transactions.InvestmentTransactionConverter
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate

internal class U06ReviewScenarioTest {
    @TempDir lateinit var dir: java.io.File
    private val syncHelper: SyncHelper = mock()
    private val plaid: PlaidSyncService = mock()
    private val fts: FireflyTransactionService = mock()
    private val held = mutableSetOf<String>()
    private val insertCount = mutableMapOf<String, Int>()
    private val plaidHas = mutableListOf<InvestmentTransaction>()
    private var failInsert = false

    private fun tx(id: String, d: LocalDate) = InvestmentTransaction(
        investmentTransactionId = id, accountId = "brokerage", securityId = "s", date = d, name = "BUY", quantity = 1.0,
        amount = 10.0, price = 10.0, fees = 0.0, type = InvestmentTransactionType.buy,
        subtype = InvestmentTransactionSubtype.buy, isoCurrencyCode = "USD", unofficialCurrencyCode = null)

    private fun orch(): PolledSyncOrchestrator {
        runBlocking {
            whenever(syncHelper.getInvestmentAccessTokenAccountIdSets()).thenReturn(sequenceOf(Pair("tokBroker", listOf("brokerage"))))
            whenever(plaid.fetchInvestmentTransactions(any(), any(), any(), any())).doSuspendableAnswer {
                val from = it.getArgument<LocalDate>(2); val to = it.getArgument<LocalDate>(3)
                plaidHas.filter { t -> !t.date.isBefore(from) && !t.date.isAfter(to) }
            }
            whenever(fts.heldPlaidIds(any())).doSuspendableAnswer { inv -> inv.getArgument<Collection<String>>(0).filter { it in held }.toSet() }
            whenever(syncHelper.optimisticInsertBatchIntoFirefly(any())).doSuspendableAnswer { inv ->
                if (failInsert) error("boom")
                inv.getArgument<List<FireflyTransactionDto>>(0).forEach { d ->
                    val id = d.tx.plaidLinks!!.single().plaidTransactionId
                    insertCount.merge(id, 1, Int::plus); held += id }
                0
            }
        }
        return PolledSyncOrchestrator(30, syncHelper, mock(), plaid, fts, mock<TransactionConverter>(),
            investmentConverter = InvestmentTransactionConverter("America/New_York"),
            investmentSyncStore = InvestmentSyncStore(dir.path, ItemStatusStore(dir.path)))
    }

    private val d0 = LocalDate.of(2026, 3, 1)

    @Test fun gapAndOverlap() = runBlocking<Unit> {
        val o = orch()
        plaidHas += tx("t0", d0.minusDays(1))
        o.syncInvestments(mapOf("brokerage" to 3), d0)      // good poll D
        plaidHas += listOf(tx("t2", d0.plusDays(2)), tx("t5", d0.plusDays(5)), tx("tD", d0), tx("t19", d0.plusDays(19)))
        o.syncInvestments(mapOf("brokerage" to 3), d0.plusDays(20))  // after 20-day outage
        o.syncInvestments(mapOf("brokerage" to 3), d0.plusDays(21))  // overlap
        assertThat(insertCount.keys).containsExactlyInAnyOrder("t0", "t2", "t5", "tD", "t19")
        assertThat(insertCount.values).allMatch { it == 1 }
    }

    @Test fun lateOrBackdatedPostingDuringTheGap() = runBlocking<Unit> {
        val o = orch()
        o.syncInvestments(mapOf("brokerage" to 3), d0)
        // Plaid posts, after the poll on D, a transaction dated D-3 (brokers backdate); normal 14-day window catches it
        plaidHas += tx("late", d0.minusDays(3))
        o.syncInvestments(mapOf("brokerage" to 3), d0.plusDays(20))
        assertThat(insertCount.keys).contains("late")
    }

    @Test fun insertFailureIsNotRecorded() = runBlocking<Unit> {
        val o = orch()
        plaidHas += tx("a", d0)
        failInsert = true
        runCatching { o.syncInvestments(mapOf("brokerage" to 3), d0) }
        assertThat(java.io.File(dir, "investment_sync.json")).doesNotExist()
    }

    @Test fun unreadableFileFallsBackToFixed() = runBlocking<Unit> {
        java.io.File(dir, "investment_sync.json").writeText("{\"abc\": \"not-a-date\"}")
        val o = orch()
        plaidHas += tx("old", d0.minusDays(30)); plaidHas += tx("new", d0.minusDays(3))
        o.syncInvestments(mapOf("brokerage" to 3), d0)
        assertThat(insertCount.keys).containsExactly("new")
        assertThat(java.io.File(dir, "investment_sync.json").readText()).doesNotContain("not-a-date")
    }
}
