# Issue #54: compact bottom navigation

## Capture setup

- API 31 emulator `thesaurus_api31`, portrait 1080 x 2400 px, density 420 dpi.
- DEV build with the existing synthetic household (50 cached entries); no real account data.
- Before: master `224400e1677b6f54636a8fd7e2bc2ea28b1948b7`.
- After: this issue's branch, independently based on that master. Issue #53 is not included.
- All screenshots were visually reviewed. Offline notices are expected because Firebase emulators were stopped during capture.

## Visual proof

| State | Screenshot |
| --- | --- |
| Previous navigation, normal font | [Before](before-navigation.png) |
| Compact navigation, Wpisy selected | [After](after-navigation.png) |
| Podsumowanie selected | [Summary](after-summary.png) |
| Raporty selected | [Reports](after-reports.png) |
| Font scale 1.8, Raporty selected | [Large font](after-large-font.png) |

The normal-font Material navigation content is reduced from 80 dp to 64 dp (16 dp / 20%). System navigation insets are additional and remain unchanged. No fixed height clips labels: the native short bar grows at larger font scales, allowing `Podsumowanie` to wrap without losing characters. Its selected indicator, icons and labels remain within the bar above system navigation.

The large-font image also exposes pre-existing wrapping in the report controls and totals. Those report components, and the DEV account-switch control, are outside this issue's scope and were not changed. The emulator font setting was restored after capture.

## Automated verification

- 76 JVM tests passed; debug lint and APK builds passed.
- `BottomNavigationTest`: 5 tests passed, covering the 320 dp CI portrait width, Polish labels and tab semantics, minimum 48 dp touch targets, synthetic horizontal/bottom insets, font scale 1.8 with display density 1.1, actual rendered text bounds and selected-indicator pixels.
- `NavigationSmokeTest`: 10 tests passed, including real household Scaffold/system-inset integration and existing navigation regressions.
- No Material dependency upgrade, custom fixed-height override or merge of #53 was required.

The inset test also reproduces the CI emulator's 320 dp / density-1 geometry. Native equal-weight items divide integer pixels: after 12 px left and 18 px right insets, 290 px yields three 96 px items and a 2 px trailing remainder. Assertions check this exact remainder and all touch targets rather than incorrectly requiring the last item to end exactly at the inset boundary.

Instrumentation classes were run separately with `connectedDebugAndroidTest` and `-Pandroid.testInstrumentationRunnerArguments.class=<fully-qualified-class>`; JVM/lint used `testDebugUnitTest lintDebug`. The DEV APK was built with `assembleDevDebug` for screenshots.
