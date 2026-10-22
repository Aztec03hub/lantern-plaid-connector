package net.djvk.fireflyPlaidConnector2.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

class ConfigLimitsTest {
    private fun problems(vararg kv: Pair<String, String>): List<String> {
        val m = kv.associate { "fireflyPlaidConnector2.${it.first}" to it.second }
        return configProblems(m::get)
    }

    @ParameterizedTest
    @CsvSource(
        "polled.syncFrequencyMinutes,0", "plaid.maxRetries,0", "firefly.writeConcurrency,0", "plaid.batchSize,501",
        "polled.existingFireflyPullWindowDays,0", "pair.reviewMin,0", "http.connectTimeoutMillis,0",
        "firefly.maxReadPages,0", "firefly.maxRangeTransactions,0", "plaid.rateLimitWaitMillis,-1",
    )
    fun `a value out of range is reported with the full key`(key: String, bad: String) {
        val p = problems(key to bad)
        assertThat(p).hasSize(1)
        assertThat(p[0]).startsWith("fireflyPlaidConnector2.$key ")
    }

    @Test
    fun `a blank access token is reported`() {
        assertThat(problems("firefly.personalAccessToken" to "")).singleElement().asString()
            .startsWith("fireflyPlaidConnector2.firefly.personalAccessToken")
    }

    @Test
    fun `autoMin below reviewMin is reported`() {
        assertThat(problems("pair.autoMin" to "2", "pair.reviewMin" to "3")).singleElement().asString()
            .startsWith("fireflyPlaidConnector2.pair.autoMin")
    }

    @Test
    fun `a pull window not larger than the transfer window is refused`() {
        assertThat(problems("polled.existingFireflyPullWindowDays" to "3", "transferMatchWindowDays" to "3"))
            .singleElement().asString().startsWith("fireflyPlaidConnector2.polled.existingFireflyPullWindowDays")
    }

    @Test
    fun `a non-numeric value names the key`() {
        assertThat(problems("plaid.maxRetries" to "abc")).singleElement().asString()
            .startsWith("fireflyPlaidConnector2.plaid.maxRetries ")
    }

    @Test
    fun `all defaults pass, including autoMin equal to reviewMin`() {
        assertThat(
            problems(
                "polled.syncFrequencyMinutes" to "10", "plaid.maxRetries" to "3", "firefly.writeConcurrency" to "8",
                "plaid.batchSize" to "100", "polled.existingFireflyPullWindowDays" to "5", "transferMatchWindowDays" to "3",
                "pair.autoMin" to "3", "pair.reviewMin" to "3", "polled.refreshIntervalMinutes" to "0",
                "plaid.rateLimitWaitMillis" to "60000", "http.connectTimeoutMillis" to "10000",
                "firefly.maxReadPages" to "1000", "firefly.maxRangeTransactions" to "200000",
            ),
        ).isEmpty()
        assertThat(problems()).isEmpty()
    }

    @Test
    fun `two bad keys are both reported`() {
        assertThat(problems("plaid.maxRetries" to "0", "firefly.writeConcurrency" to "0")).hasSize(2)
    }

    @Test
    fun `startup fails with the full key in the message`() {
        val env = StandardEnvironment().apply {
            propertySources.addFirst(MapPropertySource("t", mapOf("fireflyPlaidConnector2.firefly.writeConcurrency" to "0")))
        }
        val e = assertThrows<IllegalStateException> { ConfigLimits(env) }
        assertThat(e.message).contains("fireflyPlaidConnector2.firefly.writeConcurrency")
    }

    @Test
    fun `the shipped application yml passes the limits`() {
        val res = org.springframework.core.io.ClassPathResource("application.yml")
        val flat = org.springframework.boot.env.YamlPropertySourceLoader().load("yml", res).first()
        assertThat(configProblems { flat.getProperty(it)?.toString() }).isEmpty()
    }

    @Test
    fun `thresholds above ten are accepted`() {
        assertThat(problems("pair.autoMin" to "12", "pair.reviewMin" to "8")).isEmpty()
    }

    @TempDir
    lateinit var tmp: java.nio.file.Path

    private fun fileWithMode(name: String, mode: String): java.nio.file.Path {
        assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        val f = tmp.resolve(name)
        java.nio.file.Files.writeString(f, "secret-content")
        java.nio.file.Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString(mode))
        return f
    }

    @ParameterizedTest
    @CsvSource("rw-r--r--", "rw-r-----", "rw----r--")
    fun `a group or others readable file gets one warning with the file name only`(mode: String) {
        val w = permissionWarnings(listOf(fileWithMode("app.yml", mode)))
        assertThat(w).singleElement().asString().startsWith("app.yml is readable by group or others").contains(mode).doesNotContain("secret-content").doesNotContain(tmp.toString())
    }

    @Test
    fun `an owner only file and a missing path give no warning`() {
        assertThat(permissionWarnings(listOf(fileWithMode("ok.yml", "rw-------"), tmp.resolve("missing.yml")))).isEmpty()
    }

    @Test
    fun `config locations are split and stripped and directories skipped`() {
        val f = tmp.resolve("a.yml").also { java.nio.file.Files.writeString(it, "x") }
        assertThat(configFilePaths("optional:file:$f, $tmp", null)).containsExactly(f)
    }

    @Test
    fun `the cursor file and its directory are checked at startup`() {  // LAN-72
        fileWithMode("plaid_sync_cursors.txt", "rw-r--r--")
        java.nio.file.Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
        val w = permissionWarnings(startupPermissionPaths(listOf(), tmp))
        assertThat(w).hasSize(2)
        assertThat(w.last()).startsWith("plaid_sync_cursors.txt is readable by group or others")
    }

    @Test
    fun `a missing cursor file at first start is silent and a directory is told chmod 700`() {  // CL22 r1
        java.nio.file.Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
        val w = permissionWarnings(startupPermissionPaths(listOf(), tmp))
        assertThat(w).hasSize(1)
        assertThat(w.single()).contains("chmod 700").doesNotContain("secret")
    }
}
