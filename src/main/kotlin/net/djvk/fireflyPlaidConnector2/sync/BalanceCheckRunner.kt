package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeFilter
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapper
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountsBalanceGetRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountsBalanceGetRequestOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What one account's check found tonight, before first_seen is merged in. Money is null for `unavailable` and `missing`. */
data class BalanceResult(
    val account: Int, val plaidType: String, val status: String,
    val firefly: BigDecimal? = null, val plaid: BigDecimal? = null, val gap: BigDecimal? = null, val errorCode: String? = null,
)

private fun money(x: BigDecimal?) = x?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()

/**
 * Plaid `current` is the amount OWED (positive) for credit and loan, so those flip to Firefly's negative sign; the Plaid
 * type alone decides (a card is negative in Firefly whether it is an asset or a liability, see BatchSyncRunner.carriesOwedAsNegative).
 * gap = fireflyPosted - plaidSigned; `ok` while abs(gap) <= tolerance.
 */
fun compareBalances(account: Int, plaidType: String, fireflyPosted: BigDecimal, plaidCurrent: BigDecimal, tolerance: BigDecimal, fireflyWithPending: BigDecimal = fireflyPosted): BalanceResult {
    val plaid = if (plaidType == "credit" || plaidType == "loan") plaidCurrent.negate() else plaidCurrent
    // Institutions differ on whether Plaid `current` includes pending: a gap counts only if it is unexplained both ways,
    // and the smaller of the two is the one reported.
    val (firefly, gap) = listOf(fireflyPosted, fireflyWithPending).map { it to it - plaid }.minBy { it.second.abs() }
    return BalanceResult(account, plaidType, if (gap.abs() > tolerance) "gap" else "ok", firefly, plaid, gap)
}

/**
 * Merges tonight's [results] with the [previous] file: a gap keeps the date it first appeared (any amount, either sign),
 * an `ok` night forgets it, an `unavailable` or `missing` night neither starts nor resets the clock. Accounts not in
 * [results] are dropped (removed from the config).
 */
fun mergeFirstSeen(previous: Map<Int, GapEntry>, results: List<BalanceResult>, today: LocalDate): List<GapEntry> = results.map { r ->
    val prev = previous[r.account]
    val earlierGap = prev?.firstSeen  // null after an ok night; kept through unavailable/missing nights
    val live = r.status == "ok" || r.status == "gap"
    GapEntry(
        r.account, r.plaidType, r.status, money(r.firefly), money(r.plaid), money(r.gap),
        firstSeen = when (r.status) { "gap" -> earlierGap ?: today.toString(); "ok" -> null; else -> earlierGap },
        lastSeen = if (live) today.toString() else prev?.lastSeen,
        errorCode = r.errorCode,
    )
}

/**
 * `syncMode: balance-check`: after loan interest, compares Firefly's posted balance with Plaid's live balance for every
 * non-investment account and writes `balance_gap.json` beside `item_status.json`. Reads only; a gap is information (exit 0).
 * Fails (non-zero) only when it could not do its job: a Firefly read failed, a non-transient Plaid error, the file could not be written.
 */
