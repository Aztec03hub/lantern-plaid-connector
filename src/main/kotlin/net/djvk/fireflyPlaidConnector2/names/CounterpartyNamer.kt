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
        s = s.substringBefore(": ").takeIf { raw.contains(": ") && !Regex("(?i)\\b(type|id|co):").containsMatchIn(raw) } ?: s
        s = s.replace(Regex("(?i)\\b(type|id|co|ind id|ind name|trace|orig id):.*$"), "")
        s = s.replace(Regex("^(?i)((ac|ach|ppd|web|ccd|pos|sq \\*|tst\\* |paypal \\*|debit card purchase|purchase authorized on \\d{2}/\\d{2})\\s+)+"), "")
        // a masked account number ("XXXX0373W") and whatever follows it
        s = s.replace(Regex("\\s+[Xx*]{2,}\\d+\\w*.*$"), "")
        // from the first long digit run (trace and id numbers) on, drop everything
        s = s.replace(Regex("\\s*[Xx*#]*\\d{5,}.*$"), "")
        // store numbers, locations and terminal words: "TARGET T-1234 CHICAGO IL"
        s = s.replace(Regex("\\s+(#|no\\.?\\s*)?\\d{1,5}\\b.*$"), "")
        // a trailing "CITY ST" location, only when a name of at least two words is left
        Regex("\\s+[A-Za-z.]+\\s+[A-Z]{2}$").find(s)?.let { if (s.substring(0, it.range.first).trim().contains(' ')) s = s.substring(0, it.range.first) }
        s = s.replace(Regex("(?i)(\\s+(payroll|ppd|web|ccd|pmt|payment|autopay|direct dep|dir dep|des|inc\\.?|llc\\.?))+$"), "")
        return s.replace(Regex("\\s+"), " ").trim(' ', '*', '-', ',', '.')
    }

    private fun titleCase(s: String): String =
        if (s.any { it.isLowerCase() } && s.any { it.isUpperCase() }) s
        else s.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    companion object {
        /** `{"EVOLV CONSULTING": "Evolv Consulting", ...}`; keys are compared by [key]. A missing file is no aliases. */
        fun fromFile(path: String): CounterpartyNamer {
            if (path.isBlank() || !File(path).exists()) return CounterpartyNamer()
            val node = ObjectMapper().readTree(File(path))
            return CounterpartyNamer(node.fields().asSequence().associate { it.key to it.value.asText() })
        }
    }
}
