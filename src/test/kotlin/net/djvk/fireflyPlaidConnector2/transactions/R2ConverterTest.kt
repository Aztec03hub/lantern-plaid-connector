package net.djvk.fireflyPlaidConnector2.transactions

import com.fasterxml.jackson.databind.ObjectMapper
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

/**
 * Review R2: transfers remember both Plaid ids (H1-R2), a sign flip changes the type (H2-R2), pending transactions are
 * not turned into transfers (M2-R2), a posted version replaces the pending text (L1-R2).
 */
internal class R2ConverterTest {
    private val accountMap = PlaidFixtures.getStandardAccountMapping()
    private val accountA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" // Firefly account 1
    private val accountB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" // Firefly account 2
    private val mapper = ObjectMapper().findAndRegisterModules()

    private fun converter(pendingTag: String = "") = TransactionConverter(
        useNameForDestination = true,
        enablePrimaryCategorization = false,
        primaryCategoryPrefix = "plaid-primary-cat-",
        enableDetailedCategorization = false,
        detailedCategoryPrefix = "plaid-detailed-cat-",
        timeZoneString = "America/New_York",
        transferMatchWindowDays = 3L,
        txStyle = TransactionStyleConfig(null),
        pendingTag = pendingTag,
    )

    private val wdLink = PlaidLink("wd", PlaidLinkLeg.source, accountA)
    private val depLink = PlaidLink("dep", PlaidLinkLeg.destination, accountB)
    private val transferLinks = listOf(wdLink, depLink)

    private fun single(plaidId: String, account: String = accountA) =
        listOf(PlaidLink(plaidId, PlaidLinkLeg.single, account))

    private fun existing(
        id: String,
        type: TransactionTypeProperty,
        links: List<PlaidLink>?,
        sourceId: String? = "1",
        destinationId: String? = if (type == TransactionTypeProperty.transfer) "2" else null,
        tags: List<String> = listOf(),
    ) = TransactionRead(
        "transactions", id,
        FireflyFixtures.getTransaction(
            type = type, amount = "50.0", sourceId = sourceId, destinationId = destinationId,
            plaidLinks = links, tags = tags,
            description = "User words", currencyId = "5", currencyCode = "USD",
            sourceName = if (sourceId == null) "Employer" else null,
            destinationName = if (destinationId == null) "Shop" else null,
        ), ObjectLink()
    )

    private fun plaid(account: String, id: String, amount: Double, name: String = "Plaid $id", pending: Boolean = false) =
        PlaidFixtures.getPaymentTransaction(
            accountId = account, transactionId = id, pendingTransactionId = null, amount = amount, pending = pending, name = name,
        )

    private fun typeSent(dto: FireflyTransactionDto): String? =
        mapper.readTree(mapper.writeValueAsString(dto.toTransactionUpdate())).get("transactions").get(0).get("type")?.asText()

    // region H1-R2

    @Test
    fun aTransferBuiltFromTwoPlaidLegsLinksBothIds() = runBlocking<Unit> {
        val result = converter().convertPollSync(
            accountMap, listOf(plaid(accountA, "wd", 50.0), plaid(accountB, "dep", -50.0)), listOf(), listOf(), listOf()
        )

        val tx = result.creates.single().tx
        assertThat(tx.externalId).describedAs("the connector no longer writes external_id").isNull()
        assertThat(tx.internalReference).isNull()
        assertThat(tx.plaidLinks).containsExactly(wdLink, depLink)
    }

    @Test
    fun convertingAnExistingLegToATransferKeepsItsLinkAsTheOtherLeg() = runBlocking<Unit> {
        val result = converter().convertPollSync(
            accountMap, listOf(plaid(accountB, "dep", -50.0)), listOf(), listOf(),
            listOf(existing("ff1", TransactionTypeProperty.withdrawal, single("wd")))
        )

        val update = result.updates.single()
        assertThat(update.id).isEqualTo("ff1")
        assertThat(update.tx.plaidLinks).containsExactly(wdLink, depLink)
    }

