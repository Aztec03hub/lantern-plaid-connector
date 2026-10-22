package net.djvk.fireflyPlaidConnector2.sync

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import net.djvk.fireflyPlaidConnector2.api.firefly.infrastructure.ApiClient
import net.djvk.fireflyPlaidConnector2.api.firefly.models.RuleTrigger
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RehomeUnknownTriggerTest {
    // A rule trigger Firefly's generated enum lacks (for example source_account_id) must fail the rule read, so the rehome tool stops rather than ignore the rule and delete an account it names.
    @Test
    fun unknownTriggerTypeFailsTheRead() {
        val m: ObjectMapper = jacksonObjectMapper().apply(ApiClient.JSON_DEFAULT)
        assertThrows(Exception::class.java) {
            m.readValue("""{"type":"source_account_id","value":"5"}""", RuleTrigger::class.java)
        }
    }
}
