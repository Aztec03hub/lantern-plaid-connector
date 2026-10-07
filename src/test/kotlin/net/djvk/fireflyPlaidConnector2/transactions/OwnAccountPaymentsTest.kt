package net.djvk.fireflyPlaidConnector2.transactions

import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig
import net.djvk.fireflyPlaidConnector2.lib.PlaidFixtures
import net.djvk.fireflyPlaidConnector2.pairing.ScheduledFlow
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

internal class OwnAccountPaymentsTest {
    private val rule = OwnAccountRule(1, Regex("XXXX0198"), scheduled = true, candidates = listOf(2, 3))
    private val flows = listOf(
        ScheduledFlow(1, 2, 40000, 7), ScheduledFlow(1, 2, 40000, 22),
        ScheduledFlow(1, 3, 12000, 7), ScheduledFlow(1, 3, 12000, 22),
    )
    private fun r(cents: Long, y: Int, m: Int, d: Int, fl: List<ScheduledFlow> = flows) = route(rule, 1, cents, LocalDate.of(y, m, d), fl)

    @Test
    fun lexusRoutesOnTheDayAndTheDayAfter() {
        assertThat(r(40000, 2026, 10, 7)).isEqualTo(2)
        assertThat(r(40000, 2026, 10, 8)).isEqualTo(2)
        assertThat(r(40000, 2026, 10, 22)).isEqualTo(2)
        assertThat(r(40000, 2026, 10, 23)).isEqualTo(2)
    }

    @Test
    fun personalLoanRoutesToThree() = assertThat(r(12000, 2026, 10, 7)).isEqualTo(3)

    @Test
    fun imagineAmountIsNotRouted() = assertThat(r(11000, 2026, 10, 7)).isNull()

    @Test
    fun twoCandidatesWithTheSameAmountAndDayAreNotRouted() {
        assertThat(r(40000, 2026, 10, 7, flows + ScheduledFlow(1, 3, 40000, 7))).isNull()
    }

    @Test
    fun day30AndDay2MeetAcrossAMonthBoundary() {
        val f = listOf(ScheduledFlow(1, 2, 40000, 30))
        assertThat(r(40000, 2026, 11, 2, f)).isEqualTo(2)
        val g = listOf(ScheduledFlow(1, 2, 40000, 2))
        assertThat(r(40000, 2026, 10, 30, g)).isEqualTo(2)
    }

    @Test
    fun fixedTargetNeedsNoSchedule() {
        assertThat(route(OwnAccountRule(6, Regex("x"), toAccount = 2), 6, 1, LocalDate.now(), emptyList())).isEqualTo(2)
    }

    // region converter

    @TempDir lateinit var dir: File

    private fun converter(config: String?): TransactionConverter {
        val path = config?.let { File(dir, "pair.json").also { f -> f.writeText(it) }.path } ?: ""
        return TransactionConverter(
            useNameForDestination = true, enablePrimaryCategorization = false, primaryCategoryPrefix = "p-",
            enableDetailedCategorization = false, detailedCategoryPrefix = "d-", timeZoneString = "America/Chicago",
            transferMatchWindowDays = 3, txStyle = TransactionStyleConfig(null), pairConfigFile = path,
        )
    }

    private val json = """{"scheduled":[{"from":1,"to":2,"amount":400,"day":7}],
        "ownAccountPayments":[{"fromAccount":1,"match":"XXXX0198","route":"scheduled","candidates":[2,3]}]}"""

    private fun tx(amount: Double) = PlaidFixtures.getTransaction(
        name = "AC PHILLIP LAFAYETT ACH XFER XXXX7769WEB XXXX0198", originalDescription = "AC PHILLIP LAFAYETT ACH XFER XXXX7769WEB XXXX0198",
        paymentChannel = net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction.PaymentChannel.other,
        personalFinanceCategory = net.djvk.fireflyPlaidConnector2.api.plaid.models.PersonalFinanceCategory("TRANSFER_OUT", "TRANSFER_OUT_ACCOUNT_TRANSFER"),
        amount = amount, date = LocalDate.of(2026, 10, 7), accountId = "plaid1",
    )

    private fun convert(c: TransactionConverter, t: net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction) =
        runBlocking { c.convertBatchSync(listOf(t), mapOf("plaid1" to 1)).single() }

    @Test
    fun routedOutCreateGoesToTheLoanWithLegSource() {
        val s = convert(converter(json), tx(400.0)).tx
        assertThat(s.destinationId).isEqualTo("2")
        assertThat(s.destinationName).isNull()
        assertThat(s.plaidLinks!!.single().leg).isEqualTo(PlaidLinkLeg.source)
    }

    @Test
    fun anInRowWithTheSameTextIsNotRouted() {
        val s = convert(converter(json), tx(-400.0)).tx
        assertThat(s.destinationId).isEqualTo("1")
        assertThat(s.plaidLinks!!.single().leg).isEqualTo(PlaidLinkLeg.single)
    }

    @Test
    fun noConfigFileLeavesThePayeePath() {
        val s = convert(converter(null), tx(400.0)).tx
        assertThat(s.destinationId).isNull()
        assertThat(s.destinationName).isNotNull()
        assertThat(s.plaidLinks!!.single().leg).isEqualTo(PlaidLinkLeg.single)
    }

    // endregion
}