    /** Live finding: step 2 of the double transfer. The withdrawal leg alone must not be created again. */
    @Test
    fun aLoneLegWhoseIdIsOneOfTheLinksOfAnExistingTransferIsNotCreatedAgain() = runBlocking<Unit> {
        val result = converter().convertPollSync(
            accountMap, listOf(plaid(accountA, "wd", 50.0)), listOf(), listOf(),
            listOf(existing("ff1", TransactionTypeProperty.transfer, transferLinks))
        )

        assertThat(result.creates).isEmpty()
        assertThat(result.updates).isEmpty()
    }

    /** Live finding: step 3. The destination leg arriving after the transfer must not convert anything else. */
    @Test
    fun theOtherLegArrivingLaterIsRecognisedWhateverOrderTheLegsArriveIn() = runBlocking<Unit> {
        val transfer = existing("ff1", TransactionTypeProperty.transfer, transferLinks)

        val result = converter().convertPollSync(
            accountMap, listOf(plaid(accountB, "dep", -50.0)), listOf(), listOf(), listOf(transfer)
        )

        assertThat(result.creates).isEmpty()
        assertThat(result.updates).isEmpty()
    }

    /** The old fix only looked at creates; a conversion of a different transaction must be skipped too. */
    @Test
    fun aConversionIsSkippedWhenAnotherTransactionAlreadyRecordsTheLeg() = runBlocking<Unit> {
        val transfer = existing("ff1", TransactionTypeProperty.transfer, transferLinks)
        val strayWithdrawal = existing("ff2", TransactionTypeProperty.withdrawal, single("stray"))

        val result = converter().convertPollSync(
            accountMap, listOf(plaid(accountB, "dep", -50.0)), listOf(), listOf(), listOf(transfer, strayWithdrawal)
        )

        assertThat(result.updates.filter { it.id == "ff2" }).isEmpty()
        assertThat(result.creates).isEmpty()
    }

    /**
     * A retried pair whose transfer already holds both legs is NOT skipped here: a two-link create is left to Firefly's
     * 409 (the whole write conflicts, nothing is written), so the converter still emits it with both links.
     */
    @Test
    fun aTwoLinkCreateIsNotSkippedEvenWhenATransferAlreadyHoldsBothLegs() = runBlocking<Unit> {
        val older = existing("ff1", TransactionTypeProperty.transfer, transferLinks)

        val result = converter().convertPollSync(
            accountMap, listOf(plaid(accountA, "wd", 50.0), plaid(accountB, "dep", -50.0)), listOf(), listOf(), listOf(older)
        )

        assertThat(result.updates).isEmpty()
        assertThat(result.creates.single().tx.plaidLinks).containsExactly(wdLink, depLink)
    }

    @Test
    fun aPlaidUpdateOfTheSourceLegFindsTheTransfer() = runBlocking<Unit> {
        val transfer = existing("ff1", TransactionTypeProperty.transfer, transferLinks)

        val result = converter().convertPollSync(
            accountMap, listOf(), listOf(plaid(accountA, "wd", 60.0)), listOf(), listOf(transfer)
        )

        val update = result.updates.single()
        assertThat(update.id).isEqualTo("ff1")
        assertThat(update.tx.amount).isEqualTo("60.0")
        assertThat(update.tx.plaidLinks).describedAs("an ordinary modify sends no links").isNull()
        assertThat(update.tx.sourceId).isEqualTo("1")
        assertThat(update.tx.destinationId).isEqualTo("2")
    }

    /**
     * R3 (L1-R3): the removal of one leg says nothing about the other, so the transfer is not deleted; it becomes a
     * plain deposit for the surviving destination leg (the removed source leg's money is the one Plaid took back).
     */
    @Test
    fun theRemovalOfTheSourceLegTurnsTheTransferIntoADepositForTheOtherLeg() = runBlocking<Unit> {
        val transfer = existing("ff1", TransactionTypeProperty.transfer, transferLinks)

        val result = converter().convertPollSync(accountMap, listOf(), listOf(), listOf("wd"), listOf(transfer))

        assertThat(result.deletes).isEmpty()
        val update = result.updates.single()
        assertThat(update.id).isEqualTo("ff1")
        assertThat(update.changesType).isTrue()
        assertThat(update.tx.type).isEqualTo(TransactionTypeProperty.deposit)
        assertThat(update.tx.destinationId).isEqualTo("2")
        assertThat(update.tx.sourceId).isNull()
        assertThat(update.tx.sourceName).isEqualTo("Unknown Transfer Source")
        assertThat(update.tx.plaidLinks).containsExactly(depLink.copy(leg = PlaidLinkLeg.single))
        assertThat(typeSent(update)).isEqualTo("deposit")
    }

