package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapper
import net.djvk.fireflyPlaidConnector2.api.plaid.models.InvestmentTransaction
import net.djvk.fireflyPlaidConnector2.api.plaid.models.InvestmentTransactionSubtype
import net.djvk.fireflyPlaidConnector2.api.plaid.models.InvestmentTransactionType
import net.djvk.fireflyPlaidConnector2.api.plaid.models.InvestmentsTransactionsGetResponse
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.config.AccountConfig
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.lib.FireflyMock
import net.djvk.fireflyPlaidConnector2.lib.createPlaidResponse
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import net.djvk.fireflyPlaidConnector2.transactions.InvestmentTransactionConverter
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate

/**
 * Covers upstream issue #68: investment transactions were never synced because the connector only read
 * /transactions/sync.
 */
internal class InvestmentTransactionsTest {
    private val converter = InvestmentTransactionConverter("America/New_York")

    private fun invTx(
        id: String = "inv1",
        account: String = "brokerage",
        amount: Double,
        type: InvestmentTransactionType = InvestmentTransactionType.buy,
        subtype: InvestmentTransactionSubtype = InvestmentTransactionSubtype.buy,
        quantity: Double = 10.0,
        price: Double = 12.5,
        fees: Double? = 1.0,
    ) = InvestmentTransaction(
        investmentTransactionId = id,
        accountId = account,
        securityId = "sec1",
        date = LocalDate.of(2026, 3, 4),
        name = "BUY ACME CORP",
        quantity = quantity,
        amount = amount,
        price = price,
        fees = fees,
        type = type,
        subtype = subtype,
        isoCurrencyCode = "USD",
        unofficialCurrencyCode = null,
    )

    // region conversion

    @Test
    fun aPurchaseIsAWithdrawalFromTheInvestmentAccount() {
        val dto = converter.convert(invTx(amount = 126.0), 7, "Plaid import")!!

        assertThat(dto.id).isNull()
        assertThat(dto.tx.type).isEqualTo(TransactionTypeProperty.withdrawal)
        assertThat(dto.tx.sourceId).isEqualTo("7")
        assertThat(dto.tx.destinationName).isEqualTo("Investment buy")
        assertThat(dto.tx.amount).isEqualTo("126.0")
        assertThat(dto.tx.description).isEqualTo("BUY ACME CORP")
        assertThat(dto.tx.externalId).isEqualTo("plaid-inv1")
        assertThat(dto.tx.currencyCode).isEqualTo("USD")
        assertThat(dto.tx.tags).containsExactly("plaid-investment-buy", "Plaid import")
        assertThat(dto.tx.notes).isEqualTo("buy (buy); quantity 10.0 @ 12.5; fees 1.0; security sec1")
    }

    @Test
    fun aSaleOrDividendIsADepositIntoTheInvestmentAccount() {
        val dto = converter.convert(
            invTx(
                amount = -250.5, type = InvestmentTransactionType.cash, subtype = InvestmentTransactionSubtype.dividend,
                quantity = 0.0, price = 0.0, fees = null,
            ),
            7,
        )!!

        assertThat(dto.tx.type).isEqualTo(TransactionTypeProperty.deposit)
        assertThat(dto.tx.destinationId).isEqualTo("7")
        assertThat(dto.tx.sourceName).isEqualTo("Investment cash")
        assertThat(dto.tx.amount).isEqualTo("250.5")
        assertThat(dto.tx.tags).containsExactly("plaid-investment-cash", "plaid-investment-dividend")
        assertThat(dto.tx.notes).isEqualTo("cash (dividend); security sec1")
    }

    @Test
    fun zeroAmountTransactionsAreSkipped() {
        assertThat(converter.convert(invTx(amount = 0.0), 7)).isNull()
    }

    @Test
    fun largeAmountsAreNotInScientificNotation() {
        val dto = converter.convert(invTx(amount = 25_000_000.0), 7)!!

        assertThat(dto.tx.amount).doesNotContain("E")
        assertThat(dto.tx.amount).isEqualTo("25000000")
    }

    // endregion

    // region config

    @Test
    fun investmentAccountsAreSplitOutFromTheBankSync() {
        val firefly = FireflyMock()
        val helper = SyncHelper(
            AccountConfigs(
                listOf(
                    AccountConfig(1, "tokBank", "checking"),
                    AccountConfig(2, "tokBank", "savings"),
                    AccountConfig(3, "tokBroker", "brokerage", investment = true),
                )
            ),
            "t", firefly.aboutApi, firefly.transactionsApi, firefly.accountsApi,
        )

        val (accountMap, bank) = helper.getAllPlaidAccessTokenAccountIdSets()

        assertThat(accountMap).containsEntry("brokerage", 3) // still mapped, the converter needs it
        assertThat(bank.toList()).containsExactly(Pair("tokBank", listOf("checking", "savings")))
        assertThat(helper.getInvestmentAccessTokenAccountIdSets().toList())
            .containsExactly(Pair("tokBroker", listOf("brokerage")))
    }

