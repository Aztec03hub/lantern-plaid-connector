package net.djvk.fireflyPlaidConnector2.pairing

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.ceil

/**
 * The per-bank watermark of 4.4: the latest date D such that a sync completed after D's transactions posted, minus the
 * posting lag. A leg on date d is settled when d + fallbackDays <= the watermark of every bank that could hold a
 * competitor for it (its own bank included). An account with no known watermark holds its legs back.
 */
object Watermark {
    fun settle(engine: PairEngine, legs: List<Leg>, watermarkByAccount: Map<Int, LocalDate?>, fallbackDays: Int): List<Leg> =
        legs.map { leg ->
            val needed = leg.date.plusDays(fallbackDays.toLong())
            val relevant = engine.possiblePartners(leg) + leg.account
            val settled = relevant.all { acct -> watermarkByAccount[acct]?.let { !it.isBefore(needed) } == true }
            leg.copy(settled = settled)
        }

    /** Which banks (accounts) have not reached the window of [leg], with their watermark dates. */
    fun waitingOn(engine: PairEngine, leg: Leg, watermarkByAccount: Map<Int, LocalDate?>, fallbackDays: Int): Map<Int, LocalDate?> {
        val needed = leg.date.plusDays(fallbackDays.toLong())
        return (engine.possiblePartners(leg) + leg.account).distinct()
            .filter { acct -> watermarkByAccount[acct]?.let { it.isBefore(needed) } != false }
            .associateWith { watermarkByAccount[it] }
    }
}

/**
 * For every pair of accounts, the distribution of (inflow date - outflow date) over merged pairs (4.7). A pair of accounts
 * with fewer than [minSamples] merges has no learned lag and the caller falls back to markerDays.
 */
class LagTable(private val samples: MutableMap<Pair<Int, Int>, MutableList<Int>> = mutableMapOf(), private val minSamples: Int = 5) {
    fun add(out: Int, inn: Int, lagDays: Int) {
        samples.getOrPut(out to inn) { mutableListOf() }.add(lagDays)
    }

    /** The 95th percentile of the number of days the partner takes to post after the outflow, or null with too few merges. */
    fun p95(out: Int, inn: Int): Int? {
        val list = samples[out to inn]?.sorted() ?: return null
        if (list.size < minSamples) return null
        return list[(ceil(0.95 * list.size).toInt() - 1).coerceIn(0, list.size - 1)]
    }

    fun toMap(): Map<String, List<Int>> = samples.mapKeys { "${it.key.first}:${it.key.second}" }

    companion object {
        fun from(map: Map<String, List<Int>>): LagTable = LagTable(
            map.entries.associate { (k, v) ->
                val (a, b) = k.split(":")
                (a.toInt() to b.toInt()) to v.toMutableList()
            }.toMutableMap()
        )
    }
}

/** What an unpaired leg is waiting for and when it should resolve (4.7). */
data class AwaitingRecord(
    val legId: String,
    val account: Int,
    val date: LocalDate,
    val state: String,
    val expectedPartner: Int?,
    val expectedPairBy: LocalDate?,
    val waitingOn: Map<Int, LocalDate?>,
    val pendingSince: LocalDate?,
    val pendingExpectedEnd: LocalDate?,
    val estimateBasis: String?,
    val scoreSoFar: List<RulePoints>,
)

object Awaiting {
    private const val ACH_BUSINESS_DAYS = 5L
    private const val CARD_DAYS = 7L

    private fun addBusinessDays(from: LocalDate, days: Long): LocalDate {
        var d = from
        var left = days
        while (left > 0) {
            d = d.plusDays(1)
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) left--
        }
        return d
    }

    /**
     * One record per leg that is pending or unpaired and carries at least reviewMin of one-sided evidence.
     * [inReview] are the legs of pairs in the review band; [pendingFirstSeen] and [pendingDurations] come from the state file.
     */
    fun build(
        engine: PairEngine,
        config: PairingConfig,
        unpaired: List<Leg>,
        inReview: Set<String>,
        lags: LagTable,
        watermarks: Map<Int, LocalDate?>,
        pendingFirstSeen: Map<String, LocalDate>,
        pendingDurations: List<Int>,
        today: LocalDate,
        syncLagDays: Int = 1,
    ): List<AwaitingRecord> = unpaired.mapNotNull { leg ->
        val evidence = engine.oneSided(leg)
        if (evidence.sumOf { it.points } < config.reviewMin) return@mapNotNull null
        val partners = engine.possiblePartners(leg)
        val expected = partners.singleOrNull()
        val lag = if (leg.dir == Dir.OUT && expected != null) lags.p95(leg.account, expected) else null
        val expectedBy = leg.date.plusDays(((lag ?: config.markerDays) + syncLagDays).toLong())
        val waiting = Watermark.waitingOn(engine, leg, watermarks, config.fallbackDays)
        val firstSeen = pendingFirstSeen[leg.id]
        val estimateBasis = if (leg.pending) (if (pendingDurations.size >= 10) "observed" else "default") else null
        val pendingEnd = if (leg.pending && firstSeen != null) {
            if (pendingDurations.size >= 10) firstSeen.plusDays(pendingDurations.sorted()[(ceil(0.95 * pendingDurations.size).toInt() - 1).coerceIn(0, pendingDurations.size - 1)].toLong())
            else if (leg.dir == Dir.OUT || leg.text.contains("ACH", ignoreCase = true)) addBusinessDays(firstSeen, ACH_BUSINESS_DAYS) else firstSeen.plusDays(CARD_DAYS)
        } else null
        val state = when {
            leg.pending -> "pending"
            leg.id in inReview -> "in-review"
            today.isAfter(expectedBy) && waiting.isEmpty() -> "overdue"
            else -> "awaiting-partner"
        }
        AwaitingRecord(leg.id, leg.account, leg.date, state, expected, if (leg.pending) null else expectedBy, waiting, firstSeen, pendingEnd, estimateBasis, evidence)
    }
}
