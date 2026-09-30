# Issue #53 — compact entry cards and accounting-date headings

## Current revision after rebase

The branch was rebased onto master `06cc2fa9124a7980d73639869b00161dfd1e179d`,
including the merged compact bottom navigation from #54. Its entry layout and
author persistence are unchanged by the rebase. The conflicting navigation tests
were combined, preserving both the real system-inset test and the persistent-add
test at enlarged font/density.

Fresh captures use the same cached synthetic household (50 entries), API 31,
1080 x 2400 px, 420 dpi. The before APK has the same application source as that
master; before and after both include compact navigation. Every capture was
visually reviewed. No entry was created or modified during the manual checks.

| State | Current proof |
| --- | --- |
| Master before #53 | [Before](before-rebased-entries.png) |
| Rebased #53, five complete cards and part of a sixth | [After](after-rebased-entries.png) |
| Fixed footer after scrolling | [Scrolled](after-rebased-scrolled.png) |
| Add action opens the form from the scrolled list | [Form](after-rebased-add.png) |
| Enlarged font 180%, scrolled list with visible footer/navigation | [Large font](after-rebased-large-font.png) |

The form was dismissed without saving. Font scale was restored to 1.0. Temporary
screen-size/density overrides used for testing were reset to the original
1080 x 2400 / 420 dpi configuration; the DEV app was left open.

### CI fixture correction and verification

The previous failing five-card test requested a 411 x 700 dp route, but the CI
window constrained that request to 320 x 616 dp. It now measures the intended
viewport unbounded at native text density and scales only the rendered layer to
fit the device. Scaling density before text measurement would introduce rounding
changes at small font pixel sizes, so that approach is deliberately not used.

The test still checks every card's complete unclipped bounds inside the list,
displayed cards, the canonical rendered dimensions and window containment, and
the add target's 48 dp equivalent. A 0.001 physical-pixel tolerance applies only
to floating-point transformed outer-window bounds. Real-screen 48 dp, scrolling,
font 1.8/density 1.1 and system-inset tests remain unscaled and unchanged.

- The corrected targeted fixture passed with a temporary 320 x 640 / 160 dpi
  override. Its actual app window was 320 x 451 px because this AVD retains larger
  system bars; this is an extra constrained-window check, not a claim to reproduce
  the fresh CI window exactly.
- Final native-screen UI suites passed: entries 11, navigation 11, compact
  navigation 5 and categories 10 — 37 tests, zero failures/errors/skips.
- JVM verification passed with 82 successful tests in current reports; final
  validation reused unchanged JVM task outputs. Debug lint, debug APK/UI-test
  compilation and DEV APK assembly passed.
- Full GitHub CI additionally runs Firestore Rules, hosting and Firebase emulator
  integration tests on its own fresh API 31 emulator. Required checks must pass
  before merge.

## Original captures before rebase

Visual evidence uses Thesaurus DEV with the same cached, synthetic household on
the API 31 emulator: 1080 × 2400 pixels, 420 dpi, default font scale. No real
Google account or production data is used. The offline banner is expected because
the local Firebase emulators are not running during these captures.

The baseline was captured before this change. The installed build used for that
capture included issue #52, which did not modify the entries screen; its entry
layout matched the original branch base (`8d6f8e2`). Current review should use the
rebased captures above; these older images are retained as historical evidence.

## Before

Each card repeats creator e-mail and accounting date. The add action is a scrolling
list item and is not visible at the top of this populated list.

![Before: repeated creator and date](before-entries.png)

## After

Creator e-mail and per-card dates are removed only from presentation. Separate
Polish accounting-date headings mark consecutive date runs without changing the
selected sort order. The add action is fixed above bottom navigation.

With this unchanged data set, five complete cards and part of a sixth now fit,
compared with four complete cards and part of a fifth before. Taxonomy and amount
retain their original emphasis; secondary text is 14 sp and scales with the user's
font setting. Card and add-action touch targets remain at least 48 dp.

![After: compact cards, date headings and fixed add action](after-entries.png)

## Add action while browsing

The footer remains in the same location after scrolling. Tapping it opens the
new-entry form; the form was then dismissed without saving or changing the test data.

![After scrolling: fixed add action](after-scrolled.png)

![New-entry form opened from the scrolled list](after-add-from-scrolled-list.png)

## Automated coverage

- JVM tests check consecutive and interleaved dates, calendar boundaries, pagination,
  filtering/refresh/add/edit/delete projections, unique row keys, and unchanged author metadata.
- Compose tests check date-heading recomposition, fixed add action while scrolling
  and loading another page, loading/empty/error states, category colors, edit/delete
  and undo, minimum 48 dp card/action heights, and long text at enlarged font/density.
- Navigation tests render the real household scaffold with enlarged font/density
  and an additional 32 dp bottom system inset. They check add-action containment
  above navigation and successful navigation after scrolling.
