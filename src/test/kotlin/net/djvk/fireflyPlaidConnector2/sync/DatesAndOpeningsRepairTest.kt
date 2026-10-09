package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Account
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.LiabilityDirection
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ShortAccountTypeProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.lib.PlaidFixtures
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction as PlaidTransaction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Plaid sends a fake midnight UTC `datetime` for date-only transactions (05:00Z, or 00:00Z), which in a US winter is the
 * PREVIOUS local day. The Firefly date must come from Plaid's `date`, and the repair must fix journals stored the old way.
 */
internal class DatesAndOpeningsRepairTest {
    @Suppress("unused")
    private val warmMockUtil = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE

    private val zone = ZoneId.of("America/Chicago")
    private val accountMap = PlaidFixtures.getStandardAccountMapping()
    private val plaidAccount = "a".repeat(37)

    private fun converter() = TransactionConverter(
        useNameForDestination = true, enablePrimaryCategorization = false, primaryCategoryPrefix = "p-",
        enableDetailedCategorization = false, detailedCategoryPrefix = "d-", timeZoneString = "America/Chicago",
        transferMatchWindowDays = 3, txStyle = TransactionStyleConfig(null),
    )

    private fun plaid(
        id: String, date: LocalDate, datetime: OffsetDateTime? = null, authorizedDate: LocalDate? = null,
        authorizedDatetime: OffsetDateTime? = null, amount: Double = 10.0, pending: Boolean = false,
    ) = PlaidFixtures.getPaymentTransaction(
        accountId = plaidAccount, transactionId = id, pendingTransactionId = null, amount = amount, date = date,
        datetime = datetime, authorizedDate = authorizedDate, authorizedDatetime = authorizedDatetime, pending = pending,
    )

    private fun midnight(d: LocalDate) = TransactionConverter.getOffsetDateTimeForDate(zone, d)

    private fun firefly(tx: PlaidTransaction) = runBlocking { converter().convertBatchSync(listOf(tx), accountMap).single().tx }

    // region dates

    @Test
    fun aWinterFakeMidnightFiveZuluStaysOnPlaidsDate() {
        val day = LocalDate.of(2024, 12, 6)
        val tx = firefly(plaid("w1", day, datetime = OffsetDateTime.of(2024, 12, 6, 5, 0, 0, 0, ZoneOffset.UTC)))
        assertThat(tx.date).isEqualTo(midnight(day))
        assertThat(tx.processDate).isEqualTo(midnight(day))
    }

    @Test
    fun aFakeMidnightZeroZuluStaysOnPlaidsDateAllYear() {
        for (day in listOf(LocalDate.of(2024, 12, 6), LocalDate.of(2025, 7, 6))) {
            val tx = firefly(plaid("z$day", day, datetime = OffsetDateTime.of(day.atStartOfDay(), ZoneOffset.UTC)))
            assertThat(tx.date).describedAs("$day").isEqualTo(midnight(day))
        }
    }

    @Test
    fun aSummerFiveZuluRowIsMidnightToo() {
        val day = LocalDate.of(2025, 7, 6)
        assertThat(firefly(plaid("s1", day, datetime = OffsetDateTime.of(2025, 7, 6, 5, 0, 0, 0, ZoneOffset.UTC))).date)
            .isEqualTo(midnight(day))
    }

    @Test
    fun aRealTimeOfDayOnTheSameLocalDayIsKept() {
        val day = LocalDate.of(2024, 12, 6)
        val real = OffsetDateTime.of(2024, 12, 6, 15, 30, 0, 0, ZoneOffset.UTC) // 09:30 in Chicago
        val tx = firefly(plaid("r1", day, datetime = real))
        assertThat(tx.date.toInstant()).isEqualTo(real.toInstant())
        assertThat(tx.date.atZoneSameInstant(zone).toLocalDate()).isEqualTo(day)
    }

    @Test
    fun theFireflyDateIsThePostedDateAndTheAuthorizedDateGoesToBookDate() {
        val posted = LocalDate.of(2024, 12, 6)
        val authorized = LocalDate.of(2024, 12, 4)
        val tx = firefly(
            plaid("a1", posted, datetime = OffsetDateTime.of(2024, 12, 6, 5, 0, 0, 0, ZoneOffset.UTC),
                authorizedDate = authorized, authorizedDatetime = OffsetDateTime.of(2024, 12, 4, 5, 0, 0, 0, ZoneOffset.UTC))
        )
        assertThat(tx.date).isEqualTo(midnight(posted))
        assertThat(tx.bookDate).isEqualTo(midnight(authorized))
    }

    // endregion

    // region repair planner

    private fun account(
        type: ShortAccountTypeProperty, name: String, direction: LiabilityDirection? = null,
        opening: String? = null, openingDate: OffsetDateTime? = null,
    ) = AccountRead(
        "accounts", "x",
        Account(name, type, liabilityDirection = direction, openingBalance = opening, openingBalanceDate = openingDate),
        ObjectLink(),
    )

