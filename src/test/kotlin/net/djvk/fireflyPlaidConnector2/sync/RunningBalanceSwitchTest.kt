package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.ConfigurationApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RunningBalanceSwitchTest {
    @Test
    fun putsAJsonBooleanToTheRunningBalanceConfiguration() = runBlocking<Unit> {
        val seen = mutableListOf<Triple<HttpMethod, String, String>>()
        val api = ConfigurationApi("http://firefly.test", MockEngine { request ->
            seen.add(Triple(request.method, request.url.encodedPath, request.body.toByteArray().toString(Charsets.UTF_8)))
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })

        api.setUseRunningBalance(false)
        api.setUseRunningBalance(true)

        assertThat(seen.map { it.first }).containsOnly(HttpMethod.Put)
        assertThat(seen.map { it.second }).containsOnly("/api/v1/configuration/configuration.use_running_balance")
        assertThat(seen[0].third.replace(Regex("\\s"), "")).isEqualTo("""{"value":false}""")
        assertThat(seen[1].third.replace(Regex("\\s"), "")).isEqualTo("""{"value":true}""")
    }
}
