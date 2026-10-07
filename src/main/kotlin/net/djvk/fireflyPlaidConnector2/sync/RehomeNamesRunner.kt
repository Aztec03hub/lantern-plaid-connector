package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountTypeFilter
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountUpdate
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplitUpdate
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeFilter
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionUpdate
import net.djvk.fireflyPlaidConnector2.names.CounterpartyNamer
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** An expense or revenue account and how many journals it holds. */
data class NamedAccount(val id: Int, val name: String, val type: AccountTypeFilter, val journals: Int)

/** Accounts of one type that are one payee: [survivor] keeps the journals, [duplicates] are emptied and deleted. */
data class NameGroup(val type: AccountTypeFilter, val canonical: String, val survivor: NamedAccount, val duplicates: List<NamedAccount>) {
    val rename: Boolean get() = survivor.name != canonical
}

/** The pure decision of `rehome-names`: groups with more than one account, survivor = most journals, ties lowest id. */
fun planNameGroups(accounts: List<NamedAccount>, namer: CounterpartyNamer): List<NameGroup> =
    accounts.groupBy { it.type to namer.key(namer.canonical(null, it.name)) }
        .filter { it.value.size > 1 }
        .map { (k, members) ->
            val survivor = members.sortedWith(compareBy({ -it.journals }, { it.id })).first()
            NameGroup(k.first, namer.canonical(null, survivor.name), survivor, members.filter { it !== survivor }.sortedBy { it.id })
        }
        .sortedWith(compareBy({ it.type.value }, { it.canonical }))

/**
 * `syncMode: rehome-names`: merges the expense and revenue accounts that are one payee under different spellings
 * (see [CounterpartyNamer]). Journals of each duplicate are re-pointed to the survivor, then the emptied duplicate is
 * deleted, then the survivor is renamed to the display name. Asset and liability accounts are never touched.
 * Dry run unless `fireflyPlaidConnector2.repair.apply=true`; apply re-plans afterwards and fails if a group is left.
 */
@ConditionalOnProperty(name = ["fireflyPlaidConnector2.syncMode"], havingValue = "rehome-names")
@Component
class RehomeNamesRunner(
    private val syncHelper: SyncHelper,
    private val fireflyAccountsApi: AccountsApi,
    private val fireflyTxApi: TransactionsApi,
    private val converter: TransactionConverter,
    @Value("\${fireflyPlaidConnector2.repair.apply:false}")
    private val apply: Boolean = false,
) : Runner {
    private val namer get() = converter.namer
    private val types = listOf(AccountTypeFilter.expense, AccountTypeFilter.revenue)

    override fun run() = runBlocking<Unit> {
        syncHelper.setApiCreds()
        val plan = readPlan()
        print(plan)
        if (!apply) {
            println("DRY RUN: nothing was changed. Add --fireflyPlaidConnector2.repair.apply=true to apply.")
            return@runBlocking
        }
        applyPlan(plan)
        println("APPLIED. Re-reading to check:")
        val again = readPlan()
        print(again)
        check(again.isEmpty()) { "rehome-names is not done: ${again.size} groups of duplicate accounts are left" }
    }

    /** Reads every expense and revenue account, counts the journals of the candidates only, and plans. */
    internal suspend fun readPlan(): List<NameGroup> {
        val all = types.flatMap { type -> readAccounts(type) }
        val candidates = all.groupBy { it.type to namer.key(namer.canonical(null, it.name)) }.filter { it.value.size > 1 }.values.flatten()
        val counted = candidates.map { it.copy(journals = countJournals(it.id)) }
        return planNameGroups(counted, namer)
    }

    private suspend fun readAccounts(type: AccountTypeFilter): List<NamedAccount> {
        val out = mutableListOf<NamedAccount>()
        var page = 1
        while (true) {
            val body = fireflyAccountsApi.listAccount(page++, null, type).body()
            body.data.forEach { out.add(NamedAccount(it.id.toInt(), it.attributes.name, type, 0)) }
            val p = body.meta.pagination
            if (p == null || p.currentPage >= p.totalPages) break
        }
        return out
    }

    private suspend fun countJournals(id: Int): Int {
        val body = fireflyAccountsApi.listTransactionByAccount(id.toString(), 1, 1, null, null, TransactionTypeFilter.all).body()
        return body.meta.pagination?.total ?: body.data.size
    }

    private suspend fun journalsOf(id: Int): List<TransactionRead> {
        val out = mutableListOf<TransactionRead>()
        var page = 1
        while (true) {
            val body = fireflyAccountsApi.listTransactionByAccount(id.toString(), page++, 50, null, null, TransactionTypeFilter.all).body()
            out.addAll(body.data)
            val p = body.meta.pagination
            if (p == null || p.currentPage >= p.totalPages) break
        }
        return out
    }

    internal suspend fun applyPlan(plan: List<NameGroup>) {
        for (g in plan) {
            val to = g.survivor.id.toString()
            for (d in g.duplicates) {
                val from = d.id.toString()
                for (journal in journalsOf(d.id)) {
                    val splits = journal.attributes.transactions.filter { it.sourceId == from || it.destinationId == from }.map {
                        TransactionSplitUpdate(
                            transactionJournalId = it.transactionJournalId,
                            sourceId = if (it.sourceId == from) to else null,
                            destinationId = if (it.destinationId == from) to else null,
                        )
                    }
                    fireflyTxApi.updateTransaction(journal.id, TransactionUpdate(applyRules = false, fireWebhooks = false, transactions = splits))
                }
                // Re-check: a journal that arrived since the plan keeps the account alive
                val left = fireflyAccountsApi.listTransactionByAccount(from, 1, 1, null, null, TransactionTypeFilter.all).body().data
                if (left.isEmpty()) fireflyAccountsApi.deleteAccount(from)
                else println("The ${g.type.value} account ${d.id} \"${d.name}\" still has journals; not deleted.")
            }
            if (g.rename) fireflyAccountsApi.updateAccount(to, AccountUpdate(name = g.canonical))
        }
    }

    private fun print(plan: List<NameGroup>) {
        println("== Names: ${plan.size} groups of expense/revenue accounts that are one payee")
        for (g in plan) {
            println("   ${g.type.value} \"${g.canonical}\": keep ${g.survivor.id} (\"${g.survivor.name}\", ${g.survivor.journals} journals)${if (g.rename) ", rename" else ""}")
            g.duplicates.forEach { println("      merge ${it.id} (\"${it.name}\", ${it.journals} journals)") }
        }
    }
}
