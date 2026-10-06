package net.djvk.fireflyPlaidConnector2.transactions

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
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

    private fun existing(
        id: String,
        externalId: String,
        type: TransactionTypeProperty,
        internalReference: String? = null,
        sourceId: String? = "1",
        destinationId: String? = if (type == TransactionTypeProperty.transfer) "2" else null,
        tags: List<String> = listOf(),
    ) = TransactionRead(
        "transactions", id,
        FireflyFixtures.getTransaction(
            type = type, amount = "50.0", sourceId = sourceId, destinationId = destinationId,
            externalId = externalId, internalReference = internalReference, tags = tags,
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
    fun aTransferBuiltFromTwoPlaidLegsKeepsBothIds() = runBlocking<Unit> {
        val result = converter().convertPollSync(
            accountMap, listOf(plaid(accountA, "wd", 50.0), plaid(accountB, "dep", -50.0)), listOf(), listOf(), listOf()
        )

        val tx = result.creates.single().tx
        assertThat(tx.externalId).isEqualTo("plaid-dep")
        assertThat(tx.internalReference).isEqualTo("plaid-wd")
    }

    @Test
    fun convertingAnExistingLegToATransferKeepsItsOldIdAsTheOtherLegsId() = runBlocking<Unit> {
        val result = converter().convertPollSync(
            accountMap, listOf(plaid(accountB, "dep", -50.0)), listOf(), listOf(),
            listOf(existing("ff1", "plaid-wd", TransactionTypeProperty.withdrawal))
        )

        val update = result.updates.single()
        assertThat(update.id).isEqualTo("ff1")
        assertThat(update.tx.externalId).isEqualTo("plaid-dep")
        assertThat(update.tx.internalReference).isEqualTo("plaid-wd")
    }

    /** Live finding: step 2 of the double transfer. The withdrawal leg alone must not be created again. */
    @Test
    fun aLoneLegWhoseIdIsTheOtherIdOfAnExistingTransferIsNotCreatedAgain() = runBlocking<Unit> {
        val result = converter().convertPollSync(
            accountMap, listOf(plaid(accountA, "wd", 50.0)), listOf(), listOf(),
            listOf(existing("ff1", "plaid-dep", TransactionTypeProperty.transfer, internalReference = "plaid-wd"))
        )

        assertThat(result.creates).isEmpty()
        assertThat(result.updates).isEmpty()
    }

    /** Live finding: step 3. The destination leg arriving after the transfer must not convert anything else. */
    @Test
    fun theOtherLegArrivingLaterIsRecognisedWhateverOrderTheLegsArriveIn() = runBlocking<Unit> {
        val transfer = existing("ff1", "plaid-wd", TransactionTypeProperty.transfer, internalReference = "plaid-dep")

        val result = converter().convertPollSync(
            accountMap, listOf(plaid(accountB, "dep", -50.0)), listOf(), listOf(), listOf(transfer)
        )

        assertThat(result.creates).isEmpty()
        assertThat(result.updates).isEmpty()
    }

    /** The old fix only looked at creates; a conversion of a different transaction must be skipped too. */
    @Test
    fun aConversionIsSkippedWhenAnotherTransactionAlreadyRecordsTheLeg() = runBlocking<Unit> {
        val transfer = existing("ff1", "plaid-dep", TransactionTypeProperty.transfer, internalReference = "plaid-wd")
        val strayWithdrawal = existing("ff2", "plaid-stray", TransactionTypeProperty.withdrawal)

        val result = converter().convertPollSync(
            accountMap, listOf(plaid(accountB, "dep", -50.0)), listOf(), listOf(), listOf(transfer, strayWithdrawal)
        )

        assertThat(result.updates.filter { it.id == "ff2" }).isEmpty()
        assertThat(result.creates).isEmpty()
    }

    /**
     * A transfer recorded by an older build has only one leg's id. Re-pairing the same two legs makes a transfer whose
     * external id is the OTHER leg, found only through the internal reference of the new one.
     */
    @Test
    fun aRetriedPairIsSkippedWhenAnOlderTransferHasOnlyTheOtherLegsId() = runBlocking<Unit> {
        val older = existing("ff1", "plaid-wd", TransactionTypeProperty.transfer)

        val result = converter().convertPollSync(
            accountMap, listOf(plaid(accountA, "wd", 50.0), plaid(accountB, "dep", -50.0)), listOf(), listOf(), listOf(older)
        )

        assertThat(result.creates).isEmpty()
        assertThat(result.updates).isEmpty()
    }

    @Test
    fun aPlaidUpdateOfTheLegStoredAsInternalReferenceFindsTheTransfer() = runBlocking<Unit> {
        val transfer = existing("ff1", "plaid-dep", TransactionTypeProperty.transfer, internalReference = "plaid-wd")

        val result = converter().convertPollSync(
            accountMap, listOf(), listOf(plaid(accountA, "wd", 60.0)), listOf(), listOf(transfer)
        )

        val update = result.updates.single()
        assertThat(update.id).isEqualTo("ff1")
        assertThat(update.tx.amount).isEqualTo("60.0")
        assertThat(update.tx.externalId).describedAs("the transfer keeps its own external id").isEqualTo("plaid-dep")
        assertThat(update.tx.sourceId).isEqualTo("1")
        assertThat(update.tx.destinationId).isEqualTo("2")
    }

    /** The removal of one leg says nothing about the other; dropping the transfer would lose money Plaid still reports. */
    @Test
    fun theRemovalOfOneLegDoesNotDeleteATransferThatIsRecordedUnderTheOtherLegsId() = runBlocking<Unit> {
        val transfer = existing("ff1", "plaid-dep", TransactionTypeProperty.transfer, internalReference = "plaid-wd")

        val result = converter().convertPollSync(accountMap, listOf(), listOf(), listOf("wd"), listOf(transfer))

        assertThat(result.deletes).isEmpty()
    }

    @Test
    fun theRemovalOfTheLegThatIsTheExternalIdStillDeletesASimpleTransaction() = runBlocking<Unit> {
        val result = converter().convertPollSync(
            accountMap, listOf(), listOf(), listOf("x"), listOf(existing("ff1", "plaid-x", TransactionTypeProperty.withdrawal))
        )

        assertThat(result.deletes).containsExactly("ff1")
    }

    @Test
    fun onlyATransfersInternalReferenceIsIndexedBecauseTheUserCanEditItOnAnyTransaction() {
        val notATransfer = existing("ff1", "plaid-a", TransactionTypeProperty.withdrawal, internalReference = "plaid-b")

        assertThat(FireflyTransactionExternalIdIndexer(listOf(notATransfer)).findExistingFireflyTx("b")).isNull()
        assertThat(FireflyTransactionExternalIdIndexer(listOf(notATransfer)).findExistingFireflyTx("a")).isNotNull
    }

    // endregion

    // region H2-R2

    @Test
    fun aWithdrawalWhoseSignFlipsBecomesADepositIntoTheSameAccount() = runBlocking<Unit> {
        val result = converter().convertPollSync(
            accountMap, listOf(), listOf(plaid(accountA, "x", -20.0)), listOf(),
            listOf(existing("ff1", "plaid-x", TransactionTypeProperty.withdrawal))
        )

        val update = result.updates.single()
        assertThat(update.changesType).isTrue()
        assertThat(update.tx.type).isEqualTo(TransactionTypeProperty.deposit)
        assertThat(update.tx.destinationId).isEqualTo("1")
        assertThat(update.tx.sourceId).describedAs("the old destination (an expense account) is not the source").isNull()
        assertThat(update.tx.sourceName).isNotBlank()
        assertThat(typeSent(update)).isEqualTo("deposit")
        assertThat(update.tx.description).describedAs("the user's description is kept").isEqualTo("User words")
    }

    @Test
    fun aDepositWhoseSignFlipsBecomesAWithdrawalFromTheSameAccount() = runBlocking<Unit> {
        val result = converter().convertPollSync(
            accountMap, listOf(), listOf(plaid(accountA, "x", 20.0)), listOf(),
            listOf(existing("ff1", "plaid-x", TransactionTypeProperty.deposit, sourceId = null, destinationId = "1"))
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
            listOf(existing("ff1", "plaid-x", TransactionTypeProperty.withdrawal))
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
            listOf(existing("ff1", "plaid-x", TransactionTypeProperty.withdrawal))
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
            listOf(existing("ff1", "plaid-x", TransactionTypeProperty.deposit, sourceId = null, destinationId = "1"))
        )

        val tx = result.updates.single().tx
        assertThat(tx.sourceName).isNull()
        assertThat(tx.destinationName).isNull()
    }

    /** A leg whose sign flips swaps the transfer's direction instead of being sent as the original one. */
    @Test
    fun aTransferLegWhoseSignFlipsSwapsTheTransfersDirection() = runBlocking<Unit> {
        // account 1 (A) sent 50 to account 2 (B); Plaid now says A's leg is money IN, so it is B that sent it
        val transfer = existing("ff1", "plaid-dep", TransactionTypeProperty.transfer, internalReference = "plaid-wd")

        val result = converter().convertPollSync(
            accountMap, listOf(), listOf(plaid(accountA, "wd", -50.0)), listOf(), listOf(transfer)
        )

        val tx = result.updates.single().tx
        assertThat(tx.sourceId).isEqualTo("2")
        assertThat(tx.destinationId).isEqualTo("1")
    }

    @Test
    fun aTransferLegThatKeepsItsDirectionKeepsItsAccounts() = runBlocking<Unit> {
        val transfer = existing("ff1", "plaid-dep", TransactionTypeProperty.transfer, internalReference = "plaid-wd")

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
        val pending = existing("ffP", "plaid-pend", TransactionTypeProperty.withdrawal, tags = listOf("pending"))

        val result = converter(pendingTag = "pending").convertPollSync(
            accountMap, listOf(plaid(accountB, "dep", -50.0)), listOf(), listOf(), listOf(pending)
        )

        assertThat(result.updates).isEmpty()
        assertThat(result.creates.single().tx.type).isEqualTo(TransactionTypeProperty.deposit)
    }

    @Test
    fun aSettledTransactionIsStillConvertedIntoATransfer() = runBlocking<Unit> {
        val settled = existing("ffS", "plaid-wd", TransactionTypeProperty.withdrawal, tags = listOf("my-tag"))

        val result = converter(pendingTag = "pending").convertPollSync(
            accountMap, listOf(plaid(accountB, "dep", -50.0)), listOf(), listOf(), listOf(settled)
        )

        assertThat(result.updates.single().tx.type).isEqualTo(TransactionTypeProperty.transfer)
    }

    @Test
    fun aPostedTransactionReplacesThePendingDescriptionAndMerchant() = runBlocking<Unit> {
        val pending = existing("ff1", "plaid-pend", TransactionTypeProperty.withdrawal)
        val posted = PlaidFixtures.getPaymentTransaction(
            accountId = accountA, transactionId = "posted", pendingTransactionId = "pend", amount = 50.0,
            name = "FINAL MERCHANT NAME",
        )

        val result = converter().convertPollSync(accountMap, listOf(posted), listOf(), listOf("pend"), listOf(pending))

        val tx = result.updates.single().tx
        assertThat(tx.description).isEqualTo("FINAL MERCHANT NAME")
        assertThat(tx.destinationName).isEqualTo("FINAL MERCHANT NAME")
        assertThat(result.deletes).isEmpty()
    }

    // endregion
}
