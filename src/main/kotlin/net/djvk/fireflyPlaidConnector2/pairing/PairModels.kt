package net.djvk.fireflyPlaidConnector2.pairing

import java.time.LocalDate

enum class Dir { OUT, IN }

/**
 * One side of a possible transfer: a withdrawal (OUT) or deposit (IN) on one of the user's own accounts.
 * [id] identifies it in output and breaks ties (a Plaid transaction id when known, else the Firefly group id).
 */
data class Leg(
    val id: String,
    val account: Int,
    val dir: Dir,
    val date: LocalDate,
    val cents: Long,
    val text: String,
    val pending: Boolean = false,
    val currency: String? = null,
    /** False when a bank this leg could compete with has not synced past its window yet (4.4). */
    val settled: Boolean = true,
    /** Firefly group id and the version (updated_at) the pass read, for the merge request; not used for deciding. */
    val groupId: String? = null,
    val version: String? = null,
)

/** An own account as the pairer sees it. [institution], [mask] and [roles] feed the named-destination rules. */
data class PairAccount(
    val id: Int,
    val name: String,
    val institution: String? = null,
    val mask: String? = null,
    val roles: Set<String> = setOf(),
)

/** A marker: the text of a leg for a direction that says "this is a transfer leg". */
data class Marker(val name: String, val regex: Regex)

/** What a named-destination rule says about the inflow's account. */
sealed interface DestTarget {
    /** One of the user's accounts at this institution (exactly one qualifying: +3, several: no points, flagged). */
    data class Institution(val institution: String) : DestTarget

    /** The account at [institution] whose mask is capture group [group] of the rule's regex (+4). */
    data class Mask(val institution: String, val group: Int = 1) : DestTarget

    /** An account with this role (a vault, a card). */
    data class Role(val role: String) : DestTarget

    /** Not an account Lantern links from Plaid: the outflow never pairs (it is a one-sided payment). */
    data object Unlinked : DestTarget
}

data class DestRule(val regex: Regex, val target: DestTarget)

/** Extra evidence for one account's wording (4.3.4). With [counterpartAccount] it only counts against that account. */
data class Hint(val account: Int, val regex: Regex, val counterpartAccount: Int? = null)

/** A flow from `scheduled.toml`: [cents] leaves [from] for [to] around day [dayOfMonth] (null: any day). */
data class ScheduledFlow(val from: Int, val to: Int, val cents: Long, val dayOfMonth: Int? = null, val toleranceDays: Int = 3)

data class PairingConfig(
    val markerDays: Int = 5,
    val fallbackDays: Int = 10,
    val autoMin: Int = 6,
    val reviewMin: Int = 3,
    val outMarkers: List<Marker> = PairDefaults.outMarkers,
    val inMarkers: List<Marker> = PairDefaults.inMarkers,
    val carryOver: Regex = PairDefaults.carryOver,
    val p2p: Regex = PairDefaults.p2p,
    val income: Regex = PairDefaults.income,
    val externalPayees: List<Regex> = listOf(),
    val destinations: List<DestRule> = PairDefaults.destinations,
    val hints: List<Hint> = listOf(),
    val scheduled: List<ScheduledFlow> = listOf(),
    val ruleVersion: Int = 1,
)

/** One line of a score: which rule, how many points, and which rule family it belongs to (null: not a family). */
data class RulePoints(val rule: String, val points: Int, val family: String? = null, val note: String? = null)

/** A scored candidate. [veto] set means it can never pair. */
data class Edge(
    val out: Leg,
    val inn: Leg,
    val layer: Int,
    val gap: Int,
    val points: List<RulePoints>,
    val veto: String? = null,
    val flags: List<String> = listOf(),
) {
    val score: Int get() = points.sumOf { it.points }
    val families: Set<String> get() = points.filter { it.points > 0 && it.family != null }.mapNotNull { it.family }.toSet()
    val signature: List<Any> get() = listOf(out.account, inn.account, out.cents, out.date, inn.date)
}

/** A pair the pass decided. [auto] false: it is in the review band and is not merged on its own. */
data class Proposal(val edge: Edge, val auto: Boolean, val heldBy: String? = null)

data class Ambiguity(val edges: List<Edge>, val reason: String)

/** Everything one pass decided, for the dry run and for the merges. */
data class PairResult(
    val proposals: List<Proposal>,
    val ambiguous: List<Ambiguity>,
    /** Legs whose best partner is pending or not settled yet, so they wait. */
    val waiting: List<Edge>,
    /** Edges that matched on amount, direction and date but carry a veto, with the reason. */
    val vetoed: List<Edge>,
    /** Edges that matched on amount and date but scored under reviewMin. */
    val unmatched: List<Edge>,
    val candidates: Int,
    val tooNew: List<Leg>,
)
