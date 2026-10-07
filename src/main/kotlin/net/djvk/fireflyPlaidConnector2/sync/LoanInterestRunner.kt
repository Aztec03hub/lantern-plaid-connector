package net.djvk.fireflyPlaidConnector2.sync

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionStore
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeFilter
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapper
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountsBalanceGetRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountsBalanceGetRequestOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max

/** One loan of the pair config's `loans` list: the Firefly account, its APR in percent, and the expense account interest goes to. */
data class LoanConfig(val account: Int, val apr: BigDecimal, val interestAccount: String)

object LoanConfigLoader {
    private val mapper = ObjectMapper()

    fun load(path: String): List<LoanConfig> {
        if (path.isBlank()) return listOf()
        return mapper.readTree(File(path))["loans"]?.map { LoanConfig(it["account"].asInt(), BigDecimal(it["apr"].asText()), it["interestAccount"].asText()) } ?: listOf()
    }
}

data class LoanPayment(val date: LocalDate, val amount: BigDecimal)

/** A payment without an interest journal, with what its interest should be. */
data class InterestLine(val payment: LoanPayment, val days: Long, val owedBefore: BigDecimal, val expected: BigDecimal)

/** What `loan-interest` decided for one loan. [lines] are the interest journals to write, [residual] is a leftover to write as unexplained (zero for none). */
data class LoanPlan(
    val loan: LoanConfig, val name: String, val plaidOwed: BigDecimal, val fireflyOwed: BigDecimal, val gap: BigDecimal,
    val lines: List<InterestLine>, val residual: BigDecimal, val action: String,
) {
    val writes: Boolean get() = lines.isNotEmpty() || residual.signum() != 0
}

/** `owed x apr/100 x days/365`, rounded half-up to cents (DCU: simple daily interest, actual/365). */
fun expectedInterest(owed: BigDecimal, apr: BigDecimal, days: Long): BigDecimal =
    owed.multiply(apr).multiply(BigDecimal(days)).divide(BigDecimal(36500), 2, RoundingMode.HALF_UP)

private const val UNEXPLAINED = "Unexplained loan change"
private const val UNEXPLAINED_TAG = "lantern-unexplained"

/**
 * `syncMode: loan-interest`: DCU loans have no Plaid transactions, only a balance. Firefly gets the full payment (see
 * rehome-payments), so after a payment `plaidOwed - fireflyOwed` is the interest charged and not yet recorded. This writes
 * one interest journal per uncovered payment so that the gap closes; a gap that the payments do not explain is written
 * to [UNEXPLAINED] and printed loudly. Dry run unless `fireflyPlaidConnector2.repair.apply=true`. Idempotent: a covered
 * payment (an interest journal from the loan on the same date with the same description) is never charged twice.
 */
