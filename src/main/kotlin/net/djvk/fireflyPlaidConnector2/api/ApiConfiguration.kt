package net.djvk.fireflyPlaidConnector2.api

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.logging.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Profile("!test")
@Configuration
class ApiConfiguration(
    @Value("\${fireflyPlaidConnector2.http.connectTimeoutMillis:10000}") private val connect: Long = 10_000L,
    @Value("\${fireflyPlaidConnector2.http.socketTimeoutMillis:60000}") private val socket: Long = 60_000L,
    @Value("\${fireflyPlaidConnector2.http.requestTimeoutMillis:60000}") private val request: Long = 60_000L,
) {
    @Bean
    fun getEngine(): HttpClientEngine {
        return CIO.create()
    }

    @Bean
    fun getClientConfig(): ((HttpClientConfig<*>) -> Unit) {
        return {
            it.expectSuccess = true
            it.install(HttpTimeout) {
                /**
                 * This is high enough for Plaid's /accounts/balance/get endpoint to do whatever synchronous shenanigans
                 *  it wants to and return something useful rather than our client just timing out
                 */
                requestTimeoutMillis = request
                connectTimeoutMillis = connect
                socketTimeoutMillis = socket
            }
//            it.install(Logging) {
//                level = LogLevel.ALL
//            }
        }
    }

//    @Bean
//    fun getJsonBlock(): ObjectMapper.() -> Unit {
//        return {}
//    }
}