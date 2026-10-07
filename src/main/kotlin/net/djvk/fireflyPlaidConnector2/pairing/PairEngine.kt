package net.djvk.fireflyPlaidConnector2.pairing

import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * The matching rule of the transfer pairer (design section 4): candidates, compatibility, scoring and assignment.
 * Pure: the result is a function of the legs, the accounts and the configuration, never of the order they arrive in.
 */
class PairEngine(private val accounts: List<PairAccount>, private val config: PairingConfig = PairingConfig()) {
    private val byId = accounts.associateBy { it.id }

    /**
     * Firefly's description is "merchant: original text" for imported rows, so a pattern anchored with ^ must also be
     * tried against what follows each ": ". Raw Plaid text has no such prefix and is matched as it is.
     */
    internal fun variants(text: String): List<String> {
        val out = mutableListOf(text)
        var i = text.indexOf(": ")
        while (i >= 0) {
            out.add(text.substring(i + 2))
            i = text.indexOf(": ", i + 2)
        }
        return out
    }

    private fun Regex.matchesAny(text: String) = variants(text).any { containsMatchIn(it) }

    fun markers(leg: Leg): List<String> {
        val table = if (leg.dir == Dir.OUT) config.outMarkers else config.inMarkers
        return table.filter { it.regex.matchesAny(leg.text) }.map { it.name }
    }

    private fun hintsOn(leg: Leg, other: Leg?): List<Hint> = config.hints.filter {
        it.account == leg.account && it.regex.matchesAny(leg.text) && (it.counterpartAccount == null || it.counterpartAccount == other?.account)
    }

    /** A leg that is a statement carry-over, not money moving. */
    fun isCarryOver(leg: Leg) = config.carryOver.matchesAny(leg.text)

    private fun isRoundUp(leg: Leg) = variants(leg.text).any { it.startsWith("Roundup *") }

    private fun sameText(a: Leg, b: Leg) = variants(a.text).any { it in variants(b.text) }

    /** The qualifying inflow accounts of a named-destination rule, or null if the rule says the destination is not linked. */
    private fun qualifying(rule: DestRule, out: Leg, mask: String?): List<PairAccount>? = when (val t = rule.target) {
        is DestTarget.Unlinked -> null
        is DestTarget.Institution -> accounts.filter { it.institution == t.institution && it.id != out.account }
        is DestTarget.Role -> accounts.filter { t.role in it.roles && it.id != out.account }
        is DestTarget.Mask -> accounts.filter { it.institution == t.institution && it.mask == mask && it.id != out.account }
            .ifEmpty { null }
    }

