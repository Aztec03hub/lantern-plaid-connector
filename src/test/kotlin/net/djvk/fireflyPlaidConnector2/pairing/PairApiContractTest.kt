package net.djvk.fireflyPlaidConnector2.pairing

import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PairMergeOutcome
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PairMergeRequest
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The client of core's merge endpoint (design 5, test 6 and K6) against a fake of the endpoint's contract. */
internal class PairApiContractTest {
    private val day = LocalDate.of(2026, 1, 10)
    // the version the connector sends is the group's updated_at as PairPass.stamp prints it (seconds, "+00:00", never "Z")
    private val v = PairPass.stamp(java.time.OffsetDateTime.parse("2026-02-01T00:00:00Z"))

    private fun world(): Triple<FakePairCore, String, String> {
        val core = FakePairCore()
        val keep = core.add("out1", 1, true, day, 12_345, "AC OUT")
        val absorb = core.add("in1", 2, false, day, 12_345, "IN")
        return Triple(core, keep, absorb)
    }

    private fun request(keep: String, absorb: String, keepV: String? = v, absorbV: String? = v) =
        PairMergeRequest(keep, absorb, keepV, absorbV, mapOf("rule_version" to 1))

    @Test
    fun a200MergesAndTheMergedJournalCarriesBothLinks() = runBlocking<Unit> {
        val (core, keep, absorb) = world()
        val outcome = core.api().merge(request(keep, absorb))
        assertThat(outcome).isEqualTo(PairMergeOutcome.Merged("m1", "transfer"))
        assertThat(core.snapshot().keys).containsExactly(keep)
        assertThat(core.mergedPlaidPairs()).containsExactly("out1" to "in1")
    }

    @Test
    fun aRetryOfTheSamePairAnswersTheSameMergeIdWhateverVersionsAreSent() = runBlocking<Unit> {
        val (core, keep, absorb) = world()
        val api = core.api()
        val first = api.merge(request(keep, absorb))
        val retry = api.merge(request(keep, absorb, keepV = "2030-01-01T00:00:00Z", absorbV = null))
        assertThat(retry).isEqualTo(first)
        assertThat(core.liveMergeCount()).isEqualTo(1)
        assertThat(core.mergedPlaidPairs()).hasSize(1)
    }

    @Test
    fun anAbsorbedJournalMergedIntoAnotherKeepIsRefusedAsNotSingle() = runBlocking<Unit> {
        val (core, keep, absorb) = world()
        val other = core.add("out2", 3, true, day, 12_345, "AC OUT")
        val api = core.api()
        api.merge(request(keep, absorb))
        assertThat(api.merge(request(other, absorb))).isEqualTo(PairMergeOutcome.Rejected(409, "not_single"))
    }

    @Test
    fun everyDocumentedReasonIsReturnedAsARefusalAndNothingIsWrittenOrDeleted() = runBlocking<Unit> {
        val reasons = listOf(
            409 to "stale", 409 to "not_single", 409 to "reconciled", 409 to "attachments", 409 to "piggy_bank", 409 to "journal_links", 409 to "link_conflict",
            422 to "same_account", 422 to "amount_mismatch", 422 to "currency_mismatch", 422 to "direction", 422 to "not_own_accounts", 422 to "type_not_possible",
        )
        for ((status, reason) in reasons) {
            val (core, keep, absorb) = world()
            val before = core.snapshot()
            core.refuseNext = status to reason
            assertThat(core.api().merge(request(keep, absorb))).describedAs(reason).isEqualTo(PairMergeOutcome.Rejected(status, reason))
            assertThat(core.snapshot()).describedAs("$reason leaves both journals as they were").isEqualTo(before)
            assertThat(core.liveMergeCount()).isZero()
            assertThat(core.requestLog().map { it.first }).describedAs("$reason: the client only ever POSTs, it never deletes").containsOnly(HttpMethod.Post)
        }
    }

