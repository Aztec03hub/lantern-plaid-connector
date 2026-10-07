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

/**
 * Statement interest, fee and payment journals carry this tag: their dates are statement dates, already right, so the
 * re-dating leaves them alone. A statement OPENING balance journal (the Lexus loan's, "DCU statement opening balance")
 * is the exception on purpose: it is replaced by the account's own opening balance, same amount and date.
 */
const val OPENING_BALANCE_DESCRIPTION = "Plaid Connector Initial Balance"
const val STATEMENT_TAG = "lantern-dcu-statement"
private const val LEGACY_DCU_OPENING = "DCU statement opening balance"

/** Whether [s] is an opening-balance journal of an older build or of the DCU statement import. */
internal fun isLegacyOpeningSplit(s: net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit) =
    s.description == OPENING_BALANCE_DESCRIPTION || s.description == LEGACY_DCU_OPENING ||
            s.externalId?.startsWith("dcu-stmt:opening") == true

/** The own account of a legacy opening journal: the destination of a deposit, the source of a withdrawal. */
internal fun legacyOwner(s: net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit): Int? =
    (if (s.type == TransactionTypeProperty.deposit) s.destinationId else s.sourceId)?.toIntOrNull()

/** One journal to re-date to Plaid's posted date, and to give the authorized date in book_date. */
data class Redate(
    val groupId: String,
    val journalId: String?,
    val description: String,
    val oldDate: OffsetDateTime,
    val newDate: OffsetDateTime,
    val newBookDate: OffsetDateTime?,
    /** process_date is the posted date too; the journals of older builds hold the fake midnight. */
    val newProcessDate: OffsetDateTime = newDate,
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
    /** Posted Plaid transactions of this account that Firefly has no journal for (the opening is then left alone). */
    val missing: List<PlaidTransaction> = listOf(),
    /** Set when the new opening moves by more than the account's listed pending total plus 1.00; shown, still applied. */
    val sanityNote: String? = null,
    /** Set when the amount was kept because the balance moved by more than Plaid's transactions explain. */
    val keptAmount: String? = null,
) {
    val changes: Boolean get() {
        if (skipReason != null) return false
        val newAmount = newOpening ?: BigDecimal.ZERO
        // Signed: a debit liability is negative and a credit one positive, in what is sent and in what Firefly echoes
        val amountDiffers = (oldOpening ?: BigDecimal.ZERO).compareTo(newAmount) != 0
        // The date of an opening of zero means nothing
        return legacyGroupIds.isNotEmpty() || amountDiffers || (newAmount.signum() != 0 && oldOpeningDate != newOpeningDate)
    }
}

data class RepairPlan(
    val redates: List<Redate>,
    val unmatchedJournals: Int,
    val openings: List<OpeningFix>,
)

/** Everything [RepairPlanner] needs, already read from Plaid and Firefly. */
data class RepairInput(
    val journals: List<TransactionRead>,
    val plaidTxs: Map<String, PlaidTransaction>,
    /** Plaid `current` balance by Firefly account id, for the accounts in the configuration. */
    val plaidCurrent: Map<Int, Double>,
    val accounts: Map<Int, AccountRead>,
    /** Firefly account id of each configured Plaid account id. */
    val fireflyAccountOfPlaid: Map<String, Int> = mapOf(),
)

