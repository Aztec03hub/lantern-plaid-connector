package net.djvk.fireflyPlaidConnector2.util

object Utilities {
    fun getRandomAlphabeticalString(length: Int) : String {
        val allowedChars = ('A'..'Z') + ('a'..'z')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    /**
     * Masks a Plaid access token for logging: keeps only the last four characters, enough to tell Items apart in
     * logs but useless for calling the Plaid API.
     */
    fun redactAccessToken(token: String): String {
        return if (token.length <= 8) "access-****" else "access-****${token.takeLast(4)}"
    }
}