    @Test
    fun theRemovalOfTheLegOfASingleLinkStillDeletesASimpleTransaction() = runBlocking<Unit> {
        val result = converter().convertPollSync(
            accountMap, listOf(), listOf(), listOf("x"), listOf(existing("ff1", TransactionTypeProperty.withdrawal, single("x")))
        )

        assertThat(result.deletes).containsExactly("ff1")
    }

    @Test
    fun aTransferIsFoundByEitherOfItsLinksAndAnUnknownIdIsNotFound() {
        val transfer = existing("ff1", TransactionTypeProperty.transfer, transferLinks)
        val indexer = PlaidLinkIndexer(listOf(transfer))

        assertThat(indexer.find("wd")?.id).isEqualTo("ff1")
        assertThat(indexer.find("dep")?.id).isEqualTo("ff1")
        assertThat(indexer.find("plaid-wd")).describedAs("ids are raw, with no prefix").isNull()
    }

    // endregion

    // region H2-R2

    @Test
    fun aWithdrawalWhoseSignFlipsBecomesADepositIntoTheSameAccount() = runBlocking<Unit> {
        val result = converter().convertPollSync(
            accountMap, listOf(), listOf(plaid(accountA, "x", -20.0)), listOf(),
            listOf(existing("ff1", TransactionTypeProperty.withdrawal, single("x")))
        )

        val update = result.updates.single()
        assertThat(update.changesType).isTrue()
        assertThat(update.tx.type).isEqualTo(TransactionTypeProperty.deposit)
        assertThat(update.tx.destinationId).isEqualTo("1")
        assertThat(update.tx.plaidLinks).describedAs("a sign flip of a single sends no links").isNull()
        assertThat(update.tx.sourceId).describedAs("the old destination (an expense account) is not the source").isNull()
        assertThat(update.tx.sourceName).isNotBlank()
        assertThat(typeSent(update)).isEqualTo("deposit")
        assertThat(update.tx.description).describedAs("the user's description is kept").isEqualTo("User words")
    }

    @Test
    fun aDepositWhoseSignFlipsBecomesAWithdrawalFromTheSameAccount() = runBlocking<Unit> {
        val result = converter().convertPollSync(
            accountMap, listOf(), listOf(plaid(accountA, "x", 20.0)), listOf(),
            listOf(existing("ff1", TransactionTypeProperty.deposit, single("x"), sourceId = null, destinationId = "1"))
        )

        val update = result.updates.single()
        assertThat(update.changesType).isTrue()
        assertThat(update.tx.type).isEqualTo(TransactionTypeProperty.withdrawal)
        assertThat(update.tx.sourceId).isEqualTo("1")
        assertThat(update.tx.destinationId).isNull()
        assertThat(update.tx.destinationName).isNotBlank()
        assertThat(typeSent(update)).isEqualTo("withdrawal")
    }

    @Test
    fun anUpdateThatKeepsItsSignNeverSendsAType() = runBlocking<Unit> {
        val result = converter().convertPollSync(
            accountMap, listOf(), listOf(plaid(accountA, "x", 25.0)), listOf(),
            listOf(existing("ff1", TransactionTypeProperty.withdrawal, single("x")))
        )

        val update = result.updates.single()
        assertThat(update.changesType).isFalse()
        assertThat(typeSent(update)).isNull()
    }

    /** R3 survivor: an ordinary update must not re-send the counterparty name, which the user may have changed. */
    @Test
    fun anOrdinaryUpdateDoesNotResendTheCounterpartyName() = runBlocking<Unit> {
        val result = converter().convertPollSync(
            accountMap, listOf(), listOf(plaid(accountA, "x", 25.0)), listOf(),
            listOf(existing("ff1", TransactionTypeProperty.withdrawal, single("x")))
        )

        val tx = result.updates.single().tx
        assertThat(tx.sourceName).isNull()
        assertThat(tx.destinationName).isNull()
        assertThat(tx.description).isEqualTo("User words")
    }