    private fun journal(
        id: String, date: OffsetDateTime, plaidId: String?, type: TransactionTypeProperty = TransactionTypeProperty.withdrawal,
        amount: String = "10.00", source: String? = "1", destination: String? = null,
        description: String = "Coffee", tags: List<String> = listOf(), externalId: String? = null,
        leg: PlaidLinkLeg = PlaidLinkLeg.single,
    ) = TransactionRead(
        "transactions", id,
        FireflyFixtures.getTransaction(
            type = type, date = date, amount = amount, sourceId = source, destinationId = destination,
            description = description, tags = tags, externalId = externalId, transactionJournalId = "j$id", processDate = date,
            sourceName = if (source == null) "Initial Balance" else null,
            destinationName = if (destination == null) "Shop" else null,
            plaidLinks = plaidId?.let { listOf(PlaidLink(it, leg, plaidAccount)) },
        ),
        ObjectLink(),
    )

    private val planner = RepairPlanner(converter(), zone)

    private fun plan(reanchor: Set<Int>, journals: List<TransactionRead>, plaidTxs: List<PlaidTransaction>, current: Map<Int, Double>,
                     accounts: Map<Int, AccountRead>) =
        RepairPlanner(converter(), zone, reanchor).plan(RepairInput(journals, plaidTxs.associateBy { it.transactionId }, current, accounts, mapOf(plaidAccount to 1)))

    private fun plan(journals: List<TransactionRead>, plaidTxs: List<PlaidTransaction>, current: Map<Int, Double> = mapOf(),
                     accounts: Map<Int, AccountRead> = mapOf()) =
        planner.plan(RepairInput(journals, plaidTxs.associateBy { it.transactionId }, current, accounts, mapOf(plaidAccount to 1)))

    @Test
    fun aJournalStoredAtElevenPmTheDayBeforeIsRedatedToPlaidsDate() {
        val day = LocalDate.of(2024, 12, 6)
        val stored = midnight(day).minusHours(1) // 2024-12-05 23:00 Chicago, the old bug
        val p = plan(
            listOf(journal("g1", stored, "w1")),
            listOf(plaid("w1", day, datetime = OffsetDateTime.of(2024, 12, 6, 5, 0, 0, 0, ZoneOffset.UTC))),
        )
        assertThat(p.redates).hasSize(1)
        assertThat(p.redates.single().newDate).isEqualTo(midnight(day))
    }

    @Test
    fun aJournalStoredOnTheAuthorizedDateIsMovedToThePostedDate() {
        val p = plan(
            listOf(journal("g1", midnight(LocalDate.of(2024, 12, 4)), "a1")),
            listOf(plaid("a1", LocalDate.of(2024, 12, 6), authorizedDate = LocalDate.of(2024, 12, 4))),
        )
        assertThat(p.redates.single().newDate).isEqualTo(midnight(LocalDate.of(2024, 12, 6)))
        assertThat(p.redates.single().newBookDate).isEqualTo(midnight(LocalDate.of(2024, 12, 4)))
    }

    @Test
    fun journalsCreatedFromStatementsAreLeftAlone() {
        val p = plan(
            listOf(journal("g1", midnight(LocalDate.of(2024, 12, 5)).minusHours(1), "w1", tags = listOf(STATEMENT_TAG), externalId = "dcu-stmt:2024-12:interest")),
            listOf(plaid("w1", LocalDate.of(2024, 12, 6))),
        )
        assertThat(p.redates).isEmpty()
    }

    @Test
    fun aPlaidLinkedJournalIsRedatedEvenWhenItWasTaggedAsStatement() {
        // an O2 leg that the loan import re-pointed to a loan and tagged: still a Plaid journal, still at 23:00 the day before
        val p = plan(
            listOf(journal("g1", midnight(LocalDate.of(2024, 12, 5)).minusHours(1), "w1", tags = listOf(STATEMENT_TAG))),
            listOf(plaid("w1", LocalDate.of(2024, 12, 5))),
        )
        assertThat(p.redates.single().newDate).isEqualTo(midnight(LocalDate.of(2024, 12, 5)))
    }

    @Test
    fun aRepairedJournalIsNotRedatedAgain() {
        val day = LocalDate.of(2024, 12, 6)
        val p = plan(listOf(journal("g1", midnight(day), "w1")), listOf(plaid("w1", day)))
        assertThat(p.redates).isEmpty()
    }

    @Test
    fun aJournalNotInPlaidsHistoryIsReportedAndNeverMoved() {
        // at 23:00 (the old winter shift), at 19:00 (a fake 00:00Z in summer) and at a real 23:00 time: none are guessed at
        for (hour in listOf(23, 19, 18)) {
            val p = plan(listOf(journal("g$hour", midnight(LocalDate.of(2024, 12, 6)).minusHours((24 - hour).toLong()), "gone")), listOf())
            assertThat(p.redates).describedAs("hour $hour").isEmpty()
            assertThat(p.unmatchedJournals).isEqualTo(1)
        }
    }

