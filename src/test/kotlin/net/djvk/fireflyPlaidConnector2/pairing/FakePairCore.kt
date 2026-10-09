package net.djvk.fireflyPlaidConnector2.pairing

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PairApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.sync.FireflyTransactionService
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * A fake of Firefly plus core's pair endpoint (design 5), used through the real [PairApi] over a Ktor mock engine, so the
 * client's HTTP mapping is exercised too. Every call is serialized (core serializes writers with row locks); the contract:
 *  - the live (keep, absorb) pair answers 200 again with the same pair_merge_id, whatever versions were sent (5.3)
 *  - absorbed into another keep: 409 not_single; a missing group: 404; version differs: 409 stale
 *  - 409 reconciled / not_single, 422 same_account / amount_mismatch / direction, each checked from the stored journals
 *  - a refusal writes nothing: the journals are untouched and nothing is ever removed on a refusal
 */
private val CORE_STAMP = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}""")

/** Core's `date_format:Y-m-d\TH:i:sP` shape: a `T`, seconds and a numeric offset (no `Z`, no missing seconds). Null when it would be refused. */
internal fun parseCoreStamp(s: String?): java.time.Instant? = s?.takeIf { CORE_STAMP.matches(it) }?.let { OffsetDateTime.parse(it).toInstant() }

internal class FakePairCore {
    private val journals = linkedMapOf<String, TransactionRead>()
    private val live = linkedMapOf<Pair<String, String>, String>()
    private val absorbedInto = mutableMapOf<String, String>()
    private var groupSeq = 1
    private val requests = mutableListOf<Pair<HttpMethod, String>>()

    /** If set, the next merge call is answered with this status and reason before anything is checked or written. */
    @Volatile
    var refuseNext: Pair<Int, String>? = null

    /** If set, sent as the `Retry-After` header on every response. */
    @Volatile
    var retryAfter: String? = null

    /** The millis the client asked to pause before a retry (the real sleep is skipped, W2). */
    val pauses = java.util.Collections.synchronizedList(mutableListOf<Long>())

    /** Runs inside the call before the checks (an edit that races the merge); the fake is locked while it runs. */
    @Volatile
    var beforeChecks: (() -> Unit)? = null

    private val mapper = ObjectMapper()

    @Synchronized
    fun add(
        plaidId: String, account: Int, out: Boolean, date: LocalDate, cents: Long, text: String, tags: List<String> = listOf(),
        updated: String = "2026-02-01T00:00:00Z", reconciled: Boolean = false, links: List<PlaidLink>? = null, groupId: String? = null,
    ): String {
        val id = groupId ?: "g${groupSeq++}"
        val split = FireflyFixtures.getTransaction(
            type = if (out) TransactionTypeProperty.withdrawal else TransactionTypeProperty.deposit,
            date = OffsetDateTime.of(date.atStartOfDay(), ZoneOffset.UTC), amount = "%d.%02d".format(cents / 100, cents % 100), description = text,
            sourceId = if (out) account.toString() else null, destinationId = if (out) null else account.toString(),
            sourceName = if (out) null else "Somewhere", destinationName = if (out) "Somewhere" else null,
            plaidLinks = links ?: listOf(PlaidLink(plaidId, PlaidLinkLeg.single, "p$account")), tags = tags, updatedAt = OffsetDateTime.parse(updated),
            currencyCode = "USD", reconciled = reconciled,
        )
        journals[id] = TransactionRead("transactions", id, split, ObjectLink())
        return id
    }

    @Synchronized fun snapshot(): Map<String, TransactionRead> = LinkedHashMap(journals)

    @Synchronized fun journal(id: String): TransactionRead? = journals[id]

    @Synchronized fun edit(id: String, updated: String) {
        val g = journals.getValue(id)
        journals[id] = TransactionRead("transactions", id, g.attributes.copy(updatedAt = OffsetDateTime.parse(updated)), g.links)
    }

    /** Every (out plaid id, in plaid id) that a live merge joined, in the order merged. */
    @Synchronized fun mergedPlaidPairs(): List<Pair<String, String>> = live.keys.map { (keep, absorb) ->
        val k = journals.getValue(keep).attributes.transactions.single().plaidLinks!!
        k.first { it.leg == PlaidLinkLeg.source }.plaidTransactionId to k.first { it.leg == PlaidLinkLeg.destination }.plaidTransactionId
    }

    @Synchronized fun liveMergeCount() = live.size

    @Synchronized fun requestLog(): List<Pair<HttpMethod, String>> = requests.toList()

    /** What the pass reads: journals whose date lies in [from]..[to], in one consistent snapshot. */
    fun service(): FireflyTransactionService {
        val s = mock<FireflyTransactionService>()
        runBlocking {
            whenever(s.fetchFireflyTransactionsStrictly(any(), any(), any())).doSuspendableAnswer {
                val from = it.getArgument<LocalDate>(0)
                val to = it.getArgument<LocalDate>(1)
                snapshot().values.filter { j ->
                    val d = j.attributes.transactions.first().date.toLocalDate()
                    !d.isBefore(from) && !d.isAfter(to)
                }
            }
        }
        return s
    }

    /** The real client, talking to this fake through a mock engine; expectSuccess as in production (ApiConfiguration). */
    fun api(): PairApi = object : PairApi("http://core.test", MockEngine { request ->
        val body = request.body.toByteArray().toString(Charsets.UTF_8)
        val (status, json) = handle(request.method, request.url.encodedPath, request.url.encodedQuery, body)
        val h = headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOfNotNull(retryAfter))
        respond(json, HttpStatusCode.fromValue(status), h)
    }, { it.expectSuccess = true }) {
        override suspend fun retryPause(millis: Long) { pauses.add(millis) }
    }

    @Synchronized
    private fun handle(method: HttpMethod, path: String, query: String, body: String): Pair<Int, String> {
        requests.add(method to if (query.isEmpty()) path else "$path?$query")
        if (method != HttpMethod.Post || path != "/api/v1/plaid-links/pair") return 204 to ""
        val req = mapper.readTree(body)
        val keepId = req["keep_group_id"].asText()
        val absorbId = req["absorb_group_id"].asText()
        refuseNext?.let { refuseNext = null; return it.first to """{"reason": "${it.second}"}""" }
        live[keepId to absorbId]?.let { return ok(it) }
        beforeChecks?.let { beforeChecks = null; it() }
        if (absorbId in absorbedInto) return refuse(409, "not_single")
        val keep = journals[keepId] ?: return refuse(404, "not_found")
        val absorb = journals[absorbId] ?: return refuse(404, "not_found")
        val ks = keep.attributes.transactions.single()
        val a = absorb.attributes.transactions.single()
        // N0: validate the stamps the way core does (a T, seconds, an offset), then compare instants; never toString with toString
        val keepStamp = parseCoreStamp(req["keep_updated_at"].asText(null))
        val absorbStamp = parseCoreStamp(req["absorb_updated_at"].asText(null))
        if (keepStamp == null || absorbStamp == null) return refuse(422, "invalid_request")
        if (keep.attributes.updatedAt?.toInstant() != keepStamp || absorb.attributes.updatedAt?.toInstant() != absorbStamp) return refuse(409, "stale")
        if (ks.plaidLinks.orEmpty().size != 1 || a.plaidLinks.orEmpty().size != 1 ||
            ks.plaidLinks!!.single().leg != PlaidLinkLeg.single || a.plaidLinks!!.single().leg != PlaidLinkLeg.single
        ) return refuse(409, "not_single")
        if (ks.reconciled == true || a.reconciled == true) return refuse(409, "reconciled")
        if (ks.type != TransactionTypeProperty.withdrawal || a.type != TransactionTypeProperty.deposit) return refuse(422, "direction")
        if (ks.amount != a.amount) return refuse(422, "amount_mismatch")
        if (ks.sourceId == a.destinationId) return refuse(422, "same_account")

        val links = listOf(ks.plaidLinks!!.single().copy(leg = PlaidLinkLeg.source), a.plaidLinks!!.single().copy(leg = PlaidLinkLeg.destination))
        journals[keepId] = TransactionRead(
            "transactions", keep.id, keep.attributes.copy(transactions = listOf(ks.copy(type = TransactionTypeProperty.transfer, plaidLinks = links, destinationId = a.destinationId))), keep.links,
        )
        journals.remove(absorbId)
        absorbedInto[absorbId] = keepId
        val id = "m${live.size + 1}"
        live[keepId to absorbId] = id
        return ok(id)
    }

    private fun refuse(status: Int, reason: String) = status to """{"reason": "$reason"}"""

    private fun ok(id: String) = 200 to """{"data": {"pair_merge_id": "$id", "type": "transfer"}}"""
}
