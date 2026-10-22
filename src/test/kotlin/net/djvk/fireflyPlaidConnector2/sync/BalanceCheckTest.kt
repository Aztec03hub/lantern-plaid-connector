package net.djvk.fireflyPlaidConnector2.sync

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDate

internal class BalanceCheckTest {
    @TempDir lateinit var dir: File
    private val tol = BigDecimal("0.05")
    private fun d(s: String) = BigDecimal(s)
    private fun cmp(type: String, ff: String, plaid: String) = compareBalances(1, type, d(ff), d(plaid), tol)
    private val d1 = LocalDate.of(2026, 10, 18)
    private val d2 = d1.plusDays(1)
    private val d3 = d1.plusDays(2)
    private fun gap(a: String = "5.00") = BalanceResult(1, "depository", "gap", d("10"), d("5"), d(a))
    private fun ok() = BalanceResult(1, "depository", "ok", d("10"), d("10"), d("0"))
    private fun gone() = BalanceResult(1, "depository", "unavailable", errorCode = "INSTITUTION_DOWN")
    private fun night(prev: List<GapEntry>, r: BalanceResult, day: LocalDate) = mergeFirstSeen(prev.associateBy { it.account }, listOf(r), day)

    @Test
    fun aGapUnexplainedWithOrWithoutPendingOnlyCountsWhenUnexplainedBothWays() {
        // Plaid excludes pending: posted 100 matches Plaid 100 (Firefly incl. pending would be 80)
        assertThat(compareBalances(1, "depository", d("100"), d("100"), tol, d("80")).status).isEqualTo("ok")
        // Plaid includes pending: with-pending 80 matches Plaid 80 (posted would be 100)
        assertThat(compareBalances(1, "depository", d("100"), d("80"), tol, d("80")).status).isEqualTo("ok")
        // neither matches: a gap, reported as the smaller one
        val r = compareBalances(1, "depository", d("100"), d("90"), tol, d("85"))
        assertThat(r.status).isEqualTo("gap")
        assertThat(r.gap).isEqualByComparingTo("-5.00")
    }

    @Test
    fun depositoryToleranceSignAndEdge() {
        assertThat(cmp("depository", "1200.00", "1200.03").status).isEqualTo("ok")
        assertThat(cmp("depository", "1200.05", "1200.00").status).isEqualTo("ok")
        assertThat(cmp("depository", "1210.00", "1200.00").gap).isEqualByComparingTo("10.00")
        assertThat(cmp("depository", "1200.00", "1210.00").gap).isEqualByComparingTo("-10.00")
        assertThat(cmp("depository", "1200.00", "1210.00").status).isEqualTo("gap")
    }

    @Test
    fun creditAndLoanAreComparedAsOwed() {
        for (t in listOf("credit", "loan")) {
            assertThat(cmp(t, "-305.40", "305.40").status).isEqualTo("ok")
            val g = cmp(t, "-310.40", "305.40")
            assertThat(g.gap).isEqualByComparingTo("-5.00")
            assertThat(g.plaid).isEqualByComparingTo("-305.40")
        }
    }

    @Test
    fun firstSeenSurvivesChangingGapsAndClosesOnOk() {
        val n1 = night(listOf(), gap("5.00"), d1)
        val n2 = night(n1, gap("-7.00"), d2)
        assertThat(n2.single().firstSeen).isEqualTo(d1.toString())
        val n3 = night(n2, ok(), d3)
        assertThat(n3.single().firstSeen).isNull()
        assertThat(night(n3, gap(), d3.plusDays(1)).single().firstSeen).isEqualTo(d3.plusDays(1).toString())
    }

    @Test
    fun anUnavailableNightNeitherStartsNorResetsTheClock() {
        val n1 = night(listOf(), gap(), d1)
        val n2 = night(n1, gone(), d2)
        assertThat(n2.single().firstSeen).isEqualTo(d1.toString())
        assertThat(n2.single().lastSeen).isEqualTo(d1.toString())
        assertThat(night(n2, gap(), d3).single().firstSeen).isEqualTo(d1.toString())
        val fresh = night(night(night(listOf(), ok(), d1), gone(), d2), gap(), d3)
        assertThat(fresh.single().firstSeen).isEqualTo(d3.toString())
    }

    @Test
    fun aSecondRunOnTheSameDayChangesNothingAndARemovedAccountLeaves() {
        val n1 = night(listOf(), gap(), d1)
        assertThat(night(n1, gap(), d1)).isEqualTo(n1)
        assertThat(mergeFirstSeen(n1.associateBy { it.account }, listOf(), d2)).isEmpty()
    }

    private fun store() = BalanceGapStore(File(dir, "balance_gap.json").toPath())

    @Test
    fun theFileHasTheDocumentedShapeIsOwnerOnlyAndLeavesNoTemp() {
        val f = File(dir, "balance_gap.json")
        f.writeText("old"); Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
        store().write(night(listOf(), gap(), d1) + night(listOf(), ok(), d1).map { it.copy(account = 0) }, tol)
        val root = ObjectMapper().readTree(f)
        assertThat(root.fieldNames().asSequence().toList()).containsExactly("version", "checked_at", "tolerance", "accounts")
        assertThat(root["tolerance"].asText()).isEqualTo("0.05")
        assertThat(root["accounts"].map { it["account"].asInt() }).containsExactly(0, 1)
        assertThat(root["accounts"][1].fieldNames().asSequence().toList())
            .containsExactly("account", "plaid_type", "status", "firefly", "plaid", "gap", "first_seen", "last_seen", "error_code")
        assertThat(root["accounts"][1]["gap"].isTextual).isTrue()
        assertThat(Files.getPosixFilePermissions(f.toPath())).isEqualTo(PosixFilePermissions.fromString("rw-------"))
        assertThat(dir.list()!!.filter { it.endsWith(".tmp") }).isEmpty()
        assertThat(store().read().keys).containsExactly(0, 1)
    }

    @Test
    fun anUnreadablePreviousFileIsRebuilt() {
        File(dir, "balance_gap.json").writeText("\u0000garbage{")
        assertThat(store().read()).isEmpty()
    }

    @Test
    fun aFailedWriteLeavesTheOldFileWhole() {
        val f = File(dir, "balance_gap.json")
        store().write(night(listOf(), gap(), d1), tol)
        val before = f.readBytes()
        // a directory squatting on the temp-rename target is not portable; make the target itself a non-empty directory instead
        val target = File(dir, "sub/balance_gap.json").also { it.mkdirs(); File(it, "x").writeText("x") }
        org.junit.jupiter.api.assertThrows<Exception> { BalanceGapStore(target.toPath()).write(night(listOf(), gap(), d1), tol) }
        assertThat(f.readBytes()).isEqualTo(before)
        assertThat(target.isDirectory).isTrue()
        assertThat(File(dir, "sub").list()!!.filter { it.endsWith(".tmp") }).isEmpty()
    }
}
