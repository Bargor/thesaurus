# Thesaurus

An Android application bootstrap for a Polish-language family expense tracker.
It currently contains a Material 3 navigation shell with **Wpisy**,
**Podsumowanie**, and **Raporty** destinations and intentionally uses
placeholder empty states.

## Prerequisites

- JDK 21
- Android SDK Platform 37 and Build Tools 37.0.0
- An Android 31 emulator (for instrumentation tests)
- Node.js 22 (for the local Firebase Emulator Suite)
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
npm ci
npm run test:rules
npx firebase emulators:exec --project demo-thesaurus --only auth,firestore \
  "./gradlew connectedDebugAndroidTest"
```

On Windows, replace `./gradlew` with `gradlew.bat` (including inside the
Firebase emulator command).

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

## Firebase foundation

The app contains Firestore data contracts, an offline-persistent Firestore factory,
rules, indexes, and an emulator test suite. The Android application is registered as
`pl.bargor.thesaurus` in Firebase project `thesaurus-cef84`; the checked-in
`app/google-services.json` contains public client identifiers, not server credentials.
Its API key is restricted in Google Cloud to this Android package and the registered
debug signing certificate, and can call only Firebase Management, Cloud Logging,
Identity Toolkit, Secure Token, Cloud Datastore, and Cloud Firestore. Before using a
different signing certificate, register its SHA fingerprint in Firebase and add the
same package/fingerprint pair to the Android key restriction. Never add non-Firebase
APIs to this client key; create a separate restricted key for those services.

For local rules tests install project-local Node dependencies, then run:

```sh
npm ci
npm run test:rules
```

This starts Firestore Emulator for the `demo-thesaurus` project and runs the rules
tests. Emulator routing must be requested explicitly by debug/test wiring; it is not
enabled implicitly in production. Supply Firestore emulator routing to
`FirebaseFirestoreFactory.create`; Firebase Auth has a separate emulator connection
helper.

The default Cloud Firestore database is hosted in `europe-central2` (Warsaw). Deploy
the reviewed rules and indexes with an authenticated Firebase CLI:

```sh
npx firebase deploy --only firestore:rules,firestore:indexes
```

## Family invitations and Android App Links

Invitation URLs use `https://thesaurus-cef84.web.app/zaproszenie/{household}/{token}`. The app
manifest has a strict HTTPS intent filter for that path, but Android will treat it as a verified App
Link only after `https://thesaurus-cef84.web.app/.well-known/assetlinks.json` has been deployed.
Serve the file as `application/json`, without redirects, and include package
`pl.bargor.thesaurus` plus the SHA-256 fingerprint of every certificate that signs an installed APK
(for example, debug and/or release). Do not invent a fingerprint; publishing the association on the
HTTPS host is a deployment prerequisite.
