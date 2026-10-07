/*
 * Hand-written (not generated): Lantern's Firefly fork stores which Plaid transaction a Firefly transaction came from in
 * the `plaid_transaction_links` table and exposes it as `plaid_links` on every split. See lantern/docs/core-plaid-links.md.
 */

package net.djvk.fireflyPlaidConnector2.api.firefly.models

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/** Which side of the Firefly transaction a linked Plaid transaction is: the whole thing, or one leg of a transfer. */
enum class PlaidLinkLeg(val value: kotlin.String) {
    @JsonProperty(value = "single")
    single("single"),

    @JsonProperty(value = "source")
    source("source"),

    @JsonProperty(value = "destination")
    destination("destination");

    override fun toString(): String = value
}

/**
 * One Plaid transaction id on one split. Firefly refuses a second row for the same [plaidTransactionId], so this is
 * the connector's dedupe key. A transfer between two linked banks is one split with two links.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PlaidLink(
    @field:JsonProperty("plaid_transaction_id")
    val plaidTransactionId: kotlin.String,

    @field:JsonProperty("leg")
    val leg: PlaidLinkLeg,

    @field:JsonProperty("plaid_account_id")
    val plaidAccountId: kotlin.String? = null,
)

/** One row of `GET /api/v1/plaid-links`: where a Plaid transaction id is stored. The ids come back as strings. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PlaidLinkLookupRow(
    @field:JsonProperty("plaid_transaction_id")
    val plaidTransactionId: kotlin.String,

    @field:JsonProperty("transaction_journal_id")
    val transactionJournalId: kotlin.String,

    @field:JsonProperty("transaction_group_id")
    val transactionGroupId: kotlin.String,

    @field:JsonProperty("leg")
    val leg: PlaidLinkLeg,

    @field:JsonProperty("plaid_account_id")
    val plaidAccountId: kotlin.String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlaidLinkLookupResponse(
    @field:JsonProperty("data")
    val data: kotlin.collections.List<PlaidLinkLookupRow>,
)
