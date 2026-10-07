package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Account
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountArray
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountTypeFilter
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Meta
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ShortAccountTypeProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionArray
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PageLink
import net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.lib.createFireflyResponse
import net.djvk.fireflyPlaidConnector2.names.CounterpartyNamer
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

/** `syncMode: rehome-names`: grouping and survivor choice (pure) and what apply writes, against an in-memory Firefly. */
internal class RehomeNamesRunnerTest {
    @Suppress("unused")
    private val warmMockUtil = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE

    private val namer = CounterpartyNamer()
    private val converter = TransactionConverter(
        useNameForDestination = true, enablePrimaryCategorization = false, primaryCategoryPrefix = "p-",
        enableDetailedCategorization = false, detailedCategoryPrefix = "d-", timeZoneString = "America/Chicago",
        transferMatchWindowDays = 3, txStyle = TransactionStyleConfig(null),
    )
    private val accountsApi = mock<AccountsApi>()
    private val txApi = mock<TransactionsApi>()
    private val syncHelper = mock<SyncHelper>()

    private val paycheck = listOf(
        "EVOLV CONSULTING",
        "Evolv Consulting Type: Payroll ID: XX2465 CO: Evolv Consulting",
        "AC EVOLV CONSULTING PAYROLL 111000023909729PPD 4260302465",
    )

    private fun acct(id: Int, name: String, journals: Int, type: AccountTypeFilter = AccountTypeFilter.revenue) =
        NamedAccount(id, name, type, journals)

    // region pure plan

    @Test
    fun threeRealPaycheckSpellingsAreOneGroupAndTheBusiestSpellingSurvives() {
        val groups = planNameGroups(paycheck.mapIndexed { i, n -> acct(i + 1, n, if (i == 1) 9 else 2) }, namer)
        assertThat(groups).hasSize(1)
        assertThat(groups[0].canonical).isEqualTo("Evolv Consulting")
        assertThat(groups[0].survivor.id).isEqualTo(2)
        assertThat(groups[0].duplicates.map { it.id }).containsExactly(1, 3)
        assertThat(groups[0].rename).isTrue()
    }

    @Test
    fun aTieOnJournalsGoesToTheLowestId() {
        val groups = planNameGroups(listOf(acct(7, "EVOLV CONSULTING", 3), acct(4, "Evolv Consulting", 3)), namer)
        assertThat(groups.single().survivor.id).isEqualTo(4)
        assertThat(groups.single().rename).isFalse()
    }

    @Test
    fun twoTransfersToTheSameBankAreOneGroup() {
        val groups = planNameGroups(
            listOf(acct(1, "AC DCU XXXX0373W", 1, AccountTypeFilter.expense), acct(2, "AC DCU XXXX1179W", 1, AccountTypeFilter.expense)), namer,
        )
        assertThat(groups).hasSize(1)
    }

    @Test
    fun theSameNameInExpenseAndRevenueIsNotMerged() {
        val groups = planNameGroups(listOf(acct(1, "Target", 1, AccountTypeFilter.expense), acct(2, "TARGET", 1, AccountTypeFilter.revenue)), namer)
        assertThat(groups).isEmpty()
    }

    @Test
    fun cardLinesAndLoanInterestMergeOnlyWhenTheyAreTheSameMerchant() {
        val names = listOf(
            "DBT CRD 0514 DJVU7XEK ADVOCATE PATIENT PAYME DOWNERS GROVE IL C#7221",
            "DBT CRD 1404 DJLLB57N CHECKR PERSO BY CHECKR SAN FRANCISCO CA C#7221",
            "DBT CRD 1738 DJOA7MF5 SUNDAE FUNDAY CROWN PO CROWN POINT IN C#7221",
            "DBT CRD 1822 DJZ9OQLI 07264 - 31ST STREET HA CHICAGO IL C#7221",
            "DBT CRD 1913 DJJXSP49 07264 - 31ST STREET HA CHICAGO IL C#7221",
            "DBT CRD 0613 DJHH1TSK ABC274-CFX WILLOWBROOK IL C#7221",
            "DBT CRD 0305 DJBXFJ60 ABC274-CFX WILLOWBROOK IL C#7221",
            "Interest: DCU Lexus NX loan",
            "Interest: DCU personal loan",
        )
        val groups = planNameGroups(names.mapIndexed { i, n -> acct(i + 1, n, 1, AccountTypeFilter.expense) }, namer)
        assertThat(groups.map { g -> (listOf(g.survivor) + g.duplicates).map { it.id }.sorted() }).containsExactlyInAnyOrder(listOf(4, 5), listOf(6, 7))
    }

    // endregion

    // region apply against an in-memory Firefly

    private class Firefly(val accounts: MutableMap<Int, Pair<String, AccountTypeFilter>>, val journals: MutableMap<Int, MutableList<TransactionRead>>)

    private fun journal(id: String, source: Int?, destination: Int?) = TransactionRead(
        "transactions", id,
        FireflyFixtures.getTransaction(
            type = TransactionTypeProperty.deposit, sourceId = source?.toString(), destinationId = destination?.toString(),
            transactionJournalId = "j$id",
        ), ObjectLink(),
    )

