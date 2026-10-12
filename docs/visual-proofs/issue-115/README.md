# Issue #115 — transfer end-to-end checks

Captured and visually inspected on the API 31 emulator on 2026-10-12. The actual DEV household contains only synthetic data: 100 entries, 10 categories and 49 subcategories. No production account or database was used, and app data was not cleared.

## Real system documents

- [CSV destination](csv-create-document.png): privacy consent followed by Android's `CreateDocument`, with the dated CSV filename.
- [CSV reimport summary](csv-reimport-summary.png): the saved CSV was selected through `OpenDocument` and import explicitly started. All 100 rows were recognized as duplicates; zero added, repaired, discarded or unsaved rows.
- [JSON destination](json-create-document.png): the separate JSON export uses Android's document provider and a dated backup filename.
- [JSON reimport preview](json-reimport-preview.png): the saved JSON was selected through `OpenDocument`. Opening balance, 10 categories, 49 subcategories and 100 entries were recognized as 160 skipped items; zero new items or conflicts. No confirmation is offered when there is nothing new to write.

Both files remain in Downloads for manual verification. The global balance remained **17,855.00 PLN** before and after the flows. System-provider text follows the emulator language; application text is Polish. Fresh cross-household restores, archive/tombstone restoration and network resume are verified separately by real Firebase integration tests, without replacing this interactive dataset.

## Large font and accessibility

[Privacy actions at 320dp / 200% font](privacy-large-font.png) shows the actual updated DEV application. Confirmation and cancellation occupy separate full-width rows, with no overlap. All warning text remains visible. Emulator font scale and density were restored to their original 100% / 420dpi after inspection; physical resolution was not changed.

The final DEV regression passed 52/52 tests, including six accessibility checks and the Polish/Arabic locale check. Accessibility tests apply real device font/density settings before launching the activity and restore the exact prior settings in `finally`, so dialogs are covered too. They verify the bulk-mapping checkbox's spoken label and 48dp bounds, mapping/repair scrolling, callbacks, summaries, warning geometry, and a deliberately clipped-text fixture that must fail the layout assertion.

## CI follow-up: constrained display height

The first CI run exposed two additional failures on its 320x640 / 160dpi emulator: the CSV repair test scrolled a TextField's nested viewport instead of the outer editor, and the privacy notice was genuinely clipped at 200% font. The editor test now scrolls the explicit outer viewport and verifies that supporting errors are fully visible. The dialog heading and complete notice share a scrollable viewport; consent and cancellation remain outside it, always reachable.

These screenshots show the updated real DEV app, not a test fixture:

- [320x640, top](privacy-ci-top.png): the heading and start of the notice, with separate confirmation/cancellation actions.
- [320x640, bottom](privacy-ci-bottom.png): the end of the same notice after scrolling; the actions stay in place.
- [320x480, bottom](privacy-short-viewport.png): the final notice lines and both actions on a still shorter display, including this AVD's system insets.

The entire notice is reachable by scrolling, not simultaneously visible at these font/display settings. Only the dialog was inspected and cancelled; no export or import was started in this follow-up. Physical-size overrides, density and font scale were restored to the original 1080x2400 / 420dpi / 100% afterwards, without clearing data.

Final follow-up verification: Debug accessibility **7/7**, DEV transfer UI/navigation/restoration/locale regression **53/53**, Debug JVM **400/400**, DEV JVM **400/400**, and `lintDebug` passed. The accessibility rule now sets and reads back both the actual display size and density, covers 320x640 and 320x480, checks scrolling/text geometry and callbacks, and restores all original settings in `finally`.
