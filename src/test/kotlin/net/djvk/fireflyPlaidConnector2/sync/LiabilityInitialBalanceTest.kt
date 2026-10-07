package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Account
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountRoleProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountSingle
import net.djvk.fireflyPlaidConnector2.api.firefly.models.LiabilityDirection
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ShortAccountTypeProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountBalance
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountBase
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountsBalanceGetRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountsGetResponse
import net.djvk.fireflyPlaidConnector2.config.AccountConfig
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.lib.FireflyMock
import net.djvk.fireflyPlaidConnector2.lib.PlaidFixtures
import net.djvk.fireflyPlaidConnector2.lib.PlaidMock
import net.djvk.fireflyPlaidConnector2.lib.createFireflyResponse
import net.djvk.fireflyPlaidConnector2.lib.createPlaidResponse
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Firefly shows what a debit liability owes as a NEGATIVE balance; Plaid reports `current` as the positive amount owed. */
internal class LiabilityInitialBalanceTest {
    @Suppress("unused")
    private val warmMockUtil = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE

    private val plaidAccount = "a".repeat(37)
    private val purchase = 100.0   // Plaid: money out is positive
    private val payment = -30.0
    private val owed = 250.0

    private fun accountRead(type: ShortAccountTypeProperty, direction: LiabilityDirection? = null, role: AccountRoleProperty? = null) =
        AccountRead("accounts", "1", Account("acct", type, accountRole = role, liabilityDirection = direction), ObjectLink())

    /** Runs the batch initial-balance step for one account and returns the opening journal as a signed Firefly effect. */
    private fun opening(account: AccountRead, extra: List<net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction> = listOf(), noOwnTransactions: Boolean = false, current: Double = owed): Pair<Double, java.time.LocalDate>? = runBlocking {
        val plaid = PlaidMock()
        val firefly = FireflyMock()
        val balance = mock<AccountBalance>()
        whenever(balance.current).thenReturn(current)
        val base = mock<AccountBase>()
        whenever(base.accountId).thenReturn(plaidAccount)
        whenever(base.balances).thenReturn(balance)
        val response = mock<AccountsGetResponse>()
        whenever(response.accounts).thenReturn(listOf(base))
        val helper = mock<SyncHelper>()
        whenever(helper.getAllPlaidAccessTokenAccountIdSets()).thenReturn(
            Pair(mapOf(plaidAccount to 1), sequenceOf(Pair("token", listOf(plaidAccount))))
        )
        val balanceResponse = createPlaidResponse(response)
        whenever(plaid.api.accountsBalanceGet(any<AccountsBalanceGetRequest>())).thenReturn(balanceResponse)
        val accountResponse = createFireflyResponse(AccountSingle(account))
        whenever(firefly.accountsApi.getAccount(any(), anyOrNull())).thenReturn(accountResponse)
        val runner = BatchSyncRunnerTest.createRunner(plaid, firefly, setInitialBalance = true, syncHelper = helper)
        val day = java.time.LocalDate.of(2025, 1, 10)
        val txs = (listOf(
            PlaidFixtures.getPaymentTransaction(accountId = plaidAccount, transactionId = "t1", amount = purchase, pendingTransactionId = null, date = day),
            PlaidFixtures.getPaymentTransaction(accountId = plaidAccount, transactionId = "t2", amount = payment, pendingTransactionId = null, date = day.plusDays(2)),
        ) + extra).let { all -> if (noOwnTransactions) all.map { it.copy(accountId = "b".repeat(37)) } else all }
        runner.setInitialBalances(mapOf("token" to txs), helper, day.minusDays(30))
        if (noOwnTransactions && current == 0.0) {
            org.mockito.kotlin.verify(firefly.accountsApi, org.mockito.kotlin.never()).setOpeningBalance(any(), any(), any(), anyOrNull())
            return@runBlocking null
        }

        val amount = argumentCaptor<String>()
        val date = argumentCaptor<java.time.LocalDate>()
        org.mockito.kotlin.verify(firefly.accountsApi).setOpeningBalance(org.mockito.kotlin.eq("1"), amount.capture(), date.capture(), anyOrNull())
        amount.firstValue.toDouble() to date.firstValue
    }

    /** The balance Firefly ends at: the opening journal plus the imported transactions (Plaid out = Firefly minus). */
    private fun endBalance(opening: Double) = opening - (purchase + payment)

    @Test
    fun aDebitLiabilityEndsAtMinusWhatIsOwed() {
        val end = endBalance(opening(accountRead(ShortAccountTypeProperty.liabilities, LiabilityDirection.debit))!!.first)
        assertThat(end).isEqualTo(-owed)
    }

    @Test
    fun aCreditDirectionLiabilityEndsAtPlusWhatIsOwed() {
        val end = endBalance(opening(accountRead(ShortAccountTypeProperty.liabilities, LiabilityDirection.credit))!!.first)
        assertThat(end).isEqualTo(owed)
    }

    @Test
    fun anAssetIsUnchanged() {
        val end = endBalance(opening(accountRead(ShortAccountTypeProperty.asset))!!.first)
        assertThat(end).isEqualTo(owed)
    }

    @Test
    fun aCreditCardAssetStillEndsNegative() {
        val end = endBalance(opening(accountRead(ShortAccountTypeProperty.asset, role = AccountRoleProperty.ccAsset))!!.first)
        assertThat(end).isEqualTo(-owed)
    }

    @Test
    fun theOpeningIsDatedTheDayBeforeTheFirstTransactionNotOnImportDay() {
        val (_, date) = opening(accountRead(ShortAccountTypeProperty.asset))!!
        assertThat(date).isEqualTo(java.time.LocalDate.of(2025, 1, 9))
    }

    @Test
    fun aPendingItemInPlaidsCurrentIsBackedOutOfThePostedAnchor() {
        val pending = PlaidFixtures.getPaymentTransaction(
            accountId = plaidAccount, transactionId = "p1", amount = 36.0, pending = true, pendingTransactionId = null,
            date = java.time.LocalDate.of(2025, 1, 12),
        )
        val txs = listOf(
            PlaidFixtures.getPaymentTransaction(accountId = plaidAccount, transactionId = "t1", amount = purchase, pendingTransactionId = null),
            pending,
        )
        val o = BatchSyncRunner.openingFor(txs, owed, owedIsNegative = false)
        // Plaid's current (250) includes the pending 36 spent, so the posted balance is 286; opening = 286 + posted 100
        assertThat(o.postedAnchor.toDouble()).isEqualTo(286.0)
        assertThat(o.amount.toDouble()).isEqualTo(386.0)
        // and the account still ends at Plaid's current with the pending item imported: 386 - 136
        assertThat(o.amount.toDouble() - 136.0).isEqualTo(owed)
    }

    @Test
    fun anAccountWithNoTransactionsOpensWhenTheImportedHistoryStarts() {
        val (_, date) = opening(accountRead(ShortAccountTypeProperty.asset), noOwnTransactions = true)!!
        assertThat(date).isEqualTo(java.time.LocalDate.of(2025, 1, 9))
    }

    @Test
    fun aZeroBalanceAccountWithNoHistoryGetsNoOpening() {
        assertThat(opening(accountRead(ShortAccountTypeProperty.asset), noOwnTransactions = true, current = 0.0)).isNull()
    }
}
