package net.djvk.fireflyPlaidConnector2.names

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/**
 * One counterparty, one name. Bank text for the same payer arrives in several shapes ("EVOLV CONSULTING",
 * "Evolv Consulting Type: Payroll ID: XX2465 CO: Evolv Consulting", "AC EVOLV CONSULTING PAYROLL 1110000..."), and Firefly
 * makes one revenue or expense account per spelling. [canonical] reduces every shape to one display name.
 *
 * Order of preference: Plaid's own counterparty or merchant name (already clean), else the cleaned bank text, and the
 * [aliases] table (canonical key -> display name) has the last word.
 */
class CounterpartyNamer(private val aliases: Map<String, String> = mapOf()) {
    private val aliasByKey = aliases.mapKeys { key(it.key) }

    /** [plaidName] is `counterparties[0].name` or `merchant_name` when Plaid gave one; [text] is the raw transaction name. */
    fun canonical(plaidName: String?, text: String): String {
        val base = plaidName?.takeIf { it.isNotBlank() }?.let { clean(it) }?.takeIf { it.isNotBlank() } ?: clean(text)
        val shown = base.ifBlank { text.trim() }
        return aliasByKey[key(shown)] ?: titleCase(shown)
    }

    /** The comparison key: lower case letters and digits, single spaces. Two names with the same key are the same payee. */
    fun key(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    internal fun clean(raw: String): String {
        var s = raw.trim()
        // "Type: Payroll ID: XX2465 CO: Evolv Consulting": the company is after CO:
        Regex("(?i)\\bCO:\\s*(.+)$").find(s)?.let { s = it.groupValues[1] }
        // a leading "merchant: original text" join made by the connector keeps the merchant half
        // (only when the rest is the same text or all caps; "Interest: DCU Lexus NX loan" keeps its whole name)
        s = s.substringBefore(": ").takeIf {
            val after = s.substringAfter(": ", "")
            raw.contains(": ") && !Regex("(?i)\\b(type|id|co):").containsMatchIn(raw) &&
                (after.startsWith(s.substringBefore(": "), ignoreCase = true) ||
                    (after.none { c -> c.isLowerCase() } && key(s.substringBefore(": ")) !in PERSON_TO_PERSON))
        } ?: s
        cardLine(s)?.let { return it }
        s = s.replace(Regex("(?i)\\b(type|id|co|ind id|ind name|trace|orig id):.*$"), "")
        s = s.replace(Regex("^(?i)((ac|ach|ppd|web|ccd|pos|sq \\*|tst\\* |paypal \\*|debit card purchase|purchase authorized on \\d{2}/\\d{2})\\s+)+"), "")
        // a masked account number ("XXXX0373W") and whatever follows it
        s = s.replace(Regex("\\s+[Xx*]{2,}\\d+\\w*.*$"), "")
        // from the first long digit run (trace and id numbers) on, drop everything
        s = s.replace(Regex("\\s*[Xx*#]*\\d{5,}.*$"), "")
        // store numbers, locations and terminal words: "TARGET #12 CHICAGO IL", "ATM W/D 1234 MAIN ST". A 1-2 digit number
        // without a # is part of the name ("Pier 39 Parking", "Route 66 Diner", "Studio 54") and stays.
        s = s.replace(Regex("\\s+(#|(?i:no)\\.?\\s*)\\d{1,5}\\b.*$"), "")
        s = s.replace(Regex("\\s+\\d{3,5}\\b.*$"), "")
        // N8: a 1-2 digit store number after a known chain is a store number ("TARGET 12 CHICAGO IL", "WALMART SUPERCENTER 47")
        s = s.replace(CHAIN_STORE, "\$1")
        // a trailing "CITY ST" location, only when a name of at least two words is left
        Regex("\\s+[A-Za-z.]+\\s+[A-Z]{2}$").find(s)?.let { if (s.substring(0, it.range.first).trim().contains(' ')) s = s.substring(0, it.range.first) }
        s = s.replace(Regex("(?i)(\\s+(payroll|ppd|web|ccd|pmt|payment|autopay|direct dep|dir dep|des|inc\\.?|llc\\.?))+$"), "")
        return s.replace(Regex("\\s+"), " ").trim(' ', '*', '-', ',', '.')
    }

    /**
     * Debit-card and card-credit lines: "DBT CRD 0514 DJVU7XEK ADVOCATE PATIENT PAYME DOWNERS GROVE IL C#7221".
     * Drops the time and ref, the trailing "ST C#nnnn" and the city; the merchant field is 22 characters wide.
     */
    private fun cardLine(s: String): String? {
        val m = Regex("^(?:DBT CRD|CRE) \\d{4} \\w{8} (.+?)\\s+[A-Z]{2} C#\\d+$").find(s) ?: return null
        val rest = m.groupValues[1]
        val merchant = if (rest.length > 22 && rest[22] == ' ') rest.substring(0, 22) else rest.substringBeforeLast(' ', rest)
        return merchant.trim()
    }

    private fun titleCase(s: String): String =
        if (s.any { it.isLowerCase() } && s.any { it.isUpperCase() }) s
        else s.lowercase().split(' ').joinToString(" ") { w -> if ('&' in w && w.length <= 4) w.uppercase() else w.replaceFirstChar { it.uppercase() } }

    companion object {
        // ponytail: a fixed list; a 1-2 digit number cannot be told from part of a name ("Studio 54", "Pier 1") without knowing the chain.
        // Add a chain here when its stores show up as separate payees.
        private val CHAIN_STORE = Regex(
            "^(?i)(walmart supercenter|walmart|target|starbucks|kroger|home depot|shell oil|shell|cvs[ /]pharmacy|cvs|trader joe['\u2019]?s|lowe['\u2019]?s|costco|walgreens|mcdonald['\u2019]?s|dunkin( donuts)?)\\s+\\d{1,2}\\b.*$",
        )

        /** Prefixes whose text after the colon is a person, not a bank spelling of the prefix ("Zelle: JOHN SMITH"). */
        private val PERSON_TO_PERSON = setOf("zelle", "venmo", "cash app", "paypal", "apple cash")

        /** `{"EVOLV CONSULTING": "Evolv Consulting", ...}`; keys are compared by [key]. A missing file is no aliases. */
        fun fromFile(path: String): CounterpartyNamer {
            if (path.isBlank() || !File(path).exists()) return CounterpartyNamer()
            val node = ObjectMapper().readTree(File(path))
            return CounterpartyNamer(node.fields().asSequence().associate { it.key to it.value.asText() })
        }
    }
}
