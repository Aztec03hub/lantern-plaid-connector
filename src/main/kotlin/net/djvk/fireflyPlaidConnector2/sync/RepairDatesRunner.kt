package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountTypeFilter
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplitUpdate
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeFilter
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionUpdate
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapper
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountsBalanceGetRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountsBalanceGetRequestOptions
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsGetRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsGetRequestOptions
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction as PlaidTransaction

/** Journals whose dates are statement dates (already right); the repair never touches them. */
const val OPENING_BALANCE_DESCRIPTION = "Plaid Connector Initial Balance"
const val STATEMENT_TAG = "lantern-dcu-statement"
private const val LEGACY_DCU_OPENING = "DCU statement opening balance"

/** One journal to re-date to Plaid's posted date, and to give the authorized date in book_date. */
data class Redate(
    val groupId: String,
    val journalId: String?,
    val description: String,
    val oldDate: OffsetDateTime,
    val newDate: OffsetDateTime,
    val newBookDate: OffsetDateTime?,
)

/** An account's opening balance, before and after the repair. */
data class OpeningFix(
    val fireflyAccountId: Int,
    val name: String,
    val oldOpening: BigDecimal?,
    val oldOpeningDate: LocalDate?,
    val legacyJournalSum: BigDecimal,
    val legacyGroupIds: List<String>,
    val newOpening: BigDecimal?,
    val newOpeningDate: LocalDate?,
    val liabilityDirection: String?,
    /** Why nothing is changed for this account, if so. */
    val skipReason: String? = null,
) {
    val changes: Boolean get() = skipReason == null && (legacyGroupIds.isNotEmpty() ||
            oldOpening?.compareTo(newOpening ?: BigDecimal.ZERO) != 0 || oldOpeningDate != newOpeningDate)
}

data class RepairPlan(
    val redates: List<Redate>,
    val unmatchedJournals: Int,
    val openings: List<OpeningFix>,
    /** Legacy opening groups that sit on accounts the repair does not handle: reported, never deleted. */
    val orphanInitialBalanceAccountId: String?,
)

/** Everything [RepairPlanner] needs, already read from Plaid and Firefly. */
data class RepairInput(
    val journals: List<TransactionRead>,
    val plaidTxs: Map<String, PlaidTransaction>,
    /** Plaid `current` balance by Firefly account id, for the accounts in the configuration. */
    val plaidCurrent: Map<Int, Double>,
    val accounts: Map<Int, AccountRead>,
)

/** The pure decisions of the repair: no I/O, so they are tested on their own. */
class RepairPlanner(private val converter: TransactionConverter, private val zoneId: ZoneId) {
    private fun isLegacyOpening(s: net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit) =
        s.description == OPENING_BALANCE_DESCRIPTION || s.description == LEGACY_DCU_OPENING ||
                s.externalId?.startsWith("dcu-stmt:opening") == true

    fun plan(input: RepairInput): RepairPlan {
        val redates = mutableListOf<Redate>()
        val newDates = mutableMapOf<String, OffsetDateTime>() // groupId -> date after the repair
        var unmatched = 0
        for (group in input.journals) {
            val split = group.attributes.transactions.singleOrNull() ?: continue
            if (split.tags.orEmpty().contains(STATEMENT_TAG) || isLegacyOpening(split)) continue
            if (split.type == TransactionTypeProperty.openingBalance) continue
            val links = split.plaidLinks.orEmpty()
            if (links.isEmpty()) continue
            // A pair carries the destination leg's date (that leg has the best categorization, see convertDoublePlaid)
            val link = links.firstOrNull { it.leg == PlaidLinkLeg.destination } ?: links.first()
            val plaid = input.plaidTxs[link.plaidTransactionId]
            val newDate: OffsetDateTime
            var newBook: OffsetDateTime? = null
            if (plaid != null) {
                newDate = converter.getTxPostedTimestamp(plaid)
                newBook = converter.getTxAuthorizedTimestamp(plaid)
            } else {
                // Not in Plaid's current history: only the winter shift is certain (a fake midnight UTC stored as 23:00 the day before)
                val local = split.date.atZoneSameInstant(zoneId)
                if (local.hour != 23 || local.minute != 0) { unmatched++; newDates[group.id] = split.date; continue }
                newDate = TransactionConverter.getOffsetDateTimeForDate(zoneId, local.toLocalDate().plusDays(1))
                unmatched++
            }
            newDates[group.id] = newDate
            val bookDiffers = newBook != null &&
                    split.bookDate?.atZoneSameInstant(zoneId)?.toLocalDate() != newBook.atZoneSameInstant(zoneId).toLocalDate()
            if (!split.date.toInstant().equals(newDate.toInstant()) || bookDiffers) {
                redates.add(Redate(group.id, split.transactionJournalId, split.description, split.date, newDate, if (bookDiffers) newBook else null))
            }
        }

        // Accounts with legacy openings, plus every configured Plaid account
        val legacyByAccount = mutableMapOf<Int, MutableList<Pair<String, net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit>>>()
        for (group in input.journals) {
            val split = group.attributes.transactions.singleOrNull() ?: continue
            if (!isLegacyOpening(split)) continue
            val account = (split.destinationId ?: split.sourceId)?.toIntOrNull() ?: continue
            // the own account is the one that is not the "Initial Balance" side; for a deposit it is the destination
            val own = if (split.type == TransactionTypeProperty.deposit) split.destinationId?.toIntOrNull() else split.sourceId?.toIntOrNull()
            legacyByAccount.getOrPut(own ?: account) { mutableListOf() }.add(group.id to split)
        }
        val accountIds = (input.plaidCurrent.keys + legacyByAccount.keys).toSortedSet()
        val openings = accountIds.map { id -> openingFix(id, input, legacyByAccount[id].orEmpty(), newDates) }

        return RepairPlan(redates, unmatched, openings, null)
    }

