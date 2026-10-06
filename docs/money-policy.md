# PLN amounts and totals

Persisted ledger amounts and opening balances are signed `Long` grosze. Income is positive;
expense is negative; ledger zero is invalid. Opening/current balances permit zero and either sign.

`PlnMoney` shares exact decimal-to-grosze conversion while keeping distinct input grammars.
Entry input is an unsigned positive magnitude without grouping. Balance input accepts an optional
sign and correctly grouped spaces (including nonbreaking/narrow nonbreaking spaces). Both trim
input, accept Polish comma or dot, and allow only one or two fractional digits. Currency symbols,
scientific notation, incomplete decimals and excess precision remain invalid.

An entry magnitude uses `BigInteger` until its selected sign is applied, then converts exactly to
`Long`. This fixes editing the valid persisted expense `Long.MIN_VALUE`: its magnitude is
`92233720368547758,08` PLN, which is one grosz beyond `Long.MAX_VALUE`. The same magnitude remains
invalid for income. Entry and balance editable text retain their existing decimal conventions.

Currency output constructs a private Polish `NumberFormat` for every call and passes an exact
`BigDecimal` scaled from grosze. `NATURAL` keeps the formatter's sign, `EXPLICIT_POSITIVE` adds a
plus only above zero, and `MAGNITUDE` displays the exact absolute value. Screens retain their
existing conventions (including negative summary expenses and positive report expense totals).
Chart geometry may use floating point after exact amounts have been computed.

`SummaryTotals` and `ReportTotals` alias `LedgerTotals`: positive income, absolute expense, signed
net, active-entry count and empty state. Aggregators retain their own period/type selection and
share exact accumulation. Amounts use `BigInteger`; count increments fail explicitly if `Int`
would overflow. No financial totals or parsing use `Double`.

Regression coverage includes policy differences, precision, signed limits, edit round trips,
concurrent formatting, totals beyond `Long`, form/pending-write acknowledgement, UI magnitude and
emulator repository persistence. Local tests were intentionally not run for this change; CI is
the validation path requested by the user.
