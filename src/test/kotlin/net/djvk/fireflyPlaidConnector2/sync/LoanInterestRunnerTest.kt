package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Account
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountSingle
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Meta
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PageLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ShortAccountTypeProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionArray
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSingle
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionStore
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountBalance
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountBase
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountsBalanceGetRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountsGetResponse
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.lib.PlaidMock
import net.djvk.fireflyPlaidConnector2.lib.createFireflyResponse
import net.djvk.fireflyPlaidConnector2.lib.createPlaidResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/** `syncMode: loan-interest` against mocked Plaid and Firefly. Loan 2: APR 7.59, "DCU 2022 Lexus NX Loan (0001)". */
internal class LoanInterestRunnerTest {
    @Suppress("unused")
    private val warmMockUtil = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE

    @TempDir lateinit var dir: File

    private val zone = ZoneId.of("America/Chicago")
    private val name = "DCU 2022 Lexus NX Loan (0001)"
    private val plaid = PlaidMock()
    private val accountsApi = mock<AccountsApi>()
    private val txApi = mock<TransactionsApi>()
    private val syncHelper = mock<SyncHelper>()

    /** Owed in Firefly now; a stored interest or unexplained row moves it as Firefly would. */
    private var fireflyOwed = BigDecimal("23417.37")
    private val owedBefore = mutableMapOf<LocalDate, String>()

    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)

    private fun journal(id: String, amount: String, date: LocalDate, desc: String = "payment", interest: Boolean = false) = TransactionRead(
        "transactions", id,
        FireflyFixtures.getTransaction(
            type = if (interest) TransactionTypeProperty.withdrawal else TransactionTypeProperty.withdrawal,
            sourceId = if (interest) "2" else "1", destinationId = if (interest) "90" else "2", amount = amount, description = desc,
            date = date.atTime(12, 0).atZone(zone).toOffsetDateTime(), transactionJournalId = "j$id",
        ), ObjectLink(),
    )

    private fun payment(id: String, amount: String, date: LocalDate) = journal(id, amount, date)

    private fun interest(id: String, amount: String, payAmount: String, date: LocalDate) =
        journal(id, amount, date, "Interest on $name payment of $$payAmount", interest = true)

    private fun runner(plaidOwed: String, rows: List<TransactionRead>, apply: Boolean = true): LoanInterestRunner {
        val cfg = File(dir, "pair.json").also {
            it.writeText("""{"loans":[{"account":2,"apr":7.59,"interestAccount":"Interest: DCU Lexus NX loan"}]}""")
        }
        val ours = BigDecimal(plaidOwed)
        runBlocking {
            whenever(syncHelper.getAllPlaidAccessTokenAccountIdSets()).thenReturn(Pair(mapOf("pl" to 2), sequenceOf("tok" to listOf("pl"))))
            val balance = mock<AccountBalance>()
            whenever(balance.current).thenReturn(ours.toDouble())
            val base = mock<AccountBase>()
            whenever(base.accountId).thenReturn("pl")
            whenever(base.balances).thenReturn(balance)
            val resp = mock<AccountsGetResponse>()
            whenever(resp.accounts).thenReturn(listOf(base))
            val plaidResponse = createPlaidResponse(resp)
            whenever(plaid.api.accountsBalanceGet(any<AccountsBalanceGetRequest>())).thenReturn(plaidResponse)
            whenever(accountsApi.getAccount(any(), anyOrNull())).doSuspendableAnswer {
                val date = it.getArgument<LocalDate?>(1)
                val owed = date?.let { x -> owedBefore[x] } ?: fireflyOwed.toPlainString()
                createFireflyResponse(AccountSingle(AccountRead("accounts", "2", Account(name, ShortAccountTypeProperty.liabilities, currentBalance = "-$owed"), ObjectLink())))
            }
            whenever(accountsApi.listTransactionByAccount(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
                .doSuspendableAnswer { createFireflyResponse(TransactionArray(rows, Meta(), PageLink())) }
            whenever(txApi.storeTransaction(any())).doSuspendableAnswer {
                val s = it.getArgument<TransactionStore>(0).transactions.single()
                val amount = BigDecimal(s.amount)
                fireflyOwed += if (s.type == TransactionTypeProperty.deposit) amount.negate() else amount
                createFireflyResponse(TransactionSingle(rows.first()))
            }
        }
        return LoanInterestRunner(syncHelper, plaid.wrapper, accountsApi, txApi, "America/Chicago", cfg.path, 0.05, apply)
    }

    private fun stored(): List<TransactionStore> = runBlocking {
        val c = argumentCaptor<TransactionStore>()
        verify(txApi, org.mockito.kotlin.atLeast(0)).storeTransaction(c.capture())
        c.allValues
    }

    /** Payment 08-17 is already charged; payment 09-01 (400, 15 days later) is not. */
    private val chargedThenNew get() = listOf(
        payment("a", "400.00", d(8, 17)), interest("ia", "154.72", "400.00", d(8, 17)), payment("b", "400.00", d(9, 1)),
    )

    @Test
    fun onePaymentAfterFifteenDaysGetsTheInterestJournal() = runBlocking<Unit> {
        owedBefore[d(8, 31)] = "23817.37"
        runner("23491.66", chargedThenNew).runOnce()
        val s = stored().single().transactions.single()
        assertThat(s.type).isEqualTo(TransactionTypeProperty.withdrawal)
        assertThat(s.sourceId).isEqualTo("2")
        assertThat(s.destinationName).isEqualTo("Interest: DCU Lexus NX loan")
        assertThat(s.amount).isEqualTo("74.29")
        assertThat(s.description).isEqualTo("Interest on $name payment of \$400.00")
        assertThat(s.date.toLocalDate()).isEqualTo(d(9, 1))
    }

    @Test
    fun twoUncoveredPaymentsSplitTheGapAndTheLastTakesTheCents() = runBlocking<Unit> {
        fireflyOwed = BigDecimal("23200.00")
        owedBefore[d(8, 16)] = "24000.00"
        owedBefore[d(8, 31)] = "23600.00"
        val rows = listOf(
            payment("0", "400.00", d(7, 17)), interest("i0", "100.00", "400.00", d(7, 17)),
            payment("a", "400.00", d(8, 17)), payment("b", "400.00", d(9, 1)),
        )
        // expected 154.71 + 73.61 = 228.32; the gap is 228.34
        runner("23428.34", rows).runOnce()
        assertThat(stored().map { it.transactions.single().amount }).containsExactly("154.71", "73.63")
    }

    @Test
    fun aGapWithinToleranceWritesNothing() = runBlocking<Unit> {
        runner("23417.40", chargedThenNew).runOnce()
        verify(txApi, never()).storeTransaction(any())
    }

    @Test
    fun plaidLaggingWritesNothing() = runBlocking<Unit> {
        val plan = runner("23300.00", chargedThenNew).runOnce().single()
        assertThat(plan.action).contains("lags")
        verify(txApi, never()).storeTransaction(any())
    }

    @Test
    fun aGapFarFromExpectedWritesTheInterestAndAnUnexplainedRow() = runBlocking<Unit> {
        owedBefore[d(8, 31)] = "23817.37"
        // gap 300.00, expected 74.29, so 225.71 is unexplained
        runner("23717.37", chargedThenNew).runOnce()
        val all = stored().map { it.transactions.single() }
        assertThat(all.map { it.amount }).containsExactly("74.29", "225.71")
        assertThat(all[1].destinationName).isEqualTo("Unexplained loan change")
        assertThat(all[1].sourceId).isEqualTo("2")
        assertThat(all[1].tags).containsExactly("lantern-unexplained")
    }

    @Test
    fun aNegativeResidualIsADepositFromTheUnexplainedAccount() = runBlocking<Unit> {
        owedBefore[d(8, 31)] = "23817.37"
        // gap 20.00 is less than the expected 74.29 by 54.29
        runner("23437.37", chargedThenNew).runOnce()
        val all = stored().map { it.transactions.single() }
        assertThat(all.map { it.amount }).containsExactly("74.29", "54.29")
        assertThat(all[1].type).isEqualTo(TransactionTypeProperty.deposit)
        assertThat(all[1].sourceName).isEqualTo("Unexplained loan change")
        assertThat(all[1].destinationId).isEqualTo("2")
    }

    @Test
    fun aDryRunWritesNothing() = runBlocking<Unit> {
        owedBefore[d(8, 31)] = "23817.37"
        val plan = runner("23491.66", chargedThenNew, apply = false).runOnce().single()
        assertThat(plan.lines.single().expected).isEqualTo(BigDecimal("74.29"))
        verify(txApi, never()).storeTransaction(any())
    }

    @Test
    fun anExistingInterestJournalMakesThePaymentCovered() = runBlocking<Unit> {
        val rows = chargedThenNew + interest("ib", "74.29", "400.00", d(9, 1))
        val plan = runner("23491.66", rows, apply = false).runOnce().single()
        assertThat(plan.lines).isEmpty()
    }
}
