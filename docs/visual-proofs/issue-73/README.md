# Issue #73: report filter dialog and member filtering

These are unmodified screenshots captured from the running DEV APK on the API 31
Android emulator. Portrait resolution is 1080 x 2400, density 420 dpi; landscape
resolution is 2400 x 1080. The existing synthetic September 2026 household data
was preserved. No production account or production data was used.

- `before-report.png`: master before this change, with inline filters.
- `after-report.png`: the same September report with filters moved into the dialog.
  Income 19,062.50 PLN, expense 5,347.85 PLN, net 13,714.65 PLN are unchanged.
- `filters-dialog.png`: September, all entry types, Jedzenie / Codzienne, and the
  synthetic owner's UID explicitly selected. Changes remain a draft until Apply.
- `filtered-report.png`: applying the combined filters updates totals, chart and
  entries together: income 0.00 PLN, expense 501.95 PLN, net -501.95 PLN.
- `large-text-dialog.png` and `large-text-report.png`: actual system font scale
  1.8 (180%), with the applied selection surviving configuration changes.
- `landscape-dialog.png` and `landscape-members.png`: scrollable filter content
  in landscape with fixed heading, reset, Cancel and Apply controls.

The visual DEV household has one current member. Automated repository integration
tests separately exercise two authenticated synthetic members, member removal,
historical authors and offline filtering against Firebase emulators.

Back and outside dismissal were checked, as were dependent category/subcategory
menus. Normal font scale (1.0), portrait orientation and automatic rotation were
restored after verification; application data was not cleared.