    /** Scores one candidate: the pair requirements R1 to R4 and the rules of 4.3.5. Null if it is not even a candidate. */
    fun evaluate(out: Leg, inn: Leg): Edge? {
        if (out.dir != Dir.OUT || inn.dir != Dir.IN) return null
        if (out.account == inn.account || out.cents != inn.cents) return null
        if (out.currency != inn.currency) return null
        if (isCarryOver(out) || isCarryOver(inn)) return null
        val gap = abs(ChronoUnit.DAYS.between(out.date, inn.date)).toInt()
        if (gap > config.fallbackDays) return null

        val points = mutableListOf<RulePoints>()
        val flags = mutableListOf<String>()
        var veto: String? = null

        val outHints = hintsOn(out, inn)
        val inHints = hintsOn(inn, out)
        val outMarkers = markers(out)
        val inMarkers = markers(inn)
        val outMarked = outMarkers.isNotEmpty() || outHints.isNotEmpty()
        val inMarked = inMarkers.isNotEmpty() || inHints.isNotEmpty()
        val layer = if (outMarked && inMarked && gap <= config.markerDays) 2 else 3
        if (layer == 3 && gap > config.fallbackDays) return null

        // R4: compatibility. A round-up pairs only with the leg of the identical text, and that check runs before the
        // P2P veto (the round-up of a PayPal purchase is still a vault move).
        val roundUp = isRoundUp(out) || isRoundUp(inn)
        if (roundUp) {
            if (!sameText(out, inn)) veto = "round-up: the other leg does not carry the same text"
        } else {
            if ((config.p2p.matchesAny(out.text) && !(outHints.any { it.counterpartAccount != null })) ||
                (config.p2p.matchesAny(inn.text) && !(inHints.any { it.counterpartAccount != null }))
            ) veto = "p2p"
            else if (config.income.matchesAny(inn.text)) veto = "income"
        }
        if (veto == null && config.externalPayees.any { it.matchesAny(out.text) || it.matchesAny(inn.text) }) veto = "external-payee"

        // evidence
        outMarkers.firstOrNull()?.let { points.add(RulePoints("out-marker", 2, "marker", it)) }
        inMarkers.firstOrNull()?.let { points.add(RulePoints("in-marker", 2, "marker", it)) }
        if (outHints.isNotEmpty() || inHints.isNotEmpty()) points.add(RulePoints("hint", 3, "hint"))

        // named destination
        val rule = config.destinations.firstOrNull { it.regex.matchesAny(out.text) }
        if (rule != null && veto == null) {
            val mask = if (rule.target is DestTarget.Mask) variants(out.text).firstNotNullOfOrNull { rule.regex.find(it)?.groupValues?.getOrNull(rule.target.group) } else null
            val allowed = qualifying(rule, out, mask)
            when {
                allowed == null -> veto = "unlinked-destination"
                allowed.none { it.id == inn.account } -> veto = "destination-contradiction"
                rule.target is DestTarget.Mask -> points.add(RulePoints("destination-exact", 4, "destination", "mask $mask"))
                allowed.size == 1 -> points.add(RulePoints("destination-institution", 3, "destination", byId[inn.account]?.name))
                // 4.3.5 narrowing: an institution with several accounts (DCU) is narrowed to one by the inflow's own role,
                // a card-payment text can only land on a card, so exactly one card at the named institution qualifies
                inMarkers.contains("card-payment") && allowed.filter { "card" in it.roles }.singleOrNull()?.id == inn.account ->
                    points.add(RulePoints("destination-narrowed", 3, "destination", "the only card at the named institution"))
                rule.inflowNarrow?.matchesAny(inn.text) == true ->
                    points.add(RulePoints("destination-narrowed", 3, "destination", "the inflow's own wording names the institution"))
                else -> {
                    flags.add("ambiguous-destination")
                    points.add(RulePoints("destination-ambiguous", 0, "destination", "${allowed.size} accounts qualify"))
                }
            }
        }

        // scheduled flow
        val flows = config.scheduled.filter { f ->
            f.from == out.account && f.cents == out.cents &&
                    (f.dayOfMonth == null || abs(out.date.dayOfMonth - f.dayOfMonth) <= f.toleranceDays)
        }
        if (flows.isNotEmpty()) {
            if (flows.any { it.to == inn.account }) points.add(RulePoints("scheduled", 3, "schedule"))
            else if (veto == null) veto = "schedule-contradiction"
        }

        // date closeness: never a family
        val dateScore = when (gap) { 0 -> 2; 1 -> 1; 2 -> 0; else -> -(gap - 2) }
        points.add(RulePoints("date-gap", dateScore, null, "$gap days"))

        return Edge(out, inn, layer, gap, points, veto, flags)
    }

    /** The accounts that can be the other side of [leg]: the named destination narrows it, else every other account. */
    fun possiblePartners(leg: Leg): List<Int> {
        if (leg.dir == Dir.OUT) {
            val rule = config.destinations.firstOrNull { it.regex.matchesAny(leg.text) }
            if (rule != null) {
                val mask = if (rule.target is DestTarget.Mask) variants(leg.text).firstNotNullOfOrNull { rule.regex.find(it)?.groupValues?.getOrNull(rule.target.group) } else null
                return qualifying(rule, leg, mask)?.map { it.id } ?: listOf()
            }
        }
        return accounts.filter { it.id != leg.account }.map { it.id }
    }

    /** The evidence one leg carries on its own (a marker, a named destination, a schedule, a hint), for awaiting records. */
    fun oneSided(leg: Leg): List<RulePoints> {
        val out = mutableListOf<RulePoints>()
        markers(leg).firstOrNull()?.let { out.add(RulePoints(if (leg.dir == Dir.OUT) "out-marker" else "in-marker", 2, "marker", it)) }
        if (hintsOn(leg, null).isNotEmpty() || config.hints.any { it.account == leg.account && it.regex.matchesAny(leg.text) }) out.add(RulePoints("hint", 3, "hint"))
        if (leg.dir == Dir.OUT) {
            val rule = config.destinations.firstOrNull { it.regex.matchesAny(leg.text) }
            if (rule != null) {
                val partners = possiblePartners(leg)
                if (rule.target is DestTarget.Mask && partners.size == 1) out.add(RulePoints("destination-exact", 4, "destination", byId[partners.single()]?.name))
                else if (partners.size == 1) out.add(RulePoints("destination-institution", 3, "destination", byId[partners.single()]?.name))
            }
            if (config.scheduled.any { f -> f.from == leg.account && f.cents == leg.cents && (f.dayOfMonth == null || abs(leg.date.dayOfMonth - f.dayOfMonth) <= f.toleranceDays) })
                out.add(RulePoints("scheduled", 3, "schedule"))
        }
        return out
    }

