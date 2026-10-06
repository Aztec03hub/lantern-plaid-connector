package net.djvk.fireflyPlaidConnector2.config

data class AccountConfig(
    val fireflyAccountId: Int,
    val plaidItemAccessToken: String,
    val plaidAccountId: String,
    /**
     * Set to true for a brokerage/retirement account whose activity should be read from Plaid's investment
     * transactions instead of its bank transactions. The Item must have been linked with the Plaid "investments"
     * product. Only used in polled mode.
     */
    val investment: Boolean = false,
)
