package net.djvk.fireflyPlaidConnector2.sync

import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

/** One account's line in `balance_gap.json`. Ids and Plaid types only; money as 2-decimal strings. */
data class GapEntry(
    val account: Int, val plaidType: String, val status: String,
    val firefly: String?, val plaid: String?, val gap: String?,
    val firstSeen: String?, val lastSeen: String?, val errorCode: String?,
)

/** Reads, merges and atomically writes `balance_gap.json` (derived state: a bad file is rebuilt, never fatal). */
class BalanceGapStore(private val path: Path) {
    private val mapper = ObjectMapper()

    fun read(): Map<Int, GapEntry> {
        if (!Files.exists(path)) return mapOf()
        return try {
            mapper.readTree(path.toFile())["accounts"].map {
                fun s(k: String) = it[k]?.takeIf { n -> !n.isNull }?.asText()
                GapEntry(it["account"].asInt(), s("plaid_type")!!, s("status")!!, s("firefly"), s("plaid"), s("gap"), s("first_seen"), s("last_seen"), s("error_code"))
            }.associateBy { it.account }
        } catch (e: Exception) {
            println("WARN: ${path.fileName} cannot be read (${e.javaClass.simpleName}); every first_seen starts again")
            mapOf()
        }
    }

    /** Writes [entries] (sorted by account) atomically with mode 600; on failure the old file is untouched and the error propagates. */
    fun write(entries: List<GapEntry>, tolerance: BigDecimal, checkedAt: Instant = Instant.now()) {
        val root = linkedMapOf<String, Any?>(
            "version" to 1, "checked_at" to checkedAt.toString().replace(Regex("\\.\\d+Z$"), "Z"),
            "tolerance" to tolerance.setScale(2, RoundingMode.HALF_UP).toPlainString(),
            "accounts" to entries.sortedBy { it.account }.map {
                linkedMapOf("account" to it.account, "plaid_type" to it.plaidType, "status" to it.status, "firefly" to it.firefly, "plaid" to it.plaid,
                    "gap" to it.gap, "first_seen" to it.firstSeen, "last_seen" to it.lastSeen, "error_code" to it.errorCode)
            },
        )
        writeAtomically(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root))
        // the temp file is already 600 (createTempFile); say so explicitly in case the platform default differs
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
    }
}
