# Thesaurus

An Android application bootstrap for a Polish-language family expense tracker.
It currently contains a Material 3 navigation shell with **Wpisy**,
**Podsumowanie**, and **Raporty** destinations and intentionally uses
placeholder empty states.

## Prerequisites

- JDK 21
- Android SDK Platform 37 and Build Tools 37.0.0
- An Android 31 emulator (for instrumentation tests)
- Gradle 9.6.0 via the checked-in wrapper

The project uses Android Gradle Plugin 9.4.0, Kotlin 2.3.21, and a version
catalog with pinned dependency versions. The application ID and namespace are
`pl.bargor.thesaurus`; the minimum Android version is API 31.

## Build and test

On macOS/Linux:

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew connectedDebugAndroidTest
```

On Windows, replace `./gradlew` with `gradlew.bat`.

To install the debug build on a connected device or running emulator:

```sh
./gradlew installDebug
```

The APK is written to `app/build/outputs/apk/debug/`.

## Continuous integration

GitHub Actions runs for pull requests targeting `master`, pushes to `master`,
manual dispatches, and daily at 02:00 UTC. Pull request and push builds run
lint/static checks, JVM tests, debug assembly, and an API 31 instrumentation
smoke test. Daily builds additionally run the smoke test on API 37 and upload
the debug APK plus Android test reports.

## Planned work

Firebase integration and the expense-tracking product features are deliberately
out of scope for this bootstrap. They will be added in future issues.