    @Test
    fun aJournalWithTheRightDateButAStaleProcessDateIsRedated() {
        val day = LocalDate.of(2024, 12, 6)
        val stale = journal("g1", midnight(day), "w1").let { r ->
            TransactionRead(r.type, r.id, r.attributes.copy(transactions = r.attributes.transactions.map { it.copy(processDate = midnight(day).minusHours(1)) }), r.links)
        }
        val p = plan(listOf(stale), listOf(plaid("w1", day)))
        assertThat(p.redates.single().newProcessDate).isEqualTo(midnight(day))
    }

    @Test
    fun aPairIsDatedFromItsDestinationLegOrFromTheSourceLegWhenThatOneIsGone() {
        val day = LocalDate.of(2024, 12, 6)
        fun pair(vararg legs: Pair<String, PlaidLinkLeg>) = TransactionRead(
            "transactions", "gp",
            FireflyFixtures.getTransaction(
                type = TransactionTypeProperty.transfer, date = midnight(day.minusDays(5)), amount = "10.00", sourceId = "1", destinationId = "2",
                processDate = midnight(day.minusDays(5)),
                plaidLinks = legs.map { PlaidLink(it.first, it.second, plaidAccount) }, transactionJournalId = "jgp",
            ), ObjectLink(),
        )
        val both = plan(
            listOf(pair("src" to PlaidLinkLeg.source, "dst" to PlaidLinkLeg.destination)),
            listOf(plaid("src", day.plusDays(1)), plaid("dst", day)),
        )
        assertThat(both.redates.single().newDate).isEqualTo(midnight(day))
        val onlySource = plan(
            listOf(pair("src" to PlaidLinkLeg.source, "dst" to PlaidLinkLeg.destination)),
            listOf(plaid("src", day.plusDays(1))),
        )
        assertThat(onlySource.redates.single().newDate).isEqualTo(midnight(day.plusDays(1)))
    }

    @Test
    fun aMergedTransferKeepsTheDateOfItsOutLegAndIsNotProposed() {
        val day = LocalDate.of(2024, 12, 6)
        // core's pair merge keeps the OUT (source) leg's journal, so the date is the OUT leg's posted date; the in leg posts a day earlier
        val merged = TransactionRead(
            "transactions", "gm",
            FireflyFixtures.getTransaction(
                type = TransactionTypeProperty.transfer, date = midnight(day.plusDays(1)), amount = "10.00", sourceId = "1", destinationId = "2",
                processDate = midnight(day.plusDays(1)),
                plaidLinks = listOf(PlaidLink("src", PlaidLinkLeg.source, plaidAccount), PlaidLink("dst", PlaidLinkLeg.destination, plaidAccount)),
                transactionJournalId = "jgm",
            ), ObjectLink(),
        )
        val p = plan(listOf(merged), listOf(plaid("src", day.plusDays(1)), plaid("dst", day)))
        assertThat(p.redates).isEmpty()
    }

    @Test
    fun aMergedTransferStoredAsTheSameInstantWrittenAnotherWayIsNotProposed() {
        val day = LocalDate.of(2024, 12, 6)
        val inUtc = midnight(day.plusDays(1)).withOffsetSameInstant(ZoneOffset.UTC)  // 06:00Z instead of 00:00-06:00
        val merged = TransactionRead(
            "transactions", "gm",
            FireflyFixtures.getTransaction(
                type = TransactionTypeProperty.transfer, date = inUtc, amount = "10.00", sourceId = "1", destinationId = "2", processDate = inUtc,
                plaidLinks = listOf(PlaidLink("src", PlaidLinkLeg.source, plaidAccount), PlaidLink("dst", PlaidLinkLeg.destination, plaidAccount)),
                transactionJournalId = "jgm",
            ), ObjectLink(),
        )
        assertThat(plan(listOf(merged), listOf(plaid("src", day.plusDays(1)), plaid("dst", day))).redates).isEmpty()
        // and a single-link journal stored the same way is not proposed either
        val single = journal("gs", midnight(day).withOffsetSameInstant(ZoneOffset.UTC), "w1")
        assertThat(plan(listOf(single), listOf(plaid("w1", day))).redates).isEmpty()
    }

    private fun mergedAt(date: OffsetDateTime, book: OffsetDateTime? = date) = TransactionRead(
        "transactions", "gm",
        FireflyFixtures.getTransaction(
            type = TransactionTypeProperty.transfer, date = date, amount = "10.00", sourceId = "1", destinationId = "2", processDate = date,
            bookDate = book,
            plaidLinks = listOf(PlaidLink("src", PlaidLinkLeg.source, plaidAccount), PlaidLink("dst", PlaidLinkLeg.destination, plaidAccount)),
            transactionJournalId = "jgm",
        ), ObjectLink(),
    )

    @Test
    fun anAuthorizedDateAfterThePostedDateIsNeverProposedAsBookDate() {
        val day = LocalDate.of(2026, 9, 23)
        // the 2896 shape: both legs post on the same instant, the destination leg is "authorized" 9 days later
        val p = plan(
            listOf(mergedAt(midnight(day))),
            listOf(plaid("src", day, authorizedDate = day), plaid("dst", day, authorizedDate = day.plusDays(9),
                authorizedDatetime = OffsetDateTime.of(2026, 10, 2, 23, 29, 14, 0, ZoneOffset.UTC))),
        )
        assertThat(p.redates).isEmpty()
        // a single-link journal with a later authorized date keeps its book date too
        val single = journal("gs", midnight(day), "w1")
        val r = plan(listOf(single), listOf(plaid("w1", day, authorizedDate = day.plusDays(3)))).redates.singleOrNull()
        assertThat(r?.newBookDate).isNull()
    }