    /** R3 survivor, the deposit side: Plaid's name for the sender must not overwrite what the user set. */
    @Test
    fun anOrdinaryUpdateOfADepositDoesNotResendTheCounterpartyName() = runBlocking<Unit> {
        val result = converter().convertPollSync(
            accountMap, listOf(), listOf(plaid(accountA, "x", -25.0, name = "ACME PAYROLL")), listOf(),
            listOf(existing("ff1", TransactionTypeProperty.deposit, single("x"), sourceId = null, destinationId = "1"))
        )

        val tx = result.updates.single().tx
        assertThat(tx.sourceName).isNull()
        assertThat(tx.destinationName).isNull()
    }

    /** Both legs flip in one sync: the transfer's direction swaps once, and the two link legs swap with the accounts. */
    @Test
    fun aTransferWhoseTwoLegsBothFlipSwapsTheTransfersDirection() = runBlocking<Unit> {
        // account 1 (A) sent 50 to account 2 (B); Plaid now says A's leg is money IN and B's is money OUT
        val transfer = existing("ff1", TransactionTypeProperty.transfer, transferLinks)

        val result = converter().convertPollSync(
            accountMap, listOf(), listOf(plaid(accountA, "wd", -50.0), plaid(accountB, "dep", 50.0)), listOf(), listOf(transfer)
        )

        assertThat(result.transfersNeedingReview).isEmpty()
        assertThat(result.updates).hasSize(2)
        for (update in result.updates) {
            assertThat(update.tx.sourceId).isEqualTo("2")
            assertThat(update.tx.destinationId).isEqualTo("1")
            // the legs swap with the accounts: A's leg is now the destination, B's the source
            assertThat(update.tx.plaidLinks).containsExactlyInAnyOrder(
                PlaidLink("wd", PlaidLinkLeg.destination, accountA),
                PlaidLink("dep", PlaidLinkLeg.source, accountB),
            )
        }
    }

    /** The OUT half of the reversal test (R3 mutation C4): a deposit leg of a transfer that flips to OUT. */
    @Test
    fun aTransferWhoseDepositLegFlipsToOutAndWhoseOtherLegAgreesSwaps() = runBlocking<Unit> {
        val transfer = existing("ff1", TransactionTypeProperty.transfer, transferLinks)

        // only the update of the deposit leg is processed first in list order
        val result = converter().convertPollSync(
            accountMap, listOf(), listOf(plaid(accountB, "dep", 50.0), plaid(accountA, "wd", -50.0)), listOf(), listOf(transfer)
        )

        assertThat(result.updates.first().tx.sourceId).isEqualTo("2")
        assertThat(result.updates.first().tx.destinationId).isEqualTo("1")
    }

    /**
     * R3 (L2-R3, probe p3): one leg flips and the other does not. The banks disagree, so nothing is swapped, the
     * transfer is reported, and a later ordinary update of the untouched leg no longer flip-flops it.
     */
    @Test
    fun aTransferWhoseLegsDisagreeOnDirectionIsLeftAloneAndReported() = runBlocking<Unit> {
        val transfer = existing("ff1", TransactionTypeProperty.transfer, transferLinks)

        val flipped = converter().convertPollSync(
            accountMap, listOf(), listOf(plaid(accountA, "wd", -50.0)), listOf(), listOf(transfer)
        )
        assertThat(flipped.updates.single().tx.sourceId).isEqualTo("1")
        assertThat(flipped.updates.single().tx.destinationId).isEqualTo("2")
        assertThat(flipped.transfersNeedingReview).containsExactly("ff1")

        val untouched = converter().convertPollSync(
            accountMap, listOf(), listOf(plaid(accountB, "dep", -50.0)), listOf(), listOf(transfer)
        )
        assertThat(untouched.updates.single().tx.sourceId).isEqualTo("1")
        assertThat(untouched.updates.single().tx.destinationId).isEqualTo("2")
        assertThat(untouched.transfersNeedingReview).isEmpty()
    }

    /** Both legs are updated in one sync but only one of them flipped: the banks disagree, so no swap, and it is reported. */
    @Test
    fun aTransferWhoseOtherLegIsUpdatedWithoutFlippingIsNotSwapped() = runBlocking<Unit> {
        val transfer = existing("ff1", TransactionTypeProperty.transfer, transferLinks)

        val result = converter().convertPollSync(
            accountMap, listOf(), listOf(plaid(accountA, "wd", -40.0), plaid(accountB, "dep", -41.0)), listOf(), listOf(transfer)
        )

        assertThat(result.updates).hasSize(2)
        assertThat(result.updates.map { it.tx.sourceId }).containsOnly("1")
        assertThat(result.transfersNeedingReview).containsExactly("ff1")
    }