@ConditionalOnProperty(name = ["fireflyPlaidConnector2.syncMode"], havingValue = "balance-check")
@Component
class BalanceCheckRunner(
    private val syncHelper: SyncHelper,
    private val plaidApiWrapper: PlaidApiWrapper,
    private val fireflyAccountsApi: AccountsApi,
    @Value("\${fireflyPlaidConnector2.timeZone:UTC}")
    timeZoneString: String = "UTC",
    @Value("\${fireflyPlaidConnector2.polled.cursorFileDirectoryPath:persistence}")
    private val directory: String = "persistence",
    @Value("\${fireflyPlaidConnector2.balanceCheck.tolerance:0.05}")
    tolerance: Double = 0.05,
    @Value("\${fireflyPlaidConnector2.balanceCheck.pendingLookbackDays:21}")
    private val pendingLookbackDays: Int = 21,
    @Value("\${fireflyPlaidConnector2.pendingTag:pending}")
    private val pendingTag: String = "pending",
) : Runner {
    private val zone = ZoneId.of(timeZoneString)
    private val tolerance = BigDecimal.valueOf(tolerance)
    internal var today: () -> LocalDate = { LocalDate.now(zone) }

    override fun run() = runBlocking<Unit> {
        require(pendingLookbackDays in 1..90) { "balanceCheck.pendingLookbackDays must be 1..90" }
        syncHelper.setApiCreds()
        val store = BalanceGapStore(Path.of(directory, "balance_gap.json"))
        val previous = store.read()
        val now = today()
        val (accountMap, items) = syncHelper.getAllPlaidAccessTokenAccountIdSets()
        val results = mutableListOf<BalanceResult>()
        for ((token, ids) in items.toList()) {
            val ours = ids.filter { it in accountMap }
            if (ours.isEmpty()) continue
            val accounts = try {
                plaidApiWrapper.executeRequest(
                    { it.accountsBalanceGet(AccountsBalanceGetRequest(token, null, null, AccountsBalanceGetRequestOptions(ours, null))) },
                    "balance get request",
                ).body().accounts.associateBy { it.accountId }
            } catch (e: ClientRequestException) {
                val code = plaidApiWrapper.transientPlaidError(e) ?: throw e
                ours.forEach { results.add(BalanceResult(accountMap.getValue(it), previous[accountMap.getValue(it)]?.plaidType ?: "unknown", "unavailable", errorCode = code)) }
                continue
            }
            for (pid in ours) {
                val fid = accountMap.getValue(pid)
                val b = accounts[pid]
                val current = b?.balances?.current
                results.add(if (b == null || current == null) BalanceResult(fid, b?.type?.value ?: previous[fid]?.plaidType ?: "unknown", "missing")
                else fireflyBalances(fid, now).let { (withPending, posted) -> compareBalances(fid, b.type.value, posted, BigDecimal.valueOf(current), tolerance, withPending) })
            }
        }
        val entries = mergeFirstSeen(previous, results.sortedBy { it.account }, now)
        store.write(entries, tolerance, Instant.now())
        entries.forEach { e ->
            val since = e.firstSeen?.let { " since $it" } ?: ""
            println("account ${e.account} ${e.plaidType}: ${e.status} firefly ${e.firefly} plaid ${e.plaid} gap ${e.gap}$since")
            if (e.status == "gap" || e.status == "missing") println("!! balance ${e.status}: account ${e.account} ${e.plaidType} gap ${e.gap}$since")
        }
    }

    /** (with pending, posted): Firefly's balance includes the importer's pending-tagged rows; posted backs them out. Both are compared (see compareBalances). */
    private suspend fun fireflyBalances(id: Int, now: LocalDate): Pair<BigDecimal, BigDecimal> {
        val current = BigDecimal(fireflyAccountsApi.getAccount(id.toString(), null).body().data.attributes.currentBalance ?: error("Firefly has no balance for account $id"))
        if (pendingTag.isBlank()) return current to current
        val from = now.minusDays(pendingLookbackDays.toLong())
        var pending = BigDecimal.ZERO
        var page = 1
        while (true) {
            val body = fireflyAccountsApi.listTransactionByAccount(id.toString(), page++, 50, from, null, TransactionTypeFilter.all).body()
            body.data.flatMap { it.attributes.transactions }
                .filter { pendingTag in (it.tags ?: listOf()) && !it.date.atZoneSameInstant(zone).toLocalDate().isBefore(from) }
                .forEach {
                    val amount = BigDecimal(it.amount)
                    if (it.destinationId == id.toString()) pending += amount else if (it.sourceId == id.toString()) pending -= amount
                }
            val p = body.meta.pagination
            if (p == null || p.currentPage >= p.totalPages) break
        }
        return current to current - pending
    }
}