    @Test
    fun anAuthorizedDateOnThePostedDayIsStillABookDate() {
        val day = LocalDate.of(2026, 9, 23)
        val r = plan(listOf(journal("gs", midnight(day), "w1")), listOf(plaid("w1", day, authorizedDate = day))).redates.single()
        assertThat(r.newBookDate).isEqualTo(midnight(day))
    }

    @Test
    fun whenBothLegsMatchTheBookDateComesFromTheSourceLeg() {
        val day = LocalDate.of(2026, 9, 23)
        val r = plan(
            listOf(mergedAt(midnight(day), book = null)),
            listOf(plaid("src", day, authorizedDate = day.minusDays(1)), plaid("dst", day, authorizedDate = day)),
        ).redates.single()
        assertThat(r.newBookDate).isEqualTo(midnight(day.minusDays(1)))
    }

    @Test
    fun theDryRunNamesTheFieldsThatChange() {
        val d1 = midnight(LocalDate.of(2026, 9, 23))
        val d2 = midnight(LocalDate.of(2026, 9, 22))
        val bookOnly = Redate("1", "j1", "x", d1, d1, d2, d1, oldBookDate = d1, oldProcessDate = d1)
        val dateOnly = Redate("2", "j2", "x", d2, d1, null, d1, oldProcessDate = d1)
        val processOnly = Redate("3", "j3", "x", d1, d1, null, d1, oldProcessDate = d2)
        assertThat(bookOnly.fields).containsExactly("book")
        assertThat(bookOnly.describeChange()).isEqualTo("book $d1 -> $d2")
        assertThat(dateOnly.fields).containsExactly("date")
        assertThat(processOnly.fields).containsExactly("process")
        assertThat(processOnly.describeChange()).isEqualTo("process $d2 -> $d1")
        assertThat(summarizeFields(listOf(bookOnly, dateOnly, processOnly))).isEqualTo("date 1, book 1, process 1")
    }

    @Test
    fun aMergedTransferMatchingNeitherLegStillGetsTheDestinationLegDate() {
        val day = LocalDate.of(2024, 12, 6)
        val merged = TransactionRead(
            "transactions", "gm",
            FireflyFixtures.getTransaction(
                type = TransactionTypeProperty.transfer, date = midnight(day.minusDays(3)), amount = "10.00", sourceId = "1", destinationId = "2",
                processDate = midnight(day.minusDays(3)),
                plaidLinks = listOf(PlaidLink("src", PlaidLinkLeg.source, plaidAccount), PlaidLink("dst", PlaidLinkLeg.destination, plaidAccount)),
                transactionJournalId = "jgm",
            ), ObjectLink(),
        )
        val redate = plan(listOf(merged), listOf(plaid("src", day.plusDays(1)), plaid("dst", day))).redates.single()
        assertThat(redate.newDate).isEqualTo(midnight(day))
        assertThat(redate.ambiguous).isTrue() // N9: flagged in the dry run, because a core merge would have kept the OUT leg's date
        // a single-link journal, and a pair with one leg unknown to Plaid, are not ambiguous
        assertThat(plan(listOf(journal("gs", midnight(day.minusDays(3)), "w1")), listOf(plaid("w1", day))).redates.single().ambiguous).isFalse()
        assertThat(plan(listOf(merged), listOf(plaid("dst", day))).redates.single().ambiguous).isFalse()
        // a pair whose date is its OUT leg's, with a stale process date, is redated but is not ambiguous (it matches one leg)
        val matchesOut = TransactionRead(
            "transactions", "gm2",
            FireflyFixtures.getTransaction(
                type = TransactionTypeProperty.transfer, date = midnight(day.plusDays(1)), amount = "10.00", sourceId = "1", destinationId = "2",
                processDate = midnight(day.minusDays(3)),
                plaidLinks = listOf(PlaidLink("src", PlaidLinkLeg.source, plaidAccount), PlaidLink("dst", PlaidLinkLeg.destination, plaidAccount)),
                transactionJournalId = "jgm2",
            ), ObjectLink(),
        )
        assertThat(plan(listOf(matchesOut), listOf(plaid("src", day.plusDays(1)), plaid("dst", day))).redates.single().ambiguous).isFalse()
    }

    @Test
    fun anAuthorizedTimeWithNoAuthorizedDateIsNotUsedForBookDate() {
        val tx = firefly(plaid("a2", LocalDate.of(2024, 12, 6), authorizedDatetime = OffsetDateTime.of(2024, 12, 4, 5, 0, 0, 0, ZoneOffset.UTC)))
        assertThat(tx.bookDate).isNull()
    }