    private fun openingFix(
        id: Int,
        input: RepairInput,
        legacy: List<Pair<String, net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit>>,
        newDates: Map<String, OffsetDateTime>,
    ): OpeningFix {
        val account = input.accounts[id]
        val name = account?.attributes?.name ?: "account $id"
        val isLiability = account?.let { BatchSyncRunner.isLiabilityAccount(it) } == true
        val direction = if (isLiability) (account?.attributes?.liabilityDirection?.value ?: "debit") else null
        val oldOpening = account?.attributes?.openingBalance?.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)
        val oldDate = account?.attributes?.openingBalanceDate?.atZoneSameInstant(zoneId)?.toLocalDate()
        fun effect(s: net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit): BigDecimal {
            val amount = BigDecimal(s.amount)
            return when {
                s.destinationId?.toIntOrNull() == id -> amount
                s.sourceId?.toIntOrNull() == id -> amount.negate()
                else -> BigDecimal.ZERO
            }
        }
        val legacySum = legacy.fold(BigDecimal.ZERO) { a, (_, s) -> a + effect(s) }.setScale(2, RoundingMode.HALF_UP)
        val legacyIds = legacy.map { it.first }.distinct()

        val current = input.plaidCurrent[id]
        val (opening, date) = if (current != null) {
            // Anchored to Plaid: the account must end at +-current with all its imported journals in it
            val owedNegative = account?.let { BatchSyncRunner.carriesOwedAsNegative(it) } == true
            val target = BigDecimal.valueOf(current).let { if (owedNegative) it.negate() else it }
            val own = input.journals.filter { g ->
                val s = g.attributes.transactions.singleOrNull()
                s != null && !isLegacyOpening(s) && s.type != TransactionTypeProperty.openingBalance
            }.flatMap { g -> g.attributes.transactions.map { g.id to it } }
            val sum = own.fold(BigDecimal.ZERO) { a, (_, s) -> a + effect(s) }
            val earliest = own.filter { effect(it.second).signum() != 0 }
                .minOfOrNull { (gid, s) -> (newDates[gid] ?: s.date).atZoneSameInstant(zoneId).toLocalDate() }
            (target - sum).setScale(2, RoundingMode.HALF_UP) to (earliest ?: LocalDate.now(zoneId)).minusDays(1)
        } else {
            // Not in Plaid (a loan kept from statements): keep the opening the legacy journal had, same amount and date
            val date = legacy.firstOrNull()?.second?.date?.atZoneSameInstant(zoneId)?.toLocalDate()
            (if (isLiability) legacySum.abs() else legacySum) to date
        }
        val skip = when {
            legacy.isEmpty() && current == null -> "nothing to repair"
            isLiability && direction != "credit" && opening.signum() > 0 && current != null ->
                "a debit liability needs a positive opening (${opening.toPlainString()}), which Firefly forces to negative; set it by hand"
            else -> null
        }
        return OpeningFix(id, name, oldOpening, oldDate, legacySum, legacyIds, opening, date, direction, skip)
    }
}

