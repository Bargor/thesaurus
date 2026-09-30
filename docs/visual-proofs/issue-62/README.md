# Issue 62: toggle summary scope from the period heading

## Environment

- API 31 emulator `thesaurus_api31`, 1080 × 2400, density 420, default font scale.
- Developer APK (`pl.bargor.thesaurus.dev`) with the existing synthetic household and 50 September 2026 entries. No real account or production data was used or cleared.
- Baseline: merged `master` at `67a3209`. After images use the implementation in this PR.
- Images are unmodified device screenshots, captured with Android CLI and visually inspected immediately. The offline icon in some after images reflects the emulator's connectivity at capture time, not the summary scope.

## Visual checks

1. `before-month.png`: separate month/year selector above the period navigation.
2. `after-month.png`: the selector is gone; the primary-colored heading and swap icon form one interactive button in the existing navigation row. September still shows 50 entries and unchanged totals.
3. `after-year.png`: tapping the heading changes it to `2026` and uses the full-year period.
4. `after-return-month.png`: tapping again returns to `Wrzesień 2026`, not the current or an arbitrary month.
5. `after-previous-year.png`: the previous arrow in yearly mode selects `2025`; totals and entry count correctly become zero for this sample dataset.
6. `after-remembered-month-in-previous-year.png`: tapping `2025` returns to `Wrzesień 2025`, retaining September in the selected year.

All sample entries are in September, so September and 2026 deliberately have identical totals. Automated tests use multi-month and multi-year fixtures to verify recalculation of totals, filtered totals, and visible entries rather than inferring correctness from these identical amounts.

## Automated coverage

- ViewModel tests: month-to-year-to-month, changed month, month/year arrows, cross-year navigation, leap February, and a new SavedStateHandle restored from primitive values.
- Compose tests: heading tap and button semantics, Polish scope and click labels, absent old selector, 48dp target on a compact screen with font scale 1.8, and real ViewModel integration with active tag filtering and retained sorting.
- Saved-state instrumentation: retained ViewModelStore across lifecycle-owner recreation, plus a fresh store restored through SavedStateRegistry and a Bundle/Parcel round trip. This exercises Android's saved-state mechanism; it is not a claim that a real OS process was killed during the test.
- TalkBack semantics are asserted automatically; a manual spoken TalkBack session was not performed.

Saved-state restoration applies to configuration changes and system-initiated process recreation with a retained task. Explicit force-stop, removal from Recents, and a device reboot are not persistent preference storage.