    @Test
    fun theDaylightSavingDaysKeepTheirCalendarDate() {
        for (day in listOf(LocalDate.of(2025, 3, 9), LocalDate.of(2025, 11, 2), LocalDate.of(2025, 3, 10))) {
            for (h in listOf(0, 5, 6)) {
                val tx = firefly(plaid("d$day$h", day, datetime = OffsetDateTime.of(day.atStartOfDay().plusHours(h.toLong()), ZoneOffset.UTC)))
                assertThat(tx.date.atZoneSameInstant(zone).toLocalDate()).describedAs("$day +${h}h").isEqualTo(day)
            }
        }
    }

    @Test
    fun aMissingAccountReadSkipsTheAccountInsteadOfTreatingItAsAnAsset() {
        val day = LocalDate.of(2024, 12, 6)
        val o = plan(listOf(journal("g1", midnight(day), "c1", source = "4")), listOf(plaid("c1", day)), mapOf(4 to 250.0), mapOf()).openings.single()
        assertThat(o.skipReason).contains("account not read")
        assertThat(o.changes).isFalse()
    }

    @Test
    fun aCreditLiabilityThatWouldNeedANegativeOpeningIsSkipped() {
        val day = LocalDate.of(2024, 12, 6)
        // owed to me 20 now, but 500 was paid into it: the opening would be negative, which Firefly forces positive
        val o = plan(
            listOf(journal("g1", midnight(day), "c1", type = TransactionTypeProperty.deposit, amount = "500.00", source = null, destination = "4")),
            listOf(plaid("c1", day, amount = -500.0)), mapOf(4 to 20.0),
            mapOf(4 to account(ShortAccountTypeProperty.liabilities, "Loan to a friend", LiabilityDirection.credit)),
        ).openings.single()
        assertThat(o.skipReason).contains("set it by hand")
    }

    @Test
    fun aStatementOpeningJournalEvenWhenTaggedIsStillReplaced() {
        val legacy = journal(
            "g9", midnight(LocalDate.of(2025, 4, 1)), null, type = TransactionTypeProperty.deposit, amount = "33051.60",
            source = null, destination = "2", description = "DCU statement opening balance", externalId = "dcu-stmt:opening:2",
            tags = listOf(STATEMENT_TAG),
        )
        val interest = journal("g8", midnight(LocalDate.of(2025, 5, 1)).minusHours(1), null, description = "Interest", tags = listOf(STATEMENT_TAG), source = "2")
        val p = plan(listOf(legacy, interest), listOf(), accounts = mapOf(2 to account(ShortAccountTypeProperty.liabilities, "Lexus", LiabilityDirection.debit)))
        assertThat(p.openings.single().legacyGroupIds).containsExactly("g9")
        assertThat(p.redates).isEmpty() // the tagged interest journal keeps its date
    }

    @Test
    fun theOpeningDateIsComparedInTheOffsetFireflyEchoesIt() {
        val day = LocalDate.of(2024, 12, 6)
        // Firefly runs in New York: its midnight of the 5th is 23:00 on the 4th in Chicago, which must not read as a move
        val echoed = OffsetDateTime.of(2024, 12, 5, 0, 0, 0, 0, ZoneOffset.ofHours(-5))
        val o = plan(
            listOf(journal("g1", midnight(day), "w1")), listOf(plaid("w1", day)), mapOf(1 to 490.0),
            mapOf(1 to account(ShortAccountTypeProperty.asset, "Checking", opening = "500.00", openingDate = echoed)),
        ).openings.single()
        assertThat(o.changes).isFalse()
    }

    @Test
    fun theLexusOpeningOnTheExpenseAccountBecomesTheAccountsOwnOpening() {
        val legacy = journal(
            "g9", midnight(LocalDate.of(2025, 4, 1)), null, type = TransactionTypeProperty.deposit, amount = "33051.60",
            source = null, destination = "2", description = "DCU statement opening balance", externalId = "dcu-stmt:opening:2",
        )
        val p = plan(
            listOf(legacy), listOf(),
            accounts = mapOf(2 to account(ShortAccountTypeProperty.liabilities, "Lexus", LiabilityDirection.debit)),
        )
        val o = p.openings.single()
        assertThat(o.fireflyAccountId).isEqualTo(2)
        assertThat(o.newOpening!!.toPlainString()).isEqualTo("-33051.60") // the amount owed, with the sign Firefly gives a debit liability
        assertThat(o.newOpeningDate).isEqualTo(LocalDate.of(2025, 4, 1))
        assertThat(o.liabilityDirection).isEqualTo("debit")
        assertThat(o.legacyGroupIds).containsExactly("g9")
        assertThat(o.changes).isTrue()
    }

