package net.djvk.fireflyPlaidConnector2.config

import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

private const val P = "fireflyPlaidConnector2."

/** An inclusive integer range for one key (relative to [P]). */
private data class Rule(val key: String, val min: Long, val max: Long)

private val RULES = listOf(
    Rule("polled.syncFrequencyMinutes", 1, 1440),
    Rule("plaid.maxRetries", 1, 10),
    Rule("firefly.writeConcurrency", 1, 32),
    Rule("plaid.batchSize", 1, 500),
    Rule("polled.existingFireflyPullWindowDays", 1, 3650),
    Rule("pair.autoMin", 1, 100),
    Rule("pair.reviewMin", 1, 100),
    Rule("polled.refreshIntervalMinutes", 0, 10080),
    Rule("plaid.rateLimitWaitMillis", 0, 600000),
    Rule("http.connectTimeoutMillis", 1, 600000),
    Rule("http.socketTimeoutMillis", 1, 600000),
    Rule("http.requestTimeoutMillis", 1, 600000),
    Rule("firefly.maxReadPages", 1, 100000),
    Rule("firefly.maxRangeTransactions", 1, 5000000),
)

/**
 * One message per bad key (each starts with the full key), for every configured value outside its range. An absent key is
 * skipped: the code's own @Value default covers it. Values are never echoed, only the key and the allowed range.
 */
internal fun configProblems(get: (String) -> String?): List<String> {
    val problems = mutableListOf<String>()
    val numbers = mutableMapOf<String, Long>()
    for (r in RULES) {
        val raw = get(P + r.key) ?: continue
        val n = raw.trim().toLongOrNull()
        when {
            n == null -> problems += "$P${r.key} must be a whole number"
            n !in r.min..r.max -> problems += "$P${r.key} must be between ${r.min} and ${r.max}"
            else -> numbers[r.key] = n
        }
    }
    val auto = numbers["pair.autoMin"] ?: 4
    val review = numbers["pair.reviewMin"] ?: 3
    if (auto < review) problems += "${P}pair.autoMin must be at least ${P}pair.reviewMin"
    val pull = numbers["polled.existingFireflyPullWindowDays"]
    val transfer = get(P + "transferMatchWindowDays")?.trim()?.toLongOrNull()
    if (pull != null && transfer != null && pull <= transfer) {
        problems += "${P}polled.existingFireflyPullWindowDays must be greater than ${P}transferMatchWindowDays"
    }
    val pat = get(P + "firefly.personalAccessToken")
    if (pat != null && pat.isBlank()) problems += "${P}firefly.personalAccessToken must not be blank"
    return problems
}

/** Fails the Spring context at startup, with every problem at once, instead of an hour into the nightly. */
@Component
class ConfigLimits(env: Environment) {
    init {
        val problems = configProblems(env::getProperty)
        check(problems.isEmpty()) { "Invalid configuration:\n" + problems.joinToString("\n") }
        val url = env.getProperty(P + "firefly.url")
        if (url != null && !url.startsWith("https://") && !Regex("^http://(localhost|127\\.0\\.0\\.1|\\[::1])([:/]|$)").containsMatchIn(url)) {
            LoggerFactory.getLogger(ConfigLimits::class.java)
                .warn("${P}firefly.url is not https and not loopback; the Firefly token travels unencrypted")
        }
    }
}
