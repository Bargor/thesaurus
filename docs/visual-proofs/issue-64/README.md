# Issue #64 — Browse category and subcategory drill-down

These are unedited Android Emulator screenshots of Thesaurus DEV. The existing
synthetic household contains entries in September 2026; no real account or
production Firebase data was used. Device: Android 12 / API 31, 1080 × 2400,
420 dpi, portrait, default font scale unless explicitly noted.

The baseline `before-navigation.png` was captured from the merged #66 app,
before installing this feature. It shows the original three-tab navigation.

## Screenshots and observed results

### Review update: main-tab order

The final tab order is **Wpisy → Przegląd → Podsumowanie → Raporty**.
[Updated navigation screenshot](reordered-navigation.png) shows Przegląd
selected as the second tab. The earlier screenshots below document the
initial feature walkthrough before this review adjustment; their fourth-tab
placement is superseded by this updated screenshot.

| Screen | Evidence |
| --- | --- |
| Original three-tab navigation | [Before](before-navigation.png) |
| New selected tab, empty October 2026 | [Empty month](empty-month.png) |
| September category order, stored colors, signed totals | [Categories](month-categories.png) |
| Jedzenie expanded to Codzienne | [Subcategories](month-subcategories.png) |
| Codzienne expanded to dated entry cards | [Entries](month-entries.png) |
| Inne expanded through Bez podkategorii to its entries | [Direct category assignments](month-unassigned.png) |
| Same period control switched to 2026 | [Year](year-categories.png) |

The inspected September data shows Jedzenie and Codzienne at **−501.95 PLN**,
Inne and Bez podkategorii at **−698.60 PLN**, and Wpływy at **+19,062.50 PLN**.
The yearly totals match because this synthetic household's entries are in
September. The cached data remains usable with pending local writes; the
sync banner is expected while the local Firebase emulators are stopped.
Every screenshot was inspected immediately after capture; images are not
cropped, annotated, or edited. The existing household was not cleared/reseeded.

## Behaviour to review

1. Open **Przegląd** in the main navigation. October 2026 is initially empty.
2. Use the previous-period arrow to select September 2026. Only categories with
   entries appear, using the same stored colors as their ledger cards.
3. Expand **Jedzenie**, then **Codzienne**. The nested dated entry cards can be
   opened by their author or the household owner. Tap again to collapse.
4. Inspect **Wpływy** for positive green totals and **Inne → Bez podkategorii**
   for direct category assignments. Negative balances are red; a zero net is
   neutral. Mixed income/expense categories show the exact signed net, not an
   unsigned expense magnitude.
5. Tap the period heading to toggle between the selected month and its year,
   using exactly the same component as **Podsumowanie**. Navigate to another
   period and check that expanded entries and totals refresh together.

## Verification boundaries

Screenshots demonstrate visual layout only. Automated coverage additionally
checks leap years, inclusive period boundaries, household isolation, deleted
entries, absent/archived taxonomy, expansion pruning, member permissions, exact
BigInteger sums, order changes, cached failures, and status-only cache reuse.
Compose tests exercise nested accessibility descriptions, signed color pixels,
entry navigation, and 280 dp width with font scale 1.8. A Firebase Emulator
integration test exercises the real repositories and Browse ViewModel through
offline writes, period switching, reconnection, and tombstones.

Manual spoken TalkBack and physical-device/API 37 validation are not claimed.

## Local test results

- 115 JVM tests passed; debug and DEV APK assembly passed.
- Android lint: zero errors (34 warnings and one informational hint).
- 27 Firestore Rules tests and one hosting invitation-fallback test passed.
- Full API 31 instrumentation run: 98/99 passed. The only failure was a
  Compose `captureToImage` force-redraw timeout before the color assertion.
  The entire six-test Browse screen class was rerun on unchanged code and
  passed 6/6, including the color test. Thus all 99 distinct instrumentation
  cases have a passing execution, but the initial full run was not green.
- All six Firebase repository integration cases passed in the full run,
  including Browse offline writes, reconnection, and deletion.

CI must still produce a clean full run before merge. Test assertions were not
weakened to accommodate the redraw timeout.

The first GitHub run passed 98/99 instrumentation cases, including all Browse
tests. Its sole failure was the navigation baseline fixture requesting 411 dp
inside a narrower CI viewport and unexpectedly wrapping to 80 dp. The fixture
now scales only its local density to fit that logical width; strict 64 dp and
16 dp height-reduction assertions are retained. The separate 320 dp wrapping
test and accessibility/clip checks remain unchanged. Navigation tests also
assert the requested rendered left-to-right order.

After the review update, local verification passed all 115 JVM tests,
six BottomNavigation tests, and 12 NavigationSmoke tests. Debug lint and
DEV APK assembly passed; the updated APK is installed on the visible emulator.