    @Test
    fun aPlaidAccountIsReAnchoredAndDatedTheDayBeforeItsFirstTransaction() {
        val day = LocalDate.of(2024, 12, 6)
        val legacy = journal(
            "g9", midnight(LocalDate.of(2026, 10, 6)), null, type = TransactionTypeProperty.deposit, amount = "900.00",
            source = null, destination = "1", description = OPENING_BALANCE_DESCRIPTION,
        )
        // account 1 spent 10.00 (withdrawal from account 1); Plaid says it holds 500.00 now
        // 390 away from the old opening: only a confirmed re-anchor moves the amount (otherwise it is kept, see below)
        val p = plan(
            setOf(1), listOf(legacy, journal("g1", midnight(day), "w1")), listOf(plaid("w1", day)),
            mapOf(1 to 500.0), mapOf(1 to account(ShortAccountTypeProperty.asset, "Checking")),
        )
        val o = p.openings.single()
        assertThat(o.newOpening!!.toPlainString()).isEqualTo("510.00") // 500 + the 10 it spent
        assertThat(o.newOpeningDate).isEqualTo(LocalDate.of(2024, 12, 5))
        assertThat(o.legacyGroupIds).containsExactly("g9")
    }

    @Test
    fun aDebitLiabilityEndsNegativeAndIsIdempotentOnceTheOpeningIsSet() {
        val day = LocalDate.of(2024, 12, 6)
        // owes 250 now; one purchase of 100 into the card (a withdrawal from asset 1 to liability 4 is a payment, so use a deposit out of it)
        val purchase = journal("g1", midnight(day), "c1", type = TransactionTypeProperty.withdrawal, amount = "100.00", source = "4", destination = null)
        val first = plan(
            listOf(purchase), listOf(plaid("c1", day)), current = mapOf(4 to 250.0),
            accounts = mapOf(4 to account(ShortAccountTypeProperty.liabilities, "Card", LiabilityDirection.debit)),
        ).openings.single()
        assertThat(first.newOpening!!.toPlainString()).isEqualTo("-150.00") // -250 target, the 100 purchase is -100 in the card
        assertThat(first.changes).isTrue()

        // after the apply the account reads the opening back; a second plan changes nothing
        val second = plan(
            listOf(purchase), listOf(plaid("c1", day)), current = mapOf(4 to 250.0),
            accounts = mapOf(4 to account(ShortAccountTypeProperty.liabilities, "Card", LiabilityDirection.debit,
                opening = "-150.00", openingDate = midnight(day.minusDays(1)))),
        ).openings.single()
        assertThat(second.changes).isFalse()
    }

    @Test
    fun aDebitLiabilityThatWouldNeedAPositiveOpeningIsSkippedNotGuessed() {
        val day = LocalDate.of(2024, 12, 6)
        // owes 20 now but bought 500 on it: the opening would have to be positive, which Firefly forces negative
        val payment = journal("g1", midnight(day), "c1", type = TransactionTypeProperty.withdrawal, amount = "500.00", source = "4", destination = null)
        val o = plan(
            listOf(payment), listOf(plaid("c1", day, amount = 500.0)), current = mapOf(4 to 20.0),
            accounts = mapOf(4 to account(ShortAccountTypeProperty.liabilities, "Card", LiabilityDirection.debit)),
        ).openings.single()
        assertThat(o.skipReason).isNotNull()
        assertThat(o.changes).isFalse()
    }

    @Test
    fun aPostedPlaidTransactionMissingFromFireflyBlocksTheOpeningChange() {
        val day = LocalDate.of(2024, 12, 6)
        // Plaid holds the payroll that posted after the import; Firefly does not
        val p = plan(
            listOf(journal("g1", midnight(day), "w1")),
            listOf(plaid("w1", day), plaid("payroll", day.plusDays(3), amount = -3745.47)),
            current = mapOf(1 to 3160.30), accounts = mapOf(1 to account(ShortAccountTypeProperty.asset, "Checking")),
        )
        val o = p.openings.single()
        assertThat(o.skipReason).startsWith("SKIPPED: 1 Plaid transactions missing from Firefly")
        assertThat(o.missing.map { it.transactionId }).containsExactly("payroll")
        assertThat(o.changes).isFalse()
    }

    @Test
    fun aPendingJournalIsExcludedFromTheSumAndBackedOutOfTheAnchor() {
        val day = LocalDate.of(2024, 12, 6)
        val pendingTx = plaid("p1", day.plusDays(1), amount = 36.0, pending = true)
        // RD1: the account is listed in repair.reanchor, since pending items otherwise keep the old amount
        val p = plan(
            setOf(1),
            listOf(journal("g1", midnight(day), "w1"), journal("g2", midnight(day.plusDays(1)), "p1", amount = "36.00")),
            listOf(plaid("w1", day), pendingTx),
            mapOf(1 to 500.0),
            mapOf(1 to account(ShortAccountTypeProperty.asset, "Checking", opening = "540.00", openingDate = midnight(day.minusDays(1)))),
        )
        // anchor = 500 + 36 pending = 536; posted journals spent 10; opening 546; the pending journal does not count
        assertThat(p.openings.single().newOpening!!.toPlainString()).isEqualTo("546.00")
        assertThat(p.openings.single().sanityNote).isNull()
    }

