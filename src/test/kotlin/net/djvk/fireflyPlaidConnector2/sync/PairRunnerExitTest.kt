package net.djvk.fireflyPlaidConnector2.sync

import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PairApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PairMergeOutcome
import net.djvk.fireflyPlaidConnector2.pairing.Edge
import net.djvk.fireflyPlaidConnector2.pairing.PairFailure
import net.djvk.fireflyPlaidConnector2.pairing.PairPass
import net.djvk.fireflyPlaidConnector2.pairing.PairPassReport
import net.djvk.fireflyPlaidConnector2.pairing.PairSettings
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** A1: a pair that core refused makes the pair step exit non-zero, so the nightly prints its "!!" line. */
internal class PairRunnerExitTest {
    private val runner = PairRunner(mock<SyncHelper>(), mock<TransactionConverter>(), mock<PairPass>(), PairSettings(), mock<PairApi>())

    private fun report(rejected: List<Pair<Edge, PairMergeOutcome.Rejected>>, failed: List<PairFailure> = emptyList()) =
        mock<PairPassReport>().also { whenever(it.rejected).thenReturn(rejected); whenever(it.failed).thenReturn(failed) }

    @Test
    fun aRefusedPairFailsTheStep() {
        val refused = listOf(mock<Edge>() to PairMergeOutcome.Rejected(422, "not_own_accounts"))
        assertThatThrownBy { runner.failOnRefusals(report(refused)) }
            .hasMessageContaining("core refused 1 pair").hasMessageContaining("not_own_accounts")
    }

    @Test
    fun aStale409IsANormalEventAndDoesNotFailTheStep() {
        runner.failOnRefusals(report(listOf(mock<Edge>() to PairMergeOutcome.Rejected(409, "stale"))))
    }

    @Test
    fun aStaleDoesNotHideAnotherRefusalAndFailuresAndRefusalsShareOneMessage() {
        val rows = listOf(mock<Edge>() to PairMergeOutcome.Rejected(409, "stale"), mock<Edge>() to PairMergeOutcome.Rejected(422, "invalid_request"))
        assertThatThrownBy { runner.failOnRefusals(report(rows, listOf(PairFailure("o", "i", "2026-03-01", "HTTP 500")))) }
            .hasMessageContaining("core refused 1 pair").hasMessageContaining("invalid_request").hasMessageContaining("o+i: HTTP 500")
            .message().doesNotContain("stale")
    }

    @Test
    fun aCleanPassExitsNormally() {
        runner.failOnRefusals(report(emptyList()))
    }
}