    /** Wires the mocks to [ff]: lists, per-account journals, re-pointing updates and deletes all act on its maps. */
    private fun wire(ff: Firefly, deleted: MutableList<String> = mutableListOf(), onList: (Int) -> Unit = {}) = runBlocking {
        for (type in listOf(AccountTypeFilter.expense, AccountTypeFilter.revenue)) {
            whenever(accountsApi.listAccount(anyOrNull(), anyOrNull(), eq(type))).doSuspendableAnswer {
                val rows = ff.accounts.filter { it.value.second == type }.map { (id, v) ->
                    AccountRead("accounts", id.toString(), Account(v.first, ShortAccountTypeProperty.revenue), ObjectLink())
                }
                createFireflyResponse(AccountArray(rows, Meta()))
            }
        }
        whenever(accountsApi.listTransactionByAccount(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .doSuspendableAnswer {
                val id = it.getArgument<String>(0).toInt()
                onList(id)
                createFireflyResponse(TransactionArray(ff.journals[id].orEmpty().toList(), Meta(), PageLink()))
            }
        whenever(txApi.updateTransaction(any(), any())).doSuspendableAnswer {
            val gid = it.getArgument<String>(0)
            val update = it.getArgument<net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionUpdate>(1)
            val split = update.transactions!!.single()
            val owner = ff.journals.entries.first { e -> e.value.any { j -> j.id == gid } }
            val old = owner.value.first { j -> j.id == gid }
            owner.value.remove(old)
            val to = (split.sourceId ?: split.destinationId)!!.toInt()
            ff.journals.getOrPut(to) { mutableListOf() }.add(old)
            createFireflyResponse(net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSingle(old))
        }
        whenever(accountsApi.deleteAccount(any())).doSuspendableAnswer {
            val id = it.getArgument<String>(0)
            deleted.add(id)
            ff.accounts.remove(id.toInt())
            createFireflyResponse(Unit)
        }
    }

    private fun runner(apply: Boolean) = RehomeNamesRunner(syncHelper, accountsApi, txApi, converter, apply)

    private fun paycheckFirefly() = Firefly(
        mutableMapOf(1 to ("EVOLV CONSULTING" to AccountTypeFilter.revenue), 2 to (paycheck[1] to AccountTypeFilter.revenue), 3 to (paycheck[2] to AccountTypeFilter.revenue)),
        mutableMapOf(1 to mutableListOf(journal("a", 1, 10)), 2 to mutableListOf(journal("b", 2, 10), journal("c", 2, 10)), 3 to mutableListOf(journal("d", 3, 10))),
    )

    @Test
    fun journalsAreRepointedThenTheEmptyDuplicateIsDeletedThenTheSurvivorIsRenamed() = runBlocking<Unit> {
        val ff = paycheckFirefly()
        wire(ff)
        runner(true).applyPlan(runner(true).readPlan())

        val order = inOrder(txApi, accountsApi)
        order.verify(txApi).updateTransaction(eq("a"), any())
        order.verify(accountsApi).deleteAccount("1")
        order.verify(txApi).updateTransaction(eq("d"), any())
        order.verify(accountsApi).deleteAccount("3")
        order.verify(accountsApi).renameAccount(eq("2"), any())
        assertThat(ff.journals[2]!!.map { it.id }).containsExactlyInAnyOrder("a", "b", "c", "d")
    }

    @Test
    fun aDuplicateThatGainsAJournalAfterThePlanIsNotDeleted() = runBlocking<Unit> {
        val ff = paycheckFirefly()
        val deleted = mutableListOf<String>()
        var listedOne = 0
        // a new journal lands in account 1 right after its journals were read for re-pointing
        wire(ff, deleted) { id -> if (id == 1 && ++listedOne == 3) ff.journals[1]!!.add(journal("late", 1, 10)) }
        runner(true).applyPlan(runner(true).readPlan())
        assertThat(deleted).containsExactly("3")
        assertThat(ff.journals[1]!!.map { it.id }).containsExactly("late")
    }

    @Test
    fun aDryRunMakesNoWrites() {
        wire(paycheckFirefly())
        runner(false).run()
        runBlocking {
            verify(txApi, never()).updateTransaction(any(), any())
            verify(accountsApi, never()).deleteAccount(any())
            verify(accountsApi, never()).renameAccount(any(), any())
        }
    }

    @Test
    fun theSecondRunFindsNothing() {
        val ff = paycheckFirefly()
        wire(ff)
        runner(true).run()
        assertThat(ff.accounts).hasSize(1)
        assertThat(runBlocking { runner(true).readPlan() }).isEmpty()
    }

    @Test
    fun applyFailsWhenAGroupIsLeft() {
        val ff = paycheckFirefly()
        wire(ff)
        // an account the fake refuses to delete: every delete leaves it in place
        runBlocking { whenever(accountsApi.deleteAccount(any())).doSuspendableAnswer { createFireflyResponse(Unit) } }
        assertThatThrownBy { runner(true).run() }.hasMessageContaining("groups of duplicate accounts are left")
    }

    // endregion
}
