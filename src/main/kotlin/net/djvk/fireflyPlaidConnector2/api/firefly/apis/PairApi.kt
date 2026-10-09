/*
 * Hand-written (not generated): the pair merge and unmerge endpoints exist only in Lantern's Firefly fork.
 * See lantern/docs/design/transfer-pairer.md (sections 5 and 6) and lantern/docs/core-pair-merge.md.
 */

package net.djvk.fireflyPlaidConnector2.api.firefly.apis

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.statement.bodyAsText
import net.djvk.fireflyPlaidConnector2.api.firefly.infrastructure.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** What the connector asks core to merge: two single journals into one pair. */
data class PairMergeRequest(
    val keepGroupId: String,
    val absorbGroupId: String,
    val keepUpdatedAt: String?,
    val absorbUpdatedAt: String?,
    /** Audit only: core stores it with the merge and does not interpret it. */
    val evidence: Map<String, Any?>,
)

/** The outcome of a merge call. [rejected] is a refusal by core (nothing was written); anything else throws. */
sealed interface PairMergeOutcome {
    data class Merged(val pairMergeId: String, val type: String?) : PairMergeOutcome

    /** 409 or 422 with core's [reason] (`stale`, `not_single`, `reconciled`, `type_not_possible`, ...). */
    data class Rejected(val status: Int, val reason: String) : PairMergeOutcome
}

@Component
open class PairApi(
    @Value("\${fireflyPlaidConnector2.firefly.url}")
    baseUrl: String = ApiClient.BASE_URL,
    httpClientEngine: HttpClientEngine? = null,
    httpClientConfig: ((HttpClientConfig<*>) -> Unit)? = null,
    jsonBlock: ObjectMapper.() -> Unit = ApiClient.JSON_DEFAULT,
) : ApiClient(baseUrl, httpClientEngine, httpClientConfig, jsonBlock) {
    private val mapper = ObjectMapper()

    /** POST /api/v1/plaid-links/pair. 409/422 are returned as [PairMergeOutcome.Rejected]; every other error is thrown. */
    open suspend fun merge(request: PairMergeRequest): PairMergeOutcome {
        try {
            return mergeOnce(request)
        } catch (e: ServerResponseException) {
            if (e.response.status.value != 503) throw e
            // W2: core's 503 `busy` (a journal is being written at that moment) is a retry, not a failed pair; once, after Retry-After
            val wait = e.response.headers["Retry-After"]?.toLongOrNull()?.coerceIn(0, 5) ?: 1
            retryPause(wait * 1000)
            return mergeOnce(request)
        }
    }

    /** The sleep before the one retry of a busy core; a test overrides it. */
    internal open suspend fun retryPause(millis: Long) = kotlinx.coroutines.delay(millis)

    private suspend fun mergeOnce(request: PairMergeRequest): PairMergeOutcome {
        val body = mapOf(
            "keep_group_id" to request.keepGroupId,
            "absorb_group_id" to request.absorbGroupId,
            "keep_updated_at" to request.keepUpdatedAt,
            "absorb_updated_at" to request.absorbUpdatedAt,
            "evidence" to request.evidence,
        )
        return try {
            val response = jsonRequest(
                RequestConfig<Any?>(RequestMethod.POST, "/api/v1/plaid-links/pair", query = mutableMapOf(), headers = mutableMapOf()),
                body, listOf("firefly_iii_auth"),
            )
            val data = mapper.readTree(response.bodyAsText()).path("data")
            PairMergeOutcome.Merged(data.path("pair_merge_id").asText(), data.path("type").asText(null))
        } catch (e: ClientRequestException) {
            val status = e.response.status.value
            if (status != 409 && status != 422) throw e
            val reason = runCatching { mapper.readTree(e.response.bodyAsText()).path("reason").asText("") }.getOrDefault("")
            PairMergeOutcome.Rejected(status, reason.ifEmpty { "HTTP $status" })
        }
    }

    /** DELETE /api/v1/plaid-links/pair/{id}[?force=true]. */
    open suspend fun unmerge(pairMergeId: String, force: Boolean = false) {
        request(
            RequestConfig<Any?>(
                RequestMethod.DELETE, "/api/v1/plaid-links/pair/$pairMergeId",
                query = if (force) mutableMapOf("force" to listOf("true")) else mutableMapOf(), headers = mutableMapOf(),
            ),
            io.ktor.client.utils.EmptyContent, listOf("firefly_iii_auth"),
        )
    }
}
