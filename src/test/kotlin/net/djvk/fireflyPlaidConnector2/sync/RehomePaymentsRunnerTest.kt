package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountTypeProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Meta
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PageLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionArray
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionUpdate
import net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.lib.createFireflyResponse
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.time.OffsetDateTime

/** `syncMode: rehome-payments` against mocked Firefly APIs. */
internal class RehomePaymentsRunnerTest {
    @Suppress("unused")
    private val warmMockUtil = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE

    @TempDir lateinit var dir: File

    private val accountsApi = mock<AccountsApi>()
    private val txApi = mock<TransactionsApi>()
    private val syncHelper = mock<SyncHelper>()
    private val text = "AC PHILLIP LAFAYETT ACH XFER XXXX7769WEB XXXX0198"

    private fun converter(): TransactionConverter {
        val f = File(dir, "pair.json").also {
            it.writeText("""{"scheduled":[{"from":1,"to":2,"amount":400,"day":7}],
                "ownAccountPayments":[{"fromAccount":1,"match":"XXXX0198","route":"scheduled","candidates":[2,3]}]}""")
        }
        return TransactionConverter(
            useNameForDestination = true, enablePrimaryCategorization = false, primaryCategoryPrefix = "p-",
            enableDetailedCategorization = false, detailedCategoryPrefix = "d-", timeZoneString = "America/Chicago",
            transferMatchWindowDays = 3, txStyle = TransactionStyleConfig(null), pairConfigFile = f.path,
        )
    }

    private fun journal(id: String, amount: String, desc: String = text, destType: AccountTypeProperty = AccountTypeProperty.expenseAccount) = TransactionRead(
        "transactions", id,
        FireflyFixtures.getTransaction(
            sourceId = "1", destinationId = "50", destinationType = destType, amount = amount, description = desc,
            date = OffsetDateTime.now().minusDays(2).withDayOfMonth(7).let { if (it.isAfter(OffsetDateTime.now())) it.minusMonths(1) else it },
            transactionJournalId = "j$id", plaidLinks = listOf(PlaidLink("p$id", PlaidLinkLeg.single, "plaid1")),
        ), ObjectLink(),
    )

    private fun runner(apply: Boolean, rows: List<TransactionRead>): RehomePaymentsRunner {
        runBlocking {
            whenever(accountsApi.listTransactionByAccount(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
                .doSuspendableAnswer { createFireflyResponse(TransactionArray(rows, Meta(), PageLink())) }
        }
        return RehomePaymentsRunner(syncHelper, accountsApi, txApi, converter(), apply, "2000-01-01", dir.path)
    }

    // 400 matches the Lexus schedule only on the 7th; journals are dated on the 7th of a recent month
    private val rows get() = listOf(
        journal("a", "400.00"),
        journal("b", "110.00"),
        journal("c", "400.00", desc = "SOMETHING ELSE"),
        journal("d", "400.00", destType = AccountTypeProperty.loan),
    )

    @Test
    fun aDryRunChangesNothing() {
        runner(false, rows).run()
        runBlocking { verify(txApi, never()).updateTransaction(any(), any()) }
        assertThat(File(dir, "rehome_log.jsonl")).doesNotExist()
    }

    @Test
    fun applyLogsThenUpdatesOnlyTheMatchedRow() = runBlocking<Unit> {
        val r = runner(true, rows)
        val plan = r.readPlan()
        assertThat(plan.map { it.journalId to it.to }).containsExactlyInAnyOrder("a" to 2, "b" to null)
        r.applyPlan(plan)

        val id = argumentCaptor<String>()
        val upd = argumentCaptor<TransactionUpdate>()
        verify(txApi).updateTransaction(id.capture(), upd.capture())
        assertThat(id.firstValue).isEqualTo("a")
        val split = upd.firstValue.transactions!!.single()
        assertThat(split.destinationId).isEqualTo("2")
        assertThat(split.plaidLinks!!.single().leg).isEqualTo(PlaidLinkLeg.source)
        val lines = File(dir, "rehome_log.jsonl").readLines()
        assertThat(lines).hasSize(1)
        assertThat(lines[0]).contains("\"journal\":\"a\"").contains("\"oldDestination\":\"50\"").contains("\"newDestination\":\"2\"")
    }
}
