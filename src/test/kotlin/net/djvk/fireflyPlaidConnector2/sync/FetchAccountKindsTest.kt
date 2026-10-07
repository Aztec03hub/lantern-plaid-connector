package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.config.AccountConfig
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.transactions.AccountKind
import net.djvk.fireflyPlaidConnector2.lib.FireflyMock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [SyncHelper.fetchAccountKinds] against bodies shaped like Firefly 6.7.7's AccountTransformer output. A
 * liability carries interest_period "monthly", which the generated model once mapped to the LiabilityDirection enum
 * and crashed every importer at startup.
 */
internal class FetchAccountKindsTest {
    @Suppress("unused")
    private val warmMockUtil = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE

    private val firefly = FireflyMock()

    private fun liability(id: String, interestPeriod: String, liabilityType: String = "debt", extra: String = "") = """
        {"type":"accounts","id":"$id","attributes":{
          "created_at":"2026-10-01T10:00:00-05:00","updated_at":"2026-10-01T10:00:00-05:00","active":true,"order":null,
          "name":"Card $id","type":"liabilities","account_role":null,
          "object_group_id":null,"object_group_order":null,"object_group_title":null,
          "object_has_currency_setting":true,
          "currency_id":"1","currency_name":"US Dollar","currency_code":"USD","currency_symbol":"$","currency_decimal_places":2,
          "primary_currency_id":"1","primary_currency_name":"US Dollar","primary_currency_code":"USD","primary_currency_symbol":"$","primary_currency_decimal_places":2,
          "current_balance":"-125.50","pc_current_balance":"-125.50","opening_balance":"-100.00","pc_opening_balance":"-100.00",
          "virtual_balance":"0.00","pc_virtual_balance":"0.00","debt_amount":"125.50","pc_debt_amount":"125.50",
          "balance_difference":"-25.50","pc_balance_difference":"-25.50",
          "current_balance_date":"2026-10-06T23:59:59-05:00","notes":null,"monthly_payment_date":null,"credit_card_type":null,
          "account_number":null,"iban":null,"bic":null,"opening_balance_date":"2026-01-01T00:00:00-06:00",
          "liability_type":"$liabilityType","liability_direction":"debit","interest":"19.99","interest_period":"$interestPeriod",
          "include_net_worth":true,"longitude":null,"latitude":null,"zoom_level":null,"last_activity":null$extra,
          "links":[{"rel":"self","uri":"/accounts/$id"}]},
         "links":{"self":"http://firefly/api/v1/accounts/$id"}}
    """.trimIndent()

    private fun page(rows: List<String>, current: Int?, total: Int?) =
        """{"data":[${rows.joinToString(",")}],"meta":{"pagination":${
            if (current == null) "null" else """{"total":3,"count":${rows.size},"per_page":2,"current_page":$current,"total_pages":$total}"""
        }},"links":{"self":"x","first":"x","last":"x"}}"""

    private fun asset(id: String) = """
        {"type":"accounts","id":"$id","attributes":{"name":"Checking $id","type":"asset","account_role":"defaultAsset","active":true,
         "currency_code":"USD","current_balance":"10.00","include_net_worth":true,"liability_type":null,"interest_period":null},
         "links":{"self":"http://firefly/api/v1/accounts/$id"}}
    """.trimIndent()

    private val noAssets = page(listOf(), 1, 1)

    private fun helperFor(
        liabilityPages: Map<String, String>,
        assetPage: String = noAssets,
        configured: List<Int> = listOf(),
    ) = SyncHelper(
        AccountConfigs(configured.map { AccountConfig(it, "token", "plaid$it") }), "token", firefly.aboutApi, firefly.transactionsApi,
        AccountsApi("http://firefly.test", MockEngine { request ->
            val body = when (request.url.parameters["type"]) {
                "liabilities" -> liabilityPages.getValue(request.url.parameters["page"] ?: "1")
                "asset" -> assetPage
                else -> throw AssertionError("unexpected type filter ${request.url.parameters["type"]}")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }),
        firefly.plaidLinksApi,
    )

    private val liabilities = AccountKind.LIABILITY

    @Test
    fun readsLiabilitiesWithAMonthlyInterestPeriodAcrossTwoPages() = runBlocking<Unit> {
        val helper = helperFor(
            mapOf(
                "1" to page(listOf(liability("4", "monthly"), liability("5", "yearly", "loan")), 1, 2),
                "2" to page(listOf(liability("9", "half-year", "mortgage")), 2, 2),
            ),
            page(listOf(asset("1"), asset("2")), 1, 1),
        )
        assertThat(helper.fetchAccountKinds()).isEqualTo(
            mapOf("1" to AccountKind.ASSET, "2" to AccountKind.ASSET, "4" to liabilities, "5" to liabilities, "9" to liabilities)
        )
    }

    @Test
    fun aValueFireflyAddsToAnEnumLaterDoesNotCrashTheRead() = runBlocking<Unit> {
        val helper = helperFor(mapOf("1" to page(listOf(liability("4", "fortnightly")), 1, 1)))
        assertThat(helper.fetchAccountKinds()).isEqualTo(mapOf("4" to liabilities))
    }

    @Test
    fun nullPaginationMeansOnePage() = runBlocking<Unit> {
        val helper = helperFor(mapOf("1" to page(listOf(liability("4", "monthly")), null, null)))
        assertThat(helper.fetchAccountKinds()).isEqualTo(mapOf("4" to liabilities))
    }

    @Test
    fun aConfiguredAccountThatIsNeitherAnAssetNorALiabilityStopsTheStart() = runBlocking<Unit> {
        // 7 is, say, an expense account: it is in neither listing
        val helper = helperFor(
            mapOf("1" to page(listOf(liability("4", "monthly")), 1, 1)), page(listOf(asset("1")), 1, 1),
            configured = listOf(1, 4, 7),
        )
        val e = runCatching { helper.fetchAccountKinds() }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalStateException::class.java)
        assertThat(e!!.message).contains("7").doesNotContain("1,").doesNotContain("4")
    }

    @Test
    fun everyConfiguredAssetAndLiabilityPassesTheStartCheck() = runBlocking<Unit> {
        val helper = helperFor(
            mapOf("1" to page(listOf(liability("4", "monthly")), 1, 1)), page(listOf(asset("1")), 1, 1),
            configured = listOf(1, 4),
        )
        assertThat(helper.fetchAccountKinds()).containsKeys("1", "4")
    }
}
