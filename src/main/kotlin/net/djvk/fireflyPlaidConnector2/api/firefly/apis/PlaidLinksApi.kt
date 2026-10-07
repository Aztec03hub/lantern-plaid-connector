/*
 * Hand-written (not generated): `GET /api/v1/plaid-links` exists only in Lantern's Firefly fork.
 * See lantern/docs/core-plaid-links.md.
 */

package net.djvk.fireflyPlaidConnector2.api.firefly.apis

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.*
import io.ktor.client.engine.*
import net.djvk.fireflyPlaidConnector2.api.firefly.infrastructure.*
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLookupResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
open class PlaidLinksApi(
    @Value("\${fireflyPlaidConnector2.firefly.url}")
    baseUrl: String = ApiClient.BASE_URL,
    httpClientEngine: HttpClientEngine? = null,
    httpClientConfig: ((HttpClientConfig<*>) -> Unit)? = null,
    jsonBlock: ObjectMapper.() -> Unit = ApiClient.JSON_DEFAULT,
) : ApiClient(baseUrl, httpClientEngine, httpClientConfig, jsonBlock) {

    /**
     * Where each of [plaidTransactionIds] is stored (1 to [MAX_IDS] ids). Ids Firefly does not hold are simply absent
     * from the result. A stock Firefly has no such route and answers 404.
     */
    @Suppress("UNCHECKED_CAST")
    open suspend fun lookupPlaidLinks(plaidTransactionIds: List<String>): HttpResponse<PlaidLinkLookupResponse> {
        require(plaidTransactionIds.size in 1..MAX_IDS) { "A link lookup takes 1 to $MAX_IDS ids" }

        val localVariableAuthNames = listOf<String>("firefly_iii_auth")

        val localVariableBody =
            io.ktor.client.utils.EmptyContent

        val localVariableQuery = mutableMapOf<String, List<String>>()
        localVariableQuery["plaid_transaction_id[]"] = plaidTransactionIds

        val localVariableHeaders = mutableMapOf<String, String>()

        val localVariableConfig = RequestConfig<kotlin.Any?>(
            RequestMethod.GET,
            "/api/v1/plaid-links",
            query = localVariableQuery,
            headers = localVariableHeaders
        )

        return request(
            localVariableConfig,
            localVariableBody,
            localVariableAuthNames
        ).wrap()
    }

    companion object {
        /** The most ids Firefly resolves in one lookup. */
        const val MAX_IDS = 500
    }
}
