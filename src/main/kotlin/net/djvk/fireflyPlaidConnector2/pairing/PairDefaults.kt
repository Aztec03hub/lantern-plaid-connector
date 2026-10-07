package net.djvk.fireflyPlaidConnector2.pairing

/**
 * The default markers, destinations and vetoes (design 4.3). They are Phil's accounts' defaults: a new bank is a
 * configuration change, not code. Anything that names a person, a company or an account number lives in the pair config
 * file (see pair-config.template.json), never here. The patterns are the ones tools/pair_mock.py measured against the oracles.
 */
object PairDefaults {
    val outMarkers = listOf(
        Marker("ach", Regex("^AC ")),
        Marker("dcu-to", Regex("(?i)(^|onlin )to (loan|share) ")),
        Marker("online-withdrawal", Regex("(?i)^withdrawal onlin ")),
        Marker("balance-transfer", Regex("BALANCE CONSOLIDATION")),
        Marker("sofi-vault", Regex("^(Roundup \\*|To Emergency Fund Vault)")),
    )
    val inMarkers = listOf(
        Marker("card-payment", Regex("(?i)credit card payment received|payment.{0,3}thank")),
        Marker("ach", Regex("(?i)ach xfer|^AC ")),
        Marker("dcu-from", Regex("(?i)^from (share|loan) ")),
        Marker("sofi-vault", Regex("^(Roundup \\*|From checking balance)")),
    )

    /** A statement carry-over is not money moving: never a pairing candidate. */
    val carryOver = Regex("LAST STATEMENT BAL FROM ACCT ENDING")
    val p2p = Regex("(?i)zelle|venmo|paypal|cash ?app")
    val income = Regex("(?i)payroll|direct dep|interest|refund|tax|ides|promo bonus|^0\\.050%|loan adv|fidelity|merrill|robinhood")

    val destinations = listOf(
        DestRule(Regex("CHASE CREDIT CRD"), DestTarget.Institution("Chase")),
        DestRule(Regex("(?i)withdrawal onlin old second"), DestTarget.Institution("Old Second")),
        DestRule(Regex("^(To Emergency Fund Vault|Roundup \\*)"), DestTarget.Role("vault")),
        DestRule(Regex("BALANCE CONSOLIDATION"), DestTarget.Role("card")),
        DestRule(Regex("(?i)best buy|home depot|lowes|nordstrom|\\batt\\b|comed|bilt|affirm|klarna|coinbase|IBTRANSFER"), DestTarget.Unlinked),
    )
}