    /** The deposit leg flipping alone (OUT) is reported too, not swapped. */
    @Test
    fun aDepositLegThatFlipsToOutAloneIsReportedNotSwapped() = runBlocking<Unit> {
        val transfer = existing("ff1", TransactionTypeProperty.transfer, transferLinks)

        val result = converter().convertPollSync(
            accountMap, listOf(), listOf(plaid(accountB, "dep", 50.0)), listOf(), listOf(transfer)
        )

        assertThat(result.updates.single().tx.sourceId).isEqualTo("1")
        assertThat(result.transfersNeedingReview).containsExactly("ff1")
    }

    /** A transfer with one known Plaid link has only that leg's word on direction. */
    @Test
    fun aTransferWithOneKnownLinkSwapsOnThatLegsWord() = runBlocking<Unit> {
        val transfer = existing("ff1", TransactionTypeProperty.transfer, listOf(PlaidLink("dep", PlaidLinkLeg.destination, accountA)))

        val result = converter().convertPollSync(
            accountMap, listOf(), listOf(plaid(accountA, "dep", -50.0)), listOf(), listOf(transfer)
        )

        assertThat(result.updates.single().tx.sourceId).isEqualTo("2")
        assertThat(result.transfersNeedingReview).isEmpty()
    }

    @Test
    fun aTransferLegThatKeepsItsDirectionKeepsItsAccounts() = runBlocking<Unit> {
        val transfer = existing("ff1", TransactionTypeProperty.transfer, transferLinks)

        val result = converter().convertPollSync(
            accountMap, listOf(), listOf(plaid(accountA, "wd", 55.0)), listOf(), listOf(transfer)
        )

        val tx = result.updates.single().tx
        assertThat(tx.sourceId).isEqualTo("1")
        assertThat(tx.destinationId).isEqualTo("2")
    }

    // endregion

    // region M2-R2 / L1-R2

    @Test
    fun aTransactionImportedWhilePendingIsNotConvertedIntoATransfer() = runBlocking<Unit> {
        val pending = existing("ffP", TransactionTypeProperty.withdrawal, single("pend"), tags = listOf("pending"))

        val result = converter(pendingTag = "pending").convertPollSync(
            accountMap, listOf(plaid(accountB, "dep", -50.0)), listOf(), listOf(), listOf(pending)
        )

        assertThat(result.updates).isEmpty()
        assertThat(result.creates.single().tx.type).isEqualTo(TransactionTypeProperty.deposit)
    }

    @Test
    fun aSettledTransactionIsStillConvertedIntoATransfer() = runBlocking<Unit> {
        val settled = existing("ffS", TransactionTypeProperty.withdrawal, single("wd"), tags = listOf("my-tag"))

        val result = converter(pendingTag = "pending").convertPollSync(
            accountMap, listOf(plaid(accountB, "dep", -50.0)), listOf(), listOf(), listOf(settled)
        )

        assertThat(result.updates.single().tx.type).isEqualTo(TransactionTypeProperty.transfer)
    }

    @Test
    fun aPostedTransactionReplacesThePendingDescriptionAndMerchant() = runBlocking<Unit> {
        val pending = existing("ff1", TransactionTypeProperty.withdrawal, single("pend"))
        val posted = PlaidFixtures.getPaymentTransaction(
            accountId = accountA, transactionId = "posted", pendingTransactionId = "pend", amount = 50.0,
            name = "FINAL MERCHANT NAME",
        )

        val result = converter().convertPollSync(accountMap, listOf(posted), listOf(), listOf("pend"), listOf(pending))

        val tx = result.updates.single().tx
        assertThat(tx.description).isEqualTo("FINAL MERCHANT NAME")
        assertThat(tx.plaidLinks).containsExactly(PlaidLink("posted", PlaidLinkLeg.single, accountA))
        assertThat(tx.destinationName).isEqualTo("Final Merchant Name")
        assertThat(result.deletes).isEmpty()
    }

    // endregion
}
