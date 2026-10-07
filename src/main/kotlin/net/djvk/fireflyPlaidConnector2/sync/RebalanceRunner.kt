package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeFilter
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountsBalanceGetRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountsBalanceGetRequestOptions
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapper
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import net.djvk.fireflyPlaidConnector2.util.Utilities.redactAccessToken
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

const val OPENING_BALANCE_DESCRIPTION = "Plaid Connector Initial Balance"

/**
 * What to do to one account's opening balance so that it ends at [target]: [currentFirefly] is what Firefly shows now,
 * [currentOpening] the signed effect of the existing opening-balance journals, [newOpening] what they should add up to.
 */
data class RebalancePlan(
    val fireflyAccountId: Int,
    val name: String,
    val currentFirefly: BigDecimal,
    val target: BigDecimal,
    val currentOpening: BigDecimal,
    val oldJournalGroupIds: List<String>,
    val earliest: java.time.OffsetDateTime?,
) {
    val delta: BigDecimal get() = target - currentFirefly
    val newOpening: BigDecimal get() = currentOpening + delta
}

/**
 * `syncMode: rebalance`: repairs the opening balance of accounts that carry what they owe as a negative balance
 * (liabilities with direction debit, credit card assets) and were given the wrong sign by an older build. It keeps every
 * imported transaction and replaces only the "Plaid Connector Initial Balance" journals of those accounts so that
 * Firefly ends at -(Plaid current). Assets are never touched. Dry run unless `rebalance.apply: true`.
 */
@ConditionalOnProperty(name = ["fireflyPlaidConnector2.syncMode"], havingValue = "rebalance")
@Component
class RebalanceRunner(
    private val syncHelper: SyncHelper,
    private val plaidApiWrapper: PlaidApiWrapper,
    private val fireflyAccountsApi: AccountsApi,
    private val fireflyTxApi: TransactionsApi,
    @Value("\${fireflyPlaidConnector2.rebalance.apply:false}")
    private val apply: Boolean = false,
) : Runner {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun run() = runBlocking<Unit> {
        syncHelper.setApiCreds()
        val plans = plan()
        for (p in plans) {
            println(
                "account ${p.fireflyAccountId} (${p.name}): firefly now ${p.currentFirefly}, target ${p.target}, " +
                        "opening ${p.currentOpening} -> ${p.newOpening} (change ${p.delta})" +
                        if (p.delta.signum() == 0) "  [no change]" else ""
            )
        }
        if (!apply) {
            println("DRY RUN: nothing was changed. Set fireflyPlaidConnector2.rebalance.apply=true to apply.")
            return@runBlocking
        }
        for (p in plans.filter { it.delta.signum() != 0 }) apply(p)
    }

    suspend fun plan(): List<RebalancePlan> {
        val (accountMap, items) = syncHelper.getAllPlaidAccessTokenAccountIdSets()
        val plans = mutableListOf<RebalancePlan>()
        for ((accessToken, accountIds) in items) {
            val balances = plaidApiWrapper.executeRequest(
                { api -> api.accountsBalanceGet(AccountsBalanceGetRequest(accessToken, null, null, AccountsBalanceGetRequestOptions(accountIds, null))) },
                "balance get request"
            ).body().accounts.associate { it.accountId to it.balances.current }
            for (plaidId in accountIds) {
                val fireflyId = accountMap[plaidId] ?: continue
                val current = balances[plaidId] ?: run {
                    logger.warn("No Plaid balance for account {} ({}); skipped", fireflyId, redactAccessToken(accessToken)); null
                } ?: continue
                val account = fireflyAccountsApi.getAccount(fireflyId.toString(), null).body().data
                if (!BatchSyncRunner.carriesOwedAsNegative(account)) continue // assets keep what they have
                plans.add(planFor(fireflyId, account, BigDecimal.valueOf(current)))
            }
        }
        return plans
    }

    private suspend fun planFor(fireflyId: Int, account: AccountRead, plaidCurrent: BigDecimal): RebalancePlan {
        val opening = openingJournals(fireflyId.toString())
        val signed = opening.fold(BigDecimal.ZERO) { acc, (_, split) ->
            val amount = BigDecimal(split.amount)
            if (split.type == TransactionTypeProperty.deposit) acc + amount else acc - amount
        }
        return RebalancePlan(
            fireflyId, account.attributes.name,
            BigDecimal(account.attributes.currentBalance ?: "0").setScale(2, RoundingMode.HALF_UP),
            plaidCurrent.negate().setScale(2, RoundingMode.HALF_UP),
            signed.setScale(2, RoundingMode.HALF_UP),
            opening.map { it.first }.distinct(),
            opening.minOfOrNull { it.second.date },
        )
    }

    private suspend fun openingJournals(accountId: String): List<Pair<String, TransactionSplit>> {
        val out = mutableListOf<Pair<String, TransactionSplit>>()
        var page = 1
        do {
            val response = fireflyAccountsApi.listTransactionByAccount(accountId, page++, null, null, null, TransactionTypeFilter.all).body()
            for (group in response.data) {
                group.attributes.transactions.filter { it.description == OPENING_BALANCE_DESCRIPTION }
                    .forEach { out.add(group.id to it) }
            }
            val pagination = response.meta.pagination
            val more = pagination != null && pagination.currentPage < pagination.totalPages
        } while (more)
        return out
    }

    /** Creates the corrected opening journal first, then removes the old ones, so a crash never leaves an account without one. */
    private suspend fun apply(p: RebalancePlan) {
        val negative = p.newOpening.signum() < 0
        if (p.newOpening.signum() != 0) {
            val date = p.earliest ?: java.time.OffsetDateTime.now().minusYears(2)
            syncHelper.optimisticInsertBatchIntoFirefly(
                listOf(
                    FireflyTransactionDto(
                        null, TransactionSplit(
                            type = if (negative) TransactionTypeProperty.withdrawal else TransactionTypeProperty.deposit,
                            date = date,
                            amount = p.newOpening.abs().toPlainString(),
                            description = OPENING_BALANCE_DESCRIPTION,
                            sourceName = if (negative) null else "Initial Balance",
                            sourceId = if (negative) p.fireflyAccountId.toString() else null,
                            destinationName = if (negative) "Initial Balance" else null,
                            destinationId = if (negative) null else p.fireflyAccountId.toString(),
                            order = 0,
                            reconciled = false,
                        )
                    )
                )
            )
        }
        syncHelper.deleteBatchInFirefly(p.oldJournalGroupIds)
        println("account ${p.fireflyAccountId}: opening balance is now ${p.newOpening}")
    }
}
