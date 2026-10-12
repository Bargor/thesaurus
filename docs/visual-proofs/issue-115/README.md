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
