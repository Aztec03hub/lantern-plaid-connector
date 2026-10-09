package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountTypeProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ShortAccountTypeProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplitUpdate
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeFilter
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionUpdate
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/** One withdrawal to an outside payee that a rule matches; [to] is null when the rule cannot route it. */
data class PaymentMove(val journalId: String, val splitId: String?, val date: LocalDate, val cents: Long, val description: String, val from: Int, val oldDestination: String?, val to: Int?, val link: PlaidLink)

/**
 * `syncMode: rehome-payments`: moves the withdrawals that `ownAccountPayments` rules match (see OwnAccountPayments.kt)
 * from the outside payee they landed on to the own account the rule routes them to, and re-legs their one link to
 * `source`. Dry run unless `fireflyPlaidConnector2.repair.apply=true`; each change is appended to rehome_log.jsonl first.
 */
@ConditionalOnProperty(name = ["fireflyPlaidConnector2.syncMode"], havingValue = "rehome-payments")
@Component
class RehomePaymentsRunner(
    private val syncHelper: SyncHelper,
    private val fireflyAccountsApi: AccountsApi,
    private val fireflyTxApi: TransactionsApi,
    private val converter: TransactionConverter,
    @Value("\${fireflyPlaidConnector2.repair.apply:false}")
    private val apply: Boolean = false,
    @Value("\${fireflyPlaidConnector2.rehome.since:}")
    private val sinceText: String = "",
    @Value("\${fireflyPlaidConnector2.polled.cursorFileDirectoryPath:persistence}")
    private val directory: String = "persistence",
    @Value("\${fireflyPlaidConnector2.timeZone:UTC}")
    timeZoneString: String = "UTC",
    /** Replays the `done` lines of rehome_log.jsonl backwards (dry run unless apply): the rows go back to their old destination and link leg. */
    @Value("\${fireflyPlaidConnector2.rehome.reverse:false}")
    private val reverse: Boolean = false,
) : Runner {
    private val zone = java.time.ZoneId.of(timeZoneString)
    private val mapper = com.fasterxml.jackson.databind.ObjectMapper()
    private val logFile get() = File(directory, "rehome_log.jsonl")
    private val since: LocalDate get() = if (sinceText.isBlank()) LocalDate.now().minusDays(60) else LocalDate.parse(sinceText)
    private val own = setOf(AccountTypeProperty.assetAccount, AccountTypeProperty.loan, AccountTypeProperty.debt, AccountTypeProperty.mortgage)

    override fun run() = runBlocking<Unit> {
        syncHelper.setApiCreds()
        if (reverse) return@runBlocking reverseLog()
        checkTargets(syncHelper.getAllPlaidAccessTokenAccountIdSets().first.values.map { it.toInt() })
        val plan = readPlan()
        print(plan)
        if (!apply) {
            println("DRY RUN: nothing was changed. Add --fireflyPlaidConnector2.repair.apply=true to apply.")
            return@runBlocking
        }
        applyPlan(plan)
        println("APPLIED ${plan.count { it.to != null }} moves.")
    }

    /**
     * L5: design section 9 says a pull into an account that also has Plaid transactions must not be routed, because it pairs.
     * A routing target that is a Plaid-synced ASSET account stops the run (Plaid-synced loans have a balance only and are fine).
     */
    internal suspend fun checkTargets(plaidMapped: Collection<Int>) {
        val shared = converter.ownAccounts.targetIds().intersect(plaidMapped.toSet())
        for (id in shared) {
            val type = fireflyAccountsApi.getAccount(id.toString(), null).body().data.attributes.type
            check(type != ShortAccountTypeProperty.asset) {
                "ownAccountPayments routes to account $id, which has Plaid transactions of its own (it would pair); remove it from the routing rules"
            }
        }
    }

    internal suspend fun readPlan(): List<PaymentMove> {
        val cfg = converter.ownAccounts
        val out = mutableListOf<PaymentMove>()
        for (from in cfg.rules.map { it.fromAccount }.distinct()) {
            for (journal in journalsOf(from)) {
                for (s in journal.attributes.transactions) out.addAll(listOfNotNull(planSplit(cfg, from, journal, s)))
            }
        }
        return out
    }

    private fun planSplit(cfg: net.djvk.fireflyPlaidConnector2.transactions.OwnAccountConfig, from: Int, journal: TransactionRead, s: TransactionSplit): PaymentMove? {
        val link = s.plaidLinks.orEmpty().singleOrNull()?.takeIf { it.leg == PlaidLinkLeg.single } ?: return null
        if (s.type != TransactionTypeProperty.withdrawal || s.sourceId != from.toString() || s.destinationType in own) return null
        val date = s.date.atZoneSameInstant(zone).toLocalDate()
        if (date < since) return null
        // import matches the bank's own text; the stored description is "merchant: original text", so try both halves
        val text = listOf(s.description, s.description.substringAfter(": ", "")).filter { it.isNotBlank() }.firstOrNull { cfg.ruleFor(from, it) != null } ?: return null
        val cents = BigDecimal(s.amount).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
        return PaymentMove(journal.id, s.transactionJournalId, date, cents, s.description, from, s.destinationId, cfg.target(from, text, cents, date), link)
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

    private fun log(vararg fields: Pair<String, Any?>) = logFile.also { it.absoluteFile.parentFile.mkdirs() }
        .appendText(mapper.writeValueAsString(linkedMapOf<String, Any?>(*fields, "at" to OffsetDateTime.now().toString())) + "\n")

    /** Each move logs an `intent` line first and a `done` line after the update succeeded, so a failed update is not mistaken for a move. */
    internal suspend fun applyPlan(plan: List<PaymentMove>) {
        for (m in plan.filter { it.to != null }) {
            val fields = arrayOf<Pair<String, Any?>>(
                "journal" to m.journalId, "split" to m.splitId, "oldDestination" to m.oldDestination, "newDestination" to m.to.toString(),
                "plaidTx" to m.link.plaidTransactionId, "plaidAccount" to m.link.plaidAccountId, "oldLeg" to m.link.leg.value,
            )
            log("status" to "intent", *fields)
            fireflyTxApi.updateTransaction(
                m.journalId,
                TransactionUpdate(
                    applyRules = false, fireWebhooks = false,
                    transactions = listOf(TransactionSplitUpdate(transactionJournalId = m.splitId, destinationId = m.to.toString(), plaidLinks = listOf(m.link.copy(leg = PlaidLinkLeg.source)))),
                ),
            )
            log("status" to "done", *fields)
        }
    }

    /** `rehome.reverse=true`: puts every `done` move back, newest first, and logs a `reversed` line for each. */
    internal suspend fun reverseLog() {
        val lines = if (logFile.exists()) logFile.readLines().filter { it.isNotBlank() }.map { mapper.readTree(it) } else listOf()
        // N11: a `reversed` line names the `done` line it undid by that line's `at`, so a move made AFTER a reverse is reversed next time
        val undone = lines.filter { it["status"]?.asText() == "reversed" }.mapNotNull { it["undoes"]?.asText() }.toSet()
        val todo = lines.filter { it["status"]?.asText() == "done" && it["at"]?.asText() !in undone }.reversed()
        println("== Reverse: ${todo.size} moves to put back")
        todo.forEach { println("   journal ${it["journal"].asText()}: back to destination ${it["oldDestination"].asText()} and leg ${it["oldLeg"].asText()}") }
        if (!apply) { println("DRY RUN: nothing was changed. Add --fireflyPlaidConnector2.repair.apply=true to apply."); return }
        for (e in todo) {
            val old = e["oldDestination"]?.takeIf { !it.isNull }?.asText()
            if (old == null) { println("   journal ${e["journal"].asText()} had no destination id; not reversed"); continue }
            val link = PlaidLink(e["plaidTx"].asText(), PlaidLinkLeg.valueOf(e["oldLeg"].asText()), e["plaidAccount"]?.takeIf { !it.isNull }?.asText())
            fireflyTxApi.updateTransaction(
                e["journal"].asText(),
                TransactionUpdate(applyRules = false, fireWebhooks = false, transactions = listOf(TransactionSplitUpdate(transactionJournalId = e["split"]?.takeIf { !it.isNull }?.asText(), destinationId = old, plaidLinks = listOf(link)))),
            )
            log("status" to "reversed", "journal" to e["journal"].asText(), "split" to e["split"]?.takeIf { !it.isNull }?.asText(), "undoes" to e["at"].asText())
        }
        println("REVERSED ${todo.size} moves.")
    }

    private fun print(plan: List<PaymentMove>) {
        println("== Own-account payments: ${plan.count { it.to != null }} to route, ${plan.count { it.to == null }} unrouted")
        for (m in plan) println("   ${m.date} ${m.cents / 100.0} \"${m.description}\" ${m.from} -> ${m.to ?: "UNROUTED (stays at ${m.oldDestination})"}")
    }
}
