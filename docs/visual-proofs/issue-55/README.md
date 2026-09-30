# Issue #55: compact offline indicator

## Setup and scope

The baseline is merged master `0e4dbda` (PR #58). Captures use the existing API 31 `thesaurus_api31` AVD, native 1080 x 2400 pixels, density 420, font scale 1.0, and the DEV application with its cached synthetic household. No real Google account or production Firebase data is used. No ledger records are added, edited, or deleted for these captures.

The old warning derives from Firestore cache metadata. The new indicator derives from Android's validated internet connectivity instead. Therefore an unavailable local DEV Firebase server alone is not an offline-device test. Offline captures disable both emulator Wi-Fi and mobile data; both were enabled before verification and restored afterward. Online/offline is not a claim that all pending Firestore writes have been acknowledged. The local Firebase emulators were stopped after the integration suite.

The shared header permanently reserves 48 dp (in addition to the status-bar inset) even while online. This avoids covering controls and prevents connectivity changes from shifting content. The icon itself is a 24 dp disconnected-plug symbol with an explicit 48 x 48 dp button. Tooltip drawing has no input handler; a non-consuming root pointer observer dismisses it while the intended destination still receives the same touch.

## Visual evidence

Every final PNG below was opened and visually examined immediately after capture. Android CLI layout inspection returned an instrumentation-server error; its new screenshot capture stalled and produced an empty file. That helper was interrupted, and current images were captured with `adb shell screencap -p` followed by `adb pull` (no image editing). The earlier baseline capture used Android CLI successfully.

| Image | Observation |
| --- | --- |
| [Before entries](before-entries.png) | Original full-width cached-data warning on master. Scrolled list, not an identical scroll-position comparison. |
| [Online entries](after-online-entries.png) | Indicator absent; old warning absent; fixed add action preserved. |
| [Offline entries](after-offline-entries.png) | Disconnected-plug icon appears above actions without moving the unchanged list or controls. |
| [Tooltip](after-tooltip.png) | Readable Polish bubble, temporarily overlaid rather than inserted into the layout. |
| [Offline summary](after-offline-summary.png) | Summary navigation after opening the tooltip; icon remains, tooltip is gone, all 50 cached entries included. |
| [Offline reports](after-offline-reports.png) | Same top-right indicator, no old warning; chart and legend remain separate. |
| [Offline categories](after-offline-categories.png) | Indicator does not cover the back action; existing pending-sync notice is intentionally retained. |
| [Offline entry form](after-offline-form.png) | Icon and back action remain separate; pending taxonomy writes remain discoverable. No values entered or saved. |
| [Reconnected entries](after-reconnected-entries.png) | After re-enabling Wi-Fi/mobile data, the icon disappears without changing the entries layout. |

The existing report balance-card currency wrapping visible in the report image is outside #55; neither its width nor text formatting was changed. Timing and exact tap-through behavior are asserted by automated tests rather than inferred from a static PNG.

After verification, the form was dismissed without saving, both network toggles were restored to `1`, physical size/density remained 1080 x 2400 / 420, and font scale remained `1.0`. The DEV app was left open on **Wpisy**.

## Automated coverage

New tests cover validated internet policy, initial snapshots racing callbacks, network handovers and stale callbacks, distinct emissions, cancellation cleanup, foreground/background collection, all six household screens, pending writes while offline, preserved errors, fixed control positions, Polish accessibility/live-region semantics, five-second timeout, repeated taps, touch-through inside/outside the tooltip, and route changes.

The report-screen large-font regression measures the selector row's 16 dp top padding relative to its fixture rather than a shorter, vertically centered segmented button. Only a production test tag is added; report spacing is unchanged.

Final verification, performed after the two geometry corrections:

- `lintDebug testDebugUnitTest assembleDebug assembleDevDebug compileDebugAndroidTestKotlin`: passed; 90 JVM tests, zero failures/errors/skips.
- Firebase `emulators:exec --project demo-thesaurus --only auth,firestore` running `connectedDebugAndroidTest`: all 82 API 31 device/UI/integration tests passed, zero failures/errors/skips.
- All 16 new UI tests ran: `OfflineStatusHostTest` (9), `OfflineScreenCoverageTest` (6), `NetworkConnectivityStateTest` (1). All 8 new `NetworkMonitorTest` JVM cases ran.
- Firestore Rules: 27 passed. Hosting fallback: 1 passed.
- `git diff --check`: passed.

Implementation and test analysis/audit used GPT-6.1 Sol Medium. The primary agent reviewed the source, executed verification, and inspected the actual images. TalkBack text and live-region semantics are asserted; an enabled TalkBack listening session was not performed. Local device coverage is API 31; required GitHub PR checks must also pass before merge.
