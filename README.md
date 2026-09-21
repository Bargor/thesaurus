# Thesaurus

Thesaurus is a Polish-language Android app for a shared household ledger. It keeps income and expenses in Firestore, works from Firestore's persistent local cache, and synchronizes queued changes once a connection returns. The visible app UI is intentionally Polish.

The **Wpisy** tab is the ledger. **Podsumowanie** has independently remembered month and year views, and **Raporty** supports month, year, or custom dates, signed type filters, category shares, trends, and an entry drill-down. Household owners can invite a person by email and manage membership.

## Requirements

- JDK 21
- Android SDK Platform 37, Build Tools 37.0.0, and platform-tools
- Android Studio (current stable is recommended) or the checked-in Gradle wrapper
- Node.js 22 and npm, for Firebase Emulator Suite tests
- An API 31 Android emulator for the normal instrumentation suite; API 37 is also useful for the daily-release check

The project pins Gradle 9.6.0, Android Gradle Plugin 9.4.0, and Kotlin 2.3.21. Its application ID and namespace are `pl.bargor.thesaurus`; `minSdk` is API 31.

## First build

Clone the repository and enter its root directory:

```sh
git clone https://github.com/Bargor/thesaurus.git
cd thesaurus
```

Use the wrapper; no machine-wide Gradle installation is needed. On macOS/Linux:

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

On Windows, use `gradlew.bat` in PowerShell or Command Prompt:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
```

Open the repository root in Android Studio to import the same Gradle project. Choose the `app` run configuration and a device running API 31 or newer, then use Run or Debug. If Studio reports a missing SDK, install **Android API 37**, **Android SDK Build-Tools 37.0.0**, and **Android SDK Platform-Tools** in SDK Manager, then sync again.

The debug APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Firebase setup

`app/google-services.json` is the Android client configuration for the existing project. It contains public client identifiers, not a server credential. For an independent Firebase project, do the following before running the app against it:

1. Create a Firebase project and enable **Cloud Firestore**. Create the database in the intended production location (the existing project uses `europe-central2`, Warsaw); a Firestore location cannot be changed later.
2. Add an Android app with package name `pl.bargor.thesaurus`. Download its `google-services.json` and replace `app/google-services.json` locally. Do not commit a configuration for an unrelated production project.
3. In Firebase Authentication, enable the Google provider. Configure its OAuth consent screen/support email as required by Firebase.
4. Register every signing-certificate SHA-1 and SHA-256 used to install the app. For the usual debug keystore, this command prints both fingerprints:

   ```sh
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey \
     -storepass android -keypass android
   ```

   On Windows the keystore is normally `%USERPROFILE%\.android\debug.keystore`. Add the same package/fingerprint pair to the Android restriction on the Google API key. Release, Play App Signing, and CI certificates each need their own registered fingerprint.
5. `npm ci` installs the pinned Firebase CLI locally. Verify it without a global install with `npx --no-install firebase --version`. Local emulator tests do **not** need `firebase login`, a selected project, or a deploy. Only a Firebase project owner preparing a production deployment should authenticate, select the target, and deploy reviewed Firestore configuration:

   ```sh
   npx firebase login
   npx firebase use <your-project-id>
   npx firebase deploy --only firestore:rules,firestore:indexes
   ```

The app connects to production by default. Emulator routing is explicitly opt-in in debug/test wiring; do not point a production build at `10.0.2.2`. The checked-in rules and indexes must be reviewed before every production deploy.

## Local Firebase emulators and tests

Install the pinned Node dependencies once:

```sh
npm ci
```

Run the fast Firestore Rules and Hosting fallback tests:

```sh
npm run test:rules
npm run test:hosting
```

`test:rules` starts only the Firestore emulator for the isolated `demo-thesaurus` project. The Android integration suite needs both local Auth and Firestore and is always invoked through `emulators:exec`; this prevents it from falling through to production Firebase:

```sh
npx firebase emulators:exec --project demo-thesaurus --only auth,firestore \
  "./gradlew connectedDebugAndroidTest"