/**
 * `syncMode: repair-dates`: repairs data imported by older builds, from Plaid alone (no statements needed).
 *  1. re-dates every Plaid-sourced journal to Plaid's posted date (and keeps the authorized date in book_date);
 *     journals tagged [STATEMENT_TAG] are left alone;
 *  2. replaces every "Initial Balance" expense-account opening journal with the account's own opening balance
 *     (PUT /accounts/{id}), re-anchored to Plaid's balance and dated the day before the account's first transaction;
 *     a loan kept from statements keeps its amount and date;
 *  3. deletes the "Initial Balance" expense account if nothing is left in it;
 *  4. prints Firefly's balance against Plaid's posted balance per account, so any remaining gap is visible.
 * It prints the whole plan and changes nothing unless `fireflyPlaidConnector2.repair.apply=true`. Idempotent.
 */
@ConditionalOnProperty(name = ["fireflyPlaidConnector2.syncMode"], havingValue = "repair-dates")
@Component
class RepairDatesRunner(
    private val syncHelper: SyncHelper,
    private val plaidApiWrapper: PlaidApiWrapper,
    private val fireflyAccountsApi: AccountsApi,
    private val fireflyTxApi: TransactionsApi,
    private val fireflyTransactionService: FireflyTransactionService,
    private val converter: TransactionConverter,
    @Value("\${fireflyPlaidConnector2.timeZone}")
    timeZoneString: String,
    @Value("\${fireflyPlaidConnector2.plaid.batchSize}")
    private val plaidBatchSize: Int,
    @Value("\${fireflyPlaidConnector2.repair.apply:false}")
    private val apply: Boolean = false,
    /** How far back Plaid is asked for transactions (Plaid keeps at most about 24 months). */
    @Value("\${fireflyPlaidConnector2.repair.days:800}")
    private val repairDays: Int = 800,
) : Runner {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val zoneId = ZoneId.of(timeZoneString)
    private val planner = RepairPlanner(converter, zoneId)

    override fun run() = runBlocking<Unit> {
        syncHelper.setApiCreds()
        converter.accountKinds = syncHelper.fetchAccountKinds()
        val (input, pendingByAccount) = readState()
        val plan = planner.plan(input)
        print(plan, input, pendingByAccount)
        if (!apply) {
            println("DRY RUN: nothing was changed. Add --fireflyPlaidConnector2.repair.apply=true to apply.")
            return@runBlocking
        }
        apply(plan)
        println("APPLIED. Re-reading to check:")
        val (after, pendingAfter) = readState()
        val again = planner.plan(after)
        print(again, after, pendingAfter)
        check(again.redates.isEmpty() && again.openings.none { it.changes }) { "The repair is not idempotent: a second plan still has changes" }
    }

    private suspend fun readState(): Pair<RepairInput, Map<Int, BigDecimal>> {
        val (accountMap, items) = syncHelper.getAllPlaidAccessTokenAccountIdSets()
        val end = LocalDate.now(zoneId)
        val plaidTxs = mutableMapOf<String, PlaidTransaction>()
        val plaidCurrent = mutableMapOf<Int, Double>()
        val pending = mutableMapOf<Int, BigDecimal>()
        for ((token, ids) in items) {
            var offset = 0
            do {
                val request = TransactionsGetRequest(
                    token, end.minusDays(repairDays.toLong()), end, null,
                    TransactionsGetRequestOptions(ids, plaidBatchSize, offset, includeOriginalDescription = true, includePersonalFinanceCategory = true),
                )
                val page = plaidApiWrapper.executeRequest({ it.transactionsGet(request) }, "transaction get request").body().transactions
                page.forEach {
                    plaidTxs[it.transactionId] = it
                    if (it.pending) accountMap[it.accountId]?.let { f -> pending.merge(f, BigDecimal.valueOf(it.amount), BigDecimal::add) }
                }
                offset += page.size
            } while (page.size == plaidBatchSize)
            val balances = plaidApiWrapper.executeRequest(
                { it.accountsBalanceGet(AccountsBalanceGetRequest(token, null, null, AccountsBalanceGetRequestOptions(ids, null))) },
                "balance get request",
            ).body().accounts
            balances.forEach { b -> b.balances.current?.let { c -> accountMap[b.accountId]?.let { f -> plaidCurrent[f] = c } } }
        }
        val journals = fireflyTransactionService.fetchFireflyTransactionsBetween(end.minusDays(repairDays.toLong() + 400), end.plusDays(2), 5000)
        val accountIds = (plaidCurrent.keys + journals.flatMap { g -> g.attributes.transactions.flatMap { listOfNotNull(it.sourceId?.toIntOrNull(), it.destinationId?.toIntOrNull()) } }).toSet()
        val accounts = accountIds.mapNotNull { id ->
            runCatching { fireflyAccountsApi.getAccount(id.toString(), null).body().data }.getOrNull()?.let { id to it }
        }.toMap()
        return RepairInput(journals, plaidTxs, plaidCurrent, accounts) to pending
    }

    private fun print(plan: RepairPlan, input: RepairInput, pending: Map<Int, BigDecimal>) {
        println("== Dates: ${plan.redates.size} journals to re-date (${plan.unmatchedJournals} not found in Plaid's history)")
        plan.redates.take(20).forEach { println("   ${it.groupId} ${it.description.take(40)}: ${it.oldDate} -> ${it.newDate}") }
        if (plan.redates.size > 20) println("   ... and ${plan.redates.size - 20} more")
        println("== Opening balances")
        for (o in plan.openings) {
            println(
                "   account ${o.fireflyAccountId} (${o.name}): opening ${o.oldOpening ?: "-"} on ${o.oldOpeningDate ?: "-"}" +
                        " + ${o.legacyGroupIds.size} legacy opening journals (${o.legacyJournalSum})" +
                        " -> ${o.newOpening ?: "-"} on ${o.newOpeningDate ?: "-"}" +
                        (o.skipReason?.let { "  [SKIPPED: $it]" } ?: if (o.changes) "" else "  [no change]")
            )
        }
        println("== Firefly balance vs Plaid posted balance (Plaid current with its listed pending items backed out)")
        for ((id, current) in input.plaidCurrent.toSortedMap()) {
            val account = input.accounts[id] ?: continue
            val owedNegative = BatchSyncRunner.carriesOwedAsNegative(account)
            val target = BigDecimal.valueOf(current).let { if (owedNegative) it.negate() else it }
            val postedAnchor = target + (pending[id] ?: BigDecimal.ZERO)
            val firefly = account.attributes.currentBalance?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val fireflyPosted = firefly + (pending[id] ?: BigDecimal.ZERO)
            println(
                "   account $id (${account.attributes.name}): firefly now $firefly, plaid posted $postedAnchor, " +
                        "gap ${(fireflyPosted - postedAnchor).setScale(2, RoundingMode.HALF_UP)}"
            )
        }
        println("   (a non-zero gap after the repair means Plaid's balance holds something it never lists as a transaction)")
    }

    private suspend fun apply(plan: RepairPlan) {
        for (r in plan.redates) {
            fireflyTxApi.updateTransaction(
                r.groupId,
                TransactionUpdate(
                    applyRules = false, fireWebhooks = false,
                    transactions = listOf(TransactionSplitUpdate(date = r.newDate, bookDate = r.newBookDate, transactionJournalId = r.journalId)),
                ),
            )
        }
        for (o in plan.openings.filter { it.changes }) {
            val date = o.newOpeningDate ?: continue
            val amount = o.newOpening ?: continue
            if (amount.signum() != 0) {
                fireflyAccountsApi.setOpeningBalance(o.fireflyAccountId.toString(), amount.toPlainString(), date, o.liabilityDirection)
            }
            // The opening is in place first, so a crash here leaves a duplicate legacy journal, never a gap
            syncHelper.deleteBatchInFirefly(o.legacyGroupIds)
        }
        deleteOrphanInitialBalanceAccount()
    }

    private suspend fun deleteOrphanInitialBalanceAccount() {
        var expense: AccountRead? = null
        var page = 1
        do {
            val response = fireflyAccountsApi.listAccount(page++, null, AccountTypeFilter.expense).body()
            expense = response.data.firstOrNull { it.attributes.name == "Initial Balance" }
            val more = response.meta.pagination?.let { it.currentPage < it.totalPages } == true
        } while (expense == null && more)
        if (expense == null) return
        val left = fireflyAccountsApi.listTransactionByAccount(expense.id, 1, 1, null, null, TransactionTypeFilter.all).body().data
        if (left.isNotEmpty()) {
            println("The expense account \"Initial Balance\" still has journals; not deleted.")
            return
        }
        try {
            fireflyAccountsApi.deleteAccount(expense.id)
            println("Deleted the empty expense account \"Initial Balance\".")
        } catch (e: ClientRequestException) {
            logger.error("Could not delete the Initial Balance expense account (HTTP {})", e.response.status.value)
        }
    }
}
