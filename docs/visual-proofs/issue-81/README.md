# Issue #81 — persistent global account balance

These raw screenshots come from the API 31 `thesaurus_api31` emulator (1080 × 2400 pixels, 420 dpi), using the DEV app and its existing local Firebase Emulator household. No production account or data is used. The 100 synthetic entries span August–October 2026; no entries were added, modified, or removed for these captures.

## Before

`before-reports.png` was captured on master `d217cf7`, before installing this change. The main navigation has no persistent account balance.

## Verification scope

The shared footer sums all undeleted household entries, rather than the selected screen's period or filters. The ordinary layout is approximately 28 dp high, directly adjoining the main navigation. Full signed amounts, Polish currency formatting, and accessible balance status are retained.

Automated coverage includes arbitrary-precision aggregation, household/account isolation, local pending writes and cached offline state, edits and tombstones in a real local Firestore Emulator, all four main destinations and nested routes, the last scrolled list action, horizontal safe-area insets, narrow/wide viewports, font/display scaling, and an actually visible software keyboard. Accessibility semantics are asserted automatically; this is not a claim of manual TalkBack testing. Wide viewports are simulated in Compose tests, not a claim of a formal device-rotation journey.

Screenshots are captured with Android CLI and visually inspected individually. They are not cropped, composited, or otherwise edited.