    @Test
    fun pendingItemsListedKeepTheOpeningAmountUnlessTheAccountIsListed() {
        val day = LocalDate.of(2024, 12, 6)
        fun run(reanchor: Set<Int>) = plan(
            reanchor,
            listOf(journal("g1", midnight(day), "w1")),
            listOf(plaid("w1", day), plaid("p1", day.plusDays(1), amount = 36.0, pending = true)),
            mapOf(1 to 500.0),
            mapOf(1 to account(ShortAccountTypeProperty.asset, "Checking", opening = "540.00", openingDate = midnight(day.minusDays(1)))),
        ).openings.single()
        // anchor 536 + 10 spent = 546 would move the opening by 6, less than the pending total 36: still kept
        val kept = run(setOf())
        assertThat(kept.newOpening!!.toPlainString()).isEqualTo("540.00")
        assertThat(kept.keptAmount).isEqualTo("KEPT AMOUNT 540.00: 1 pending items listed; re-run when they post (or list the account in repair.reanchor)")
        assertThat(run(setOf(1)).newOpening!!.toPlainString()).isEqualTo("546.00")
    }

    private fun unlisted(reanchor: Set<Int>): OpeningFix {
        val day = LocalDate.of(2024, 12, 6)
        // Plaid's balance (3160) includes a 3,745 payroll that its transaction list does not have yet
        return plan(
            reanchor, listOf(journal("g1", midnight(day), "w1")), listOf(plaid("w1", day)), mapOf(1 to 3160.30),
            mapOf(1 to account(ShortAccountTypeProperty.asset, "Checking", opening = "2729.09", openingDate = midnight(day.minusDays(10)))),
        ).openings.single()
    }

    @Test
    fun aBalanceThatMovedByMoreThanTheTransactionsExplainKeepsTheAmountAndMovesTheDate() {
        val o = unlisted(setOf())
        assertThat(o.newOpening!!.toPlainString()).isEqualTo("2729.09")
        assertThat(o.newOpeningDate).isEqualTo(LocalDate.of(2024, 12, 5))
        assertThat(o.keptAmount).startsWith("KEPT AMOUNT 2729.09")
        assertThat(o.changes).isTrue() // the date moved
    }

    @Test
    fun aListedAccountIsReAnchoredEvenWhenTheBalanceMovedUnexplained() {
        val o = unlisted(setOf(1))
        assertThat(o.newOpening!!.toPlainString()).isEqualTo("3170.30")
        assertThat(o.keptAmount).isNull()
    }

    @Test
    fun anOpeningThatMovesByMoreThanThePendingTotalIsFlagged() {
        val day = LocalDate.of(2024, 12, 6)
        val p = plan(
            listOf(journal("g1", midnight(day), "w1")), listOf(plaid("w1", day)),
            current = mapOf(1 to 500.0),
            accounts = mapOf(1 to account(ShortAccountTypeProperty.asset, "Checking", opening = "100.00", openingDate = midnight(day.minusDays(1)))),
        )
        assertThat(p.openings.single().sanityNote).contains("510.00")
        assertThat(p.openings.single().newOpening!!.toPlainString()).isEqualTo("100.00") // kept: the move is unexplained
    }

    @Test
    fun anAccountWithNoTransactionsIsOpenedAtTheStartOfTheHistoryAndAZeroOneNotAtAll() {
        val day = LocalDate.of(2024, 12, 6)
        val p = plan(
            listOf(journal("g1", midnight(day), "w1")), listOf(plaid("w1", day)),
            current = mapOf(1 to 500.0, 5 to 0.39, 6 to 0.0),
            accounts = mapOf(
                1 to account(ShortAccountTypeProperty.asset, "Checking"),
                5 to account(ShortAccountTypeProperty.asset, "Savings"),
                6 to account(ShortAccountTypeProperty.asset, "Vault"),
            ),
        )
        val savings = p.openings.single { it.fireflyAccountId == 5 }
        assertThat(savings.newOpening!!.toPlainString()).isEqualTo("0.39")
        assertThat(savings.newOpeningDate).isEqualTo(day.minusDays(1))
        val vault = p.openings.single { it.fireflyAccountId == 6 }
        assertThat(vault.skipReason).contains("no opening needed")
        assertThat(vault.changes).isFalse()
    }

    @Test
    fun aStatementKeptDebitLoanWhoseLegacyJournalHadTheWrongSignIsOpenedWithTheDirectionsSign() {
        val legacy = journal(
            "g9", midnight(LocalDate.of(2025, 4, 1)), null, type = TransactionTypeProperty.deposit, amount = "100.00",
            source = null, destination = "2", description = "DCU statement opening balance", externalId = "dcu-stmt:opening:2",
        )
        val debit = plan(listOf(legacy), listOf(), accounts = mapOf(2 to account(ShortAccountTypeProperty.liabilities, "Loan", LiabilityDirection.debit))).openings.single()
        assertThat(debit.newOpening!!.toPlainString()).isEqualTo("-100.00")
        val credit = plan(listOf(legacy), listOf(), accounts = mapOf(2 to account(ShortAccountTypeProperty.liabilities, "Loan", LiabilityDirection.credit))).openings.single()
        assertThat(credit.newOpening!!.toPlainString()).isEqualTo("100.00")
        assertThat(debit.skipReason).isNull()
    }

