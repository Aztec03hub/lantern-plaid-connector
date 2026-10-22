package net.djvk.fireflyPlaidConnector2.sync

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
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

/** Plaid error_codes of an institution that will not come back; such a loan is reported every night but does not fail the step alone (M3). */
private val PERMANENT_CODES = setOf("INSTITUTION_NOT_SUPPORTED", "INSTITUTION_NO_LONGER_SUPPORTED")

/** Plaid's institution did not answer for a loan's balance (transient error_code, no figures or tokens). */
internal class PlaidBalanceUnavailable(val errorCode: String, cause: Throwable) : RuntimeException("Plaid balance unavailable: $errorCode", cause)

object LoanConfigLoader {
    private val mapper = ObjectMapper()

    fun load(path: String): List<LoanConfig> {
        if (path.isBlank()) return listOf()
        return mapper.readTree(File(path))["loans"]?.map { LoanConfig(it["account"].asInt(), BigDecimal(it["apr"].asText()), it["interestAccount"].asText()) } ?: listOf()
    }
}

data class LoanPayment(val date: LocalDate, val amount: BigDecimal, val journalId: String? = null)

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
private const val UNEXPLAINED_DESC = "Unexplained change in "
private const val EXTERNAL_PREFIX = "lantern-interest:"

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
    /**
     * Write nothing for a loan whose gap the payments don't explain, and retry next run (the nightly job sets this).
     * A routed payment imported before Plaid's loan balance moves looks exactly like that, and fixes itself a day later.
     */
    @Value("\${fireflyPlaidConnector2.loan.holdUnexplained:false}")
    private val holdUnexplained: Boolean = false,
    /** N6: where `loan-lag.json` lives (blank: no tracking). A loan that "Firefly lags" for [lagNights] nights in a row fails the run. */
    @Value("\${fireflyPlaidConnector2.polled.cursorFileDirectoryPath:persistence}")
    private val lagDirectory: String = "",
    @Value("\${fireflyPlaidConnector2.loan.lagNights:3}")
    private val lagNights: Int = 3,
) : Runner {
    internal var today: () -> LocalDate = { LocalDate.now(zone) }
    private val zone = ZoneId.of(timeZoneString)
    private val tolerance = BigDecimal.valueOf(tolerance)

    /** Loans [runOnce] skipped because Plaid's institution did not answer (LAN-50), with Plaid's error_code. */
    private var skipped: List<Pair<LoanConfig, String>> = listOf()

    /** F2: loans written whose post-write re-read got no answer from Plaid. Their writes stand (a rerun is idempotent); the run fails so a human looks. */
    private var unchecked: List<Pair<LoanConfig, String>> = listOf()

    /** A loan left HELD exits non-zero, so the nightly prints its "!!" line instead of looking fine (A1). */
    override fun run() = runBlocking<Unit> {
        val plans = runOnce(apply)
        val held = plans.filter { it.action.startsWith("HELD") }
        // V3: track first, so a night with a HELD loan still records the recovery of the others
        // L2: a skipped loan was not read tonight, so its carried lag date must not raise the "payment probably missing" alert
        val stuck = trackLag(plans).filter { a -> skipped.none { it.first.account == a } }
        // M3: a permanently unsupported institution is reported every night but does not fail the step by itself
        val (permanent, transient) = skipped.partition { it.second in PERMANENT_CODES }
        val parts = listOfNotNull(
            transient.takeIf { it.isNotEmpty() }?.let { "${it.size} loan(s) SKIPPED, Plaid did not answer or needs a login: ${it.map { (l, c) -> "${l.account} ($c)" }}" },
            unchecked.takeIf { it.isNotEmpty() }?.let { "${it.size} loan(s) WRITTEN but not re-checked, Plaid did not answer or a gap is left: ${it.map { (l, c) -> "${l.account} ($c)" }}" },
            held.takeIf { it.isNotEmpty() }?.let { "${it.size} loan(s) HELD, nothing written: ${it.map { h -> h.loan.account }}" },
            stuck.takeIf { it.isNotEmpty() }?.let { "loan(s) $it have been \"Firefly lags\" for $lagNights or more nights; a payment Plaid has seen is probably missing in Firefly" },
        )
        val notes = permanent.takeIf { it.isNotEmpty() }?.let { "${it.size} loan(s) SKIPPED (permanent), institution not supported by Plaid: ${it.map { (l, c) -> "${l.account} ($c)" }}" }
        if (parts.isNotEmpty() || notes != null) println("!! loan-interest: " + (parts + listOfNotNull(notes)).joinToString(" | "))
        check(parts.isEmpty()) { "loan-interest: " + parts.joinToString(" | ") }
    }

    /**
     * N6: "Firefly lags" is self-healing for a night or two and a missing payment after that. Remembers the first night each loan
     * lagged in `loan-lag.json` (real runs only) and returns the loans that have lagged for [lagNights] nights or more.
     */
    private fun trackLag(plans: List<LoanPlan>): List<Int> {
        if (!apply || lagDirectory.isBlank()) return listOf()
        val file = File(lagDirectory, "loan-lag.json")
        val old: Map<String, String> = if (file.exists()) try {
            (ObjectMapper().readValue(file, Map::class.java) ?: throw com.fasterxml.jackson.core.JsonParseException(null, "document is null")).entries.associate { it.key.toString() to it.value.toString() }
        } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
            // U03: keep the bad file for a person; if the move throws, the loan step fails loudly and nothing is overwritten
            val aside = moveAside(file.toPath())
            println("ERROR: ${file.path} cannot be read (${e::class.simpleName}); moved to ${aside.fileName}; the lag counters start again")
            mapOf()
        } else mapOf()
        val now = today()
        // a hand-edited date that does not parse counts as "first lagged tonight"
        // L3: a HELD night neither starts nor resets the clock; a loan that alternates lagging and HELD keeps its first date
        val lagging = plans.mapNotNull {
            val k = it.loan.account.toString()
            val prior = old[k]?.takeIf { d -> runCatching { LocalDate.parse(d) }.isSuccess }
            when {
                it.action.startsWith("Firefly lags") -> k to (prior ?: now.toString())
                it.action.startsWith("HELD") && prior != null -> k to prior
                else -> null
            }
        }.toMap() + skipped.mapNotNull { (l, _) -> old[l.account.toString()]?.takeIf { d -> runCatching { LocalDate.parse(d) }.isSuccess }?.let { l.account.toString() to it } }.toMap()
        File(lagDirectory).mkdirs()
        // W3 + U03: unique temp file then atomic move, so a kill mid-write leaves the old file whole
        writeAtomically(file.toPath(), ObjectMapper().writeValueAsString(lagging))
        return lagging.filterValues { ChronoUnit.DAYS.between(LocalDate.parse(it), now) >= lagNights - 1 }.keys.map { it.toInt() }
    }

    /** One pass, also callable by a later nightly job. Apply re-reads every loan it wrote to and fails if a gap is left. */
    suspend fun runOnce(apply: Boolean = this.apply): List<LoanPlan> {
        syncHelper.setApiCreds()
        val loans = LoanConfigLoader.load(configFile)
        val skip = mutableListOf<Pair<LoanConfig, String>>()
        // LAN-50: only a loan whose Plaid balance cannot be read after the wrapper's retries is skipped; nothing is written for it
        val plans = loans.mapNotNull {
            try { plan(it) } catch (e: PlaidBalanceUnavailable) {
                println("WARN: loan ${it.account} skipped: Plaid balance unavailable (${e.errorCode}); nothing written for it")
                skip.add(it to e.errorCode); null
            }
        }
        skipped = skip
        unchecked = listOf()
        plans.forEach { print(it) }
        if (!apply) {
            println("DRY RUN: nothing was changed. Add --fireflyPlaidConnector2.repair.apply=true to apply.")
            return plans
        }
        plans.filter { it.writes }.forEach { write(it) }
        println("APPLIED. Re-reading to check:")
        val written = plans.filter { it.writes }
        val (plaidAfter, failedAfter) = try { readPlaidOwed(written.map { it.loan }) } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // F2/L1: the writes are done; do not abort before trackLag and the summary. Nothing is written again, so this cannot double-post.
            val code = (e as? PlaidBalanceUnavailable)?.errorCode ?: "ERROR_${e.javaClass.simpleName}"
            println("WARN: written but not re-checked ($code)")
            unchecked = written.map { it.loan to code }
            return plans
        }
        val left = written.filter { it.loan.account in failedAfter }.map { it.loan to failedAfter.getValue(it.loan.account) }.toMutableList()
        left.forEach { (l, c) -> println("WARN: loan ${l.account} written but not re-checked ($c)") }
        // M1: one loan's missing balance, Firefly error or leftover gap marks that loan unchecked; the others are still checked
        for (p in written.filter { it.loan.account !in failedAfter }) {
            try {
                val again = gapOf(p.loan, plaidAfter)
                println("   loan ${p.loan.account}: gap now $again")
                if (again.abs() > tolerance) { println("WARN: loan ${p.loan.account} still has a gap after writing"); left.add(p.loan to "GAP") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("WARN: loan ${p.loan.account} written but not re-checked (ERROR_${e.javaClass.simpleName})")
                left.add(p.loan to "ERROR_${e.javaClass.simpleName}")
            }
        }
        unchecked = left
        return plans
    }

    /**
     * LAN-54: SKIP when the state is the institution's or the user's (ITEM_ERROR, INSTITUTION_ERROR, RATE_LIMIT, 5xx, network), returning
     * a code with no message or figures. null means ABORT: our request is wrong or our code is broken.
     */
    internal suspend fun unavailableCode(e: Exception): String? {
        if (e is ClientRequestException) {
            // M1 (r2): a 429 is skippable whatever its body (a gateway page has no Plaid error_type), so check the status first
            if (e.response.status == HttpStatusCode.TooManyRequests) {
                return runCatching { plaidApiWrapper.plaidErrorTypeAndCode(e).let { it.second ?: it.first } }.getOrNull() ?: "HTTP_429"
            }
            val (type, code) = plaidApiWrapper.plaidErrorTypeAndCode(e)
            // L3 (r2): the permanent codes skip whatever error_type they arrive under
            return if (type == "ITEM_ERROR" || type == "INSTITUTION_ERROR" || type == "RATE_LIMIT" || code in PERMANENT_CODES) code ?: type else null
        }
        val cause = e.cause
        return when {
            // L1: a connection cut while the body is read; a JSON parse error is also an IOException and must still abort
            e is java.io.IOException && e !is com.fasterxml.jackson.core.JsonProcessingException -> "NETWORK_${e.javaClass.simpleName}"
            e !is RuntimeException -> null
            cause is ServerResponseException -> "HTTP_${cause.response.status.value}"
            cause is java.io.IOException -> "NETWORK_${cause.javaClass.simpleName}"
            else -> null
        }
    }

    /** Plaid balances per Firefly loan account, plus the loans whose item did not answer (account -> code). An item that fails does not stop the next one. */
    private suspend fun readPlaidOwed(loans: List<LoanConfig>): Pair<Map<Int, BigDecimal>, Map<Int, String>> {
        val (accountMap, items) = syncHelper.getAllPlaidAccessTokenAccountIdSets()
        val wanted = loans.map { it.account }.toSet()
        val out = mutableMapOf<Int, BigDecimal>()
        val failed = mutableMapOf<Int, String>()
        for ((token, ids) in items.toList()) {
            val ours = ids.filter { accountMap[it] in wanted }
            if (ours.isEmpty()) continue
            val balances = try {
                plaidApiWrapper.executeRequest(
                    { it.accountsBalanceGet(AccountsBalanceGetRequest(token, null, null, AccountsBalanceGetRequestOptions(ours, null))) },
                    "balance get request",
                    boundRateLimit = true,
                ).body().accounts
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = unavailableCode(e) ?: throw e
                ours.forEach { failed[accountMap[it]!!] = code }
                continue
            }
            balances.forEach { b -> b.balances.current?.let { c -> accountMap[b.accountId]?.let { f -> out[f] = BigDecimal.valueOf(c) } } }
        }
        return out to failed
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
        val plaidOwed = (plaid ?: readPlaidOwed(listOf(loan)).let { (owed, failed) ->
            failed[loan.account]?.let { throw PlaidBalanceUnavailable(it, RuntimeException("Plaid did not answer")) }
            owed
        })[loan.account] ?: error("Plaid has no balance for loan ${loan.account}")
        val name = fireflyAccountsApi.getAccount(id, null).body().data.attributes.name
        val fireflyOwed = fireflyOwed(loan.account, null)
        val gap = plaidOwed - fireflyOwed
        fun done(action: String, lines: List<InterestLine> = listOf(), residual: BigDecimal = BigDecimal.ZERO) =
            LoanPlan(loan, name, plaidOwed, fireflyOwed, gap, lines, residual, action)
        if (gap.abs() <= tolerance) return done("OK: within $tolerance")
        // Plaid owes LESS than Firefly: Plaid has seen a payment that Firefly has not (not imported or not routed yet)
        if (gap.signum() < 0) return done("Firefly lags: Plaid owes less than Firefly, so a payment Plaid has seen may be missing in Firefly, or Firefly holds interest or withdrawals Plaid does not; nothing written")

        val splits = splitsOf(loan.account)
        // the connector's own rows (interest, unexplained residual) and tagged rows are never payments
        fun own(it: TransactionSplit) = it.description.startsWith("Interest on ") || it.description.startsWith(UNEXPLAINED_DESC) ||
            it.tags?.contains(UNEXPLAINED_TAG) == true
        val payments = splits.filter { it.destinationId == id && !own(it) }
            .map { LoanPayment(dateOf(it), BigDecimal(it.amount), it.transactionJournalId) }.sortedBy { it.date }
        val interestRows = splits.filter { it.sourceId == id && it.description.startsWith("Interest on ") }
        val covered = interestRows.map { dateOf(it) to it.description }.toSet()
        val coveredIds = interestRows.mapNotNull { it.externalId }.toSet()
        val lines = mutableListOf<InterestLine>()
        payments.forEachIndexed { i, p ->
            if (p.journalId != null && EXTERNAL_PREFIX + p.journalId in coveredIds) return@forEachIndexed
            if ((p.date to interestDescription(name, p)) in covered) return@forEachIndexed
            val days = if (i == 0) 0 else ChronoUnit.DAYS.between(payments[i - 1].date, p.date)
            // Firefly's balance lacks the interest of the earlier uncovered payments of this plan
            val owedBefore = fireflyOwed(loan.account, p.date.minusDays(1)) + lines.fold(BigDecimal.ZERO) { a, l -> a + l.expected }
            lines.add(InterestLine(p, days, owedBefore, expectedInterest(owedBefore, loan.apr, days)))
        }
        val sum = lines.fold(BigDecimal.ZERO) { a, l -> a + l.expected }
        val off = gap - sum
        val explained = off.abs() <= BigDecimal.valueOf(max(tolerance.toDouble(), sum.toDouble() * 0.005))
        // the cents remainder rides on the biggest line, so a negative one cannot turn a line zero or negative
        val big = lines.indices.maxByOrNull { lines[it].expected }
        val fits = big != null && lines[big].expected + off > BigDecimal.ZERO
        if (lines.isEmpty() || !explained || !fits) {
            if (holdUnexplained) return done("HELD: payments explain $sum of the gap $gap; nothing written, retried next run")
            return done("UNEXPLAINED: payments explain $sum of the gap $gap; ${money(off)} goes to \"$UNEXPLAINED\"", lines, off)
        }
        val adjusted = lines.mapIndexed { i, l -> if (i == big) l.copy(expected = l.expected + off) else l }
        return done("write ${lines.size} interest journals", adjusted)
    }

    private suspend fun write(p: LoanPlan) {
        val id = p.loan.account.toString()
        // U04: re-list just before storing, with the identity rule plan() used, so a second writer's line is not stored again
        val now = splitsOf(p.loan.account).filter { it.sourceId == id && it.description.startsWith("Interest on ") }
        val coveredNow = now.map { dateOf(it) to it.description }.toSet()
        val coveredIdsNow = now.mapNotNull { it.externalId }.toSet()
        var skipped = 0
        // the line that carries the cents remainder goes first: a later failure leaves exact lines and the rerun has no remainder
        for (l in p.lines.sortedByDescending { it.expected }) {
            if (l.expected.signum() <= 0) continue
            if ((l.payment.journalId != null && EXTERNAL_PREFIX + l.payment.journalId in coveredIdsNow) ||
                (l.payment.date to interestDescription(p.name, l.payment)) in coveredNow) {
                skipped++
                println("   loan ${p.loan.account}: interest for payment ${l.payment.date} already stored by another writer; skipped")
                continue
            }
            store(TransactionSplit(
                type = TransactionTypeProperty.withdrawal, date = l.payment.date.atStartOfDay(zone).toOffsetDateTime(),
                amount = money(l.expected), description = interestDescription(p.name, l.payment),
                sourceId = id, destinationId = null, destinationName = p.loan.interestAccount,
                externalId = l.payment.journalId?.let { EXTERNAL_PREFIX + it },
            ))
        }
        // the residual was sized for the old gap; with a line skipped it is stale, so the next run recomputes it
        if (p.residual.signum() != 0 && skipped == 0) {
            val date = LocalDate.now(zone).atStartOfDay(zone).toOffsetDateTime()
            val desc = "$UNEXPLAINED_DESC${p.name}"
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
        // Firefly answers 422 to a create with order or reconciled sent as null; the importer sends 0 and false too
        val s = split.copy(order = split.order ?: 0, reconciled = split.reconciled ?: false)
        fireflyTxApi.storeTransaction(TransactionStore(listOf(s), errorIfDuplicateHash = false, applyRules = false, fireWebhooks = false, groupTitle = null))
    }

    private fun print(p: LoanPlan) {
        println("== Loan ${p.loan.account} \"${p.name}\": plaidOwed=${money(p.plaidOwed)} fireflyOwed=${money(p.fireflyOwed)} gap=${money(p.gap)}")
        p.lines.forEach { println("   payment ${it.payment.date} ${money(it.payment.amount)}: ${it.days} days on ${money(it.owedBefore)} at ${p.loan.apr}% -> interest ${money(it.expected)}") }
        if (p.residual.signum() != 0) println("   !!! UNEXPLAINED ${money(p.residual)} (tag $UNEXPLAINED_TAG)")
        println("   ${p.action}")
    }
}