    // endregion

    // region Plaid paging

    @Test
    fun fetchFollowsOffsetPaginationUntilEverythingIsRead() = runBlocking<Unit> {
        val wrapper: PlaidApiWrapper = mock()
        val page1 = mock<InvestmentsTransactionsGetResponse> {
            on { investmentTransactions } doReturn listOf(invTx("a", amount = 1.0), invTx("b", amount = 2.0))
            on { totalInvestmentTransactions } doReturn 3
        }
        val page2 = mock<InvestmentsTransactionsGetResponse> {
            on { investmentTransactions } doReturn listOf(invTx("c", amount = 3.0))
            on { totalInvestmentTransactions } doReturn 3
        }
        var call = 0
        whenever(wrapper.executeRequest<Any>(any(), any(), any())).doSuspendableAnswer {
            createPlaidResponse(if (++call == 1) page1 else page2)
        }

        val txs = PlaidSyncService(wrapper, 2, false)
            .fetchInvestmentTransactions("tok", listOf("brokerage"), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 1))

        assertThat(txs.map { it.investmentTransactionId }).containsExactly("a", "b", "c")
        assertThat(call).isEqualTo(2)
    }

    // endregion

    // region orchestrator

    private val syncHelper: SyncHelper = mock()
    private val plaidSyncService: PlaidSyncService = mock()
    private val fireflyTransactionService: FireflyTransactionService = mock()

    private fun orchestrator(withInvestments: Boolean = true) = PolledSyncOrchestrator(
        30, syncHelper, mock(), plaidSyncService, fireflyTransactionService, mock<TransactionConverter>(),
        investmentConverter = if (withInvestments) converter else null,
    )

    @Test
    fun onlyInvestmentTransactionsNotAlreadyInFireflyAreInserted() = runBlocking<Unit> {
        val known = net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead(
            "transactions", "ff1",
            net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures.getTransaction(externalId = "plaid-old", sourceId = "3"),
            net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink()
        )
        whenever(syncHelper.getInvestmentAccessTokenAccountIdSets())
            .thenReturn(sequenceOf(Pair("tokBroker", listOf("brokerage"))))
        whenever(fireflyTransactionService.fetchExistingFireflyTransactions()).thenReturn(listOf(known))
        whenever(plaidSyncService.fetchInvestmentTransactions(any(), any(), any(), any())).thenReturn(
            listOf(invTx("old", amount = 5.0), invTx("new", amount = 6.0), invTx("elsewhere", account = "other", amount = 7.0))
        )

        orchestrator().syncInvestments(mapOf("brokerage" to 3), LocalDate.of(2026, 3, 10))

        verify(plaidSyncService).fetchInvestmentTransactions(
            eq("tokBroker"), eq(listOf("brokerage")), eq(LocalDate.of(2026, 2, 24)), eq(LocalDate.of(2026, 3, 10))
        )
        val inserted = argumentCaptor<List<FireflyTransactionDto>>()
        verify(syncHelper).optimisticInsertBatchIntoFirefly(inserted.capture())
        assertThat(inserted.firstValue.map { it.tx.externalId }).containsExactly("plaid-new")
    }

    @Test
    fun oneFailingItemDoesNotStopTheOthers() = runBlocking<Unit> {
        whenever(syncHelper.getInvestmentAccessTokenAccountIdSets()).thenReturn(
            sequenceOf(Pair("tokBad", listOf("a")), Pair("tokGood", listOf("b")))
        )
        whenever(fireflyTransactionService.fetchExistingFireflyTransactions()).thenReturn(emptyList())
        whenever(plaidSyncService.fetchInvestmentTransactions(eq("tokBad"), any(), any(), any()))
            .doSuspendableAnswer { throw RuntimeException("PRODUCTS_NOT_SUPPORTED") }
        whenever(plaidSyncService.fetchInvestmentTransactions(eq("tokGood"), any(), any(), any()))
            .thenReturn(listOf(invTx("g1", account = "b", amount = 9.0)))

        orchestrator().syncInvestments(mapOf("a" to 1, "b" to 2))

        val inserted = argumentCaptor<List<FireflyTransactionDto>>()
        verify(syncHelper).optimisticInsertBatchIntoFirefly(inserted.capture())
        assertThat(inserted.firstValue.map { it.tx.externalId }).containsExactly("plaid-g1")
    }

    @Test
    fun withoutAnInvestmentConverterNothingHappens() = runBlocking<Unit> {
        orchestrator(withInvestments = false).syncInvestments(mapOf())

        verify(plaidSyncService, never()).fetchInvestmentTransactions(any(), any(), any(), any())
    }

    // endregion
}