/** The pure decisions of the repair: no I/O, so they are tested on their own. */
class RepairPlanner(
    private val converter: TransactionConverter,
    private val zoneId: ZoneId,
    /** Firefly account ids whose opening amount may move even when the anchor sanity check fails (gap confirmed by a person). */
    private val reanchor: Set<Int> = setOf(),
    /** Plaid transaction ids that are deliberately not in Firefly: they do not block their account's opening. */
    private val ignoreMissing: Set<String> = setOf(),
) {
    private fun isLegacyOpening(s: net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit) = isLegacyOpeningSplit(s)

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
            // A pair carries the destination leg's date (that leg has the best categorization, see convertDoublePlaid);
            //  when that leg is not in Plaid's history the source leg's record is used
            val plaid = links.sortedBy { it.leg != PlaidLinkLeg.destination }
                .firstNotNullOfOrNull { input.plaidTxs[it.plaidTransactionId] }
            if (plaid == null) {
                // Not in Plaid's history: reported, never moved (there is no record of the real posted date)
                unmatched++
                newDates[group.id] = split.date
                continue
            }
            val newDate = converter.getTxPostedTimestamp(plaid)
            val newBook = converter.getTxAuthorizedTimestamp(plaid)
            newDates[group.id] = newDate
            val bookDiffers = newBook != null &&
                    split.bookDate?.atZoneSameInstant(zoneId)?.toLocalDate() != newBook.atZoneSameInstant(zoneId).toLocalDate()
            val processDiffers = split.processDate?.toInstant() != newDate.toInstant()
            if (!split.date.toInstant().equals(newDate.toInstant()) || bookDiffers || processDiffers) {
                redates.add(Redate(group.id, split.transactionJournalId, split.description, split.date, newDate, if (bookDiffers) newBook else null, newDate))
            }
        }

        // Accounts with legacy openings, plus every configured Plaid account
        val legacyByAccount = mutableMapOf<Int, MutableList<Pair<String, net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit>>>()
        for (group in input.journals) {
            val split = group.attributes.transactions.singleOrNull() ?: continue
            if (!isLegacyOpening(split)) continue
            val own = legacyOwner(split) ?: continue
            legacyByAccount.getOrPut(own) { mutableListOf() }.add(group.id to split)
        }
        val accountIds = (input.plaidCurrent.keys + legacyByAccount.keys).toSortedSet()
        // The start of the imported history, across every configured account: an account with no transactions of its
        //  own is opened then, so its chart does not read 0 until its first (or the importer's) day
        val historyStart = input.journals.flatMap { g -> g.attributes.transactions.map { g.id to it } }
            .filter { (_, t) -> !isLegacyOpening(t) && t.type != TransactionTypeProperty.openingBalance && t.plaidLinks.orEmpty().isNotEmpty() }
            .minOfOrNull { (gid, t) -> (newDates[gid] ?: t.date).atZoneSameInstant(zoneId).toLocalDate() }
        val posted = input.plaidTxs.values.filter { !it.pending }
        val held = input.journals.flatMap { g -> g.attributes.transactions.flatMap { it.plaidLinks.orEmpty() } }
            .map { it.plaidTransactionId }.toSet()
        val splitAccounts = input.journals.filter { it.attributes.transactions.size > 1 }
            .flatMap { g -> g.attributes.transactions.flatMap { listOfNotNull(it.sourceId?.toIntOrNull(), it.destinationId?.toIntOrNull()) } }.toSet()
        val missingByAccount = posted.filter { it.transactionId !in held && it.transactionId !in ignoreMissing }
            .groupBy { input.fireflyAccountOfPlaid[it.accountId] }
        val openings = accountIds.map { id ->
            openingFix(id, input, legacyByAccount[id].orEmpty(), newDates, historyStart, missingByAccount[id].orEmpty(), id in splitAccounts)
        }

        return RepairPlan(redates, unmatched, openings)
    }

    private fun openingFix(
        id: Int,
        input: RepairInput,
        legacy: List<Pair<String, net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit>>,
        newDates: Map<String, OffsetDateTime>,
        historyStart: LocalDate?,
        missing: List<PlaidTransaction>,
        touchedBySplit: Boolean,
    ): OpeningFix {
        val account = input.accounts[id]
        val name = account?.attributes?.name ?: "account $id"
        val isLiability = account?.let { BatchSyncRunner.isLiabilityAccount(it) } == true
        val direction = if (isLiability) (account?.attributes?.liabilityDirection?.value ?: "debit") else null
        val oldOpening = account?.attributes?.openingBalance?.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)
        // the date Firefly echoes, in the offset it echoes it (its own time zone), not converted to ours
        val oldDate = account?.attributes?.openingBalanceDate?.toLocalDate()
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
        var pendingTotal = BigDecimal.ZERO
        var hasOwnJournals = true
        val (opening, date) = if (current != null) {
            // Anchored to Plaid: the posted balance (Plaid's current with its listed pending items backed out) must be
            //  matched by the account's POSTED journals; pending journals are left out of that sum, the same way the
            //  pending items are backed out of the anchor.
            val owedNegative = account?.let { BatchSyncRunner.carriesOwedAsNegative(it) } == true
            val target = BigDecimal.valueOf(current).let { if (owedNegative) it.negate() else it }
            val pendingHere = input.plaidTxs.values.filter { it.pending && input.fireflyAccountOfPlaid[it.accountId] == id }
            val pendingSigned = pendingHere.fold(BigDecimal.ZERO) { a, t -> a + BigDecimal.valueOf(t.amount) }
            pendingTotal = pendingHere.fold(BigDecimal.ZERO) { a, t -> a + BigDecimal.valueOf(t.amount).abs() }
            val anchor = target + pendingSigned
            fun isPending(g: TransactionRead) = g.attributes.transactions.any { t ->
                t.plaidLinks.orEmpty().any { input.plaidTxs[it.plaidTransactionId]?.pending == true }
            }
            val own = input.journals.filter { g ->
                val s = g.attributes.transactions.singleOrNull()
                s != null && !isLegacyOpening(s) && s.type != TransactionTypeProperty.openingBalance && !isPending(g)
            }.flatMap { g -> g.attributes.transactions.map { g.id to it } }
            val sum = own.fold(BigDecimal.ZERO) { a, (_, s) -> a + effect(s) }
            val earliest = own.filter { effect(it.second).signum() != 0 }
                .minOfOrNull { (gid, s) -> (newDates[gid] ?: s.date).atZoneSameInstant(zoneId).toLocalDate() }
            hasOwnJournals = earliest != null
            val day = earliest ?: historyStart ?: LocalDate.now(zoneId)
            (anchor - sum).setScale(2, RoundingMode.HALF_UP) to day.minusDays(1)
        } else {
            // Not in Plaid (a loan kept from statements): keep the opening the legacy journal had, same amount and date.
            //  For a liability the amount owed is what counts, and Firefly gives it the sign of its direction (debit:
            //  negative, credit: positive), so that sign is sent too: it is also what Firefly echoes back later.
            val date = legacy.minOfOrNull { it.second.date.toInstant() }?.atZone(zoneId)?.toLocalDate()
            (if (isLiability) (if (direction == "credit") legacySum.abs() else legacySum.abs().negate()) else legacySum) to date
        }
        val previous = oldOpening ?: legacySum
        val moved = (opening - previous).abs()
        val sanity = if (current != null && moved > pendingTotal + BigDecimal.ONE)
            "opening moves ${previous.toPlainString()} -> ${opening.toPlainString()} (${moved.setScale(2, RoundingMode.HALF_UP)}), more than the listed pending " +
                    "total ${pendingTotal.setScale(2, RoundingMode.HALF_UP)} + 1.00: check it" else null
        // Plaid's balance can include money its transaction list has not caught up with yet (a deposit that posted
        //  today). A move the pending items do not explain keeps the old amount; the date still moves.
        val keep = sanity != null && id !in reanchor && (oldOpening != null || legacy.isNotEmpty())
        val finalOpening = if (keep) previous else opening
        val badSign = isLiability && ((direction == "credit" && finalOpening.signum() < 0) || (direction != "credit" && finalOpening.signum() > 0))
        val skip = when {
            account == null -> "account not read: not changing anything for it"
            current == null && id in input.fireflyAccountOfPlaid.values -> "Plaid returned no balance for this configured account; nothing changed, run again"
            touchedBySplit -> "a split transaction touches this account and its splits are not summed; set the opening by hand"
            legacy.isEmpty() && current == null -> "nothing to repair"
            missing.isNotEmpty() && current != null -> "SKIPPED: ${missing.size} Plaid transactions missing from Firefly (run a sync first, or list the ids in repair.ignoreMissing if they are deliberately not imported)"
            current != null && !hasOwnJournals && opening.signum() == 0 -> "no history and a zero balance: no opening needed"
            badSign ->
                "this liability needs an opening of ${finalOpening.toPlainString()}, which Firefly forces to the other sign (${direction ?: "debit"}); set it by hand"
            else -> null
        }
        val kept = if (keep) "KEPT AMOUNT ${previous.toPlainString()}: balance moved by ${moved.setScale(2, RoundingMode.HALF_UP)} " +
                "that Plaid's transactions don't explain yet; re-run after the next sync (or list the account in repair.reanchor)" else null
        return OpeningFix(id, name, oldOpening, oldDate, legacySum, legacyIds, finalOpening, date, direction, skip, missing, sanity, kept)
    }
}

