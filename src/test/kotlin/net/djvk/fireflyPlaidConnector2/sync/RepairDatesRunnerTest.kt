package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Account
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountArray
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountSingle
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountTypeFilter
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Meta
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PageLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ShortAccountTypeProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Transaction
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionArray
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeFilter
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountsBalanceGetRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsGetRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsGetResponse
import net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.lib.PlaidFixtures
import net.djvk.fireflyPlaidConnector2.lib.PlaidMock
import net.djvk.fireflyPlaidConnector2.lib.createFireflyResponse
import net.djvk.fireflyPlaidConnector2.lib.createPlaidResponse
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate

/** The I/O side of `syncMode: repair-dates`: what apply writes and in which order, the dry run, the reads. */
internal class RepairDatesRunnerTest {
    @Suppress("unused")
    private val warmMockUtil = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE

    private val converter = TransactionConverter(
        useNameForDestination = true, enablePrimaryCategorization = false, primaryCategoryPrefix = "p-",
        enableDetailedCategorization = false, detailedCategoryPrefix = "d-", timeZoneString = "America/Chicago",
        transferMatchWindowDays = 3, txStyle = TransactionStyleConfig(null),
    )
    private val plaid = PlaidMock()
    private val accountsApi = mock<AccountsApi>()
    private val txApi = mock<TransactionsApi>()
    private val syncHelper = mock<SyncHelper>()
    private val service = mock<FireflyTransactionService>()

    private fun runner(apply: Boolean = false, batch: Int = 100) = RepairDatesRunner(
        syncHelper, plaid.wrapper, accountsApi, txApi, service, converter, "America/Chicago", batch, apply, 800, "",
    )

    private fun fix(old: String?, new: String, legacy: List<String> = listOf(), direction: String? = null) = OpeningFix(
        2, "Lexus", old?.let { BigDecimal(it) }, null, BigDecimal.ZERO, legacy, BigDecimal(new), LocalDate.of(2025, 3, 31), direction,
    )

    // region apply

    /** What Firefly answers when the account is read back after the opening was set. */
    private fun storedOpening(amount: String?, date: LocalDate?) = runBlocking {
        val account = AccountRead(
            "accounts", "2",
            Account("Lexus", ShortAccountTypeProperty.liabilities, openingBalance = amount,
                openingBalanceDate = date?.atStartOfDay()?.atOffset(java.time.ZoneOffset.ofHours(-6))),
            ObjectLink(),
        )
        val response = createFireflyResponse(AccountSingle(account))
        whenever(accountsApi.getAccount(any(), anyOrNull())).thenReturn(response)
    }

    private fun noExpenseAccounts() = runBlocking {
        val none = createFireflyResponse(AccountArray(listOf(), Meta()))
        whenever(accountsApi.listAccount(anyOrNull(), anyOrNull(), eq(AccountTypeFilter.expense))).thenReturn(none)
    }

    @Test
    fun theOpeningIsSetBeforeTheLegacyJournalIsDeleted() = runBlocking<Unit> {
        noExpenseAccounts()
        storedOpening("33051.60", LocalDate.of(2025, 3, 31))
        runner().applyPlan(RepairPlan(listOf(), 0, listOf(fix(null, "33051.60", listOf("g9"), "debit"))))

        val order = inOrder(accountsApi, syncHelper)
        order.verify(accountsApi).setOpeningBalance("2", "33051.60", LocalDate.of(2025, 3, 31), "debit")
        order.verify(syncHelper).deleteBatchInFirefly(listOf("g9"))
    }

    @Test
    fun aZeroOpeningClearsTheStaleOneInsteadOfSendingZero() = runBlocking<Unit> {
        noExpenseAccounts()
        storedOpening(null, null)
        runner().applyPlan(RepairPlan(listOf(), 0, listOf(fix("500.00", "0.00", listOf("g9")))))

        verify(accountsApi).clearOpeningBalance("2")
        verify(accountsApi, never()).setOpeningBalance(any(), any(), any(), anyOrNull())
        verify(syncHelper).deleteBatchInFirefly(listOf("g9"))
    }

    @Test
    fun anEmptyInitialBalanceExpenseAccountIsDeletedAndANonEmptyOneIsNot() = runBlocking<Unit> {
        val expense = AccountRead("accounts", "77", Account("Initial Balance", ShortAccountTypeProperty.expense), ObjectLink())
        val list = createFireflyResponse(AccountArray(listOf(expense), Meta()))
        whenever(accountsApi.listAccount(anyOrNull(), anyOrNull(), eq(AccountTypeFilter.expense))).thenReturn(list)
        val empty = createFireflyResponse(TransactionArray(listOf(), Meta(), PageLink()))
        whenever(accountsApi.listTransactionByAccount(eq("77"), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), eq(TransactionTypeFilter.all))).thenReturn(empty)
        runner().deleteOrphanInitialBalanceAccount()
        verify(accountsApi).deleteAccount("77")

