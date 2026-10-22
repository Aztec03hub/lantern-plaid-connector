package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.RecurrencesApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.RulesApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Recurrence
import net.djvk.fireflyPlaidConnector2.api.firefly.models.RecurrenceArray
import net.djvk.fireflyPlaidConnector2.api.firefly.models.RecurrenceRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.RecurrenceTransaction
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Rule
import net.djvk.fireflyPlaidConnector2.api.firefly.models.RuleAction
import net.djvk.fireflyPlaidConnector2.api.firefly.models.RuleActionKeyword
import net.djvk.fireflyPlaidConnector2.api.firefly.models.RuleArray
import net.djvk.fireflyPlaidConnector2.api.firefly.models.RuleRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.RuleTrigger
import net.djvk.fireflyPlaidConnector2.api.firefly.models.RuleTriggerKeyword
import net.djvk.fireflyPlaidConnector2.api.firefly.models.RuleTriggerType
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
import org.junit.jupiter.api.io.TempDir
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
        txStyle = TransactionStyleConfig(null),
    )
    private val accountsApi = mock<AccountsApi>()
    private val txApi = mock<TransactionsApi>()
    private val syncHelper = mock<SyncHelper>()
    private val rulesApi = mock<RulesApi>()
    private val recurrencesApi = mock<RecurrencesApi>()
    private var rules = listOf<RuleRead>()
    private var recurrences = listOf<RecurrenceRead>()

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
        assertThat(groups.filter { it.duplicates.isNotEmpty() }).isEmpty()  // two rename-only groups, no merge
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
        assertThat(groups.filter { it.duplicates.isNotEmpty() }.map { g -> (listOf(g.survivor) + g.duplicates).map { it.id }.sorted() }).containsExactlyInAnyOrder(listOf(4, 5), listOf(6, 7))
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
        whenever(rulesApi.listRule(anyOrNull())).doSuspendableAnswer { createFireflyResponse(RuleArray(rules, Meta(), PageLink())) }
        whenever(recurrencesApi.listRecurrence(anyOrNull())).doSuspendableAnswer { createFireflyResponse(RecurrenceArray(recurrences, Meta(), PageLink())) }
        whenever(accountsApi.renameAccount(any(), any())).doSuspendableAnswer {
            val id = it.getArgument<String>(0).toInt()
            ff.accounts[id] = it.getArgument<String>(1) to ff.accounts.getValue(id).second
            Unit
        }
        whenever(accountsApi.deleteAccount(any())).doSuspendableAnswer {
            val id = it.getArgument<String>(0)
            deleted.add(id)
            ff.accounts.remove(id.toInt())
            createFireflyResponse(Unit)
        }
    }

    @TempDir lateinit var dir: java.io.File

    private fun runner(apply: Boolean) = RehomeNamesRunner(syncHelper, accountsApi, txApi, converter, rulesApi, recurrencesApi, apply, dir.path)

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
        // duplicate 1 is spelled like the display name (any case), so it must go first and the rename follows its delete
        order.verify(txApi).updateTransaction(eq("a"), any())
        order.verify(accountsApi).deleteAccount("1")
        order.verify(accountsApi).renameAccount(eq("2"), any())
        order.verify(txApi).updateTransaction(eq("d"), any())
        order.verify(accountsApi).deleteAccount("3")
        assertThat(ff.journals[2]!!.map { it.id }).containsExactlyInAnyOrder("a", "b", "c", "d")
    }

    @Test
    fun theSurvivorIsRenamedBeforeAnyJournalMovesWhenNoDuplicateHoldsTheName() = runBlocking<Unit> {  // held rename gap
        val ff = Firefly(
            mutableMapOf(2 to (paycheck[1] to AccountTypeFilter.revenue), 3 to (paycheck[2] to AccountTypeFilter.revenue)),
            mutableMapOf(2 to mutableListOf(journal("b", 2, 10), journal("c", 2, 10)), 3 to mutableListOf(journal("d", 3, 10))),
        )
        wire(ff)
        runner(true).applyPlan(runner(true).readPlan())
        val order = inOrder(txApi, accountsApi)
        order.verify(accountsApi).renameAccount(eq("2"), eq("Evolv Consulting"))
        order.verify(txApi).updateTransaction(eq("d"), any())
        order.verify(accountsApi).deleteAccount("3")
        assertThat(ff.accounts.values.map { it.first }).containsExactly("Evolv Consulting")
    }

    @Test
    fun aRenameThatFailedAfterTheMergeIsPlannedAgainAsAnAloneAccount() = runBlocking<Unit> {  // held rename gap
        val ff = paycheckFirefly()
        wire(ff)
        whenever(accountsApi.renameAccount(any(), any())).doSuspendableAnswer { error("422 rename") }
        assertThatThrownBy { runBlocking { runner(true).applyPlan(runner(true).readPlan()) } }.hasMessageContaining("422 rename")
        val again = runner(true).readPlan()
        assertThat(again).hasSize(1)
        assertThat(again[0].duplicates.map { it.id }).containsExactly(3)  // the run stopped before duplicate 3
        // finish what is left, then the survivor is alone and still misnamed: the plan must still list it
        whenever(accountsApi.renameAccount(any(), any())).doSuspendableAnswer { Unit }
        ff.accounts[2] = paycheck[1] to AccountTypeFilter.revenue
        ff.accounts.remove(3)
        ff.accounts.remove(1)
        val alone = runner(true).readPlan().single()
        assertThat(alone.duplicates).isEmpty()
        assertThat(alone.rename).isTrue()
    }

    @Test
    fun everyJournalMoveAndDeleteIsLoggedBeforeItHappens() = runBlocking<Unit> {  // M4
        val ff = paycheckFirefly()
        wire(ff)
        runner(true).applyPlan(runner(true).readPlan())
        val lines = java.io.File(dir, "rehome_names_log.jsonl").readLines()
        assertThat(lines.count { it.contains("\"op\":\"repoint\"") }).isEqualTo(2)
        assertThat(lines.filter { it.contains("\"op\":\"delete\"") }).hasSize(2)
        assertThat(lines.first { it.contains("\"journal\":\"a\"") }).contains("\"oldAccount\":\"1\"").contains("\"oldName\":\"EVOLV CONSULTING\"").contains("\"newAccount\":\"2\"")
        assertThat(lines.first { it.contains("\"op\":\"delete\"") }).contains("\"name\":\"EVOLV CONSULTING\"")
        assertThat(lines.count { it.contains("\"op\":\"rename\"") }).isEqualTo(1)
    }

    @Test
    fun aDryRunWritesNoLog() {
        wire(paycheckFirefly())
        runner(false).run()
        assertThat(java.io.File(dir, "rehome_names_log.jsonl")).doesNotExist()
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

    // region rules and recurrences keep a duplicate alive

    private fun rule(title: String, trigger: Pair<RuleTriggerKeyword, String>? = null, action: Pair<RuleActionKeyword, String>? = null) = RuleRead(
        "rules", title, Rule(
            title, "1", RuleTriggerType.storeMinusJournal,
            listOfNotNull(trigger?.let { RuleTrigger(it.first, it.second) }),
            listOfNotNull(action?.let { RuleAction(it.first, it.second) }),
        ), ObjectLink(),
    )

    private fun recurrence(id: String, source: String? = null, destination: String? = null) = RecurrenceRead(
        "recurrences", id, Recurrence(transactions = listOf(RecurrenceTransaction("d", "1.00", sourceId = source, destinationId = destination))), ObjectLink(),
    )

    private fun log() = java.io.File(dir, "rehome_names_log.jsonl").readLines()

    private fun applyBlocked(): MutableList<String> {
        val deleted = mutableListOf<String>()
        wire(paycheckFirefly(), deleted)
        runBlocking { runner(true).applyPlan(runner(true).readPlan()) }
        return deleted
    }

    @Test
    fun aRuleThatNamesTheDuplicateKeepsItFromTheDelete() {
        rules = listOf(rule("Paycheck", trigger = RuleTriggerKeyword.fromAccountIs to "evolv consulting"))  // duplicate 1 is EVOLV CONSULTING
        val deleted = applyBlocked()
        assertThat(deleted).containsExactly("3")
        assertThat(log().filter { it.contains("\"op\":\"delete-skipped\"") }.single()).contains("rule \\\"Paycheck\\\"").contains("\"account\":\"1\"")
    }

    @Test
    fun aRuleActionNamingTheDuplicateAlsoBlocks() {
        rules = listOf(rule("Route", action = RuleActionKeyword.setDestinationAccount to "Evolv Consulting"))
        assertThat(applyBlocked()).containsExactly("3")
    }

    @Test
    fun aStartsWithTriggerMatchesByPrefixAndAnUnrelatedRuleDoesNot() {
        rules = listOf(
            rule("Prefix", trigger = RuleTriggerKeyword.fromAccountStarts to "AC EVOLV"),
            rule("Other", trigger = RuleTriggerKeyword.fromAccountIs to "Some Other Payee"),
            rule("Not account", trigger = RuleTriggerKeyword.descriptionIs to "EVOLV CONSULTING"),
        )
        assertThat(applyBlocked()).containsExactly("1")  // only duplicate 3 starts with the prefix
    }

    @Test
    fun containsEndsAndSourceActionAndToTriggersAllBlock() {
        rules = listOf(rule("C", trigger = RuleTriggerKeyword.toAccountContains to "volv cons"))
        assertThat(applyBlocked()).isEmpty()
        rules = listOf(rule("E", trigger = RuleTriggerKeyword.fromAccountEnds to "ULTING"))
        assertThat(applyBlocked()).containsExactly("3")
        rules = listOf(rule("S", action = RuleActionKeyword.setSourceAccount to "evolv consulting"))
        assertThat(applyBlocked()).containsExactly("3")
    }

    @Test
    fun aRecurrenceUsingTheDuplicateByIdBlocks() {
        recurrences = listOf(recurrence("r1", destination = "1"), recurrence("r2", source = "3"), recurrence("r3", destination = "99"))
        assertThat(applyBlocked()).isEmpty()
        recurrences = listOf(recurrence("r3", destination = "99"))
        assertThat(applyBlocked()).containsExactlyInAnyOrder("1", "3")
    }

    @Test
    fun aDryRunListsTheBlockedDuplicateAndWritesNothing() {
        rules = listOf(rule("Paycheck", trigger = RuleTriggerKeyword.fromAccountIs to "EVOLV CONSULTING"))
        wire(paycheckFirefly())
        val out = captureOut { runner(false).run() }
        assertThat(out).contains("KEEP 1").contains("rule \"Paycheck\"")
        assertThat(java.io.File(dir, "rehome_names_log.jsonl")).doesNotExist()
        runBlocking { verify(accountsApi, never()).deleteAccount(any()) }
    }

    @Test
    fun theRunDoesNotFailWhenTheOnlyLeftoverIsAKeptDuplicate() {
        rules = listOf(rule("Paycheck", trigger = RuleTriggerKeyword.fromAccountIs to "EVOLV CONSULTING"))
        wire(paycheckFirefly())
        val out = captureOut { runner(true).run() }
        assertThat(out).contains("KEPT 1 accounts")
    }

    @Test
    fun aKeptDuplicateDoesNotHideAnotherGroupThatIsLeft() {
        rules = listOf(rule("Paycheck", trigger = RuleTriggerKeyword.fromAccountIs to "EVOLV CONSULTING"))
        wire(paycheckFirefly())
        runBlocking { whenever(accountsApi.deleteAccount(any())).doSuspendableAnswer { createFireflyResponse(Unit) } }  // duplicate 3 stays too
        assertThatThrownBy { captureOut { runner(true).run() } }.hasMessageContaining("groups of duplicate accounts are left")
    }

    @Test
    fun aRuleAddedAfterThePlanIsStillSeenAtDeleteTime() {
        val deleted = mutableListOf<String>()
        wire(paycheckFirefly(), deleted)
        var calls = 0
        runBlocking {
            whenever(rulesApi.listRule(anyOrNull())).doSuspendableAnswer {
                val named = if (++calls == 1) listOf() else listOf(rule("Late", trigger = RuleTriggerKeyword.fromAccountIs to paycheck[2]))
                createFireflyResponse(RuleArray(named, Meta(), PageLink()))
            }
        }
        runBlocking { runner(true).applyPlan(runner(true).readPlan()) }
        assertThat(deleted).containsExactly("1")  // the rule appeared before duplicate 3's delete
    }

    @Test
    fun anUnreadableRuleListStopsBeforeAnyDelete() {
        val deleted = mutableListOf<String>()
        wire(paycheckFirefly(), deleted)
        runBlocking { whenever(rulesApi.listRule(anyOrNull())).doSuspendableAnswer { error("500 rules") } }
        assertThatThrownBy { runBlocking { runner(true).applyPlan(runner(true).readPlan()) } }.hasMessageContaining("500 rules")
        assertThat(deleted).isEmpty()
    }

    private fun captureOut(block: () -> Unit): String {
        val old = System.out
        val buf = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(buf, true))
        try { block() } finally { System.setOut(old) }
        return buf.toString()
    }

    // endregion
}
