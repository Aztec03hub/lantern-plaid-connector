package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.ApiConfiguration
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapper
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.lib.FireflyMock
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class SecurityHardeningTest {
    // region cursor file

    @Test
    fun cursorFileIsOwnerOnlyAndLeavesNoTempFiles(@TempDir dir: Path) = runBlocking<Unit> {
        val manager = CursorManager(dir.toString())

        manager.writeCursorMap(mapOf("access-production-secret-1" to "cursor-1"))
        manager.writeCursorMap(mapOf("access-production-secret-1" to "cursor-2")) // replace existing

        val perms = PosixFilePermissions.toString(Files.getPosixFilePermissions(manager.cursorFilePath))
        assertEquals("rw-------", perms)
        assertEquals(listOf("plaid_sync_cursors.txt"), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })
        assertEquals(mapOf("access-production-secret-1" to "cursor-2"), manager.readCursorMap())
    }

    @Test
    fun cursorsContainingPipesRoundTrip(@TempDir dir: Path) = runBlocking<Unit> {
        val manager = CursorManager(dir.toString())

        manager.writeCursorMap(mapOf("tok" to "a|b|c"))

        assertEquals(mapOf("tok" to "a|b|c"), manager.readCursorMap())
    }

    @Test
    fun malformedCursorFileFailsClearlyWithoutEchoingTheToken(@TempDir dir: Path) = runBlocking<Unit> {
        val manager = CursorManager(dir.toString())
        Files.writeString(manager.cursorFilePath, "access-production-SECRET-WITHOUT-CURSOR\n")

        val e = assertThrows<IllegalStateException> { runBlocking { manager.readCursorMap() } }

        assertTrue(e.message!!.contains("line 1"))
        assertFalse(e.message!!.contains("SECRET"), "access token must not appear in the error")
    }

    @Test
    fun missingCursorDirectoryIsCreated(@TempDir dir: Path) = runBlocking<Unit> {
        val manager = CursorManager(dir.resolve("not/yet/there").toString())

        manager.writeCursorMap(mapOf("tok" to "cur"))

        assertEquals(mapOf("tok" to "cur"), manager.readCursorMap())
    }

    // endregion

    // region plaid url

    @Test
    fun plaidUrlMustBeHttpsUnlessLoopback() {
        fun wrapper(url: String) = PlaidApiWrapper(
            baseUrl = url, maxRetries = 1, plaidClientId = "id", plaidSecret = "s",
            httpClientEngine = MockEngine { respond("{}") },
            httpClientConfig = ApiConfiguration().getClientConfig(),
        )

        wrapper("https://production.plaid.com")
        wrapper("http://localhost:8080")
        wrapper("http://127.0.0.1:8080")
        assertThrows<IllegalArgumentException> { wrapper("http://production.plaid.com") }
        assertThrows<IllegalArgumentException> { wrapper("ftp://production.plaid.com") }
        assertThrows<IllegalArgumentException> { wrapper("production.plaid.com") }
    }

    // endregion

    // region silent data loss

    @Test
    fun optimisticInsertDoesNotSwallowClientErrorsOtherThan409() = runBlocking<Unit> {
        val firefly = FireflyMock()
        // Produce a genuine 403 ClientRequestException using a mock engine
        val client = io.ktor.client.HttpClient(MockEngine { respond("{}", HttpStatusCode.Forbidden) }) {
            expectSuccess = true
        }
        val forbidden = try {
            client.get("https://firefly.test/api/v1/transactions"); null
        } catch (e: ClientRequestException) {
            e
        }!!
        whenever(firefly.transactionsApi.storeTransaction(any())).doSuspendableAnswer { throw forbidden }
        val helper = SyncHelper(AccountConfigs(emptyList()), "token", firefly.aboutApi, firefly.transactionsApi, firefly.accountsApi, firefly.plaidLinksApi)
        val dto = FireflyTransactionDto(null, FireflyFixtures.getTransaction().transactions.first())

        // Only a 409 ("already imported") is skipped; swallowing any other client error would lose the transaction
        // once the caller commits its Plaid cursor.
        assertThrows<ClientRequestException> { runBlocking { helper.optimisticInsertBatchIntoFirefly(listOf(dto)) } }
    }

    // endregion
}
