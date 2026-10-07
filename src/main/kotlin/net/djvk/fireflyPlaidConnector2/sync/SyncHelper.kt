package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.call.*
import io.ktor.client.network.sockets.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AboutApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.FireflyTransactionId
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PlaidLinksApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.FireflyApiError
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.transactions.FireflyAccountId
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import net.djvk.fireflyPlaidConnector2.versionManagement.VersionComparison
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

typealias PlaidAccessToken = String
typealias PlaidAccountId = String

const val MINIMUM_FIREFLY_VERSION = "6.1.2"

/** A Plaid id that cannot exist, looked up at startup to prove the link table endpoint is there. */
private const val STARTUP_PROBE_ID = "lantern-startup-probe"

@Component
class SyncHelper(
    private val plaidAccountsConfig: AccountConfigs,

    @Value("\${fireflyPlaidConnector2.firefly.personalAccessToken}")
    private val fireflyAccessToken: String,
    private val fireflyAboutApi: AboutApi,
    private val fireflyTxApi: TransactionsApi,
    private val fireflyAccountsApi: AccountsApi,
    private val fireflyPlaidLinksApi: PlaidLinksApi,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun setApiCreds() {
        // Spring components are singletons by default, so this should set these credentials for any other
        //  component that also uses these components
        fireflyTxApi.setAccessToken(fireflyAccessToken)
        fireflyAccountsApi.setAccessToken(fireflyAccessToken)
        fireflyAboutApi.setAccessToken(fireflyAccessToken)
        fireflyPlaidLinksApi.setAccessToken(fireflyAccessToken)
        validateFireflyApiVersion()
        validatePlaidLinksEndpoint()
    }

    /**
     * The connector's dedupe is Firefly's Plaid link table, which only Lantern's Firefly fork has. On a stock Firefly
     * the `plaid_links` fields would be silently ignored and every retry would import the same transactions again, so
     * a missing endpoint stops the start instead of letting the connector run without the guarantee.
     */
    protected suspend fun validatePlaidLinksEndpoint() {
        val missing = IllegalStateException(
            "This Firefly has no /api/v1/plaid-links endpoint, so it is not Lantern's Firefly fork. The connector " +
                    "relies on its Plaid link table to refuse duplicate transactions and will not run without it. " +
                    "Point fireflyPlaidConnector2.firefly.url at the Lantern fork."
        )
        try {
            fireflyPlaidLinksApi.lookupPlaidLinks(listOf(STARTUP_PROBE_ID)).body()
        } catch (cre: ClientRequestException) {
            if (cre.response.status == HttpStatusCode.NotFound || cre.response.status == HttpStatusCode.MethodNotAllowed) throw missing
            throw cre
        } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
            throw missing
        }
    }

    protected suspend fun validateFireflyApiVersion() {
        val fireflyVersion = fireflyAboutApi.getAbout().body().data.version
        if (!VersionComparison.isVersionSufficient(MINIMUM_FIREFLY_VERSION, fireflyVersion)) {
            throw RuntimeException("This version of the connector requires at least version $MINIMUM_FIREFLY_VERSION " +
                "of Firefly; version $fireflyVersion found")
        }
    }

    fun getAllPlaidAccessTokenAccountIdSets():
            Pair<Map<PlaidAccountId, FireflyAccountId>, Sequence<Pair<PlaidAccessToken, List<PlaidAccountId>>>> {
        val accountMap = plaidAccountsConfig.accounts.associate { Pair(it.plaidAccountId, it.fireflyAccountId) }
        logger.trace("Read config mapping data for ${accountMap.size} Firefly accounts")
        // Investment accounts are synced through a different Plaid endpoint, see getInvestmentAccessTokenAccountIdSets
        val accountsByAccessToken = plaidAccountsConfig.accounts
            .filter { !it.investment }
            .groupBy { it.plaidItemAccessToken }
        logger.trace("Read config mapping data for ${accountsByAccessToken.size} Plaid access tokens")

        return Pair(accountMap, sequence {
            for ((accessToken, accountConfigs) in accountsByAccessToken) {
                val accountIds = accountConfigs.map { it.plaidAccountId }
                yield(Pair(accessToken, accountIds))
            }
        })
    }

    /**
     * The configured accounts marked `investment: true`, grouped by Plaid Item. These are read with
     * /investments/transactions/get rather than /transactions/sync.
     */
    fun getInvestmentAccessTokenAccountIdSets(): Sequence<Pair<PlaidAccessToken, List<PlaidAccountId>>> {
        val investmentsByAccessToken = plaidAccountsConfig.accounts
            .filter { it.investment }
            .groupBy { it.plaidItemAccessToken }
        return sequence {
            for ((accessToken, accountConfigs) in investmentsByAccessToken) {
                yield(Pair(accessToken, accountConfigs.map { it.plaidAccountId }))
            }
        }
    }

    /**
     * Creates each of [fireflyTxs]. A 409 means Firefly already holds one of its Plaid ids (the link table refuses a
     * second row), so that transaction is "already imported" and skipped; the rest carry on.
     *
     * @return how many transactions were actually created (not skipped as already imported or as zero amount)
     */
    suspend fun optimisticInsertBatchIntoFirefly(fireflyTxs: List<FireflyTransactionDto>): Int {
        if (fireflyTxs.isNotEmpty()) {
            logger.debug("Optimistic insert of ${fireflyTxs.size} txs into Firefly")
        }
        var created = 0
        var timedOut = 0
        var firstTimeout: ConnectTimeoutException? = null
        for (fireflyTx in fireflyTxs) {
            val plaidIds = fireflyTx.tx.plaidLinks.orEmpty().map { it.plaidTransactionId }
            try {
                if (insertIntoFirefly(fireflyTx)) {
                    created++
                    if (created % 100 == 0) {
                        logger.debug("Insert of tx index $created successful")
                    }
                }
            } catch (cre: ClientRequestException) {
                when (cre.response.status) {
                    HttpStatusCode.Conflict -> logger.info("Skipped transaction $plaidIds that Firefly already holds")
                    HttpStatusCode.UnprocessableEntity -> {
                        val error = cre.response.body<FireflyApiError>()
                        // Log the Plaid ids and Firefly's message only: the full error object and the transaction hold field values
                        logger.error("Firefly transaction insert rejected (${error.message}) for tx $plaidIds")
                        throw cre
                    }
                    else -> throw cre
                }
            } catch (e: ConnectTimeoutException) {
                // Keep going so one timeout doesn't block the rest, but remember it: silently skipping would lose
                //  the transaction for good once the caller commits its Plaid cursor.
                logger.error("Timeout inserting firefly tx $plaidIds; will fail this batch", e)
                firstTimeout = firstTimeout ?: e
                timedOut++
            }
        }
        if (firstTimeout != null) {
            throw java.io.IOException(
                "$timedOut of ${fireflyTxs.size} Firefly inserts timed out; the caller must retry this batch",
                firstTimeout
            )
        }
        return created
    }

    /** @return false if the transaction was skipped (zero amount), true if it was sent */
    suspend fun insertIntoFirefly(fireflyTx: FireflyTransactionDto): Boolean {
        if (fireflyTx.tx.amount.toDouble() == 0.0) {
            logger.info("Skipped transaction ${fireflyTx.tx.plaidLinks?.map { it.plaidTransactionId }} with amount 0.0")
            return false
        }
        fireflyTxApi.storeTransaction(fireflyTx.toTransactionStore())
        return true
    }

    suspend fun updateBatchInFirefly(fireflyTxs: List<FireflyTransactionDto>) {
        for (fireflyTx in fireflyTxs) {
            fireflyTxApi.updateTransaction(
                fireflyTx.id
                    ?: throw IllegalArgumentException(
                        "Can't update Firefly transaction without id (external id ${fireflyTx.tx.externalId})"
                    ),
                fireflyTx.toTransactionUpdate(),
            )
        }
    }

    suspend fun deleteBatchInFirefly(fireflyTxIds: List<FireflyTransactionId>) {
        if (fireflyTxIds.isNotEmpty()) {
            logger.debug("Delete batch of ${fireflyTxIds.size} txs in Firefly")
        }
        for (fireflyTxId in fireflyTxIds) {
            try {
                fireflyTxApi.deleteTransaction(fireflyTxId)
            } catch (cre: ClientRequestException) {
                // Already gone, for example deleted by an earlier attempt of an iteration that then failed and is
                //  being retried. The goal state is reached, and failing here would block the retry for good.
                if (cre.response.status != HttpStatusCode.NotFound) throw cre
                logger.info("Firefly transaction $fireflyTxId was already deleted")
            }
        }
    }
}