    @Test
    fun theFakeDerivesTheRefusalsItCanCheckFromTheStoredJournals() = runBlocking<Unit> {
        // stale: a version that differs from the stored one
        var (core, keep, absorb) = world()
        assertThat(core.api().merge(request(keep, absorb, keepV = "2026-02-02T00:00:00+00:00"))).isEqualTo(PairMergeOutcome.Rejected(409, "stale"))
        // an edit that lands after the connector read but before core checks
        world().let { (c, k, a) -> c.beforeChecks = { c.edit(k, "2026-02-20T00:00:00Z") }; assertThat(c.api().merge(request(k, a))).isEqualTo(PairMergeOutcome.Rejected(409, "stale")) }
        // reconciled and already-paired journals
        core = FakePairCore()
        keep = core.add("o", 1, true, day, 500, "x", reconciled = true)
        absorb = core.add("i", 2, false, day, 500, "y")
        assertThat(core.api().merge(request(keep, absorb))).isEqualTo(PairMergeOutcome.Rejected(409, "reconciled"))
        core = FakePairCore()
        keep = core.add("o", 1, true, day, 500, "x", links = listOf(PlaidLink("o", PlaidLinkLeg.source, "p1"), PlaidLink("z", PlaidLinkLeg.destination, "p2")))
        absorb = core.add("i", 2, false, day, 500, "y")
        assertThat(core.api().merge(request(keep, absorb))).isEqualTo(PairMergeOutcome.Rejected(409, "not_single"))
        // 422s
        core = FakePairCore()
        keep = core.add("o", 1, true, day, 500, "x")
        absorb = core.add("i", 2, false, day, 600, "y")
        assertThat(core.api().merge(request(keep, absorb))).isEqualTo(PairMergeOutcome.Rejected(422, "amount_mismatch"))
        core = FakePairCore()
        keep = core.add("o", 1, true, day, 500, "x")
        absorb = core.add("i", 1, false, day, 500, "y")
        assertThat(core.api().merge(request(keep, absorb))).isEqualTo(PairMergeOutcome.Rejected(422, "same_account"))
        core = FakePairCore()
        keep = core.add("o", 1, false, day, 500, "x")
        absorb = core.add("i", 2, false, day, 500, "y")
        assertThat(core.api().merge(request(keep, absorb))).isEqualTo(PairMergeOutcome.Rejected(422, "direction"))
        // after every one of these both journals are still there
        assertThat(core.snapshot()).hasSize(2)
    }

    @Test
    fun aMissingGroupAndServerErrorsAreThrownNotReportedAsMerged() = runBlocking<Unit> {
        val (core, keep, _) = world()
        val failure = runCatching { core.api().merge(request(keep, "nope")) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(ClientRequestException::class.java).hasMessageContaining("404")
        core.refuseNext = 500 to "boom"
        assertThat(runCatching { core.api().merge(request(keep, "nope")) }.exceptionOrNull()).isNotNull()
    }

    @Test
    fun aRefusalWithoutAReasonBodyIsStillARefusalWithTheStatus() = runBlocking<Unit> {
        val (core, keep, absorb) = world()
        // the fake answers an empty reason: the client falls back to "HTTP <status>"
        core.refuseNext = 409 to ""
        val outcome = core.api().merge(request(keep, absorb)) as PairMergeOutcome.Rejected
        assertThat(outcome.status).isEqualTo(409)
        assertThat(outcome.reason).isEqualTo("HTTP 409")
    }

    @Test
    fun unmergeSendsADeleteWithForceOnlyWhenAsked() = runBlocking<Unit> {
        val core = FakePairCore()
        val api = core.api()
        api.unmerge("17")
        api.unmerge("17", force = true)
        assertThat(core.requestLog()).containsExactly(HttpMethod.Delete to "/api/v1/plaid-links/pair/17", HttpMethod.Delete to "/api/v1/plaid-links/pair/17?force=true")
    }
}
