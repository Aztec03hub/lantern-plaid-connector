package net.djvk.fireflyPlaidConnector2.sync

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapper
import net.djvk.fireflyPlaidConnector2.api.plaid.models.ItemGetRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.LiabilitiesGetRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.LiabilitiesGetRequestOptions
import net.djvk.fireflyPlaidConnector2.api.plaid.models.Products
import net.djvk.fireflyPlaidConnector2.config.AccountConfig
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

private fun money(d: Double?) = d?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP).toPlainString() }
private fun rate(d: Double) = BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()

/** One card account's terms as Plaid sent them. Everything is a string or null; no token, no Plaid id. */
internal data class LiabilityRow(
    val account: AccountConfig, val itemStatus: String, val status: String, val errorCode: String? = null,
    val aprs: List<Pair<String, String>> = listOf(), val minimum: String? = null, val nextDue: String? = null,
    val statementBalance: String? = null, val statementDate: String? = null, val overdue: Boolean? = null,
)

/**
 * `syncMode: liabilities` (U50a): per configured Item, `/item/get` (free) and, only when `liabilities` is among the Item's BILLED
 * products (never `products` alone: the first call could start billing), `/liabilities/get`. Writes `liabilities.json` (mode 600)
 * beside `balance_gap.json` and prints one line per account. Reads Plaid only: no Firefly call, nothing adopted. Information
 * (exit 0) whatever the per-account status; fails only when the file cannot be written.
 */
@ConditionalOnProperty(name = ["fireflyPlaidConnector2.syncMode"], havingValue = "liabilities")
@Component
class LiabilitiesRunner(
    private val syncHelper: SyncHelper,
    private val plaidApiWrapper: PlaidApiWrapper,
    private val accountConfigs: AccountConfigs,
    @Value("\${fireflyPlaidConnector2.polled.cursorFileDirectoryPath:persistence}")
    private val directory: String = "persistence",
) : Runner {
    override fun run() = runBlocking<Unit> {
        val (accountMap, items) = syncHelper.getAllPlaidAccessTokenAccountIdSets()
        val byPlaidId = accountConfigs.accounts.associateBy { it.plaidAccountId }
        val rows = mutableListOf<LiabilityRow>()
        for ((token, ids) in items.toList()) {
            val ours = ids.filter { it in accountMap }
            if (ours.isEmpty()) continue
            val configs = ours.map { byPlaidId.getValue(it) }
            try {
                val billed = plaidApiWrapper.executeRequest({ it.itemGet(ItemGetRequest(token)) }, "item get request", boundRateLimit = true)
                    .body().item.billedProducts
                if (Products.liabilities !in billed) {
                    configs.forEach { rows.add(LiabilityRow(it, "not_linked", "not_linked")) }
                    continue
                }
                val body = plaidApiWrapper.executeRequest(
                    { it.liabilitiesGet(LiabilitiesGetRequest(accessToken = token, options = LiabilitiesGetRequestOptions(ours))) },
                    "liabilities get request", boundRateLimit = true,
                ).body()
                val credit = (body.liabilities.credit ?: listOf()).associateBy { it.accountId }
                configs.forEach { c ->
                    val l = credit[c.plaidAccountId]
                    rows.add(if (l == null) LiabilityRow(c, "ok", "no_credit_liability") else LiabilityRow(
                        c, "ok", "ok",
                        aprs = l.aprs.map { it.aprType.value to rate(it.aprPercentage) },
                        minimum = money(l.minimumPaymentAmount), nextDue = l.nextPaymentDueDate?.toString(),
                        statementBalance = money(l.lastStatementBalance), statementDate = l.lastStatementIssueDate?.toString(),
                        overdue = l.isOverdue,
                    ))
                }
            } catch (e: ClientRequestException) {
                val transient = plaidApiWrapper.transientPlaidError(e) != null
                val code = plaidApiWrapper.plaidErrorTypeAndCode(e).second
                val s = if (transient) "unavailable" else "error"
                configs.forEach { rows.add(LiabilityRow(it, s, s, code)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the exception text can hold response data, so only the fixed code goes in the file
                configs.forEach { rows.add(LiabilityRow(it, "error", "error", "DECODE")) }
            }
        }
        val sorted = rows.sortedBy { it.account.fireflyAccountId }
        write(sorted, Instant.now())
        sorted.forEach { r ->
            val a = r.account
            val purchase = r.aprs.firstOrNull { it.first == "purchase_apr" }?.second
            println("account ${a.fireflyAccountId} ${a.displayName ?: a.institutionName ?: "-"} mask ${a.mask ?: "n/a"}: ${r.status}" +
                (r.errorCode?.let { " $it" } ?: "") + " purchase_apr ${purchase ?: "n/a"}% min ${r.minimum ?: "n/a"} due ${r.nextDue ?: "n/a"}")
        }
    }

    private fun write(rows: List<LiabilityRow>, checkedAt: Instant) {
        val root = linkedMapOf<String, Any?>(
            "version" to 1, "checked_at" to checkedAt.toString().replace(Regex("\\.\\d+Z$"), "Z"),
            "accounts" to rows.map {
                linkedMapOf(
                    "firefly_account" to it.account.fireflyAccountId, "institution" to it.account.institutionName,
                    "display_name" to it.account.displayName, "mask" to it.account.mask,
                    "item_status" to it.itemStatus, "status" to it.status, "error_code" to it.errorCode,
                    "aprs" to it.aprs.map { (t, p) -> linkedMapOf("type" to t, "percentage" to p) },
                    "minimum_payment" to it.minimum, "next_payment_due_date" to it.nextDue,
                    "last_statement_balance" to it.statementBalance, "last_statement_issue_date" to it.statementDate,
                    "is_overdue" to it.overdue,
                )
            },
        )
        val path = Path.of(directory, "liabilities.json")
        writeAtomically(path, ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(root))
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
    }
}