    @Test
    fun anAccountTouchedByASplitTransactionIsSkippedNotSummed() {
        val day = LocalDate.of(2024, 12, 6)
        val split = TransactionRead(
            "transactions", "gs",
            net.djvk.fireflyPlaidConnector2.api.firefly.models.Transaction(
                transactions = listOf(
                    FireflyFixtures.getTransaction(amount = "5.00", sourceId = "1", date = midnight(day)).transactions.first(),
                    FireflyFixtures.getTransaction(amount = "7.00", sourceId = "1", date = midnight(day)).transactions.first(),
                ),
                groupTitle = "Split",
            ),
            ObjectLink(),
        )
        val o = plan(
            listOf(journal("g1", midnight(day), "w1"), split), listOf(plaid("w1", day)), mapOf(1 to 500.0),
            mapOf(1 to account(ShortAccountTypeProperty.asset, "Checking")),
        ).openings.single()
        assertThat(o.skipReason).contains("split transaction")
    }

    @Test
    fun aMissingTransactionThatIsListedAsIgnoredDoesNotBlockTheOpening() {
        val day = LocalDate.of(2024, 12, 6)
        val planner = RepairPlanner(converter(), zone, setOf(), setOf("payroll"))
        val o = planner.plan(
            RepairInput(
                listOf(journal("g1", midnight(day), "w1")),
                listOf(plaid("w1", day), plaid("payroll", day.plusDays(3), amount = -5.0)).associateBy { it.transactionId },
                mapOf(1 to 505.0), mapOf(1 to account(ShortAccountTypeProperty.asset, "Checking")), mapOf(plaidAccount to 1),
            )
        ).openings.single()
        assertThat(o.skipReason).isNull()
    }

    @Test
    fun aDaylightSavingGapAtMidnightGivesTheFirstInstantOfTheDay() {
        // Sao Paulo (before 2019) skipped 00:00 on the start of DST
        val sp = ZoneId.of("America/Sao_Paulo")
        val d = TransactionConverter.getOffsetDateTimeForDate(sp, LocalDate.of(2018, 11, 4))
        assertThat(d.atZoneSameInstant(sp).toLocalDate()).isEqualTo(LocalDate.of(2018, 11, 4))
    }

    @Test
    fun aConfiguredPlaidAccountWithNoBalanceIsSkippedNotPlannedAsAStatementKeptLoan() {
        val legacy = journal(
            "g9", midnight(LocalDate.of(2025, 4, 1)), null, type = TransactionTypeProperty.deposit, amount = "100.00",
            source = null, destination = "1", description = OPENING_BALANCE_DESCRIPTION,
        )
        // account 1 is configured (plaidAccount -> 1) but Plaid gave no `current` for it
        val o = plan(listOf(legacy), listOf(), mapOf(), mapOf(1 to account(ShortAccountTypeProperty.asset, "Checking"))).openings.single()
        assertThat(o.skipReason).contains("no balance")
        assertThat(o.changes).isFalse()
    }

    @Test
    fun theOpeningDateOfAStatementKeptLoanIsTheEarliestLegacyJournalWhateverTheReadOrder() {
        fun legacy(id: String, d: LocalDate) = journal(
            id, midnight(d), null, type = TransactionTypeProperty.deposit, amount = "10.00", source = null, destination = "2",
            description = "DCU statement opening balance",
        )
        val acct = mapOf(2 to account(ShortAccountTypeProperty.liabilities, "Loan", LiabilityDirection.debit))
        val a = planner.plan(RepairInput(listOf(legacy("g1", LocalDate.of(2025, 4, 1)), legacy("g2", LocalDate.of(2025, 2, 1))), mapOf(), mapOf(), acct)).openings.single()
        val b = planner.plan(RepairInput(listOf(legacy("g2", LocalDate.of(2025, 2, 1)), legacy("g1", LocalDate.of(2025, 4, 1))), mapOf(), mapOf(), acct)).openings.single()
        assertThat(a.newOpeningDate).isEqualTo(LocalDate.of(2025, 2, 1))
        assertThat(b.newOpeningDate).isEqualTo(a.newOpeningDate)
    }

    @Test
    fun aDebitLoanIsOnlyUnchangedWhenFireflyEchoesTheNegativeSign() {
        val legacyDate = LocalDate.of(2025, 4, 1)
        fun withOpening(echo: String) = plan(
            listOf(), listOf(),
            accounts = mapOf(2 to account(ShortAccountTypeProperty.liabilities, "Loan", LiabilityDirection.debit, opening = echo, openingDate = midnight(legacyDate))),
        ).openings
        // no legacy journal and no Plaid balance: nothing to repair, whatever the sign
        assertThat(withOpening("-100.00")).isEmpty()
        // with a legacy journal still present the account is always replaced; the signed compare is the OpeningFix's own
        val fix = OpeningFix(2, "Loan", java.math.BigDecimal("100.00"), legacyDate, java.math.BigDecimal.ZERO, listOf(), java.math.BigDecimal("-100.00"), legacyDate, "debit")
        assertThat(fix.changes).isTrue() // an echoed +100 against the expected -100 is a difference, not a match
        assertThat(fix.copy(oldOpening = java.math.BigDecimal("-100.00")).changes).isFalse()
    }

    // endregion
}