    /** True when the pair may be merged without a person looking: enough points and two independent rule families. */
    fun isAuto(edge: Edge) = edge.veto == null && edge.score >= config.autoMin && edge.families.size >= 2

    private fun edgeKey(e: Edge) = Triple(-e.score, e.layer, e.gap)

    /**
     * Decides every settled pair of [legs]: scores all candidates, takes them in the total order of 4.5, never
     * tie-breaks between partners on different accounts, and lets pending and not yet settled legs hold their partners.
     */
    fun decide(legs: List<Leg>): PairResult {
        val outs = legs.filter { it.dir == Dir.OUT }.sortedWith(compareBy({ it.date }, { it.id }))
        val ins = legs.filter { it.dir == Dir.IN }.groupBy { it.cents }
        val all = mutableListOf<Edge>()
        for (o in outs) for (i in ins[o.cents].orEmpty()) evaluate(o, i)?.let(all::add)

        val vetoed = all.filter { it.veto != null }
        val live = all.filter { it.veto == null }
        val weak = live.filter { it.score < config.reviewMin }
        val strong = live.filter { it.score >= config.reviewMin }
            .sortedWith(compareBy<Edge>({ -it.score }, { it.layer }, { it.gap }, { it.out.date }, { it.out.id }, { it.inn.id }))

        val used = mutableSetOf<String>()
        val proposals = mutableListOf<Proposal>()
        val ambiguous = mutableListOf<Ambiguity>()
        val waiting = mutableListOf<Edge>()
        for (e in strong) {
            if (e.out.id in used || e.inn.id in used) continue
            val k = edgeKey(e)
            val rivals = strong.filter { r ->
                r !== e && edgeKey(r) == k && r.out.id !in used && r.inn.id !in used &&
                        ((r.out.id == e.out.id && r.inn.account != e.inn.account) || (r.inn.id == e.inn.id && r.out.account != e.out.account))
            }
            if (rivals.isNotEmpty()) {
                ambiguous.add(Ambiguity(listOf(e) + rivals, "two compatible partners on different accounts at the same score, layer and gap"))
                used.add(e.out.id); used.add(e.inn.id)
                rivals.forEach { used.add(it.out.id); used.add(it.inn.id) }
                continue
            }
            used.add(e.out.id); used.add(e.inn.id)
            val takeable = e.out.settled && e.inn.settled && !e.out.pending && !e.inn.pending
            if (takeable) proposals.add(Proposal(e, isAuto(e))) else waiting.add(e)
        }
        return PairResult(
            proposals = proposals,
            ambiguous = ambiguous,
            waiting = waiting,
            vetoed = vetoed,
            unmatched = weak.filter { it.out.id !in used && it.inn.id !in used },
            candidates = all.size,
            tooNew = legs.filter { !it.settled },
        )
    }

    /**
     * The late-competitor check of 4.4: a leg that arrived after a pair was merged and, had it been there, would have
     * competed for it (same amount, opposite direction, compatible, within the window of either leg, at a better or
     * equal key). The merge is flagged, never undone.
     */
    fun lateCompetitors(merged: List<Edge>, singles: List<Leg>): List<Pair<Edge, Edge>> {
        val flags = mutableListOf<Pair<Edge, Edge>>()
        for (m in merged) {
            val key = edgeKey(m)
            for (s in singles) {
                val rival = when (s.dir) { Dir.IN -> evaluate(m.out, s); Dir.OUT -> evaluate(s, m.inn) } ?: continue
                if (rival.veto != null || rival.score < config.reviewMin) continue
                val rk = edgeKey(rival)
                if (compareValuesBy(rk, key, { it.first }, { it.second }, { it.third }) <= 0) flags.add(m to rival)
            }
        }
        return flags
    }
}
