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
import java.math.BigDecimal

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
    private fun opening(account: AccountRead): Double = runBlocking {
        val plaid = PlaidMock()
        val firefly = FireflyMock()
        val balance = mock<AccountBalance>()
        whenever(balance.current).thenReturn(owed)
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
        val txs = listOf(
            PlaidFixtures.getPaymentTransaction(accountId = plaidAccount, transactionId = "t1", amount = purchase, pendingTransactionId = null),
            PlaidFixtures.getPaymentTransaction(accountId = plaidAccount, transactionId = "t2", amount = payment, pendingTransactionId = null),
        )
        runner.setInitialBalances(mapOf("token" to txs), helper, java.time.LocalDate.now().minusDays(30))

        val captor = argumentCaptor<List<FireflyTransactionDto>>()
        org.mockito.kotlin.verify(helper).optimisticInsertBatchIntoFirefly(captor.capture())
        val split = captor.firstValue.single().tx
        val amount = split.amount.toDouble()
        if (split.type == TransactionTypeProperty.deposit) amount else -amount
    }

    /** The balance Firefly ends at: the opening journal plus the imported transactions (Plaid out = Firefly minus). */
    private fun endBalance(opening: Double) = opening - (purchase + payment)

    @Test
    fun aDebitLiabilityEndsAtMinusWhatIsOwed() {
        val end = endBalance(opening(accountRead(ShortAccountTypeProperty.liabilities, LiabilityDirection.debit)))
        assertThat(end).isEqualTo(-owed)
    }

    @Test
    fun aCreditDirectionLiabilityEndsAtPlusWhatIsOwed() {
        val end = endBalance(opening(accountRead(ShortAccountTypeProperty.liabilities, LiabilityDirection.credit)))
        assertThat(end).isEqualTo(owed)
    }

    @Test
    fun anAssetIsUnchanged() {
        val end = endBalance(opening(accountRead(ShortAccountTypeProperty.asset)))
        assertThat(end).isEqualTo(owed)
    }

    @Test
    fun aCreditCardAssetStillEndsNegative() {
        val end = endBalance(opening(accountRead(ShortAccountTypeProperty.asset, role = AccountRoleProperty.ccAsset)))
        assertThat(end).isEqualTo(-owed)
    }

    @Test
    fun theRebalancePlanChangesOnlyTheOpeningByTheGap() {
        // Firefly shows +23,817.37 for a debit loan whose opening is +23,817.37 and which has no transactions
        val plan = RebalancePlan(2, "Lexus", BigDecimal("23817.37"), BigDecimal("-23817.37"), BigDecimal("23817.37"), listOf("g1"), null)
        assertThat(plan.delta).isEqualByComparingTo("-47634.74")
        assertThat(plan.newOpening).isEqualByComparingTo("-23817.37")
    }
}
