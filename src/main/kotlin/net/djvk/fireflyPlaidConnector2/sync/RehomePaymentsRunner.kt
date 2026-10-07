package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountTypeProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
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
) : Runner {
    private val since: LocalDate get() = if (sinceText.isBlank()) LocalDate.now().minusDays(60) else LocalDate.parse(sinceText)
    private val own = setOf(AccountTypeProperty.assetAccount, AccountTypeProperty.loan, AccountTypeProperty.debt, AccountTypeProperty.mortgage)

    override fun run() = runBlocking<Unit> {
        syncHelper.setApiCreds()
        val plan = readPlan()
        print(plan)
        if (!apply) {
            println("DRY RUN: nothing was changed. Add --fireflyPlaidConnector2.repair.apply=true to apply.")
            return@runBlocking
        }
        applyPlan(plan)
        println("APPLIED ${plan.count { it.to != null }} moves.")
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
        val date = s.date.toLocalDate()
        if (date < since) return null
        cfg.ruleFor(from, s.description) ?: return null
        val cents = BigDecimal(s.amount).movePointRight(2).toBigInteger().toLong()
        return PaymentMove(journal.id, s.transactionJournalId, date, cents, s.description, from, s.destinationId, cfg.target(from, s.description, cents, date), link)
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

    internal suspend fun applyPlan(plan: List<PaymentMove>) {
        val log = File(directory, "rehome_log.jsonl").also { it.absoluteFile.parentFile.mkdirs() }
        for (m in plan.filter { it.to != null }) {
            log.appendText("""{"journal":"${m.journalId}","split":${m.splitId?.let { "\"$it\"" }},"oldDestination":${m.oldDestination?.let { "\"$it\"" }},"newDestination":"${m.to}","at":"${OffsetDateTime.now()}"}""" + "\n")
            fireflyTxApi.updateTransaction(
                m.journalId,
                TransactionUpdate(
                    applyRules = false, fireWebhooks = false,
                    transactions = listOf(TransactionSplitUpdate(transactionJournalId = m.splitId, destinationId = m.to.toString(), plaidLinks = listOf(m.link.copy(leg = PlaidLinkLeg.source)))),
                ),
            )
        }
    }

    private fun print(plan: List<PaymentMove>) {
        println("== Own-account payments: ${plan.count { it.to != null }} to route, ${plan.count { it.to == null }} unrouted")
        for (m in plan) println("   ${m.date} ${m.cents / 100.0} \"${m.description}\" ${m.from} -> ${m.to ?: "UNROUTED (stays at ${m.oldDestination})"}")
    }
}
