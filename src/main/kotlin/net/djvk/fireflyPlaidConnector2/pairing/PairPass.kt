package net.djvk.fireflyPlaidConnector2.pairing

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PairApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PairMergeOutcome
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PairMergeRequest
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountTypeProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.config.AccountConfig
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.sync.FireflyTransactionService
import net.djvk.fireflyPlaidConnector2.sync.ItemStatusStore
import net.djvk.fireflyPlaidConnector2.sync.SyncHelper
import net.djvk.fireflyPlaidConnector2.transactions.AccountKind
import net.djvk.fireflyPlaidConnector2.transactions.pairedTransactionType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** Settings of the pairer; every value has the default of the design (13.9) and the pair is off after runs by default. */
@Component
class PairSettings(
    /** A pass only prints the plan, writing nothing, until this is false. */
    @Value("\${fireflyPlaidConnector2.pair.dryRun:true}") val dryRun: Boolean = true,
    @Value("\${fireflyPlaidConnector2.pair.lookbackDays:3650}") val lookbackDays: Long = 3650,
    @Value("\${fireflyPlaidConnector2.pair.markerDays:5}") val markerDays: Int = 5,
    @Value("\${fireflyPlaidConnector2.pair.fallbackDays:10}") val fallbackDays: Int = 10,
    @Value("\${fireflyPlaidConnector2.pair.autoMin:4}") val autoMin: Int = 4,
    @Value("\${fireflyPlaidConnector2.pair.reviewMin:3}") val reviewMin: Int = 3,
    /** Settle by the per-bank watermark (4.4). Off for the repair mode, which decides everything already read. */
    @Value("\${fireflyPlaidConnector2.pair.useWatermark:false}") val useWatermark: Boolean = false,
    /** Run a pass after every batch run and poll. Off until the first dry run has been read. */
    @Value("\${fireflyPlaidConnector2.pair.afterRun:false}") val afterRun: Boolean = false,
    /** Optional JSON file: externalPayees, hints, scheduled, destinations (added before the defaults), p2p, income (see [PairConfigLoader]). */
    @Value("\${fireflyPlaidConnector2.pair.configFile:}") val configFile: String = "",
    @Value("\${fireflyPlaidConnector2.pendingTag:}") val pendingTag: String = "",
    @Value("\${fireflyPlaidConnector2.polled.cursorFileDirectoryPath:persistence}") val directory: String = "persistence",
    @Value("\${fireflyPlaidConnector2.firefly.personalAccessToken:}") val fireflyAccessToken: String = "",
    @Value("\${fireflyPlaidConnector2.timeZone:UTC}") val timeZone: String = "UTC",
    /** Days the sync lags the bank's posting (4.4): the watermark is the last sync date minus this. */
    @Value("\${fireflyPlaidConnector2.pair.postingLagDays:1}") val postingLagDays: Int = 1,
)

object PairConfigLoader {
    private val mapper = ObjectMapper()

    /** Reads the optional JSON file over [base]; a missing file leaves [base] as it is, a bad one fails the pass. */
    fun load(path: String, base: PairingConfig): PairingConfig {
        if (path.isBlank()) return base
        val n = mapper.readTree(java.io.File(path))
        return base.copy(
            externalPayees = n["externalPayees"]?.map { Regex(it.asText()) } ?: base.externalPayees,
            hints = n["hints"]?.map { Hint(it["account"].asInt(), Regex(it["regex"].asText()), it["counterpartAccount"]?.takeIf { v -> !v.isNull }?.asInt()) } ?: base.hints,
            scheduled = n["scheduled"]?.map {
                ScheduledFlow(it["from"].asInt(), it["to"].asInt(), Math.round(it["amount"].asDouble() * 100), it["day"]?.takeIf { v -> !v.isNull }?.asInt(), it["tolerance"]?.asInt() ?: 3)
            } ?: base.scheduled,
            destinations = n["destinations"]?.map {
                val v = it["value"]?.asText().orEmpty()
                val target = when (it["kind"].asText()) {
                    "institution" -> DestTarget.Institution(v)
                    "mask" -> DestTarget.Mask(v, it["group"]?.asInt() ?: 1)
                    "role" -> DestTarget.Role(v)
                    "unlinked" -> DestTarget.Unlinked
                    else -> error("pair config: unknown destination kind ${it["kind"]}")
                }
                DestRule(Regex(it["regex"].asText()), target, it["inflowNarrow"]?.takeIf { x -> !x.isNull }?.let { x -> Regex(x.asText()) })
            }?.let { custom -> custom + base.destinations } ?: base.destinations,
            p2p = n["p2p"]?.let { Regex(it.asText()) } ?: base.p2p,
            income = n["income"]?.let { Regex(it.asText()) } ?: base.income,
        )
    }
}