```

On Windows, quote the inner command for the current shell, for example:

```powershell
npx firebase emulators:exec --project demo-thesaurus --only auth,firestore ".\gradlew.bat connectedDebugAndroidTest"
```

For the full local release check, run `lintDebug`, `testDebugUnitTest`, `assembleDebug`, `npm run test:rules`, `npm run test:hosting`, and the emulator-wrapped `connectedDebugAndroidTest` command above. The instrumented suite covers household creation, starter taxonomy, invitations/membership, local pending writes, network recovery, and tombstone protection without using the production project.

## Create an emulator

In Android Studio open **Tools > Device Manager**, select **Create Virtual Device**, choose a phone profile, and install an **API 31 Google APIs x86_64** system image. Finish the wizard and start the device. Confirm that it is ready before running instrumentation tests:

```sh
adb devices
```

The device should be listed as `device`, not `offline`. The normal local suite needs only the API 31 emulator. API 37 is exercised by the scheduled or manually dispatched GitHub workflow.

## Build and install on a phone

1. Install the prerequisites and complete the Firebase/SHA setup above.
2. On the phone, open **Settings > About phone** and tap **Build number** seven times. Return to **Developer options** and enable **USB debugging**.
3. Connect the unlocked phone with a data-capable USB cable and accept its RSA debugging prompt.
4. Verify the connection:

   ```sh
   adb devices
   ```

5. Build the debug APK from the repository root:

   ```sh
   ./gradlew assembleDebug
   ```

   On Windows use `.\gradlew.bat assembleDebug`.

6. Install or update the APK while keeping its existing app data:

   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

7. Open **Thesaurus** from the device launcher and sign in with an account registered for the configured Firebase project.

`adb` is in the Android SDK's `platform-tools` directory (for example, `%LOCALAPPDATA%\Android\Sdk\platform-tools` on a default Windows installation). `./gradlew installDebug` is an equivalent convenience command when one device is connected. If `adb devices` shows `unauthorized`, unlock the phone and accept the prompt; if it shows no device, try a data-capable cable, another USB mode/port, or restart `adb` with `adb kill-server` followed by `adb start-server`. Windows devices may also require the manufacturer's OEM USB driver.

### Android Studio installation workflow

1. Open the repository root and wait for Gradle sync to finish with JDK 21.
2. Select the `app` run configuration.
3. Select the connected phone or the running API 31+ emulator.
4. Click **Run**. Android Studio builds, installs, and launches the same debug application.

## CI

GitHub Actions runs the standard **API 31** verification for pull requests targeting `master` and for pushes to `master`. It validates the wrapper, runs lint and JVM tests, Rules and Hosting tests, assembles the APK, and runs the Android suite inside Auth/Firestore emulators.

The scheduled run at 02:00 UTC performs the same checks and also runs the **API 37** job. A maintainer can request API 37 through **Run workflow** with `run_api_37` selected. Daily/API 37 jobs upload the debug APK and Android reports; the API 31 job uploads those artifacts on scheduled runs. CI is deliberately not a release-signing or Firebase-deployment workflow.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Google sign-in says configuration is required | Confirm `google-services.json`, package name, OAuth provider, and the installed APK's SHA-1/SHA-256 in Firebase. Uninstall old builds signed with a different key if necessary. |
| Emulator tests cannot connect | Start them only through `firebase emulators:exec`; use `demo-thesaurus`, not a production project. Android Emulator reaches the host at `10.0.2.2`. |
| `npm ci` or emulator startup fails | Use Node 22, remove only the generated local `node_modules` directory if needed, run `npm ci` again, and ensure Java is available for Firebase Emulator Suite. |
| Gradle cannot find SDK/API 37 | Install Platform 37 and Build Tools 37.0.0, set `ANDROID_HOME`/`ANDROID_SDK_ROOT` if using the CLI, then run the wrapper again. |
| Instrumented test has no device | Start an API 31 Google APIs emulator in Device Manager and confirm it appears in `adb devices`. |
| App links open in a browser | Deploy `hosting/.well-known/assetlinks.json` as JSON without redirects and include the SHA-256 of the APK signing certificate. |

## Family invitation links

Invitation URLs have the form `https://thesaurus-cef84.web.app/zaproszenie/{household}/{token}`. The manifest's HTTPS intent filter becomes a verified Android App Link only after `https://thesaurus-cef84.web.app/.well-known/assetlinks.json` is deployed with `application/json`, no redirects, and every relevant package/SHA-256 pair. Never invent a fingerprint: retrieve it from the actual signing certificate.
