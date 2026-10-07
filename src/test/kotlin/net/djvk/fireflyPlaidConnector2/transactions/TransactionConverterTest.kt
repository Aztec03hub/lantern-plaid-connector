package net.djvk.fireflyPlaidConnector2.transactions

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidTransactionId
import net.djvk.fireflyPlaidConnector2.api.plaid.models.CounterpartyType
import net.djvk.fireflyPlaidConnector2.api.plaid.models.Location
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionCode
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionCounterparty
import net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.lib.PlaidFixtures
import net.djvk.fireflyPlaidConnector2.lib.defaultLocalNow
import net.djvk.fireflyPlaidConnector2.lib.defaultOffsetNow
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction as PlaidTransaction

internal class TransactionConverterTest {
    companion object {
        val defaultStyle = TransactionStyleConfig(null)

        @JvmStatic
        fun provideConvertPollSync(): List<Arguments> {
            return listOf(
                Arguments.of(
//                    testName: String,
                    "Existing Firefly withdrawal",
//                    accountMap: Map<PlaidAccountId, FireflyAccountId>,
                    PlaidFixtures.getStandardAccountMapping(),
//                    plaidCreatedTxs: List<PlaidTransaction>,
                    listOf(
                        PlaidFixtures.getPaymentTransaction(
                            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            transactionId = "plaidTransactionId",
                            amount = -1111.22,
                        ),
                    ),
//                    plaidUpdatedTxs: List<PlaidTransaction>,
                    listOf<PlaidTransaction>(),
//                    plaidDeletedTxs: List<PlaidTransactionId>,
                    listOf<PlaidTransactionId>(),
//                    existingFireflyTxs: List<TransactionRead>,
                    listOf(
                        TransactionRead(
                            "thing", "wrongTypeFireflyTransactionId",
                            FireflyFixtures.getTransaction(
                                type = TransactionTypeProperty.openingBalance,
                                amount = "2222.33",
                                sourceId = "3",
                            ), ObjectLink()
                        ),
                        // This is identical to the expected matching transaction, except for the amount.
                        TransactionRead(
                            "thing", "wrongAmountFireflyTransactionId",
                            FireflyFixtures.getTransaction(
                                type = TransactionTypeProperty.withdrawal,
                                amount = "1234.56",
                                sourceId = "2",
                            ), ObjectLink()
                        ),
                        // This is identical to the expected matching transaction, except that it's the same account
                        // as the Plaid transaction.
                        TransactionRead(
                            "thing", "wrongAccountfireflyTransactionId",
                            FireflyFixtures.getTransaction(
                                type = TransactionTypeProperty.withdrawal,
                                amount = "1111.22",
                                sourceId = "1",
                            ), ObjectLink()
                        ),
                        // This is identical to the expected matching transaction, except that it's a deposit.
                        TransactionRead(
                            "thing", "wrongTypeFireflyDepositTransactionId",
                            FireflyFixtures.getTransaction(
                                type = TransactionTypeProperty.deposit,
                                amount = "1111.22",
                                destinationId = "2",
                            ), ObjectLink()
                        ),
                        // This is the transaction that we expect to match with the Plaid transaction to be converted
                        // into a transfer.
                        TransactionRead(
                            "thing", "fireflyTransactionId",
                            FireflyFixtures.getTransaction(
                                type = TransactionTypeProperty.withdrawal,
                                amount = "1111.22",
                                sourceId = "2",
                                plaidLinks = listOf(PlaidLink("existingPlaidId", PlaidLinkLeg.single, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")),
                                // The transfer matching logic attempts to find the closest (by date) matching
                                // transaction. Offset this date compared to the other Firefly transactions above so
                                // that when this one "wins" we know it's not incidental just because this one was
                                // processed first.
                                dateSubtractHours = 12
                            ), ObjectLink()
                        ),
                    ),
//                    expectedResult: TransactionConverter.ConvertPollSyncResult,
                    TransactionConverter.ConvertPollSyncResult(
                        creates = listOf(),
                        updates = listOf(
                            FireflyTransactionDto(
                                "fireflyTransactionId",
                                FireflyFixtures.getTransaction(
                                    type = TransactionTypeProperty.transfer,
                                    amount = "1111.22",
                                    plaidLinks = listOf(
                                        PlaidLink("existingPlaidId", PlaidLinkLeg.source, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
                                        PlaidLink("plaidTransactionId", PlaidLinkLeg.destination, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                                    ),
                                    sourceId = "2",
                                    destinationId = "1",
                                ).transactions.first()
                                    // An in-place update never re-sends the reconciled flag or order (M2)
                                    .copy(order = null, reconciled = null),
                                // If Firefly refuses the pairing, the new Plaid leg is created on its own
                                fallbackCreate = FireflyTransactionDto(
                                    null,
                                    FireflyFixtures.getTransaction(
                                        type = TransactionTypeProperty.deposit,
                                        description = "AMERICAN EXPRESS DES:ACH PMT ID : W1111 INDN:JOHN Q PUBLIC CO ID:XXXXX11111 WEB",
                                        amount = "1111.22",
                                        sourceName = "Unknown Transfer Source",
                                        destinationId = "1",
                                        plaidLinks = listOf(PlaidLink("plaidTransactionId", PlaidLinkLeg.single, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
                                    ).transactions.first()
                                ),
                            )
                        ),
                        deletes = listOf(),
                    ),
                ),
                Arguments.of(
//                    testName: String,
                    "Existing Firefly deposit",
//                    accountMap: Map<PlaidAccountId, FireflyAccountId>,
                    PlaidFixtures.getStandardAccountMapping(),
//                    plaidCreatedTxs: List<PlaidTransaction>,
                    listOf(
                        PlaidFixtures.getPaymentTransaction(
                            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            transactionId = "plaidTransactionId",
                            amount = 1111.22,
                        ),
                    ),
//                    plaidUpdatedTxs: List<PlaidTransaction>,
                    listOf<PlaidTransaction>(),
//                    plaidDeletedTxs: List<PlaidTransactionId>,
                    listOf<PlaidTransactionId>(),
//                    existingFireflyTxs: List<TransactionRead>,
                    listOf(
                        TransactionRead(
                            "thing", "fireflyTransactionId",
                            FireflyFixtures.getTransaction(
                                type = TransactionTypeProperty.deposit,
                                amount = "1111.22",
                                destinationId = "2",
                                plaidLinks = listOf(PlaidLink("existingPlaidId", PlaidLinkLeg.single, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")),
                            ), ObjectLink()
                        ),
                    ),
//                    expectedResult: TransactionConverter.ConvertPollSyncResult,
                    TransactionConverter.ConvertPollSyncResult(
                        creates = listOf(),
                        updates = listOf(
                            FireflyTransactionDto(
                                "fireflyTransactionId",
                                FireflyFixtures.getTransaction(
                                    type = TransactionTypeProperty.transfer,
                                    amount = "1111.22",
                                    plaidLinks = listOf(
                                        PlaidLink("existingPlaidId", PlaidLinkLeg.destination, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
                                        PlaidLink("plaidTransactionId", PlaidLinkLeg.source, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                                    ),
                                    sourceId = "1",
                                    destinationId = "2",
                                ).transactions.first()
                                    // An in-place update never re-sends the reconciled flag or order (M2)
                                    .copy(order = null, reconciled = null),
                                // If Firefly refuses the pairing, the new Plaid leg is created on its own
                                fallbackCreate = FireflyTransactionDto(
                                    null,
                                    FireflyFixtures.getTransaction(
                                        type = TransactionTypeProperty.withdrawal,
                                        description = "AMERICAN EXPRESS DES:ACH PMT ID : W1111 INDN:JOHN Q PUBLIC CO ID:XXXXX11111 WEB",
                                        amount = "1111.22",
                                        sourceId = "1",
                                        destinationName = "Unknown Transfer Recipient",
                                        plaidLinks = listOf(PlaidLink("plaidTransactionId", PlaidLinkLeg.single, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
                                    ).transactions.first()
                                ),
                            )
                        ),
                        deletes = listOf(),
                    ),
                ),
                Arguments.of(
//                    testName: String,
                    "Incoming Plaid Deposit and Withdrawal",
//                    accountMap: Map<PlaidAccountId, FireflyAccountId>,
                    PlaidFixtures.getStandardAccountMapping(),
//                    plaidCreatedTxs: List<PlaidTransaction>,
                    listOf(
                        PlaidFixtures.getPaymentTransaction(
                            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            transactionId = "plaidWithdrawalId",
                            name = "Plaid Withdrawal Tx",
                            amount = 1111.22,
                        ),
                        PlaidFixtures.getPaymentTransaction(
                            accountId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                            transactionId = "plaidDepositId",
                            name = "Plaid Deposit Tx",
                            amount = -1111.22,
                        ),
                    ),
//                    plaidUpdatedTxs: List<PlaidTransaction>,
                    listOf<PlaidTransaction>(),
//                    plaidDeletedTxs: List<PlaidTransactionId>,
                    listOf<PlaidTransactionId>(),
//                    existingFireflyTxs: List<TransactionRead>,
                    listOf(
                        TransactionRead(
                            "thing", "unrelatedFireflyTransactionId",
                            FireflyFixtures.getTransaction(
                                type = TransactionTypeProperty.deposit,
                                amount = "123.45",
                                destinationId = "2",
                            ), ObjectLink()
                        ),
                    ),
//                    expectedResult: TransactionConverter.ConvertPollSyncResult,
                    TransactionConverter.ConvertPollSyncResult(
                        creates = listOf(
                            FireflyTransactionDto(
                                null,
                                FireflyFixtures.getTransaction(
                                    type = TransactionTypeProperty.transfer,
                                    amount = "1111.22",
                                    plaidLinks = listOf(
                                        PlaidLink("plaidWithdrawalId", PlaidLinkLeg.source, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                                        PlaidLink("plaidDepositId", PlaidLinkLeg.destination, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
                                    ),
                                    description = "Plaid Deposit Tx",
                                    sourceId = "1",
                                    destinationId = "2",
                                ).transactions.first()
                            ),
                        ),
                        updates = listOf(),
                        deletes = listOf(),
                    ),
                ),
                Arguments.of(
//                    testName: String,
                    "Update without matching Firefly Transaction",
//                    accountMap: Map<PlaidAccountId, FireflyAccountId>,
                    PlaidFixtures.getStandardAccountMapping(),
//                    plaidCreatedTxs: List<PlaidTransaction>,
                    listOf<PlaidTransaction>(),
//                    plaidUpdatedTxs: List<PlaidTransaction>,
                    listOf(
                        PlaidFixtures.getPaymentTransaction(
                            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            transactionId = "plaidUpdateId",
                            amount = 1111.22,
                        ),
                    ),
//                    plaidDeletedTxs: List<PlaidTransactionId>,
                    listOf<PlaidTransactionId>(),
//                    existingFireflyTxs: List<TransactionRead>,
                    listOf(
                        TransactionRead(
                            "thing", "unrelatedFireflyTransactionId",
                            FireflyFixtures.getTransaction(
                                type = TransactionTypeProperty.deposit,
                                amount = "123.45",
                                destinationId = "2",
                                plaidLinks = listOf(PlaidLink("unrelated", PlaidLinkLeg.single, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
                            ), ObjectLink()
                        ),
                    ),
//                    expectedResult: TransactionConverter.ConvertPollSyncResult,
                    TransactionConverter.ConvertPollSyncResult(
                        creates = listOf(),
                        updates = listOf(),
                        deletes = listOf(),
                    ),
                ),
                Arguments.of(
//                    testName: String,
                    "Update with matching Firefly Transaction",
//                    accountMap: Map<PlaidAccountId, FireflyAccountId>,
                    PlaidFixtures.getStandardAccountMapping(),
//                    plaidCreatedTxs: List<PlaidTransaction>,
                    listOf<PlaidTransaction>(),
//                    plaidUpdatedTxs: List<PlaidTransaction>,
                    listOf(
                        PlaidFixtures.getPaymentTransaction(
                            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            name = "Updated transaction name",
                            transactionId = "plaidUpdateId",
                            amount = 1111.22,
                        ),
                    ),
//                    plaidDeletedTxs: List<PlaidTransactionId>,
                    listOf<PlaidTransactionId>(),
//                    existingFireflyTxs: List<TransactionRead>,
                    listOf(
                        TransactionRead(
                            "thing", "updatedFireflyId",
                            FireflyFixtures.getTransaction(
                                type = TransactionTypeProperty.withdrawal,
                                description = "Old transaction name",
                                amount = "123.45",
                                sourceId = "1",
                                destinationName = "Unknown Transfer Recipient",
                                plaidLinks = listOf(PlaidLink("plaidUpdateId", PlaidLinkLeg.single, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
                            ), ObjectLink()
                        ),
                    ),
//                    expectedResult: TransactionConverter.ConvertPollSyncResult,
                    TransactionConverter.ConvertPollSyncResult(
                        creates = listOf(),
                        updates = listOf(
                            FireflyTransactionDto(
                                "updatedFireflyId",
                                FireflyFixtures.getTransaction(
                                    type = TransactionTypeProperty.withdrawal,
                                    description = "Old transaction name",
                                    amount = "1111.22",
                                    sourceId = "1",
                                    destinationName = "Unknown Transfer Recipient",
                                ).transactions.first()
                                    // M2: the user's description and counterparty, and the reconciled flag, are kept
                                    .copy(order = null, reconciled = null, destinationName = null)
                            ),
                        ),
                        deletes = listOf(),
                    ),
                ),
                Arguments.of(
//                    testName: String,
                    "Delete without matching Firefly Transaction",
//                    accountMap: Map<PlaidAccountId, FireflyAccountId>,
                    PlaidFixtures.getStandardAccountMapping(),
//                    plaidCreatedTxs: List<PlaidTransaction>,
                    listOf<PlaidTransaction>(),
//                    plaidUpdatedTxs: List<PlaidTransaction>,
                    listOf<PlaidTransaction>(),
//                    plaidDeletedTxs: List<PlaidTransactionId>,
                    listOf<PlaidTransactionId>(
                        "deletedTransactionId",
                    ),
//                    existingFireflyTxs: List<TransactionRead>,
                    listOf(
                        TransactionRead(
                            "thing", "updatedFireflyId",
                            FireflyFixtures.getTransaction(
                                type = TransactionTypeProperty.deposit,
                                amount = "123.45",
                                destinationId = "2",
                                plaidLinks = listOf(PlaidLink("unrelated", PlaidLinkLeg.single, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
                            ), ObjectLink()
                        ),
                    ),
//                    expectedResult: TransactionConverter.ConvertPollSyncResult,
                    TransactionConverter.ConvertPollSyncResult(
                        creates = listOf(),
                        updates = listOf(),
                        deletes = listOf(),
                    ),
                ),
                Arguments.of(
//                    testName: String,
                    "Delete with matching Firefly Transaction",
//                    accountMap: Map<PlaidAccountId, FireflyAccountId>,
                    PlaidFixtures.getStandardAccountMapping(),
//                    plaidCreatedTxs: List<PlaidTransaction>,
                    listOf<PlaidTransaction>(),
//                    plaidUpdatedTxs: List<PlaidTransaction>,
                    listOf<PlaidTransaction>(),
//                    plaidDeletedTxs: List<PlaidTransactionId>,
                    listOf<PlaidTransactionId>(
                        "deletedTransactionId",
                    ),
//                    existingFireflyTxs: List<TransactionRead>,
                    listOf(
                        TransactionRead(
                            "thing", "deletedFireflyId",
                            FireflyFixtures.getTransaction(
                                type = TransactionTypeProperty.deposit,
                                description = "Old transaction name",
                                amount = "123.45",
                                sourceId = "1",
                                destinationName = "Unknown Transfer Recipient",
                                plaidLinks = listOf(PlaidLink("deletedTransactionId", PlaidLinkLeg.single, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
                            ), ObjectLink()
                        ),
                    ),
//                    expectedResult: TransactionConverter.ConvertPollSyncResult,
                    TransactionConverter.ConvertPollSyncResult(
                        creates = listOf(),
                        updates = listOf(),
                        deletes = listOf(
                            "deletedFireflyId",
                        ),
                    ),
                ),
                Arguments.of(
//                    testName: String,
                    "Create singles",
//                    accountMap: Map<PlaidAccountId, FireflyAccountId>,
                    PlaidFixtures.getStandardAccountMapping(),
//                    plaidCreatedTxs: List<PlaidTransaction>,
                    listOf(
                        PlaidFixtures.getPaymentTransaction(
                            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            name = "Plaid deposit",
                            transactionId = "plaidDepositId",
                            amount = -1111.22,
                        ),
                        PlaidFixtures.getPaymentTransaction(
                            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            name = "Plaid withdrawal",
                            transactionId = "plaidWithdrawalId",
                            amount = 123.45,
                        ),
                        PlaidFixtures.getPaymentTransaction(
                            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            name = "Plaid withdrawal with matching FF",
                            transactionId = "plaidWithdrawalWithMatchingFfId",
                            amount = 234.56,
                        ),
                    ),
//                    plaidUpdatedTxs: List<PlaidTransaction>,
                    listOf<PlaidTransaction>(),
//                    plaidDeletedTxs: List<PlaidTransactionId>,
                    listOf<PlaidTransactionId>(),
//                    existingFireflyTxs: List<TransactionRead>,
                    listOf<TransactionRead>(
                        TransactionRead(
                            "thing", "matchingFireflyTxId",
                            FireflyFixtures.getTransaction(
                                type = TransactionTypeProperty.withdrawal,
                                description = "Matching Firefly Tx",
                                amount = "234.56",
                                sourceId = "1",
                                destinationName = "Unknown Transfer Recipient",
                                plaidLinks = listOf(PlaidLink("plaidWithdrawalWithMatchingFfId", PlaidLinkLeg.single, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
                            ), ObjectLink()
                        ),
                    ),
//                    expectedResult: TransactionConverter.ConvertPollSyncResult,
                    TransactionConverter.ConvertPollSyncResult(
                        creates = listOf(
                            FireflyTransactionDto(
                                null,
                                FireflyFixtures.getTransaction(
                                    type = TransactionTypeProperty.deposit,
                                    description = "Plaid deposit",
                                    amount = "1111.22",
                                    sourceName = "Unknown Transfer Source",
                                    destinationId = "1",
                                    plaidLinks = listOf(PlaidLink("plaidDepositId", PlaidLinkLeg.single, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
                                ).transactions.first()
                            ),
                            FireflyTransactionDto(
                                null,
                                FireflyFixtures.getTransaction(
                                    type = TransactionTypeProperty.withdrawal,
                                    description = "Plaid withdrawal",
                                    amount = "123.45",
                                    sourceId = "1",
                                    destinationName = "Unknown Transfer Recipient",
                                    plaidLinks = listOf(PlaidLink("plaidWithdrawalId", PlaidLinkLeg.single, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
                                ).transactions.first()
                            ),
                        ),
                        updates = listOf(),
                        deletes = listOf(),
                    ),
                ),

                // This test case creates Plaid transactions with various combinations of date fields present, each
                // field having a slightly different offset from our "default" date. We then validate that the expected
                // offset was used to create the Firefly transactions.
                Arguments.of(
//                    testName: String,
                    "The Firefly date is the posted Plaid date at local midnight; authorized goes to book_date",
//                    accountMap: Map<PlaidAccountId, FireflyAccountId>,
                    PlaidFixtures.getStandardAccountMapping(),
//                    plaidCreatedTxs: List<PlaidTransaction>,
                    listOf(
                        PlaidFixtures.getPaymentTransaction(
                            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            name = "Tx with authorized date and dateTime",
                            transactionId = "txWithAuthorizedDateAndDateTime",
                            amount = -1111.11,
                            date = defaultLocalNow,
                            datetime = defaultOffsetNow.minusDays(1),
                            authorizedDate = defaultLocalNow.minusDays(2),
                            authorizedDatetime = defaultOffsetNow.minusDays(3),
                        ),
                        PlaidFixtures.getPaymentTransaction(
                            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            name = "Tx with authorized date",
                            transactionId = "txWithAuthorizedDate",
                            amount = -1111.22,
                            date = defaultLocalNow,
                            datetime = defaultOffsetNow.minusDays(1),
                            authorizedDate = defaultLocalNow.minusDays(2),
                        ),
                        PlaidFixtures.getPaymentTransaction(
                            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            name = "Tx with posted date and dateTime",
                            transactionId = "txWithPostedDateAndDateTime",
                            amount = -1111.33,
                            date = defaultLocalNow,
                            datetime = defaultOffsetNow.minusDays(1),
                        ),
                        PlaidFixtures.getPaymentTransaction(
                            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            name = "Tx with posted date",
                            transactionId = "txWithPostedDate",
                            amount = -1111.44,
                            date = defaultLocalNow,
                        ),
                    ),
//                    plaidUpdatedTxs: List<PlaidTransaction>,
                    listOf<PlaidTransaction>(),
//                    plaidDeletedTxs: List<PlaidTransactionId>,
                    listOf<PlaidTransactionId>(),
//                    existingFireflyTxs: List<TransactionRead>,
                    listOf<TransactionRead>(),
//                    expectedResult: TransactionConverter.ConvertPollSyncResult,
                    TransactionConverter.ConvertPollSyncResult(
                        creates = listOf(
                            FireflyTransactionDto(
                                null,
                                FireflyFixtures.getTransaction(
                                    type = TransactionTypeProperty.deposit,
                                    description = "Tx with authorized date and dateTime",
                                    amount = "1111.11",
                                    sourceName = "Unknown Transfer Source",
                                    destinationId = "1",
                                    plaidLinks = listOf(PlaidLink("txWithAuthorizedDateAndDateTime", PlaidLinkLeg.single, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
                                    date = TransactionConverter.getOffsetDateTimeForDate(java.time.ZoneId.of("America/New_York"), defaultLocalNow),
                                    processDate = TransactionConverter.getOffsetDateTimeForDate(java.time.ZoneId.of("America/New_York"), defaultLocalNow),
                                    bookDate = TransactionConverter.getOffsetDateTimeForDate(java.time.ZoneId.of("America/New_York"), defaultLocalNow.minusDays(2)),
                                ).transactions.first()
                            ),
                            FireflyTransactionDto(
                                null,
                                FireflyFixtures.getTransaction(
                                    type = TransactionTypeProperty.deposit,
                                    description = "Tx with authorized date",
                                    amount = "1111.22",
                                    sourceName = "Unknown Transfer Source",
                                    destinationId = "1",
                                    plaidLinks = listOf(PlaidLink("txWithAuthorizedDate", PlaidLinkLeg.single, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
                                    date = TransactionConverter.getOffsetDateTimeForDate(java.time.ZoneId.of("America/New_York"), defaultLocalNow),
                                    processDate = TransactionConverter.getOffsetDateTimeForDate(java.time.ZoneId.of("America/New_York"), defaultLocalNow),
                                    bookDate = TransactionConverter.getOffsetDateTimeForDate(java.time.ZoneId.of("America/New_York"), defaultLocalNow.minusDays(2)),
                                ).transactions.first()
                            ),
                            FireflyTransactionDto(
                                null,
                                FireflyFixtures.getTransaction(
                                    type = TransactionTypeProperty.deposit,
                                    description = "Tx with posted date and dateTime",
                                    amount = "1111.33",
                                    sourceName = "Unknown Transfer Source",
                                    destinationId = "1",
                                    plaidLinks = listOf(PlaidLink("txWithPostedDateAndDateTime", PlaidLinkLeg.single, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
                                    date = TransactionConverter.getOffsetDateTimeForDate(java.time.ZoneId.of("America/New_York"), defaultLocalNow),
                                    processDate = TransactionConverter.getOffsetDateTimeForDate(java.time.ZoneId.of("America/New_York"), defaultLocalNow),
                                ).transactions.first()
                            ),
                            FireflyTransactionDto(
                                null,
                                FireflyFixtures.getTransaction(
                                    type = TransactionTypeProperty.deposit,
                                    description = "Tx with posted date",
                                    amount = "1111.44",
                                    sourceName = "Unknown Transfer Source",
                                    destinationId = "1",
                                    plaidLinks = listOf(PlaidLink("txWithPostedDate", PlaidLinkLeg.single, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
                                    date = TransactionConverter.getOffsetDateTimeForDate(java.time.ZoneId.of("America/New_York"), defaultLocalNow),
                                    processDate = TransactionConverter.getOffsetDateTimeForDate(java.time.ZoneId.of("America/New_York"), defaultLocalNow),
                                ).transactions.first()
                            ),
                        ),
                        updates = listOf(),
                        deletes = listOf(),
                    ),
                ),
                Arguments.of(
//                    testName: String,
                    "Populates expected miscellaneous fields",
//                    accountMap: Map<PlaidAccountId, FireflyAccountId>,
                    PlaidFixtures.getStandardAccountMapping(),
//                    plaidCreatedTxs: List<PlaidTransaction>,
                    listOf(
                        PlaidFixtures.getPaymentTransaction(
                            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            name = "Transaction Name",
                            originalDescription = "Transaction Orig Desc",
                            transactionId = "txWithAllMiscFieldsPopulated",
                            amount = 1111.11,
                            merchantName = "Test Merchant",
                            accountOwner = "Test Account Owner",
                            transactionCode = TransactionCode.purchase,
                            logoUrl = "https://example.org/logo.png",
                            personalFinanceCategoryIconUrl = "https://example.org/category.png",
                            counterparties = listOf(
                                TransactionCounterparty(
                                    name = "Example Merchant",
                                    type = CounterpartyType.merchant,
                                    website = "merchant.example.org",
                                    logoUrl = "https://example.org/logo_merchant.png",
                                    entityId = "merchantCounterpartyId1",
                                    confidenceLevel = "HIGH",
                                ),
                                TransactionCounterparty(
                                    name = "Example Marketplace",
                                    type = CounterpartyType.marketplace,
                                    website = "marketplace.example.org",
                                    logoUrl = "https://example.org/logo_marketplace.png",
                                    entityId = "marketplaceCounterpartyId1",
                                    confidenceLevel = "HIGH",
                                )
                            ),
                            merchantEntityId = "abc123",
                            website = "example.org",
                            location = Location(
                                address = "14400 State Hwy Z",
                                city = "St Robert",
                                region = "MO",
                                postalCode = "65584",
                                country = "US",
                                lat = 37.8291789099181,
                                lon = -92.10510834805055,
                                storeNumber = "101",
                            ),
                        ),
                    ),
//                    plaidUpdatedTxs: List<PlaidTransaction>,
                    listOf<PlaidTransaction>(),
//                    plaidDeletedTxs: List<PlaidTransactionId>,
                    listOf<PlaidTransactionId>(),
//                    existingFireflyTxs: List<TransactionRead>,
                    listOf<TransactionRead>(),
//                    expectedResult: TransactionConverter.ConvertPollSyncResult,
                    TransactionConverter.ConvertPollSyncResult(
                        creates = listOf(
                            FireflyTransactionDto(
                                null,
                                FireflyFixtures.getTransaction(
                                    type = TransactionTypeProperty.withdrawal,
                                    description = "Test Merchant: Transaction Orig Desc",
                                    amount = "1111.11",
                                    destinationName = "Test Merchant",
                                    sourceId = "1",
                                    plaidLinks = listOf(PlaidLink("txWithAllMiscFieldsPopulated", PlaidLinkLeg.single, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
                                    date = defaultOffsetNow,
                                    processDate = defaultOffsetNow,
                                    latitude = 37.8291789099181,
                                    longitude = -92.10510834805055,
                                    externalUrl = "https://example.org"
                                ).transactions.first()
                            ),
                        ),
                        updates = listOf(),
                        deletes = listOf(),
                    ),
                ),
            )
        }

        @JvmStatic
        fun provideConvertSingleSourceAndDestination(): List<Arguments> {
            return listOf(
                Arguments.of(
//                    testName: String,
                    "'Payment' category, payment PFC, no paymentMeta",
//                    input: Transaction,
                    PlaidFixtures.getPaymentTransaction(
                        accountId = "testPlaidAccountId"
                    ),
//                    accountMap: Map<PlaidAccountId, FireflyAccountId>,
                    mapOf("testPlaidAccountId" to 42),
//                    expectedSourceId: String?,
                    "42",
//                    expectedSourceName: String?,
                    null,
//                    expectedDestinationId: String?,
                    null,
//                    expectedDestinationName: String?,
                    "Unknown Transfer Recipient"
                )
            )
        }

        fun convertCreates(
            converter: TransactionConverter,
            poll: Boolean,
            inputPlaidTxs: List<PlaidTransaction>,
            accountMap: Map<PlaidAccountId, FireflyAccountId>,
        ): List<FireflyTransactionDto> {
            return runBlocking {
                if (poll) {
                    val result = converter.convertPollSync(
                        accountMap,
                        inputPlaidTxs,
                        listOf(),
                        listOf(),
                        listOf(),
                    )
                    // Since this isn't passing-in any existing Plaid transactions, these should always be empty
                    assertThat(result.deletes).isEmpty()
                    assertThat(result.updates).isEmpty()
                    return@runBlocking result.creates
                } else {
                    return@runBlocking converter.convertBatchSync(
                        inputPlaidTxs,
                        accountMap,
                    )
                }
            }
        }

        @JvmStatic
        fun provideDescriptionCases(): List<Arguments> {
            return listOf(
                Arguments.of(
                    "Expression can access fields from the Plaid transaction",
                    TransactionStyleConfig("transaction.name + ': ' + transaction.originalDescription"),
                    "Name", // Plaid "name"
                    "Merchant", // Plaid "merchantName"
                    "Desc", // Plaid "originalDescription"
                    "Name: Desc", // Expected FF description
                ),
                Arguments.of(
                    "Demonstration of the ternary operator to conditionally append a string",
                    TransactionStyleConfig("#merchantAndDescription + (transaction.amount > 100 ? ' (expensive!)' : '')"),
                    "Name", // Plaid "name"
                    "Merchant", // Plaid "merchantName"
                    "Desc", // Plaid "originalDescription"
                    "Merchant: Desc (expensive!)", // Expected FF description
                ),
                Arguments.of(
                    "Demonstration of the Elvis operator to fallback to a non-null value",
                    TransactionStyleConfig("transaction.merchantName?:transaction.name + ': ' + transaction.originalDescription"),
                    "Name", // Plaid "name"
                    null, // Plaid "merchantName"
                    "Desc", // Plaid "originalDescription"
                    "Name: Desc", // Expected FF description
                ),
                Arguments.of(
                    "Demonstration of multiple chained elvis operators",
                    TransactionStyleConfig("transaction.originalDescription ?: transaction.merchantName ?: transaction.name"),
                    "Name", // Plaid "name"
                    null, // Plaid "merchantName"
                    null, // Plaid "originalDescription"
                    "Name", // Expected FF description
                ),
                Arguments.of(
                    "#merchantAndDescription uses merchant, if available, followed by originalDescription",
                    TransactionStyleConfig("#merchantAndDescription"),
                    "unused", // Plaid "name"
                    "Merchant", // Plaid "merchantName"
                    "OriginalDesc", // Plaid "originalDescription"
                    "Merchant: OriginalDesc", // Expected FF description
                ),
                Arguments.of(
                    "null expression is same as merchantAndDescription",
                    TransactionStyleConfig(null),
                    "unused", // Plaid "name"
                    "Merchant", // Plaid "merchantName"
                    "OriginalDesc", // Plaid "originalDescription"
                    "Merchant: OriginalDesc", // Expected FF description
                ),
                Arguments.of(
                    "empty expression is same as merchantAndDescription",
                    TransactionStyleConfig("  "), // (Add a couple spaces to ensure we're trimming)
                    "unused", // Plaid "name"
                    "Merchant", // Plaid "merchantName"
                    "OriginalDesc", // Plaid "originalDescription"
                    "Merchant: OriginalDesc", // Expected FF description
                ),
                Arguments.of(
                    "spaces are trimmed from the expression",
                    TransactionStyleConfig("  #merchantAndDescription  "),
                    "unused", // Plaid "name"
                    "Merchant", // Plaid "merchantName"
                    "OriginalDesc", // Plaid "originalDescription"
                    "Merchant: OriginalDesc", // Expected FF description
                ),
                Arguments.of(
                    "#merchantAndDescription uses name if merchant is not available, followed by originalDescription",
                    TransactionStyleConfig("#merchantAndDescription"),
                    "Name", // Plaid "name"
                    null, // Plaid "merchantName"
                    "OriginalDesc", // Plaid "originalDescription"
                    "Name: OriginalDesc", // Expected FF description
                ),
                Arguments.of(
                    "#merchantAndDescription does not include originalDescription if it's not available",
                    TransactionStyleConfig("#merchantAndDescription"),
                    "Name", // Plaid "name"
                    null, // Plaid "merchantName"
                    null, // Plaid "originalDescription"
                    "Name", // Expected FF description
                ),
                Arguments.of(
                    "#merchantNameWithFallback is available and is set to merchant name when available",
                    TransactionStyleConfig("#merchantNameWithFallback"),
                    "Name", // Plaid "name"
                    "Contoso", // Plaid "merchantName"
                    "unused", // Plaid "originalDescription"
                    "Contoso", // Expected FF description
                ),
                Arguments.of(
                    "#merchantNameWithFallback is available and is set to name when merchant is not available",
                    TransactionStyleConfig("#merchantNameWithFallback"),
                    "Name", // Plaid "name"
                    null, // Plaid "merchantName"
                    "unused", // Plaid "originalDescription"
                    "Name", // Expected FF description
                ),
                Arguments.of(
                    "if the expression references a null field, the string \"null\" is used",
                    TransactionStyleConfig("transaction.merchantName + ': ' + transaction.originalDescription"),
                    "unused", // Plaid "name"
                    null, // Plaid "merchantName"
                    "Desc", // Plaid "originalDescription"
                    "null: Desc", // Expected FF description
                ),
                Arguments.of(
                    "if the expression results in null, the default description is used",
                    TransactionStyleConfig("transaction.merchantName"),
                    "Name", // Plaid "name"
                    null, // Plaid "merchantName"
                    "Desc", // Plaid "originalDescription"
                    "Name: Desc", // Expected FF description
                ),
                Arguments.of(
                    "if the expression is invalid, the default description is used",
                    TransactionStyleConfig("this-is-invalid"),
                    "Name", // Plaid "name"
                    null, // Plaid "merchantName"
                    "Desc", // Plaid "originalDescription"
                    "Name: Desc", // Expected FF description
                ),
            )
        }
    }

    @ParameterizedTest(name = "{index} => {0}")
    @MethodSource("provideConvertPollSync")
    fun convertPollSync(
        testName: String,
        accountMap: Map<PlaidAccountId, FireflyAccountId>,
        plaidCreatedTxs: List<PlaidTransaction>,
        plaidUpdatedTxs: List<PlaidTransaction>,
        plaidDeletedTxs: List<PlaidTransactionId>,
        existingFireflyTxs: List<TransactionRead>,
        expectedResult: TransactionConverter.ConvertPollSyncResult,
    ) {
        runBlocking {
            val converter = TransactionConverter(
                useNameForDestination = false,
                enablePrimaryCategorization = false,
                primaryCategoryPrefix = "a",
                enableDetailedCategorization = false,
                detailedCategoryPrefix = "b",
                timeZoneString = "America/New_York",
                transferMatchWindowDays = 10L,
                txStyle = defaultStyle,
            )
            val actual = converter.convertPollSync(
                accountMap,
                plaidCreatedTxs,
                plaidUpdatedTxs,
                plaidDeletedTxs,
                existingFireflyTxs,
            )

            assertEquals(expectedResult, actual)
        }
    }

    @ParameterizedTest(name = "{index} => {0}")
    @MethodSource("provideConvertSingleSourceAndDestination")
    fun convertSingleSourceAndDestination(
        testName: String,
        input: PlaidTransaction,
        accountMap: Map<PlaidAccountId, FireflyAccountId>,
        expectedSourceId: String?,
        expectedSourceName: String?,
        expectedDestinationId: String?,
        expectedDestinationName: String?,
    ) {
        runBlocking {
            val converter = TransactionConverter(
                false,
                enablePrimaryCategorization = false,
                primaryCategoryPrefix = "a",
                enableDetailedCategorization = false,
                detailedCategoryPrefix = "b",
                timeZoneString = "America/New_York",
                transferMatchWindowDays = 10L,
                txStyle = defaultStyle,
            )
            val actual = converter.convertBatchSync(listOf(input), accountMap)

            assertEquals(1, actual.size)
            val tx = actual.first().tx
            assertEquals(expectedSourceId, tx.sourceId)
            assertEquals(expectedSourceName, tx.sourceName)
            assertEquals(expectedDestinationId, tx.destinationId)
            assertEquals(expectedDestinationName, tx.destinationName)
        }
    }

    @ParameterizedTest(name = "poll mode = {0}")
    @ValueSource(booleans = [true, false])
    fun convertWithCategorizationEnabledAddsExpectedTags(poll: Boolean) {
        val converter = TransactionConverter(
            false,
            enablePrimaryCategorization = true,
            primaryCategoryPrefix = "pcat-",
            enableDetailedCategorization = true,
            detailedCategoryPrefix = "dcat-",
            timeZoneString = "America/New_York",
            transferMatchWindowDays = 10L,
            txStyle = defaultStyle,
        )

        val plaidTxWithCategory = PlaidFixtures.getPaymentTransaction(
            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            name = "Plaid transaction with categories",
            transactionId = "plaidIdWithCats",
            amount = 123.45,
            personalFinanceCategory = PersonalFinanceCategoryEnum.TRAVEL_FLIGHTS.toPersonalFinanceCategory(),
        )

        val plaidTxWithoutCategory = PlaidFixtures.getPaymentTransaction(
            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            name = "Plaid transaction without categories",
            transactionId = "plaidIdWithoutCats",
            amount = 234.56,
            personalFinanceCategory = null,
        )

        val expectedFfTxWithTags = FireflyTransactionDto(
            null,
            FireflyFixtures.getTransaction(
                type = TransactionTypeProperty.withdrawal,
                description = "Plaid transaction with categories",
                amount = "123.45",
                sourceId = "1",
                destinationName = "Unknown Payment Recipient",
                tags = listOf("pcat-travel", "dcat-flights"),
                plaidLinks = listOf(PlaidLink("plaidIdWithCats", PlaidLinkLeg.single, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
            ).transactions.first()
        )

        val expectedFfTxWithoutTags = FireflyTransactionDto(
            null,
            FireflyFixtures.getTransaction(
                type = TransactionTypeProperty.withdrawal,
                description = "Plaid transaction without categories",
                amount = "234.56",
                sourceId = "1",
                destinationName = "Unknown",
                tags = listOf(),
                plaidLinks = listOf(PlaidLink("plaidIdWithoutCats", PlaidLinkLeg.single, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
            ).transactions.first()
        )

        val actual = convertCreates(
            converter = converter,
            poll = poll,
            inputPlaidTxs = listOf(plaidTxWithCategory, plaidTxWithoutCategory),
            accountMap = PlaidFixtures.getStandardAccountMapping()
        )
        assertThat(actual).containsExactlyInAnyOrder(expectedFfTxWithTags, expectedFfTxWithoutTags)
    }

    private fun converterWithImportTag(template: String) = TransactionConverter(
        useNameForDestination = true,
        enablePrimaryCategorization = true,
        primaryCategoryPrefix = "pcat-",
        enableDetailedCategorization = false,
        detailedCategoryPrefix = "b",
        timeZoneString = "America/New_York",
        transferMatchWindowDays = 10L,
        txStyle = defaultStyle,
        importTagTemplate = template,
    )

    @org.junit.jupiter.api.Test
    fun renderImportTagFormatsDatePatternsInBraces() {
        val now = java.time.ZonedDateTime.of(2024, 2, 7, 13, 19, 5, 0, java.time.ZoneId.of("America/New_York"))
        assertThat(TransactionConverter.renderImportTag("Plaid import {yyyy-MM-dd @ HH:mm}", now))
            .isEqualTo("Plaid import 2024-02-07 @ 13:19")
        assertThat(TransactionConverter.renderImportTag("plain-tag", now)).isEqualTo("plain-tag")
        assertThat(TransactionConverter.renderImportTag("  ", now)).isNull()
        assertThat(TransactionConverter.renderImportTag("", now)).isNull()
    }

    @org.junit.jupiter.api.Test
    fun invalidImportTagPatternFailsAtStartup() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { converterWithImportTag("bad {ii}") }
    }

    @ParameterizedTest(name = "poll mode = {0}")
    @ValueSource(booleans = [true, false])
    fun convertAddsImportTagToCreatedTransactionsAlongsideCategoryTags(poll: Boolean) {
        val converter = converterWithImportTag("Plaid import")
        val plaidTx = PlaidFixtures.getPaymentTransaction(
            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            name = "Coffee",
            transactionId = "plaidTagged",
            amount = 5.0,
            personalFinanceCategory = PersonalFinanceCategoryEnum.TRAVEL_FLIGHTS.toPersonalFinanceCategory(),
        )

        val actual = convertCreates(converter, poll, listOf(plaidTx), PlaidFixtures.getStandardAccountMapping())

        assertThat(actual.single().tx.tags).containsExactly("pcat-travel", "Plaid import")
    }

    @org.junit.jupiter.api.Test
    fun importTagIsNotAddedToPlaidUpdatesOfExistingTransactions() {
        val converter = converterWithImportTag("Plaid import")
        val existing = TransactionRead(
            "thing", "ffId1",
            FireflyFixtures.getTransaction(
                type = TransactionTypeProperty.withdrawal,
                amount = "5.0",
                sourceId = "1",
                plaidLinks = listOf(PlaidLink("plaidUpdated", PlaidLinkLeg.single, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
            ), ObjectLink()
        )
        val updated = PlaidFixtures.getPaymentTransaction(
            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            name = "Coffee updated",
            transactionId = "plaidUpdated",
            amount = 5.0,
        )

        val result = runBlocking {
            converter.convertPollSync(
                PlaidFixtures.getStandardAccountMapping(), listOf(), listOf(updated), listOf(), listOf(existing)
            )
        }

        assertThat(result.updates).hasSize(1)
        assertThat(result.updates.single().tx.tags).doesNotContain("Plaid import")
    }

    @ParameterizedTest(name = "poll mode = {0}")
    @ValueSource(booleans = [true, false])
    fun convertWithDisableAccountAssignmentLeavesCounterpartyUnset(poll: Boolean) {
        val converter = TransactionConverter(
            useNameForDestination = true,
            enablePrimaryCategorization = false,
            primaryCategoryPrefix = "a",
            enableDetailedCategorization = false,
            detailedCategoryPrefix = "b",
            timeZoneString = "America/New_York",
            transferMatchWindowDays = 10L,
            txStyle = defaultStyle,
            disableAccountAssignment = true,
        )

        val withdrawal = PlaidFixtures.getPaymentTransaction(
            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            name = "Coffee Shop",
            transactionId = "plaidOut",
            amount = 12.34,
        )
        val deposit = PlaidFixtures.getPaymentTransaction(
            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            name = "Employer",
            transactionId = "plaidIn",
            amount = -2000.0,
        )

        val actual = convertCreates(
            converter = converter,
            poll = poll,
            inputPlaidTxs = listOf(withdrawal, deposit),
            accountMap = PlaidFixtures.getStandardAccountMapping()
        )

        assertThat(actual).hasSize(2)
        val actualWithdrawal = actual.single { it.tx.type == TransactionTypeProperty.withdrawal }.tx
        assertThat(actualWithdrawal.sourceId).isEqualTo("1")
        assertThat(actualWithdrawal.destinationName).isNull()
        assertThat(actualWithdrawal.destinationId).isNull()
        val actualDeposit = actual.single { it.tx.type == TransactionTypeProperty.deposit }.tx
        assertThat(actualDeposit.destinationId).isEqualTo("1")
        assertThat(actualDeposit.sourceName).isNull()
        assertThat(actualDeposit.sourceId).isNull()
    }

    @ParameterizedTest(name = "poll mode = {0}")
    @ValueSource(booleans = [true, false])
    fun convertProvidesValidExternalUrl(poll: Boolean) {
        val converter = TransactionConverter(
            useNameForDestination = false,
            enablePrimaryCategorization = false,
            primaryCategoryPrefix = "a",
            enableDetailedCategorization = false,
            detailedCategoryPrefix = "b",
            timeZoneString = "America/New_York",
            transferMatchWindowDays = 10L,
            txStyle = defaultStyle,
        )

        val plaidTx = PlaidFixtures.getPaymentTransaction(
            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            name = "Plaid transaction",
            transactionId = "plaidId",
            amount = 123.45,
            website = "example.org",
        )

        val expectedFfTx = FireflyTransactionDto(
            null,
            FireflyFixtures.getTransaction(
                type = TransactionTypeProperty.withdrawal,
                description = "Plaid transaction",
                amount = "123.45",
                sourceId = "1",
                destinationName = "Unknown Transfer Recipient",
                plaidLinks = listOf(PlaidLink("plaidId", PlaidLinkLeg.single, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
                externalUrl = "https://example.org",
            ).transactions.first()
        )

        val actual = convertCreates(
            converter = converter,
            poll = poll,
            inputPlaidTxs = listOf(plaidTx),
            accountMap = PlaidFixtures.getStandardAccountMapping()
        )

        assertThat(actual).isEqualTo(listOf(expectedFfTx))
    }

    @ParameterizedTest(name = "poll mode = {0}")
    @ValueSource(booleans = [true, false])
    fun convertPollSyncThrowsWhenFireflyAccountIdNotFound(poll: Boolean) {
        val converter = TransactionConverter(
            useNameForDestination = false,
            enablePrimaryCategorization = false,
            primaryCategoryPrefix = "a",
            enableDetailedCategorization = false,
            detailedCategoryPrefix = "b",
            timeZoneString = "America/New_York",
            transferMatchWindowDays = 10L,
            txStyle = defaultStyle,
        )

        val plaidTx = PlaidFixtures.getPaymentTransaction(
            accountId = "unknownAccountId",
            name = "Plaid transaction",
            transactionId = "plaidId",
            amount = 123.45,
        )

        assertThatThrownBy {
            convertCreates(
                converter = converter,
                poll = poll,
                inputPlaidTxs = listOf(plaidTx),
                accountMap = PlaidFixtures.getStandardAccountMapping()
            )
        }
            .hasMessageContaining("Can not match Plaid transactions from accounts not mapped to a Firefly account id")
    }

    @ParameterizedTest(name = "{index} => {0}")
    @MethodSource("provideDescriptionCases")
    fun convertedTxHasExpectedDescription(
        testName: String,
        txStyle: TransactionStyleConfig,
        plaidName: String,
        plaidMerchant: String?,
        plaidOriginalDesc: String?,
        expectedFfDesc: String,
    ) {
        val converter = TransactionConverter(
            useNameForDestination = false,
            enablePrimaryCategorization = false,
            primaryCategoryPrefix = "a",
            enableDetailedCategorization = false,
            detailedCategoryPrefix = "b",
            timeZoneString = "America/New_York",
            transferMatchWindowDays = 10L,
            txStyle = txStyle,
        )

        val plaidTx = PlaidFixtures.getPaymentTransaction(
            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            name = plaidName,
            merchantName = plaidMerchant,
            originalDescription = plaidOriginalDesc,
            transactionId = "plaidId",
            amount = 123.45,
        )

        val expectedFfTx = FireflyTransactionDto(
            null,
            FireflyFixtures.getTransaction(
                type = TransactionTypeProperty.withdrawal,
                description = expectedFfDesc,
                amount = "123.45",
                sourceId = "1",
                destinationName = plaidMerchant ?: "Unknown Transfer Recipient",
                plaidLinks = listOf(PlaidLink("plaidId", PlaidLinkLeg.single, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
            ).transactions.first()
        )

        val actual = convertCreates(
            converter = converter,
            poll = true,
            inputPlaidTxs = listOf(plaidTx),
            accountMap = PlaidFixtures.getStandardAccountMapping()
        )

        assertThat(actual).isEqualTo(listOf(expectedFfTx))
    }
}
