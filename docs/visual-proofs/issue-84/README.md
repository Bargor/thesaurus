# Issue #84: global settings and opening balance

Real, unedited screenshots from the `devDebug` APK on the API 31 emulator
(`thesaurus_api31`, 1080 × 2400, 420 dpi). The local Firebase demo household
contains 100 entries: 40 in August, 40 in September, and 20 in October 2026.
Its unchanged signed ledger total is +17,855.00 PLN. No production Firebase
services or real Google account were used.

## Screens

- [Entries](entries-settings-gear.png): fixed rightmost settings gear, compact
  sorting controls, existing category-colored entries and shared global balance.
  The former taxonomy/family controls are absent.
- [Settings hub](settings-hub.png): `Kategorie`, `Rodzina`, and
  `Ustaw stan konta`; selected gear and Back action.
- [Opening balance](opening-balance.png): both explained modes, signed decimal
  amount with a separate ± control, and save action. The screenshot is an
  unsaved form: it does not modify the demo household's balance or entries.
- [Reports toolbar](reports-filter-and-settings.png): distinct filter then gear
  actions, with the gear at the outer right edge. Report period totals remain
  different from the all-history account balance in the footer.

Save, offline persistence, household isolation, and concurrent current-balance
rejection are verified with separate UUID-based Firebase integration fixtures,
not by changing this existing visual-testing household.

## Verification

- Complete API 31 Android suite: 170 tests, zero failures and ignored tests
  (8m 13s). This includes real Firestore offline/current-balance race tests and
  a visible-software-keyboard opening-balance form test.
- Final navigation rerun: 5/5 tests passed, including the additional 320 × 640
  physical-pixel, 1.8 font-scale reports toolbar regression. Both actions remain
  independently visible, accessible, and at least 48 dp.
- 192 JVM tests passed; lint passed with zero errors (33 pre-existing warnings,
  predominantly dependency update advisories). Debug, instrumentation, and DEV
  APK assembly passed. No new Kotlin compiler warnings remained.
- Firestore Rules: 28/28; CI watchdog wrapper: 9/9.
- A read-only audit after Android testing confirmed the same original 100
  entries, month distribution, and exact +17,855.00 PLN ledger total.

Accessibility descriptions, touch targets, and large-font layout are asserted
automatically; this is not a claim of a manual TalkBack or hardware-keyboard
journey. Device resolution, density, and font scale were not changed for these
screenshots (compact layouts are simulated in Compose tests).

For the former Entries controls and reports toolbar, compare the previous
[issue #81 screenshots](../issue-81/README.md). The new settings and balance
screens did not exist previously.
