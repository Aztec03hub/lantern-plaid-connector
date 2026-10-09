package net.djvk.fireflyPlaidConnector2.names

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class CounterpartyNamerTest {
    private val namer = CounterpartyNamer()

    @Test
    fun theThreeShapesOfOnePaycheckBecomeOneName() {
        val shapes = listOf(
            "EVOLV CONSULTING",
            "Evolv Consulting Type: Payroll ID: XX2465 CO: Evolv Consulting",
            "AC EVOLV CONSULTING PAYROLL 11100001234567 PP",
        )
        assertThat(shapes.map { namer.canonical(null, it) }.toSet()).containsExactly("Evolv Consulting")
    }

    @Test
    fun plaidsOwnNamePreferredWhenPresent() {
        assertThat(namer.canonical("Starbucks", "STARBUCKS STORE 12345 CHICAGO IL")).isEqualTo("Starbucks")
        assertThat(namer.canonical("  ", "STARBUCKS STORE 12345 CHICAGO IL")).isEqualTo("Starbucks Store")
    }

    @Test
    fun storeNumbersTraceIdsAndLocationsAreStripped() {
        assertThat(namer.canonical(null, "TARGET #1234 CHICAGO IL")).isEqualTo("Target")
        assertThat(namer.canonical(null, "COMED ONLINE PMT WEB 8800123456")).isEqualTo("Comed Online")
        assertThat(namer.canonical(null, "Home Depot Naperville IL")).isEqualTo("Home Depot")
        assertThat(namer.canonical(null, "ACH PPD AMAZON 1234567890")).isEqualTo("Amazon")
    }

    @Test
    fun aShortNameIsNeverEmptiedByTheCleaning() {
        assertThat(namer.canonical(null, "AC 12345678")).isNotBlank()
        assertThat(namer.canonical(null, "IL")).isEqualTo("Il")
    }

    @Test
    fun theAliasTableHasTheLastWord() {
        val aliased = CounterpartyNamer(mapOf("EVOLV CONSULTING" to "Evolv (employer)"))
        assertThat(aliased.canonical(null, "AC EVOLV CONSULTING PAYROLL 1110000123")).isEqualTo("Evolv (employer)")
        assertThat(aliased.canonical("Evolv Consulting", "x")).isEqualTo("Evolv (employer)")
    }

    private fun k(s: String) = namer.key(namer.canonical(null, s))

    @Test
    fun debitCardLinesKeepTheMerchantAndDropTimeRefCityAndCardNumber() {
        assertThat(namer.canonical(null, "DBT CRD 0514 DJVU7XEK ADVOCATE PATIENT PAYME DOWNERS GROVE IL C#7221")).isEqualTo("Advocate Patient Payme")
        assertThat(namer.canonical(null, "DBT CRD 1404 DJLLB57N CHECKR PERSO BY CHECKR SAN FRANCISCO CA C#7221")).isEqualTo("Checkr Perso By Checkr")
        assertThat(namer.canonical(null, "DBT CRD 1738 DJOA7MF5 SUNDAE FUNDAY CROWN PO CROWN POINT IN C#7221")).isEqualTo("Sundae Funday Crown Po")
        val street = listOf("DBT CRD 1822 DJZ9OQLI 07264 - 31ST STREET HA CHICAGO IL C#7221", "DBT CRD 1913 DJJXSP49 07264 - 31ST STREET HA CHICAGO IL C#7221")
        assertThat(street.map { k(it) }.toSet()).hasSize(1)
        assertThat(namer.canonical(null, street[0])).isEqualTo("07264 - 31st Street Ha")
        val abc = listOf("DBT CRD 0613 DJHH1TSK ABC274-CFX WILLOWBROOK IL C#7221", "DBT CRD 0305 DJBXFJ60 ABC274-CFX WILLOWBROOK IL C#7221")
        assertThat(abc.map { k(it) }.toSet()).hasSize(1)
        assertThat(namer.canonical(null, abc[0])).isEqualTo("Abc274-cfx")
    }

    @Test
    fun cardCreditsAreCleanedLikeDebitCardLines() {
        assertThat(namer.canonical(null, "CRE 0000 DJRGWBX1 SP OMI AI WILMINGTON DE C#7221")).isEqualTo("Sp Omi Ai")
    }

    @Test
    fun aColonNameIsKeptWholeUnlessTheRestRepeatsOrIsAllCaps() {
        assertThat(k("Interest: DCU Lexus NX loan")).isNotEqualTo(k("Interest: DCU personal loan"))
        assertThat(namer.canonical(null, "Interest: DCU Lexus NX loan")).isEqualTo("Interest: DCU Lexus NX loan")
        assertThat(namer.canonical(null, "Google: GOOGLE *YOUTUBE")).isEqualTo("Google")
        assertThat(namer.canonical(null, "Google: Google Youtube")).isEqualTo("Google")
    }

    @Test
    fun autopayAndAtmRowsStillCollapse() {
        val autopay = listOf("AC CHASE CREDIT CRD AUTOPAY 0210000123456PPD 4760039224", "AC CHASE CREDIT CRD AUTOPAY 0210000987654PPD 4760039225")
        assertThat(autopay.map { k(it) }.toSet()).hasSize(1)
        val atm = listOf("ATM W/D 1234 MAIN ST CHICAGO IL", "ATM W/D 5678 OAK AVE NAPERVILLE IL")
        assertThat(atm.map { k(it) }.toSet()).hasSize(1)
    }

    @Test
    fun aNumberThatIsPartOfTheNameKeepsDistinctPayeesDistinct() {  // M3 / T8
        assertThat(k("Pier 1 Imports")).isNotEqualTo(k("Pier 39 Parking"))
        assertThat(k("Route 66 Diner")).isNotEqualTo(k("Route 9 Gas"))
        assertThat(namer.canonical(null, "Studio 54")).isEqualTo("Studio 54")
        assertThat(k("Fitness 19")).isNotEqualTo(k("Fitness 24 Seven"))
        assertThat(namer.key("Pier 1")).isNotEqualTo(namer.key("Pier 39"))  // digits are part of the key
    }

    @Test
    fun storeNumbersOfThreeToFiveDigitsAndHashNumbersStillCollapseTheSamePayee() {
        assertThat(k("TARGET #12 CHICAGO IL")).isEqualTo(k("TARGET #345 NAPERVILLE IL"))
        assertThat(k("KROGER 482 CHICAGO IL")).isEqualTo(k("KROGER 1930 NAPERVILLE IL"))
        assertThat(namer.canonical(null, "Target No. 7 Chicago")).isEqualTo("Target")
    }

    @Test
    fun aOneOrTwoDigitStoreNumberAfterAKnownChainCollapsesButNamesWithNumbersStayDistinct() {  // N8
        assertThat(namer.canonical(null, "TARGET 12 CHICAGO IL")).isEqualTo("Target")
        assertThat(k("TARGET 12 CHICAGO IL")).isEqualTo(k("TARGET 31 NAPERVILLE IL"))
        assertThat(namer.canonical(null, "WALMART SUPERCENTER 47")).isEqualTo("Walmart Supercenter")
        listOf("KROGER 12" to "Kroger", "STARBUCKS 12" to "Starbucks", "HOME DEPOT 12" to "Home Depot", "SHELL OIL 12" to "Shell Oil", "TRADER JOES 12" to "Trader Joes")
            .forEach { (raw, shown) -> assertThat(namer.canonical(null, raw)).describedAs(raw).isEqualTo(shown) }
        // not a chain store number: a check number, and the names that carry a number
        assertThat(namer.canonical(null, "CHECK 12")).isEqualTo("Check 12")
        assertThat(k("Pier 1 Imports")).isNotEqualTo(k("Pier 39 Parking"))
        assertThat(k("Route 66 Diner")).isNotEqualTo(k("Route 9 Gas"))
        assertThat(namer.canonical(null, "Pier 1 Imports")).isEqualTo("Pier 1 Imports")
    }

    @Test
    fun twoPeopleUnderAZellePrefixStayTwoPayees() {  // M3 colon rule
        assertThat(k("Zelle: JOHN SMITH")).isNotEqualTo(k("Zelle: JANE DOE"))
        assertThat(namer.canonical(null, "Amazon: AMZN MKTP US")).isEqualTo("Amazon")
    }

    @Test
    fun theCompanyAfterCoWinsAndPaymentWordsAreDropped() {  // T8 survivors
        assertThat(namer.canonical(null, "Foo Bar CO: Evolv Consulting")).isEqualTo("Evolv Consulting")
        assertThat(namer.canonical(null, "Acme Payment")).isEqualTo("Acme")
    }

    @Test
    fun anAllCapsAmpersandNameKeepsItsCaps() {  // nit2
        assertThat(namer.canonical(null, "AT&T")).isEqualTo("AT&T")
        assertThat(namer.key("AT&T")).isEqualTo("at t")
    }

    @Test
    fun keysIgnoreCasePunctuationAndSpacing() {
        assertThat(namer.key("Evolv  Consulting, Inc.")).isEqualTo(namer.key("EVOLV CONSULTING INC"))
    }
}