/**
 * `syncMode: repair-dates`: repairs data imported by older builds, from Plaid alone (no statements needed).
 *  1. re-dates every Plaid-sourced journal to Plaid's posted date (date and process_date; the authorized date goes to
 *     book_date). Journals tagged [STATEMENT_TAG] (statement interest, fee and payment journals) are left alone, and a
 *     journal Plaid has no record of is reported, never moved;
 *  2. replaces every "Initial Balance" expense-account opening journal with the account's own opening balance
 *     (PUT /accounts/{id}), re-anchored to Plaid's balance and dated the day before the account's first transaction;
 *     a loan kept from statements keeps its amount and date. This includes a statement opening journal even when it
 *     carries [STATEMENT_TAG]: replacing that one is intended;
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
    /** Comma separated Firefly account ids that may be re-anchored although the balance moved unexplained. */
    @Value("\${fireflyPlaidConnector2.repair.reanchor:}")
    reanchorIds: String = "",
    /** Comma separated Plaid transaction ids that are deliberately not in Firefly. */
    @Value("\${fireflyPlaidConnector2.repair.ignoreMissing:}")
    ignoreMissingIds: String = "",
) : Runner {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val zoneId = ZoneId.of(timeZoneString)
    private val planner = RepairPlanner(
        converter, zoneId, reanchorIds.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet(),
        ignoreMissingIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
    )

    override fun run() = runBlocking<Unit> {
        syncHelper.setApiCreds()
        converter.accountKinds = syncHelper.fetchAccountKinds()
        val (input, pendingByAccount) = readState()
        val plan = planner.plan(input)
        print(plan, input, pendingByAccount)
        if (!apply) {
            println("DRY RUN: nothing was changed. Add --fireflyPlaidConnector2.repair.apply=true to apply (apply reads Plaid and Firefly again, so it can differ from this plan).")
            return@runBlocking
        }
        applyPlan(plan)
        println("APPLIED. Re-reading to check:")
        val (after, pendingAfter) = readState()
        val again = planner.plan(after)
        print(again, after, pendingAfter)
        // Plaid's balance may have moved between the two reads, so an opening difference is a warning; a date is not
        check(again.redates.isEmpty()) { "The repair is not idempotent: a second plan still wants to re-date ${again.redates.size} journals" }
        again.openings.filter { it.changes }.forEach {
            logger.warn("Account {} still differs after the repair (Plaid's balance may have moved since the first read)", it.fireflyAccountId)
        }
    }

    internal suspend fun readState(): Pair<RepairInput, Map<Int, BigDecimal>> {
        val (accountMap, items) = syncHelper.getAllPlaidAccessTokenAccountIdSets()
        val end = LocalDate.now(zoneId)
        val plaidTxs = mutableMapOf<String, PlaidTransaction>()
        val plaidCurrent = mutableMapOf<Int, Double>()
        val pending = mutableMapOf<Int, BigDecimal>()
        val itemList = items.toList()
        // Balances first, then transactions, then the transaction counts again: a posting in between would move the
        //  balance without being in the list, so it aborts the read instead of landing in an opening
        println("Plaid read started at ${OffsetDateTime.now()}")
        for ((token, ids) in itemList) {
            val balances = plaidApiWrapper.executeRequest(
                { it.accountsBalanceGet(AccountsBalanceGetRequest(token, null, null, AccountsBalanceGetRequestOptions(ids, null))) },
                "balance get request",
            ).body().accounts
            balances.forEach { b -> b.balances.current?.let { c -> accountMap[b.accountId]?.let { f -> plaidCurrent[f] = c } } }
        }
        val totals = mutableMapOf<String, Int>()
        for ((token, ids) in itemList) {
            var offset = 0
            var total = 0
            do {
                val request = TransactionsGetRequest(
                    token, end.minusDays(repairDays.toLong()), end, null,
                    TransactionsGetRequestOptions(ids, plaidBatchSize, offset, includeOriginalDescription = true, includePersonalFinanceCategory = true),
                )
                val body = plaidApiWrapper.executeRequest({ it.transactionsGet(request) }, "transaction get request").body()
                val page = body.transactions
                total = body.totalTransactions
                page.forEach {
                    plaidTxs[it.transactionId] = it
                    if (it.pending) accountMap[it.accountId]?.let { f -> pending.merge(f, BigDecimal.valueOf(it.amount), BigDecimal::add) }
                }
                offset += page.size
            } while (offset < total && page.isNotEmpty())
            // An empty page before Plaid's total is a truncated read, which would hide missing transactions
            check(offset >= total) { "Plaid returned an empty page at offset $offset of $total transactions; nothing was decided, run again" }
            totals[token] = total
        }
        for ((token, ids) in itemList) {
            val again = plaidApiWrapper.executeRequest(
                { it.transactionsGet(TransactionsGetRequest(token, end.minusDays(repairDays.toLong()), end, null, TransactionsGetRequestOptions(ids, 1, 0))) },
                "transaction get request",
            ).body().totalTransactions
            check(again == totals[token]) { "Plaid's transactions changed while they were being read ($again now, ${totals[token]} before); run again" }
        }
        println("Plaid read finished at ${OffsetDateTime.now()}")
        // The whole history: openings are sums over every journal of the account, so a window would silently under-read
        //  them. fetchFireflyTransactionsBetween throws, instead of returning a truncated list, when it cannot read it all.
        val journals = fireflyTransactionService.fetchFireflyTransactionsStrictly(LocalDate.of(2000, 1, 1), end.plusDays(2), 5000)
        // Only the accounts the repair decides about: the configured ones and the owners of a legacy opening journal
        val accountIds = plaidCurrent.keys + journals.flatMap { g -> g.attributes.transactions.filter { isLegacyOpeningSplit(it) }.mapNotNull { legacyOwner(it) } }
        // A failed read is an error, never a default: an account read as an asset would get the wrong sign
        val accounts = accountIds.toSet().associateWith { id -> fireflyAccountsApi.getAccount(id.toString(), null).body().data }
        return RepairInput(journals, plaidTxs, plaidCurrent, accounts, accountMap) to pending
    }

    private fun print(plan: RepairPlan, input: RepairInput, pending: Map<Int, BigDecimal>) {
        println("== Dates: ${plan.redates.size} journals to re-date; ${plan.unmatchedJournals} Plaid journals not found in Plaid's history are reported only, never moved")
        plan.redates.take(20).forEach { println("   ${it.groupId} ${it.description.take(40)}: ${it.oldDate} -> ${it.newDate}") }
        if (plan.redates.size > 20) println("   ... and ${plan.redates.size - 20} more")
        val winter = plan.redates.filter { it.newDate.monthValue in listOf(12, 1, 2) }
        println("   winter samples (Dec-Feb), ${winter.size} in total:")
        listOf(winter.firstOrNull(), winter.getOrNull(winter.size / 2), winter.lastOrNull()).filterNotNull().distinct().forEach {
            println("   ${it.groupId} ${it.description.take(40)}: ${it.oldDate} -> ${it.newDate}")
        }
        println("== Opening balances (anchor = Plaid current with its listed pending items backed out; pending journals are excluded from the sum of the account's other journals)")
        for (o in plan.openings) {
            println(
                "   account ${o.fireflyAccountId} (${o.name}): opening ${o.oldOpening ?: "-"} on ${o.oldOpeningDate ?: "-"}" +
                        " + ${o.legacyGroupIds.size} legacy opening journals (${o.legacyJournalSum})" +
                        " -> ${o.newOpening ?: "-"} on ${o.newOpeningDate ?: "-"}" +
                        (o.skipReason?.let { "  [${if (it.startsWith("SKIPPED")) it else "SKIPPED: $it"}]" } ?: if (o.changes) "" else "  [no change]")
            )
            o.missing.sortedBy { it.date }.forEach {
                println("      missing from Firefly: ${it.date} ${it.transactionId} ${it.amount} ${it.name.take(40)}")
            }
            o.sanityNote?.let { println("      ANCHOR SANITY: $it") }
            o.keptAmount?.let { println("      $it") }
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

    internal suspend fun applyPlan(plan: RepairPlan) {
        for (r in plan.redates) {
            fireflyTxApi.updateTransaction(
                r.groupId,
                TransactionUpdate(
                    applyRules = false, fireWebhooks = false,
                    transactions = listOf(
                        TransactionSplitUpdate(date = r.newDate, processDate = r.newProcessDate, bookDate = r.newBookDate, transactionJournalId = r.journalId)
                    ),
                ),
            )
        }
        for (o in plan.openings.filter { it.changes }) {
            val date = o.newOpeningDate ?: continue
            val amount = o.newOpening ?: continue
            if (amount.signum() != 0) {
                fireflyAccountsApi.setOpeningBalance(o.fireflyAccountId.toString(), amount.toPlainString(), date, o.liabilityDirection)
            } else if (o.oldOpening != null && o.oldOpening.signum() != 0) {
                // Firefly ignores an opening of 0; only an empty one makes it delete the opening balance journal
                fireflyAccountsApi.clearOpeningBalance(o.fireflyAccountId.toString())
            }
            // A 2xx does not prove Firefly stored it: read the account back before the legacy journals go
            val stored = fireflyAccountsApi.getAccount(o.fireflyAccountId.toString(), null).body().data.attributes
            val storedAmount = stored.openingBalance?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            // Signed on purpose: what was sent carries the direction's sign (debit negative, credit positive), and Firefly
            //  must echo exactly that. A wrong sign would put the ledger off by twice the debt, so it stops before any delete.
            check(storedAmount.compareTo(amount) == 0 && (amount.signum() == 0 || stored.openingBalanceDate?.toLocalDate() == date)) {
                "Firefly did not store the opening balance of account ${o.fireflyAccountId} (sent $amount on $date, it holds " +
                        "$storedAmount on ${stored.openingBalanceDate}); the legacy opening journals were NOT deleted"
            }
            // The opening is in place first, so a crash here leaves a duplicate legacy journal, never a gap
            syncHelper.deleteBatchInFirefly(o.legacyGroupIds)
        }
        deleteOrphanInitialBalanceAccount()
    }

    private suspend fun findInitialBalanceExpenseAccount(): AccountRead? {
        var page = 1
        while (true) {
            val response = fireflyAccountsApi.listAccount(page++, null, AccountTypeFilter.expense).body()
            response.data.firstOrNull { it.attributes.name == "Initial Balance" }?.let { return it }
            if (response.meta.pagination?.let { it.currentPage < it.totalPages } != true) return null
        }
    }

    internal suspend fun deleteOrphanInitialBalanceAccount() {
        val expense = findInitialBalanceExpenseAccount() ?: return
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
