package net.djvk.fireflyPlaidConnector2.transactions

import com.fasterxml.jackson.databind.ObjectMapper
import net.djvk.fireflyPlaidConnector2.pairing.ScheduledFlow
import java.time.LocalDate

/** A one-sided payment out of Firefly account [fromAccount] whose text matches [match]: it goes to [toAccount] or to the scheduled candidate. */
data class OwnAccountRule(
    val fromAccount: Int,
    val match: Regex,
    val toAccount: Int? = null,
    val scheduled: Boolean = false,
    val candidates: List<Int> = emptyList(),
)

/** The rules and the scheduled flows of the optional pair config file; a blank path or a missing key means none. */
data class OwnAccountConfig(val rules: List<OwnAccountRule> = emptyList(), val scheduled: List<ScheduledFlow> = emptyList()) {
    /** The first rule of [fromAccount] whose regex is found in [text]. */
    fun ruleFor(fromAccount: Int, text: String): OwnAccountRule? = rules.firstOrNull { it.fromAccount == fromAccount && it.match.containsMatchIn(text) }

    /** The target account for a payment, or null when no rule matches or the routing is not unique. */
    fun target(fromAccount: Int, text: String, cents: Long, date: LocalDate): Int? =
        ruleFor(fromAccount, text)?.let { route(it, fromAccount, cents, date, scheduled) }

    companion object {
        private val mapper = ObjectMapper()

        fun load(path: String): OwnAccountConfig {
            if (path.isBlank()) return OwnAccountConfig()
            val f = java.io.File(path)
            if (!f.exists()) return OwnAccountConfig()
            val n = mapper.readTree(f)
            return OwnAccountConfig(
                rules = n["ownAccountPayments"]?.map {
                    OwnAccountRule(
                        it["fromAccount"].asInt(), Regex(it["match"].asText()),
                        it["toAccount"]?.takeIf { v -> !v.isNull }?.asInt(),
                        it["route"]?.asText() == "scheduled",
                        it["candidates"]?.map { c -> c.asInt() }.orEmpty(),
                    )
                }.orEmpty(),
                scheduled = n["scheduled"]?.map {
                    ScheduledFlow(it["from"].asInt(), it["to"].asInt(), Math.round(it["amount"].asDouble() * 100), it["day"]?.takeIf { v -> !v.isNull }?.asInt(), it["tolerance"]?.asInt() ?: 3)
                }.orEmpty(),
            )
        }
    }
}

/** True when [date] is within [tolerance] days of day [dayOfMonth] of this, the previous or the next month (the 30th and the 2nd meet). */
fun dayOfMonthWithin(date: LocalDate, dayOfMonth: Int, tolerance: Int): Boolean = (-1L..1L).any { m ->
    val month = date.withDayOfMonth(1).plusMonths(m)
    val due = month.withDayOfMonth(minOf(dayOfMonth, month.lengthOfMonth()))
    kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(due, date)) <= tolerance
}

/**
 * The account a payment goes to: the rule's fixed [OwnAccountRule.toAccount], or the ONE candidate whose scheduled flow
 * from [fromAccount] has the same [cents] and a day of month within its tolerance of [date] (this, previous and next month
 * are tried, so the 30th and the 2nd meet). Zero or several candidates give null.
 */
fun route(rule: OwnAccountRule, fromAccount: Int, cents: Long, date: LocalDate, scheduled: List<ScheduledFlow>): Int? {
    rule.toAccount?.let { return it }
    if (!rule.scheduled) return null
    val hits = scheduled.filter { f ->
        f.from == fromAccount && f.to in rule.candidates && f.cents == cents && f.dayOfMonth != null &&
            dayOfMonthWithin(date, f.dayOfMonth, f.toleranceDays)
    }.map { it.to }.distinct()
    return hits.singleOrNull()
}