        val left = createFireflyResponse(
            TransactionArray(listOf(TransactionRead("transactions", "5", Transaction(listOf(FireflyFixtures.getTransaction().transactions.first())), ObjectLink())), Meta(), PageLink())
        )
        whenever(accountsApi.listTransactionByAccount(eq("77"), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), eq(TransactionTypeFilter.all))).thenReturn(left)
        org.mockito.kotlin.clearInvocations(accountsApi)
        runner().deleteOrphanInitialBalanceAccount()
        verify(accountsApi, never()).deleteAccount(any())
    }

    // endregion

    // region the reads and the dry run

    private fun emptyFirefly() = runBlocking {
        whenever(service.fetchFireflyTransactionsStrictly(any(), any(), any())).thenReturn(listOf())
        whenever(syncHelper.fetchAccountKinds()).thenReturn(mapOf())
        whenever(syncHelper.getAllPlaidAccessTokenAccountIdSets()).thenReturn(Pair(mapOf(), sequenceOf()))
    }

    @Test
    fun theDryRunWritesNothing() = runBlocking<Unit> {
        emptyFirefly()
        val legacy = TransactionRead(
            "transactions", "g9",
            FireflyFixtures.getTransaction(
                type = net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty.deposit, amount = "10.00",
                destinationId = "2", description = "DCU statement opening balance", externalId = "dcu-stmt:opening:2",
            ), ObjectLink(),
        )
        whenever(service.fetchFireflyTransactionsStrictly(any(), any(), any())).thenReturn(listOf(legacy))
        val account = createFireflyResponse(AccountSingle(AccountRead("accounts", "2", Account("Lexus", ShortAccountTypeProperty.liabilities), ObjectLink())))
        whenever(accountsApi.getAccount(any(), anyOrNull())).thenReturn(account)

        runner(apply = false).run()

        verify(accountsApi, never()).setOpeningBalance(any(), any(), any(), anyOrNull())
        verify(accountsApi, never()).clearOpeningBalance(any())
        verify(syncHelper, never()).deleteBatchInFirefly(any())
        verify(txApi, never()).updateTransaction(any(), any())
    }

    @Test
    fun aFailedAccountReadAbortsTheReadInsteadOfDefaultingToAnAsset() {
        emptyFirefly()
        val journal = TransactionRead(
            "transactions", "g1",
            FireflyFixtures.getTransaction(
                type = net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty.deposit, destinationId = "4",
                description = "DCU statement opening balance",
            ), ObjectLink(),
        )
        runBlocking { whenever(service.fetchFireflyTransactionsStrictly(any(), any(), any())).thenReturn(listOf(journal)) }
        runBlocking { whenever(accountsApi.getAccount(any(), anyOrNull())).doSuspendableAnswer { throw IllegalStateException("Firefly 503") } }

        val failure = runCatching { runBlocking { runner().readState() } }.exceptionOrNull()

        assertThat(failure).hasMessageContaining("503")
    }

    @Test
    fun plaidIsReadToTheEndByTotalEvenWhenAPageIsShort() = runBlocking<Unit> {
        emptyFirefly()
        val acct = "a".repeat(37)
        whenever(syncHelper.getAllPlaidAccessTokenAccountIdSets()).thenReturn(Pair(mapOf(acct to 1), sequenceOf("token" to listOf(acct))))
        fun tx(id: String) = PlaidFixtures.getPaymentTransaction(accountId = acct, transactionId = id, pendingTransactionId = null)
        // page size 100, but Plaid answers 1 transaction per call, with 3 in total
        whenever(plaid.api.transactionsGet(any<TransactionsGetRequest>())).doSuspendableAnswer {
            val offset = it.getArgument<TransactionsGetRequest>(0).options!!.offset!!
            val response = mock<TransactionsGetResponse>()
            whenever(response.transactions).thenReturn(listOf(tx("t$offset")))
            whenever(response.totalTransactions).thenReturn(3)
            createPlaidResponse(response)
        }
        val balances = mock<net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountsGetResponse>()
        whenever(balances.accounts).thenReturn(listOf())
        val balanceResponse = createPlaidResponse(balances)
        whenever(plaid.api.accountsBalanceGet(any<AccountsBalanceGetRequest>())).thenReturn(balanceResponse)

        val (input, _) = runner(batch = 100).readState()

        assertThat(input.plaidTxs.keys).containsExactlyInAnyOrder("t0", "t1", "t2")
    }

    // endregion

    // region the request body

    @Test
    fun theOpeningRequestCarriesLiabilityDirectionForLiabilitiesOnly() = runBlocking<Unit> {
        val seen = mutableListOf<Triple<HttpMethod, String, String>>()
        val api = AccountsApi("http://firefly.test", MockEngine { request ->
            seen.add(Triple(request.method, request.url.encodedPath, request.body.toByteArray().toString(Charsets.UTF_8).replace(Regex("\\s"), "")))
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })

        api.setOpeningBalance("2", "33051.60", LocalDate.of(2025, 4, 1), "debit")
        api.setOpeningBalance("1", "510.00", LocalDate.of(2024, 12, 5), null)
        api.clearOpeningBalance("1")

        assertThat(seen.map { it.first }).containsOnly(HttpMethod.Put)
        assertThat(seen[0].second).isEqualTo("/api/v1/accounts/2")
        assertThat(seen[0].third).contains(""""opening_balance":"33051.60"""", """"opening_balance_date":"2025-04-01"""", """"liability_direction":"debit"""")
        assertThat(seen[1].third).doesNotContain("liability_direction")
        assertThat(seen[2].third).contains(""""opening_balance":""""", """"opening_balance_date":""""")
    }

    @Test
    fun theLegacyJournalsAreNotDeletedWhenFireflyDidNotStoreTheOpening() = runBlocking<Unit> {
        noExpenseAccounts()
        storedOpening("-1.00", LocalDate.of(2025, 3, 31)) // the PUT answered 2xx but the account still holds something else
        val failure = runCatching {
            runner().applyPlan(RepairPlan(listOf(), 0, listOf(fix(null, "33051.60", listOf("g9"), "debit"))))
        }.exceptionOrNull()

        assertThat(failure).hasMessageContaining("did not store")
        verify(syncHelper, never()).deleteBatchInFirefly(any())
    }

    @Test
    fun aPlaidReadThatEndsOnAnEmptyPageBeforeTheTotalFails() {
        emptyFirefly()
        val acct = "a".repeat(37)
        runBlocking { whenever(syncHelper.getAllPlaidAccessTokenAccountIdSets()).thenReturn(Pair(mapOf(acct to 1), sequenceOf("token" to listOf(acct)))) }
        runBlocking {
            val balances = mock<net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountsGetResponse>()
            whenever(balances.accounts).thenReturn(listOf())
            val balanceResponse = createPlaidResponse(balances)
            whenever(plaid.api.accountsBalanceGet(any<AccountsBalanceGetRequest>())).thenReturn(balanceResponse)
            whenever(plaid.api.transactionsGet(any<TransactionsGetRequest>())).doSuspendableAnswer {
                val response = mock<TransactionsGetResponse>()
                whenever(response.transactions).thenReturn(listOf()) // empty, though 5 are promised
                whenever(response.totalTransactions).thenReturn(5)
                createPlaidResponse(response)
            }
        }

        val failure = runCatching { runBlocking { runner().readState() } }.exceptionOrNull()

        assertThat(failure).hasMessageContaining("empty page")
    }

    @Test
    fun aPlaidTransactionCountThatChangesDuringTheReadAbortsIt() {
        emptyFirefly()
        val acct = "a".repeat(37)
        runBlocking { whenever(syncHelper.getAllPlaidAccessTokenAccountIdSets()).thenReturn(Pair(mapOf(acct to 1), sequenceOf("token" to listOf(acct)))) }
        runBlocking {
            val balances = mock<net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountsGetResponse>()
            whenever(balances.accounts).thenReturn(listOf())
            val balanceResponse = createPlaidResponse(balances)
            whenever(plaid.api.accountsBalanceGet(any<AccountsBalanceGetRequest>())).thenReturn(balanceResponse)
            val calls = java.util.concurrent.atomic.AtomicInteger(0)
            whenever(plaid.api.transactionsGet(any<TransactionsGetRequest>())).doSuspendableAnswer {
                val n = calls.incrementAndGet()
                val response = mock<TransactionsGetResponse>()
                val one = PlaidFixtures.getPaymentTransaction(accountId = acct, transactionId = "t1", pendingTransactionId = null)
                whenever(response.transactions).thenReturn(if (n == 1) listOf(one) else listOf())
                whenever(response.totalTransactions).thenReturn(if (n == 1) 1 else 2) // a posting arrived in between
                createPlaidResponse(response)
            }
        }

        val failure = runCatching { runBlocking { runner().readState() } }.exceptionOrNull()

        assertThat(failure).hasMessageContaining("changed while")
    }

    @Test
    fun aWrongSignEchoedForALiabilityStopsBeforeAnyDelete() = runBlocking<Unit> {
        noExpenseAccounts()
        storedOpening("33051.60", LocalDate.of(2025, 3, 31)) // sent -33051.60 for a debit loan, Firefly holds the opposite sign
        val failure = runCatching {
            runner().applyPlan(RepairPlan(listOf(), 0, listOf(fix(null, "-33051.60", listOf("g9"), "debit"))))
        }.exceptionOrNull()
        assertThat(failure).hasMessageContaining("did not store")
        verify(syncHelper, never()).deleteBatchInFirefly(any())
    }

    @Test
    fun theSignFireflyEchoesForADebitLoanIsAcceptedWhenItMatches() = runBlocking<Unit> {
        noExpenseAccounts()
        storedOpening("-33051.60", LocalDate.of(2025, 3, 31))
        runner().applyPlan(RepairPlan(listOf(), 0, listOf(fix(null, "-33051.60", listOf("g9"), "debit"))))
        verify(syncHelper).deleteBatchInFirefly(listOf("g9"))
    }

    // endregion
}