/** Everything one pass learned, and what it did. */
data class PairPassReport(
    val result: PairResult,
    val dryRun: Boolean,
    val merged: List<MergeRecord>,
    val rejected: List<Pair<Edge, PairMergeOutcome.Rejected>>,
    val needsHuman: List<Pair<Leg, String>>,
    val lateCompetitors: List<Pair<Edge, Edge>>,
    val awaiting: List<AwaitingRecord>,
    val legsRead: Int,
    val kinds: Map<Int, AccountKind>,
    val text: String,
    /** Auto pairs the pass tried and could not merge because core (or the call) failed; the runner exits non-zero for these. */
    val failed: List<PairFailure> = listOf(),
)

data class MergeRecord(val pairMergeId: String, val edge: Edge)

/** One pass of the pairer: read Firefly, decide, then (unless dry) ask core to merge each auto pair, one atomic call each. */
@Component
class PairPass(
    private val syncHelper: SyncHelper,
    private val service: FireflyTransactionService,
    private val pairApi: PairApi,
    private val accountConfigs: AccountConfigs,
    private val itemStatusStore: ItemStatusStore,
    private val settings: PairSettings,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val zone = ZoneId.of(settings.timeZone)
    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    fun pairAccounts(): List<PairAccount> = accountConfigs.accounts.filter { !it.investment }.distinctBy { it.fireflyAccountId }.map {
        PairAccount(it.fireflyAccountId, it.displayName ?: "account ${it.fireflyAccountId}", it.institutionName, it.mask, it.roles.toSet())
    }

    /** Runs one pass over [from]..[to] (the dates it may decide); [dryRun] overrides the setting when not null. */
    suspend fun run(from: LocalDate, to: LocalDate, dryRun: Boolean = settings.dryRun, now: Instant = Instant.now()): PairPassReport {
        pairApi.setAccessToken(settings.fireflyAccessToken)
        val config = PairConfigLoader.load(
            settings.configFile,
            PairingConfig(markerDays = settings.markerDays, fallbackDays = settings.fallbackDays, autoMin = settings.autoMin, reviewMin = settings.reviewMin),
        )
        // one line, always: the values this run really uses (A4), so a log shows which settings produced the plan
        println(
            "Pair settings in effect: dryRun=$dryRun autoMin=${config.autoMin} reviewMin=${config.reviewMin} markerDays=${config.markerDays} " +
                "fallbackDays=${config.fallbackDays} lookbackDays=${settings.lookbackDays} useWatermark=${settings.useWatermark}",
        )
        val accounts = pairAccounts()
        val kinds = syncHelper.fetchAccountKinds().mapKeys { it.key.toInt() }.mapValues { it.value }
        val window = config.fallbackDays.toLong()

        // 4.6: the range that lets every leg of the decision window see its competitors and theirs; fails, rather than
        //  deciding on a truncated or shifting view
        val readFrom = from.minusDays(2 * window)
        val readTo = minOf(LocalDate.now(zone), to.plusDays(2 * window))
        val journals = service.fetchFireflyTransactionsStrictly(readFrom, readTo, MAX_PAGES)

        val needsHuman = mutableListOf<Pair<Leg, String>>()
        val legs = journals.mapNotNull { toLeg(it, accounts.map { a -> a.id }.toSet(), needsHuman) }
        val today = LocalDate.ofInstant(now, zone)

        // H1: a pair this connector merged whose two journals are single again was unmerged by a person; it is never proposed again
        val state = PairStateFile.read(settings.directory)
        val singleIds = legs.map { it.id }.toSet()
        val (stillMerged, unmerged) = (state.merges + recoverCommittedMerges(state, journals)).partition { !(it.out.id in singleIds && it.inn.id in singleIds) }
        val rejectedPairs = (state.rejected + unmerged.map { RejectedPair(it.out.id, it.inn.id, it.pairMergeId, today.toString()) }).distinctBy { it.out to it.inn }
        unmerged.forEach { logger.warn("Pair {} ({} and {}) is no longer merged; it will not be proposed again", it.pairMergeId, it.out.id, it.inn.id) }
        val engine = PairEngine(accounts, config, rejectedPairs.map { it.out to it.inn }.toSet())

        val watermarks = if (settings.useWatermark) watermarks(now) else accounts.associate { it.id to (today as LocalDate?) }
        val settled = Watermark.settle(engine, legs, watermarks, config.fallbackDays)
            // a leg outside the decision window is read to be a competitor, never decided
            .map { if (it.date.isBefore(from) || it.date.isAfter(to)) it.copy(settled = false) else it }
        val result = engine.decide(settled)

        val lateFlags = engine.lateCompetitors(
            stillMerged.mapNotNull { engine.evaluate(it.out, it.inn) },
            settled.filter { !it.pending && it.id !in result.proposals.flatMap { p -> listOf(p.edge.out.id, p.edge.inn.id) } },
        )

        val merged = mutableListOf<MergeRecord>()
        val rejected = mutableListOf<Pair<Edge, PairMergeOutcome.Rejected>>()
        val failed = mutableListOf<PairFailure>()
        val auto = result.proposals.filter { it.auto }
        if (!dryRun) {
            for (p in auto) {
                val e = p.edge
                // A2: one bad pair must not stop the others, nor stop the merges already made from being recorded
                try {
                    val keepType = pairedTransactionType(kinds[e.out.account] ?: AccountKind.ASSET, kinds[e.inn.account] ?: AccountKind.ASSET)
                    val request = PairMergeRequest(
                        e.out.groupId!!, e.inn.groupId!!, e.out.version, e.inn.version,
                        mapOf(
                            "rule_version" to config.ruleVersion, "layer" to e.layer, "gap_days" to e.gap, "score" to e.score,
                            "points" to e.points.map { mapOf("rule" to it.rule, "points" to it.points, "family" to it.family, "note" to it.note) },
                            "flags" to e.flags, "type" to keepType.name,
                        ),
                    )
                    when (val outcome = pairApi.merge(request)) {
                        is PairMergeOutcome.Merged -> {
                            merged.add(MergeRecord(outcome.pairMergeId, e))
                            logger.info("Merged {} and {} into one transfer (pair_merge_id {})", e.out.id, e.inn.id, outcome.pairMergeId)
                        }
                        is PairMergeOutcome.Rejected -> {
                            rejected.add(e to outcome)
                            // stale: someone edited a journal since it was read; the next pass reads and decides again
                            logger.warn("Core refused to merge {} and {}: HTTP {} {}", e.out.id, e.inn.id, outcome.status, outcome.reason)
                        }
                    }
                } catch (ex: kotlin.coroutines.cancellation.CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    failed.add(PairFailure(e.out.id, e.inn.id, today.toString(), ex.message ?: ex::class.java.simpleName))
                    logger.error("Merging {} and {} failed; going on with the other pairs", e.out.id, e.inn.id, ex)
                }
            }
        }

        val pairedIds = result.proposals.filter { it.auto && (dryRun || merged.any { m -> m.edge === it.edge }) }.flatMap { listOf(it.edge.out.id, it.edge.inn.id) }.toSet()
        val inReview = result.proposals.filter { !it.auto }.flatMap { listOf(it.edge.out.id, it.edge.inn.id) }.toSet()
        val lags = LagTable.from(state.lags)
        merged.forEach { lags.add(it.edge.out.account, it.edge.inn.account, java.time.temporal.ChronoUnit.DAYS.between(it.edge.out.date, it.edge.inn.date).toInt()) }
        val firstSeen = state.pendingFirstSeen.toMutableMap()
        settled.filter { it.pending }.forEach { firstSeen.putIfAbsent(it.id, today) }
        val awaiting = Awaiting.build(
            engine, config, settled.filter { it.id !in pairedIds }, inReview, lags, watermarks,
            firstSeen.mapValues { it.value }, state.pendingDurations, today,
        )

        val text = PairReportRenderer.render(result, config, dryRun, merged, rejected, needsHuman, lateFlags, awaiting, legs.size, kinds, accounts)
        println(text)
        if (!dryRun || settings.directory.isNotBlank()) {
            val next = PairState(
                lags.toMap(), firstSeen, state.pendingDurations, stillMerged + merged.map { StoredMerge(it.pairMergeId, it.edge.out, it.edge.inn) },
                rejectedPairs, failed.toList(),
            )
            writeFiles(text, awaiting, next, now, dryRun)
        }
        return PairPassReport(result, dryRun, merged, rejected, needsHuman, lateFlags, awaiting, legs.size, kinds, text, failed)
    }

    /**
     * N1: a merge that core committed although the call failed (5xx, timeout) sits in `failures`, never in `merges`. When both of
     * its Plaid ids now are the source and destination links of one journal, it counts as merged, so a later unmerge is noticed.
     * The inflow leg only has the outflow's date and amount here (core keeps the OUT leg's journal); ids and accounts are exact.
     */
    internal fun recoverCommittedMerges(state: PairState, journals: List<TransactionRead>): List<StoredMerge> =
        state.failures.filter { f -> state.merges.none { it.out.id == f.out && it.inn.id == f.inn } }.mapNotNull { f ->
            journals.firstNotNullOfOrNull { g ->
                val s = g.attributes.transactions.singleOrNull() ?: return@firstNotNullOfOrNull null
                val links = s.plaidLinks.orEmpty()
                val merged = links.any { it.leg == PlaidLinkLeg.source && it.plaidTransactionId == f.out } &&
                    links.any { it.leg == PlaidLinkLeg.destination && it.plaidTransactionId == f.inn }
                val src = s.sourceId?.toIntOrNull()
                val dst = s.destinationId?.toIntOrNull()
                val cents = runCatching { java.math.BigDecimal(s.amount).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
                if (!merged || src == null || dst == null || cents == null) return@firstNotNullOfOrNull null
                val date = s.date.atZoneSameInstant(zone).toLocalDate()
                StoredMerge(
                    "unknown",
                    Leg(f.out, src, Dir.OUT, date, cents, s.description, currency = s.currencyCode, groupId = g.id),
                    Leg(f.inn, dst, Dir.IN, date, cents, s.description, currency = s.currencyCode, groupId = g.id),
                )
            }
        }

    private fun watermarks(now: Instant): Map<Int, LocalDate?> {
        val statuses = kotlinx.coroutines.runBlocking { itemStatusStore.read() }
        return accountConfigs.accounts.filter { !it.investment }.associate { a ->
            val status = statuses[itemStatusStore.itemId(a.plaidItemAccessToken)]
            a.fireflyAccountId to status?.lastSuccessfulSync?.let { runCatching { LocalDate.ofInstant(Instant.parse(it), zone).minusDays(settings.postingLagDays.toLong()) }.getOrNull() }
        }
    }

    /** A candidate or competitor leg from a Firefly journal; anything the pairer must not touch goes to [needsHuman]. */
    internal fun toLeg(group: TransactionRead, own: Set<Int>, needsHuman: MutableList<Pair<Leg, String>>): Leg? {
        val split = group.attributes.transactions.singleOrNull() ?: return null
        val dir = when (split.type) {
            TransactionTypeProperty.withdrawal -> Dir.OUT
            TransactionTypeProperty.deposit -> Dir.IN
            else -> return null
        }
        val account = (if (dir == Dir.OUT) split.sourceId else split.destinationId)?.toIntOrNull() ?: return null
        if (account !in own) return null
        // a payment since re-pointed at an own account (a loan, a card) is routed, not a transfer candidate, whatever its link says
        if (dir == Dir.OUT && split.destinationType in OWN_ACCOUNT_TYPES) return null
        val links = split.plaidLinks.orEmpty()
        if (links.size != 1 || links[0].leg != PlaidLinkLeg.single) return null
        val cents = runCatching { java.math.BigDecimal(split.amount).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull() ?: return null
        val pending = settings.pendingTag.isNotBlank() && split.tags?.contains(settings.pendingTag) == true
        val leg = Leg(
            links[0].plaidTransactionId, account, dir, split.date.atZoneSameInstant(zone).toLocalDate(), cents, split.description,
            pending, split.currencyCode ?: split.currencyId, true, group.id, group.attributes.updatedAt?.let(::stamp),
        )
        when {
            split.reconciled == true -> { needsHuman.add(leg to "reconciled"); return null }
            split.foreignAmount != null -> { needsHuman.add(leg to "foreign amount"); return null }
        }
        return leg
    }

    private fun writeFiles(text: String, awaiting: List<AwaitingRecord>, state: PairState, now: Instant, dryRun: Boolean) {
        val dir = Path.of(settings.directory)
        Files.createDirectories(dir)
        if (dryRun) Files.writeString(dir.resolve("pair-dry-run-${now.toString().replace(':', '-')}.txt"), text)
        else PairStateFile.write(settings.directory, state)
        // the awaiting records are rewritten whole on every real pass (4.7); Lantern reads them from pair-awaiting.json. A dry run
        //  merged nothing, so it must not claim its pairs as paired there (B3): it writes a file of its own.
        val awaitingFile = if (dryRun) "pair-awaiting-dryrun.json" else "pair-awaiting.json"
        Files.writeString(dir.resolve(awaitingFile), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(awaiting))
    }

    companion object {
        const val MAX_PAGES = 5000
        private val STAMP = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

        /** N0: the one place a version stamp is built: always seconds, an offset like `+00:00`, never `Z` (lowercase xxx) and never a dropped `:00`. */
        internal fun stamp(t: OffsetDateTime): String = t.format(STAMP)
        private val OWN_ACCOUNT_TYPES = setOf(AccountTypeProperty.assetAccount, AccountTypeProperty.loan, AccountTypeProperty.debt, AccountTypeProperty.mortgage)
    }
}

/**
 * The file the pairer keeps between passes: learned lags, when each pending id was first seen, observed pending durations,
 * the merges it made, the pairs a person rejected by unmerging them (H1) and the pairs that failed in the last real pass (A2).
 * Version 1 files (no `version`, `rejected` or `failures`) load as they are: the new fields default to empty.
 */
data class PairState(
    val lags: Map<String, List<Int>> = mapOf(),
    val pendingFirstSeen: Map<String, LocalDate> = mapOf(),
    val pendingDurations: List<Int> = listOf(),
    /** The merges this connector made, with both legs as they were, for the late-competitor check and for undoing a pass. */
    val merges: List<StoredMerge> = listOf(),
    /** Pairs of leg ids that a person unmerged. The engine never proposes them again. */
    val rejected: List<RejectedPair> = listOf(),
    /** Auto pairs that failed in the most recent real pass; replaced by the next real pass. */
    val failures: List<PairFailure> = listOf(),
    val version: Int = CURRENT_VERSION,
) {
    companion object {
        const val CURRENT_VERSION = 2
    }
}

data class StoredMerge(val pairMergeId: String, val out: Leg, val inn: Leg)

/** An unmerged pair: the Plaid ids of the outflow and the inflow leg, the merge it came from and the day it was noticed. */
data class RejectedPair(val out: String, val inn: String, val pairMergeId: String? = null, val at: String? = null)

data class PairFailure(val out: String, val inn: String, val at: String, val error: String)

/** Reads and writes `pair-state.json`. A file that cannot be read stops the pass: it holds the rejected pairs and must never be replaced silently (L6). */
object PairStateFile {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    fun read(directory: String): PairState {
        val f = Path.of(directory, "pair-state.json")
        if (!Files.exists(f)) return PairState()
        val state = try {
            mapper.readValue(f.toFile(), PairState::class.java)
        } catch (e: Exception) {
            throw IllegalStateException("$f cannot be read (${e.message?.lineSequence()?.firstOrNull()}). Fix or move it aside; the pass will not replace it.", e)
        }
        check(state.version <= PairState.CURRENT_VERSION) { "$f has version ${state.version}, newer than this connector understands (${PairState.CURRENT_VERSION})" }
        return state
    }

    /** Writes through a temp file and an atomic move, so a kill mid-write leaves the old file whole. */
    fun write(directory: String, state: PairState) {
        val dir = Path.of(directory)
        Files.createDirectories(dir)
        // N4: v2 is a one-way format (an old jar fails on it and starts empty); keep the v1 file the first time it is upgraded
        val current = dir.resolve("pair-state.json")
        val bak = dir.resolve("pair-state.json.v1.bak")
        if (Files.exists(current) && !Files.exists(bak) && !Files.readString(current).contains("\"version\"")) Files.copy(current, bak)
        val tmp = dir.resolve("pair-state.json.tmp")
        Files.writeString(tmp, mapper.writeValueAsString(state))
        Files.move(tmp, dir.resolve("pair-state.json"), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
