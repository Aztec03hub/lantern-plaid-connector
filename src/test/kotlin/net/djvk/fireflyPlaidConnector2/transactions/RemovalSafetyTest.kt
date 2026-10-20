package net.djvk.fireflyPlaidConnector2.transactions

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.lib.PlaidFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction as PlaidTransaction

/**
 * U01 (LAN-56): a Plaid removal must never delete a Firefly group that holds several splits. All ids are invented.
 */
internal class RemovalSafetyTest {
    private val accountMap = PlaidFixtures.getStandardAccountMapping()
    private val accountA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    private fun converter() = TransactionConverter(
        useNameForDestination = true,
        enablePrimaryCategorization = false,
        primaryCategoryPrefix = "plaid-primary-cat-",
        enableDetailedCategorization = false,
        detailedCategoryPrefix = "plaid-detailed-cat-",
        timeZoneString = "America/New_York",
        transferMatchWindowDays = 3L,
        txStyle = TransactionStyleConfig(null),
    )

    private fun link(id: String) = PlaidLink(id, PlaidLinkLeg.single, accountA)

    /** One group holding one withdrawal split per entry; a null entry is a split with no Plaid link (added by the user). */
    private fun group(id: String, vararg links: String?): TransactionRead {
        val splits = links.mapIndexed { i, l ->
            FireflyFixtures.getTransaction(
                type = TransactionTypeProperty.withdrawal, amount = "${10 + i}.0",
                sourceId = "1", destinationId = null,
                plaidLinks = l?.let { listOf(link(it)) },
                description = "Split words $i", currencyId = "5", currencyCode = "USD",
                destinationName = "Shop",
            ).transactions.single()
        }
        return TransactionRead(
            "transactions", id,
            net.djvk.fireflyPlaidConnector2.api.firefly.models.Transaction(transactions = splits), ObjectLink()
        )
    }

    private fun errors(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(TransactionConverter::class.java) as ch.qos.logback.classic.Logger
        val seen = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(seen)
        try {
            block()
        } finally {
            logger.detachAppender(seen)
        }
        return seen.list.filter { it.level == Level.ERROR }
    }

    private fun sync(
        created: List<PlaidTransaction> = listOf(),
        removed: List<String>,
        existing: List<TransactionRead>,
    ) = runBlocking { converter().convertPollSync(accountMap, created, listOf(), removed, existing) }

    @Test
    fun aTwoSplitGroupIsNotDeletedWhenPlaidRemovesItsId() {
        lateinit var result: TransactionConverter.ConvertPollSyncResult
        val errs = errors { result = sync(removed = listOf("wd"), existing = listOf(group("ff1", "wd", null))) }

        assertThat(result.deletes).isEmpty()
        assertThat(result.updates).isEmpty()
        assertThat(result.creates).isEmpty()
        assertThat(result.transfersNeedingReview).containsExactly("ff1")
        assertThat(errs).hasSize(1)
        assertThat(errs.single().formattedMessage).contains("has 2 splits").doesNotContain("Split words").doesNotContain("Shop")
    }

    @Test
    fun aFiveSplitGroupWithEverySplitLinkedAndRemovedIsStillNotDeleted() {
        lateinit var result: TransactionConverter.ConvertPollSyncResult
        val errs = errors {
            result = sync(removed = listOf("a", "b", "c", "d", "e"), existing = listOf(group("ff1", "a", "b", "c", "d", "e")))
        }

        assertThat(result.deletes).isEmpty()
        assertThat(result.transfersNeedingReview).hasSize(1)
        assertThat(errs).hasSize(1)
    }

    @Test
    fun theSameIdRemovedTwiceDeletesASingleSplitGroupOnce() {
        val result = sync(removed = listOf("wd", "wd"), existing = listOf(group("ff1", "wd")))

        assertThat(result.deletes).containsExactly("ff1")
    }

    @Test
    fun theSameIdRemovedTwiceFlagsAMultiSplitGroupOnce() {
        lateinit var result: TransactionConverter.ConvertPollSyncResult
        val errs = errors { result = sync(removed = listOf("wd", "wd"), existing = listOf(group("ff1", "wd", null))) }

        assertThat(result.deletes).isEmpty()
        assertThat(result.transfersNeedingReview).containsExactly("ff1")
        assertThat(errs).hasSize(1)
    }

    @Test
    fun aSingleSplitRemovalStillDeletes() {
        val result = sync(removed = listOf("wd"), existing = listOf(group("ff1", "wd")))

        assertThat(result.deletes).containsExactly("ff1")
        assertThat(result.transfersNeedingReview).isEmpty()
    }

    @Test
    fun aMultiSplitGroupAndASingleSplitGroupInOneSyncDeleteOnlyTheSingle() {
        val result = sync(removed = listOf("wd", "solo"), existing = listOf(group("ff1", "wd", null), group("ff2", "solo")))

        assertThat(result.deletes).containsExactly("ff2")
        assertThat(result.transfersNeedingReview).containsExactly("ff1")
    }

    @Test
    fun aRemovalForAnIdNoGroupHoldsDeletesNothing() {
        lateinit var result: TransactionConverter.ConvertPollSyncResult
        val errs = errors { result = sync(removed = listOf("ghost"), existing = listOf(group("ff9", "other"))) }

        assertThat(result.deletes).isEmpty()
        assertThat(result.transfersNeedingReview).isEmpty()
        assertThat(errs).isEmpty()

        // The same removal next to a create of that id in the same sync only creates
        val created = PlaidFixtures.getPaymentTransaction(accountId = accountA, transactionId = "ghost", pendingTransactionId = null, amount = 5.0, name = "Plaid ghost")
        val both = sync(created = listOf(created), removed = listOf("ghost"), existing = listOf())
        assertThat(both.deletes).isEmpty()
        assertThat(both.creates).hasSize(1)
    }

    @Test
    fun aMultiSplitPendingGroupIsKeptWhenItsPostedVersionIsCreated() {
        val posted = PlaidFixtures.getPaymentTransaction(accountId = accountA, transactionId = "posted", pendingTransactionId = "pend", amount = 11.0, name = "Plaid posted")
        val result = sync(created = listOf(posted), removed = listOf("pend"), existing = listOf(group("ff1", "pend", null)))

        assertThat(result.creates).hasSize(1)
        assertThat(result.deletes).isEmpty()
        assertThat(result.transfersNeedingReview).containsExactly("ff1")
    }
}
