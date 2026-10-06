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
    /**
     * Optional label for the bank this account is at (for example "Chase"). Only used to name the institution in
     * the log and in the failure report when the Item's sync fails; Plaid itself is never asked for it.
     */
    val institutionName: String? = null,
)
