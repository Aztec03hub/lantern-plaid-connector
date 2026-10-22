package net.djvk.fireflyPlaidConnector2.pairing

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path

/** One human decision from `pair-decisions.json`: [action] is exactly `confirm` or `reject`. */
data class PairDecision(val out: String, val inn: String, val action: String)

/** Reads the decisions the app queued. The connector only reads this file; the app writes it. */
object PairDecisionFile {
    const val NAME = "pair-decisions.json"
    private const val MAX_BYTES = 256 * 1024L
    private const val MAX_ID = 128
    private val mapper = jacksonObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    /** An absent file is `[]` and no problem; an unreadable, oversized or wrong-shaped one is `[]` plus a problem. Never throws. */
    fun read(directory: String): Pair<List<PairDecision>, String?> {
        val path = Path.of(directory, NAME)
        if (!Files.exists(path)) return listOf<PairDecision>() to null
        return try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) return listOf<PairDecision>() to "unreadable"
            val items = mapper.readTree(path.toFile()).path("decisions")
            if (!items.isArray) return listOf<PairDecision>() to "unreadable"
            items.mapNotNull { n ->
                val action = n.path("action").takeIf { it.isTextual }?.asText()
                val out = n.path("out").takeIf { it.isTextual }?.asText()
                val inn = n.path("inn").takeIf { it.isTextual }?.asText()
                if ((action != "confirm" && action != "reject") || out.isNullOrBlank() || inn.isNullOrBlank() || out.length > MAX_ID || inn.length > MAX_ID) null
                else PairDecision(out, inn, action)
            } to null
        } catch (e: Exception) {
            listOf<PairDecision>() to "unreadable"
        }
    }
}
