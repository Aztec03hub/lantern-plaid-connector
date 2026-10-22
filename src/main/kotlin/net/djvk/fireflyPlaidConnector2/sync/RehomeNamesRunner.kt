package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.RecurrencesApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.RulesApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountTypeFilter
import net.djvk.fireflyPlaidConnector2.api.firefly.models.RuleActionKeyword
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

/**
 * The pure decision of `rehome-names`: groups with more than one account, survivor = most journals, ties lowest id.
 * A lone account whose name differs from its canonical name is a group of one with no duplicates (a rename that failed
 * after the merge, or never ran), so a re-plan sees it.
 */
fun planNameGroups(accounts: List<NamedAccount>, namer: CounterpartyNamer): List<NameGroup> =
    accounts.groupBy { it.type to namer.key(namer.canonical(null, it.name)) }
        .filter { it.value.size > 1 || it.value[0].name != namer.canonical(null, it.value[0].name) }
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
    private val fireflyRulesApi: RulesApi,
    private val fireflyRecurrencesApi: RecurrencesApi,
    @Value("\${fireflyPlaidConnector2.repair.apply:false}")
    private val apply: Boolean = false,
    @Value("\${fireflyPlaidConnector2.polled.cursorFileDirectoryPath:persistence}")
    private val directory: String = "persistence",
) : Runner {
    private val mapper = com.fasterxml.jackson.databind.ObjectMapper()
    private val logFile get() = java.io.File(directory, "rehome_names_log.jsonl")
    private val namer get() = converter.namer
    private val types = listOf(AccountTypeFilter.expense, AccountTypeFilter.revenue)

    override fun run() = runBlocking<Unit> {
        syncHelper.setApiCreds()
        val plan = readPlan()
        print(plan)
        if (!apply) {
            printNamed(plan)
            println("DRY RUN: nothing was changed. Add --fireflyPlaidConnector2.repair.apply=true to apply.")
            return@runBlocking
        }
        val kept = applyPlan(plan)
        println("APPLIED. Re-reading to check:")
        val again = readPlan()
        print(again)
        // a duplicate kept because a rule or recurrence names it is listed forever; it is not a failure
        val left = again.filter { g -> g.duplicates.isEmpty() || g.duplicates.any { it.id !in kept } }
        check(left.isEmpty()) { "rehome-names is not done: ${left.size} groups of duplicate accounts are left" }
        if (kept.isNotEmpty()) println("KEPT ${kept.size} accounts because rules or recurrences name them: ${kept.sorted()}")
    }

    /** Reads every expense and revenue account, counts the journals of the candidates only, and plans. */
    internal suspend fun readPlan(): List<NameGroup> {
        val all = types.flatMap { type -> readAccounts(type) }
        // journals are counted for the accounts that might merge only; a lone account is a rename and needs no count
        val multi = all.groupBy { it.type to namer.key(namer.canonical(null, it.name)) }.filter { it.value.size > 1 }.values.flatten().toSet()
        val counted = all.map { if (it in multi) it.copy(journals = countJournals(it.id)) else it }
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

    /** Why [d] must not be deleted: the titles of rules whose triggers/actions name it and the ids of recurrences that use it. Empty = safe. */
    internal suspend fun namedBy(d: NamedAccount): List<String> {
        val out = mutableListOf<String>()
        var page = 1
        while (true) {
            val body = fireflyRulesApi.listRule(page++).body()
            for (r in body.data) {
                val a = r.attributes
                val byTrigger = a.triggers.any { t ->
                    val k = t.type.value
                    k.contains("account") && when {
                        k.endsWith("_is") -> t.value.equals(d.name, ignoreCase = true)
                        k.endsWith("_starts") -> d.name.startsWith(t.value, ignoreCase = true)
                        k.endsWith("_ends") -> d.name.endsWith(t.value, ignoreCase = true)
                        k.endsWith("_contains") -> d.name.contains(t.value, ignoreCase = true)
                        else -> false
                    }
                }
                val byAction = a.actions.any {
                    (it.type == RuleActionKeyword.setSourceAccount || it.type == RuleActionKeyword.setDestinationAccount) && it.value.equals(d.name, ignoreCase = true)
                }
                if (byTrigger || byAction) out.add("rule \"${a.title}\"")
            }
            val p = body.meta.pagination
            if (p == null || p.currentPage >= p.totalPages) break
        }
        page = 1
        while (true) {
            val body = fireflyRecurrencesApi.listRecurrence(page++).body()
            for (r in body.data) {
                if (r.attributes.transactions.orEmpty().any { it.sourceId == d.id.toString() || it.destinationId == d.id.toString() }) out.add("recurrence ${r.id}")
            }
            val p = body.meta.pagination
            if (p == null || p.currentPage >= p.totalPages) break
        }
        return out
    }

    /** One JSONL line per step, appended BEFORE the step, so a wrong group can be reversed by hand (M4). */
    private fun log(vararg fields: Pair<String, Any?>) {
        appendDurably(logFile, mapper.writeValueAsString(linkedMapOf<String, Any?>(*fields, "at" to java.time.OffsetDateTime.now().toString())) + "\n")
    }

    /**
     * Per group: rename the survivor FIRST, so a failure later cannot strand a stale name. Firefly refuses two accounts of
     * one type with the same name, so a duplicate already spelled like the display name (any case) is merged and deleted
     * before the rename; the rename then runs right after that delete, ahead of the other duplicates. A rename that still
     * fails leaves a lone account whose name differs from its canonical, which the next plan lists again.
     *
     * A duplicate that a rule or recurrence names ([namedBy]) is emptied but not deleted; its id is returned.
     * Firefly's REST delete has no move-to (core DestroyController.php:61 passes null), so a journal that arrives
     * between the re-check and the delete cannot be saved; the window is one API round trip. Run this tool when no
     * import is running.
     */
    internal suspend fun applyPlan(plan: List<NameGroup>): Set<Int> {
        val kept = mutableSetOf<Int>()
        for (g in plan) {
            val to = g.survivor.id.toString()
            val blockers = g.duplicates.filter { it.name.equals(g.canonical, ignoreCase = true) }.map { it.id }.toMutableSet()
            var renamed = !g.rename
            suspend fun renameNow() {
                if (renamed || blockers.isNotEmpty()) return
                log("op" to "rename", "account" to to, "oldName" to g.survivor.name, "newName" to g.canonical, "type" to g.type.value)
                fireflyAccountsApi.renameAccount(to, g.canonical)
                renamed = true
            }
            renameNow()
            for (d in g.duplicates.sortedBy { if (it.id in blockers) 0 else 1 }) {
                val from = d.id.toString()
                for (journal in journalsOf(d.id)) {
                    val splits = journal.attributes.transactions.filter { it.sourceId == from || it.destinationId == from }.map {
                        TransactionSplitUpdate(
                            transactionJournalId = it.transactionJournalId,
                            sourceId = if (it.sourceId == from) to else null,
                            destinationId = if (it.destinationId == from) to else null,
                        )
                    }
                    log(
                        "op" to "repoint", "journal" to journal.id, "splits" to splits.map { it.transactionJournalId },
                        "oldAccount" to from, "oldName" to d.name, "newAccount" to to, "type" to g.type.value,
                    )
                    fireflyTxApi.updateTransaction(journal.id, TransactionUpdate(applyRules = false, fireWebhooks = false, transactions = splits))
                }
                // Re-check right before the delete: a journal that arrived since the plan keeps the account alive
                val left = fireflyAccountsApi.listTransactionByAccount(from, 1, 1, null, null, TransactionTypeFilter.all).body().data
                val refs = if (left.isEmpty()) namedBy(d) else emptyList()
                if (left.isEmpty() && refs.isNotEmpty()) {
                    log("op" to "delete-skipped", "account" to from, "name" to d.name, "type" to g.type.value, "namedBy" to refs)
                    println("The ${g.type.value} account ${d.id} \"${d.name}\" is named by ${refs.joinToString(", ")}; not deleted.")
                    kept.add(d.id)
                } else if (left.isEmpty()) {
                    log("op" to "delete", "account" to from, "name" to d.name, "type" to g.type.value, "mergedInto" to to)
                    fireflyAccountsApi.deleteAccount(from)
                    blockers.remove(d.id)
                    renameNow()
                } else println("The ${g.type.value} account ${d.id} \"${d.name}\" still has journals; not deleted.")
            }
        }
        return kept
    }

    /** Dry run: which duplicates a rule or recurrence names, so they would be merged but not deleted. */
    private suspend fun printNamed(plan: List<NameGroup>) {
        for (g in plan) for (d in g.duplicates) {
            val refs = namedBy(d)
            if (refs.isNotEmpty()) println("   KEEP ${d.id}: named by ${refs.joinToString(", ")}; it would be merged but not deleted")
        }
    }

    private fun print(plan: List<NameGroup>) {
        val (renameOnly, merges) = plan.partition { it.duplicates.isEmpty() }
        // N12: rename-only groups (every account whose name differs from the namer's spelling) are listed after the merges and counted apart
        println("== Names: ${merges.size} groups of expense/revenue accounts that are one payee, and ${renameOnly.size} lone accounts to rename only (listed last)")
        for (g in merges + renameOnly) {
            println("   ${g.type.value} \"${g.canonical}\": keep ${g.survivor.id} (\"${g.survivor.name}\", ${g.survivor.journals} journals)${if (g.rename) ", rename" else ""}")
            if (g.duplicates.isEmpty()) println("      (alone: rename only)")
            g.duplicates.forEach { println("      merge ${it.id} (\"${it.name}\", ${it.journals} journals)") }
        }
    }
}