@ConditionalOnProperty(name = ["fireflyPlaidConnector2.syncMode"], havingValue = "loan-interest")
@Component
class LoanInterestRunner(
    private val syncHelper: SyncHelper,
    private val plaidApiWrapper: PlaidApiWrapper,
    private val fireflyAccountsApi: AccountsApi,
    private val fireflyTxApi: TransactionsApi,
    @Value("\${fireflyPlaidConnector2.timeZone:UTC}")
    timeZoneString: String = "UTC",
    @Value("\${fireflyPlaidConnector2.pair.configFile:}")
    private val configFile: String = "",
    @Value("\${fireflyPlaidConnector2.loan.tolerance:0.05}")
    tolerance: Double = 0.05,
    @Value("\${fireflyPlaidConnector2.repair.apply:false}")
    private val apply: Boolean = false,
) : Runner {
    private val zone = ZoneId.of(timeZoneString)
    private val tolerance = BigDecimal.valueOf(tolerance)

    override fun run() = runBlocking<Unit> { runOnce(apply) }

    /** One pass, also callable by a later nightly job. Apply re-reads every loan it wrote to and fails if a gap is left. */
    suspend fun runOnce(apply: Boolean = this.apply): List<LoanPlan> {
        syncHelper.setApiCreds()
        val loans = LoanConfigLoader.load(configFile)
        val plans = loans.map { plan(it) }
        plans.forEach { print(it) }
        if (!apply) {
            println("DRY RUN: nothing was changed. Add --fireflyPlaidConnector2.repair.apply=true to apply.")
            return plans
        }
        plans.filter { it.writes }.forEach { write(it) }
        println("APPLIED. Re-reading to check:")
        val plaidAfter = readPlaidOwed(loans)
        for (p in plans.filter { it.writes }) {
            val again = gapOf(p.loan, plaidAfter)
            println("   loan ${p.loan.account}: gap now $again")
            check(again.abs() <= tolerance) { "loan-interest is not done: loan ${p.loan.account} still has a gap of $again" }
        }
        return plans
    }

    private suspend fun readPlaidOwed(loans: List<LoanConfig>): Map<Int, BigDecimal> {
        val (accountMap, items) = syncHelper.getAllPlaidAccessTokenAccountIdSets()
        val wanted = loans.map { it.account }.toSet()
        val out = mutableMapOf<Int, BigDecimal>()
        for ((token, ids) in items.toList()) {
            val ours = ids.filter { accountMap[it] in wanted }
            if (ours.isEmpty()) continue
            val balances = plaidApiWrapper.executeRequest(
                { it.accountsBalanceGet(AccountsBalanceGetRequest(token, null, null, AccountsBalanceGetRequestOptions(ours, null))) },
                "balance get request",
            ).body().accounts
            balances.forEach { b -> b.balances.current?.let { c -> accountMap[b.accountId]?.let { f -> out[f] = BigDecimal.valueOf(c) } } }
        }
        return out
    }

    private suspend fun fireflyOwed(id: Int, date: LocalDate?): BigDecimal =
        -BigDecimal(fireflyAccountsApi.getAccount(id.toString(), date).body().data.attributes.currentBalance ?: error("Firefly has no balance for account $id"))

    private suspend fun gapOf(loan: LoanConfig, plaid: Map<Int, BigDecimal>): BigDecimal =
        (plaid[loan.account] ?: error("Plaid has no balance for loan ${loan.account}")) - fireflyOwed(loan.account, null)

    private suspend fun splitsOf(id: Int): List<TransactionSplit> {
        val out = mutableListOf<TransactionSplit>()
        var page = 1
        while (true) {
            val body = fireflyAccountsApi.listTransactionByAccount(id.toString(), page++, 50, null, null, TransactionTypeFilter.all).body()
            body.data.forEach { out.addAll(it.attributes.transactions) }
            val p = body.meta.pagination
            if (p == null || p.currentPage >= p.totalPages) break
        }
        return out
    }

    private fun dateOf(s: TransactionSplit): LocalDate = s.date.atZoneSameInstant(zone).toLocalDate()
    private fun money(x: BigDecimal) = x.setScale(2, RoundingMode.HALF_UP).toPlainString()
    private fun interestDescription(name: String, p: LoanPayment) = "Interest on $name payment of $${money(p.amount)}"

    /** Reads Plaid and Firefly for [loan] and decides; writes nothing. */
    internal suspend fun plan(loan: LoanConfig, plaid: Map<Int, BigDecimal>? = null): LoanPlan {
        val id = loan.account.toString()
        val plaidOwed = (plaid ?: readPlaidOwed(listOf(loan)))[loan.account] ?: error("Plaid has no balance for loan ${loan.account}")
        val name = fireflyAccountsApi.getAccount(id, null).body().data.attributes.name
        val fireflyOwed = fireflyOwed(loan.account, null)
        val gap = plaidOwed - fireflyOwed
        fun done(action: String, lines: List<InterestLine> = listOf(), residual: BigDecimal = BigDecimal.ZERO) =
            LoanPlan(loan, name, plaidOwed, fireflyOwed, gap, lines, residual, action)
        if (gap.abs() <= tolerance) return done("OK: within $tolerance")
        if (gap.signum() < 0) return done("Plaid balance lags: Plaid has not seen a payment Firefly has; nothing written")

        val splits = splitsOf(loan.account)
        val payments = splits.filter { it.destinationId == id && !it.description.startsWith("Interest on ") }
            .map { LoanPayment(dateOf(it), BigDecimal(it.amount)) }.sortedBy { it.date }
        val covered = splits.filter { it.sourceId == id && it.description.startsWith("Interest on ") }.map { dateOf(it) to it.description }.toSet()
        val lines = mutableListOf<InterestLine>()
        payments.forEachIndexed { i, p ->
            if ((p.date to interestDescription(name, p)) in covered) return@forEachIndexed
            val days = if (i == 0) 0 else ChronoUnit.DAYS.between(payments[i - 1].date, p.date)
            val owedBefore = fireflyOwed(loan.account, p.date.minusDays(1))
            lines.add(InterestLine(p, days, owedBefore, expectedInterest(owedBefore, loan.apr, days)))
        }
        val sum = lines.fold(BigDecimal.ZERO) { a, l -> a + l.expected }
        val off = gap - sum
        val explained = off.abs() <= BigDecimal.valueOf(max(tolerance.toDouble(), sum.toDouble() * 0.005))
        if (lines.isEmpty() || !explained) {
            return done("UNEXPLAINED: payments explain $sum of the gap $gap; ${money(off)} goes to \"$UNEXPLAINED\"", lines, off)
        }
        // the last line takes the cents so that the total is exactly the gap
        val last = lines.last()
        return done("write ${lines.size} interest journals", lines.dropLast(1) + last.copy(expected = last.expected + off))
    }

    private suspend fun write(p: LoanPlan) {
        val id = p.loan.account.toString()
        for (l in p.lines) {
            if (l.expected.signum() <= 0) continue
            store(TransactionSplit(
                type = TransactionTypeProperty.withdrawal, date = l.payment.date.atStartOfDay(zone).toOffsetDateTime(),
                amount = money(l.expected), description = interestDescription(p.name, l.payment),
                sourceId = id, destinationId = null, destinationName = p.loan.interestAccount,
            ))
        }
        if (p.residual.signum() != 0) {
            val date = LocalDate.now(zone).atStartOfDay(zone).toOffsetDateTime()
            val desc = "Unexplained change in ${p.name}"
            store(
                if (p.residual.signum() > 0) TransactionSplit(
                    type = TransactionTypeProperty.withdrawal, date = date, amount = money(p.residual), description = desc,
                    sourceId = id, destinationId = null, destinationName = UNEXPLAINED, tags = listOf(UNEXPLAINED_TAG),
                ) else TransactionSplit(
                    type = TransactionTypeProperty.deposit, date = date, amount = money(p.residual.negate()), description = desc,
                    sourceId = null, sourceName = UNEXPLAINED, destinationId = id, tags = listOf(UNEXPLAINED_TAG),
                )
            )
        }
    }

    private suspend fun store(split: TransactionSplit) {
        fireflyTxApi.storeTransaction(TransactionStore(listOf(split), errorIfDuplicateHash = false, applyRules = false, fireWebhooks = false, groupTitle = null))
    }

    private fun print(p: LoanPlan) {
        println("== Loan ${p.loan.account} \"${p.name}\": plaidOwed=${money(p.plaidOwed)} fireflyOwed=${money(p.fireflyOwed)} gap=${money(p.gap)}")
        p.lines.forEach { println("   payment ${it.payment.date} ${money(it.payment.amount)}: ${it.days} days on ${money(it.owedBefore)} at ${p.loan.apr}% -> interest ${money(it.expected)}") }
        if (p.residual.signum() != 0) println("   !!! UNEXPLAINED ${money(p.residual)} (tag $UNEXPLAINED_TAG)")
        println("   ${p.action}")
    }
}
