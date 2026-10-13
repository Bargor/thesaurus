# Thesaurus

Architecture responsibility boundaries are documented in [the responsibility map](docs/responsibility-map.md).

Build variant sharing and isolated Firebase test lifecycles are documented in [the variant and fixture guide](docs/variant-test-fixtures.md).

Strict persisted-data validation and unavailable financial states are documented in [the Firestore decoding contract](docs/firestore-decoding.md).

Thesaurus is a Polish-language Android app for a shared household ledger. It keeps income and expenses in Firestore, works from Firestore's persistent local cache, and synchronizes queued changes once a connection returns. The visible app UI is intentionally Polish.

Entry cards in **Podsumowanie** period details include a small author footer inside the category-colored card. The complete author remains available to screen readers even when the visible value is shortened. The footer shares the card's existing edit action and permissions; date headings stay outside the cards. The main **Wpisy** list does not show author metadata.

The **Wpisy** tab is the ledger. **Podsumowanie** shows monthly cards for the selected year, newest first: December through January in past years and the current month through January in the current year, including empty months. Use the arrows to change the year; tap the year heading to switch to **Wszystkie lata**, showing yearly cards newest first for years containing eligible entries. Future-dated entries and periods are excluded. Changing the year or switching between months and years starts at the newest eligible card; returning to months restores the selected year. Each card shows exact PLN income, negative expenses, balance, an income/expense donut with labeled shares, and the category with the largest expenses. Empty periods have a neutral ring. Large text and unusually wide totals use a stacked layout to keep amounts readable. Tap a card for entries grouped by accounting date; owners and entry authors can open editable entries. Back returns to the overview at its saved scroll position. **Raporty** supports month, year, or custom dates, income/expense type filters, category and subcategory filters, category shares, trends, and an entry drill-down sorted by accounting date or signed amount in either direction. Report filters define one scope for totals, category shares, the annual trend, and entries. The global balance chart uses the selected period as its viewport and retains all household history. Household owners can invite a person by email and manage membership.

The **Przegląd** tab has its own month/year scope, with the shared period-control styling. Category tiles use saved category colors and show signed net totals; expand a category, then a subcategory to inspect its dated entries. Entries without a subcategory and historical entries with missing taxonomy remain visible. Owners and entry authors can open editable entries. Cached data and pending local writes work offline.

**Saldo konta** appears immediately above the main navigation on every household screen. Shared household and ledger listeners add the opening balance to the signed grosze of every undeleted household entry, including entries outside the displayed period and future accounting dates; screen filters, taxonomy archival, and sorting do not change it. Exact arbitrary-precision PLN amounts show an explicit positive sign, a negative sign, or neutral zero, with corresponding accessible descriptions. Local cached snapshots and pending writes update the same balance. Loading and errors display explanatory states rather than a made-up zero, and account or household changes clear the old amount before new data arrives. The compact footer stacks its label when needed; only amounts wider than the whole footer scroll horizontally at the normal text size, with a visible hint and the complete amount available to screen readers.

On `Wpisy`, sorting and status messages scroll with the ledger, including loading, empty and error states. The global settings gear stays in the header; the add button remains fixed above the shared balance/navigation, leaving a usable list viewport on short screens with large text.

`Wpisy` observes the complete active household ledger and initially exposes 20 entries. The existing show-more action reveals up to 20 more entries from this local ordering; it makes no additional Firestore request. Changing sort resets the visible prefix to 20. Both orders are descending: accounting date then creation timestamp, or creation timestamp then accounting date, with ascending entry ID as the final tie-breaker. Pending entries without a server creation timestamp use `Instant.MAX`. Live changes and cached snapshots recompute the ordering; tombstones disappear. Sorting presentation rows directly preserves category names/colors, author metadata, and management permissions in O(n log n), without an ID lookup per row.

This is deliberately local reveal, not Firestore cursor pagination. Rendering is bounded by the revealed prefix, but observation, row metadata, and sorting still scale with the full household history. Cursor paging would need a separate contract for both deterministic sort orders, live boundary changes, and offline/pending entries. The shared balance, summaries, and reports continue to observe complete history independently; the list's visible count and sorting do not limit their aggregates. An offline cache contains only history already synchronized to that device, so local reveal does not imply a complete server download.

### Global settings and opening account balance

The rightmost header gear opens **Ustawienia** from every authenticated household tab and nested screen. **Raporty** keeps its independent filter button immediately to the left of the gear. Each action has a separate 48 dp touch target and Polish screen-reader description. The settings hub links to **Kategorie**, **Rodzina**, **Ustaw stan konta**, and **Dane**. Back returns to the exact originating screen, retaining period/filter selections, scroll position, and unsaved entry or settings drafts. Selecting a main tab closes temporary settings screens instead of restoring them as a tab.

Only the household owner may change the account balance. **Stan początkowy** stores the entered signed PLN amount directly in `households/{householdId}.openingBalanceGrosze`; a missing legacy field means zero. Positive, negative, and zero balances are valid, with comma or dot decimals and correctly grouped spaces. The ± button makes a negative amount enterable even when the decimal keyboard has no minus key. The stored value must fit a signed 64-bit integer in grosze; aggregation uses arbitrary precision. A direct opening-balance change can be queued offline and is shown as pending until synchronization.

**Bieżący stan** treats the entered amount as the desired global balance now and computes `opening balance = desired balance − sum(all undeleted signed ledger entries)`. This includes every household author, accounting date, and category, without report filters or pagination. It requires a complete synchronized server query, refuses pending or malformed data, and checks a server-enforced ledger revision before and after the query and in the final transaction. Concurrent ledger/settings changes reject the save with a retryable Polish error; an offline current-balance operation is not queued using an incomplete cache. Neither mode creates, edits, or deletes a ledger entry, so period income/expense totals, filtered summaries, and the monthly-result trend remain unchanged. The shared footer and the absolute **Saldo w czasie** chart include the opening balance; an opening-only household has a flat chart.

Deployment compatibility: every ledger mutation now atomically increments `ledgerRevision`, and security rules require that increment. The new rules therefore reject ledger writes from older APKs. Coordinate the rules and updated APK rollout; do not deploy these rules independently while older clients must continue writing. Existing households need no backfill (absent opening balance/revision defaults to zero). Development and integration tests use local Firebase emulators; this feature does not automatically deploy production rules.

In **Raporty**, use the filter icon at the top right to choose the period, entry type, sorting, category, dependent subcategory, and one or more household members. Sort by accounting date or signed amount and use the adjacent arrow to switch direction. All changes are drafts until **Zastosuj**; **Anuluj**, Back, or tapping outside the window discards them. **Wyczyść** resets the draft filters and sorting to the current month, all entries and members, and accounting date descending; Apply is still required. The filter icon marks any applied nondefault filter or sorting. Sorting only reorders the selected entries, keeping totals and charts unchanged, and the applied sort survives state restoration and works offline. Member selection defaults to everyone; **Wyczyść wybór** selects nobody and produces an empty report when applied. Former members remain selectable through their retained entries. Filters use immutable author IDs, work on locally cached data offline, and restore only the applied selection for the same household. There is no tag filter.

Reports pair each donut segment with a compact, single-line category row: matching color swatch and name on the left, with the exact PLN amount and share beside each other on the right. Amounts and percentages each align vertically along their shared right edge. All rows use shared columns measured from the rendered text. When long names, large amounts, or increased text/display scaling cannot fit, the whole legend scrolls horizontally together, keeping every value readable. The chart keeps distinguishable category colors and resolves duplicate or similar colors locally using a deterministic palette that extends with additional hues and tones instead of cycling. Assignment uses category identifiers, so recomposition or changes to entry ordering do not reshuffle colors. Category rows retain report order (largest amount first); persisted category colors and entry-card accents are unchanged. With very many categories, color differences necessarily become smaller, so visible names and screen-reader descriptions also identify every segment.

Balance and monthly-result charts reserve only the measured Y-label width rather than a fixed fraction of the screen. Their data plots and date axes share the remaining width; compact PLN ticks keep large amounts readable while accessibility retains exact values. See [Report chart layout](docs/report-chart-layout.md) for responsive-layout and CI-evidence details.

Reports show **Saldo w czasie** directly below the category chart and legend, before the existing annual monthly trend. The step chart shows the global household balance as of each accounting date: all undeleted household entries contribute, irrespective of the report's type, category, subcategory, member filters, and sorting. The selected period defines the chart viewport; entries before it contribute the opening balance, and entries after its end are excluded. Thus a period without new entries retains a flat historical balance. Totals, category shares, the existing annual trend, and the entry list still use report filters. Months and inclusive custom ranges of at most 62 days use daily buckets; years and longer custom ranges use calendar months. Partial months and days without activity are retained. Dates are accounting `LocalDate` values without timezone conversion.

The chart has signed PLN amount ticks, a zero reference line, and accounting date labels. Measured labels avoid collisions, showing a midpoint date when space permits. Very large tick amounts use Polish compact units or scientific notation, while the final visible value and screen-reader descriptions retain exact PLN amounts. Every inclusive bucket's dates and exact cumulative balance are exposed to screen readers without extra controls or a dialog. A household without entries through the period end and with zero opening balance shows an explanation without a line. To keep extreme custom date ranges responsive, ranges exceeding 1200 buckets compress consecutive empty buckets into inclusive zero-change spans while preserving every active bucket and its exact balance; the chart places those spans according to their actual calendar duration. This bounds work by ledger size instead of the number of empty years, without sampling or rounding financial values.

The existing **Trend miesięczny** remains a chart of each active month's result under the applied report filters, rather than a cumulative balance: all entries show income minus expenses, while expense-only reports show positive expense magnitudes. It now shares the measured PLN amount axis and zero reference with the global chart, with Polish month-and-year labels instead of a joined text legend. Connected points use their real chronological spacing, including gaps between active months. A single active month has one centered point. Exact signed monthly values remain available to screen readers.

### Entry category and subcategory selection

Both **Nowy wpis** and entry editing use compact category and optional subcategory dropdowns. Tap anywhere on a field to open a scrollable menu; choosing an option closes it. The subcategory field shows active options from the selected category and includes **Bez podkategorii** to clear the selection. It appears after choosing a category; categories without active subcategories show a disabled **Bez podkategorii** field and an empty-state hint. An archived historical subcategory remains visible and can be cleared even without active replacements, but cannot be newly selected. Category colors and a checkmark identify the current selection. Back or an outside tap dismisses either menu without changing the selection. Archived historical categories remain visible when editing but cannot be newly selected.

All active categories are available for both income and expense. Choosing a different category applies its default direction, clears the previous subcategory, and closes its menu; changing direction manually preserves the valid category and subcategory. The menus use the user's saved category order, existing subcategory order, and locally cached data offline. The form draft survives state restoration for the same household, account, and entry, but open menus are not restored. Pending saves retain their identifier so retrying a restored draft does not create a duplicate entry.

### Category ordering

Open **Ustawienia → Kategorie** to arrange top-level categories. Long-press anywhere on a category card and drag it, or expand it and use **Przenieś wyżej / Przenieś niżej** under **Kolejność kategorii**. Expanded cards also contain editing and archive actions. The same order is used by category management and by both the new-entry and edit-entry forms; subcategories always remain attached to their parent and keep their own alphabetical order.

The preference belongs to one user in one household and is synchronized through Firestore, including its persistent offline queue, so another household member can choose a different order. Without a saved preference, the repository's deterministic alphabetical order is used. Once an order exists, new categories are appended. Archiving does not remove a category from the saved sequence, and restoring it returns it to its former position. Stale identifiers are ignored safely and categories absent from an older preference are appended without changing or deleting taxonomy documents.

### Offline indicator

A disconnected-plug icon in the top-right corner indicates that Android has no validated internet connection. Tap it for a Polish explanation; the tooltip closes after five seconds or on the next touch, which still activates the control underneath. Navigation and reconnection also dismiss it. The shared 48 dp header remains reserved when online, so the icon never covers screen actions and connectivity changes do not move content.

This indicator is independent of Firestore snapshot/cache metadata and pending writes. Pending-sync notices and actionable errors remain visible. In developer mode, stopping the local Firebase emulators does not by itself mean the device has lost internet, and a local emulator connection does not imply internet access. The app still uses Firestore's existing offline queue; the indicator does not enable, disable, or retry synchronization.

## Household observation architecture

Summary, overview, reports, and entry screens reuse a household read model and entry-presentation helpers. Each ViewModel owns its listener group; account/household changes cancel obsolete subscriptions. Historical taxonomy, cached values, pending writes, and errors remain explicit. See [Household observation ownership](docs/household-observation.md) for the subscription, retry, and stricter balance contracts.

Reports refresh calendar boundaries at local midnight while resumed and immediately when returning to a retained screen. Explicit months, years, custom ranges, filters and sorting remain selected; only eligibility through today and navigation/default limits advance. See [Reports calendar refresh](docs/report-date-refresh.md) for rollover, time-zone, lifecycle and test contracts.

## Requirements

Import/export is documented in [Import/export contracts](docs/data-transfer-contracts.md). Open **Ustawienia → Dane** to export entries as CSV, export a complete JSON backup, or import either format. Every export asks you to confirm that the destination file will contain private financial information. Android's system document picker creates or selects the file; the app does not request broad storage permissions. Suggested filenames identify the format/content and export date.

For third-party CSV files, use the [CSV format guide](docs/csv-format.md) and [editable UTF-8 template](docs/templates/thesaurus-entries.csv). The guide lists the stable English columns, exact supported header/type aliases, date and amount formats, quoting, JSON tags, limits, and duplicate rules. CSV transfers entries only; use JSON for opening balance, taxonomy, archives and deletion history. Files are sensitive financial data, not account or authentication backups.

Any household member may export a complete synchronized server snapshot. Import is available only to the household owner and requires synchronization with the server; offline, pending, or unavailable data cannot authorize an import. Selecting a CSV opens mapping, validation and repair; selecting JSON opens a read-only merge preview. Selecting or restoring a file never writes ledger records: CSV import/repair and JSON confirmation require explicit actions. Cancellation returns to **Dane** without database changes. Document-provider failures, including denied access and failed writes, show an error rather than a successful export; a provider may leave an incomplete destination file, which should not be treated as a backup.

In a DEV build, open the developer tools and choose **Symulacja importu CSV** to exercise automatic/manual mapping, errors, repairs and the final summary. This uses synthetic data and an in-memory backend: it does not change the household you use for manual testing. **Ustawienia → Dane** instead uses the actual DEV household and system file picker. [Visual evidence for #112](docs/visual-proofs/issue-112/README.md) includes the repair workflow and enlarged-font mapping screen.

Choose **Symulacja kopii JSON** to exercise the full-backup preview, explicit confirmation, conflicts, partial failure/retry, repeated import and export. This simulation also writes only synthetic memory and needs no Firebase services. Real JSON files are saved or selected through **Ustawienia → Dane**. [Visual evidence for #113](docs/visual-proofs/issue-113/README.md) covers preview, summary and large-font confirmation.

JSON imports require the updated Firestore rules in this change to create archived taxonomy, tombstones and current-member authors. No production rules are deployed automatically. Deploy the reviewed rules before enabling JSON import in a production APK; ordinary member permissions, immutable authorship and tombstone protection remain enforced.

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

## Developer mode on Windows

`devDebug` is a separate installable app, **Thesaurus DEV** (`pl.bargor.thesaurus.dev`). It uses only the `demo-thesaurus` Firebase project and routes Authentication to `10.0.2.2:9099` and Firestore to `10.0.2.2:8080`. Its checked-in `app/src/devDebug/google-services.json` contains fake emulator identifiers. The normal `debug` and `release` builds keep Google login and the production Firebase configuration. **Never enter real credentials or production data in the DEV app.** The DEV app is intended for an Android emulator; `10.0.2.2` will not reach the host from a physical phone.

### One-command DEV launch

Install JDK 21, Node.js 22 or later, the Android SDK packages from **Requirements**, and the Android Emulator. Create an API 31+ Google APIs AVD as described in **Create an emulator**. Make sure `JAVA_HOME` points to JDK 21. Install the repository's pinned Firebase CLI once with `npm ci`; a global Firebase CLI, Firebase login, and the separate `android` CLI are not required by the launcher.

From PowerShell in the repository root:

```powershell
.\scripts\start-dev.ps1 -AvdName thesaurus_api31
```

Use your own AVD name from Android Studio Device Manager (or `emulator -list-avds`) instead of `thesaurus_api31`. The launcher checks prerequisites, starts or reuses that Android emulator and the local `demo-thesaurus` Auth/Firestore services, waits until they are ready, builds `devDebug`, installs its APK with `adb install -r` on the selected emulator only, and opens **Thesaurus DEV**. It never installs on a connected phone, deploys Firebase configuration, wipes the AVD, uninstalls the app, or clears app data. A wrong project, occupied incompatible ports, or failed readiness/build/install stops the launch with an error instead of falling back to production.

If your SDK is not at the standard location, pass its path explicitly:

```powershell
.\scripts\start-dev.ps1 -AvdName thesaurus_api31 -SdkPath 'D:\Android\Sdk'
```

If `local.properties` already defines `sdk.dir`, it must agree with the selected SDK; the launcher refuses conflicting paths rather than having Gradle silently build against a different installation.

After launch, sign in using the fake account described below. Existing emulator services and app data are reused; the first launch of a new Firebase process starts with empty temporary server data unless you provide a previously exported DEV snapshot. New Android emulator launches cold-boot without loading or saving Quick Boot snapshots; their AVD disk and app data are preserved. Use `-SoftwareRendering` if host GPU startup fails; this applies only to a newly started emulator, not to a running device or saved AVD settings.

See the launcher's help (`Get-Help .\scripts\start-dev.ps1 -Detailed`) for options. Readiness defaults to 300 seconds per service (`-TimeoutSeconds` changes this); the Gradle build has its own 30-minute limit. Logs and per-invocation ownership records are in the ignored `build\dev-launcher` directory. Helpers start in the background; the Android emulator window is visible. Closing the launcher does not stop those services, including after a build/readiness failure. Reused services retain their original ownership; recorded PIDs are informational and must not be blindly used later to stop a possibly unrelated process.

To preserve synthetic Firebase data before stopping a suite, export it while it is running to a **new** local directory:

```powershell
npx --no-install firebase emulators:export .\app\build\my-dev-export --project demo-thesaurus --config .\firebase.json
```

Afterwards, stop only the Firebase process you started (for a manual foreground suite, use Ctrl+C in its terminal; for a launcher-started suite, identify the live Node process by its exact command line and session record before ending it). The exported snapshot remains available. On a later start:

```powershell
.\scripts\start-dev.ps1 -AvdName thesaurus_api31 -FirebaseImportPath .\app\build\my-dev-export
```

Import is applied only when starting a new suite; it never replaces data in a running suite. Do not use real-account or production snapshots. `app\build` is ignored but may be removed by Gradle clean; keep any snapshot you need long-term in a private location outside generated build directories and outside version control. Do not confuse preserving app data during installation with persisting Firebase's in-memory data across a computer restart.

If PowerShell blocks execution of the checked-in script, use a trusted-repository invocation without changing machine-wide policy:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-dev.ps1 -AvdName thesaurus_api31
```

### Manual launch (alternative)

From PowerShell in the repository root, install dependencies and start Auth and Firestore in one terminal. Leave it running:

```powershell
npm ci
npx --no-install firebase emulators:start --project demo-thesaurus --only auth,firestore
```

Start an Android emulator (API 31 or newer). In a second PowerShell terminal, build and install the DEV app:

```powershell
.\gradlew.bat :app:assembleDevDebug :app:installDevDebug
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell am start -n pl.bargor.thesaurus.dev/pl.bargor.thesaurus.MainActivity
```

On the Polish login screen, keep the prefilled `owner@example.test` and `dev-password-123`, then tap **Zaloguj do emulatora**. The first login creates that fake account. Create a household to become its owner. Tap **Zmień konto testowe** to sign out; enter `member@example.test` with the same test password to create a second fake account. To test membership, create an invitation for `member@example.test` as the owner, copy its URL, sign in as the member, then open the URL explicitly in the DEV app (replace `<invitation-url>` with the copied URL):

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell am start -n pl.bargor.thesaurus.dev/pl.bargor.thesaurus.MainActivity -a android.intent.action.VIEW -d "<invitation-url>"
```

For focused DEV tests, run this command with the Android emulator booted. `-PdevTest=true` selects `devDebug` for Android unit and instrumentation test tasks; without it, existing `testDebugUnitTest` and `connectedDebugAndroidTest` remain the default:

```powershell
npx --no-install firebase emulators:exec --project demo-thesaurus --only auth,firestore ".\gradlew.bat -PdevTest=true :app:testDevDebugUnitTest :app:connectedDevDebugAndroidTest"
```

To reset both test accounts and Firestore data, stop `emulators:start` with Ctrl+C and restart it without `--import`; the emulators do not persist data by default. Clear the DEV app's saved Auth session and Firestore cache too:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell pm clear pl.bargor.thesaurus.dev
```

## Local Firebase emulators and tests

Install the pinned Node dependencies once:

```sh
npm ci
```

Run the fast Firestore Rules and Hosting fallback tests:

```sh
npm run test:rules
npm run test:hosting
npm run test:variants
npm run test:project-safety
```

`test:rules` starts only the Firestore emulator for `demo-thesaurus-rules`. The Android integration fixtures use `demo-thesaurus-integration`, named SDK instances, fake identifiers and literal local emulator endpoints. **Separate project names alone do not isolate an Authentication emulator process. Do not connect integration fixtures to the running DEV services.** With the default ports free, the suite needs both Auth and Firestore and is invoked through `emulators:exec`:

```sh
npx firebase emulators:exec --project demo-thesaurus-integration --only auth,firestore \
  "./gradlew connectedDebugAndroidTest"
```

On Windows, quote the inner command for the current shell, for example:

```powershell
npx firebase emulators:exec --project demo-thesaurus-integration --only auth,firestore ".\gradlew.bat connectedDebugAndroidTest"
```

To test while keeping interactive DEV services/data intact, start the separate process using `firebase.integration.json` (Auth 9199, Firestore 8180, separate hub/logging ports), then pass **both** port arguments. The fixture rejects mixed or arbitrary endpoints; CI retains its existing defaults.

```powershell
npx --no-install firebase emulators:start --project demo-thesaurus-integration --only auth,firestore --config firebase.integration.json
```

In a second terminal, run all import/export integration tests:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.package=pl.bargor.thesaurus.data.transfer' `
  '-Pandroid.testInstrumentationRunnerArguments.firebaseIntegrationAuthPort=9199' `
  '-Pandroid.testInstrumentationRunnerArguments.firebaseIntegrationFirestorePort=8180'
```

Omit the package argument to run the full Android suite against these isolated services. Stop only this integration process afterwards; do not clear the DEV app or restart its Firebase services.

Final transfer regressions include real cross-household CSV/JSON export→preview→import, exact signed money and taxonomy, partial repairs/discards, interrupted import/resume without duplicate writes, and owner/member permission checks. `TransferRoundTripTest` also imports the published CSV template, checks reimport deduplication and extreme `Long` amounts across four locales. Android tests check canonical bytes under Polish and Arabic configurations and mapping, repair, summaries and export privacy at 320dp with 200% font scale.

For the full local release check, run `lintDebug`, `testDebugUnitTest`, `assembleDebug assembleRelease assembleDevDebug`, `npm run test:rules`, `npm run test:hosting`, `npm run test:variants`, `npm run test:project-safety`, and the emulator-wrapped `connectedDebugAndroidTest` command above. CI also runs `-PdevTest=true testDevDebugUnitTest compileDevDebugAndroidTestKotlin` to check developer test source wiring without executing DEV instrumentation or changing interactive DEV data. The instrumented suite covers household creation, starter taxonomy, invitations/membership, local pending writes, network recovery, and tombstone protection without using the production project.

## Create an emulator

In Android Studio open **Tools > Device Manager**, select **Create Virtual Device**, choose a phone profile, and install an **API 31 Google APIs x86_64** system image. Finish the wizard and start the device. Confirm that it is ready before running instrumentation tests:

```sh
adb devices
```

The device should be listed as `device`, not `offline`. The normal local suite needs only the API 31 emulator. API 37 is exercised by the scheduled or manually dispatched GitHub workflow.

## Build and install on a phone

Use the ordinary **`debug`** build (`pl.bargor.thesaurus`), not **`devDebug`**. A physical phone needs Android 12 / API 31 or newer, internet access, and Google sign-in for the configured Firebase project. It does **not** need the local Firebase emulators or Node.js. The checked-in DEV launcher is intentionally emulator-only.

1. Install JDK 21 and the Android SDK prerequisites above. Set `JAVA_HOME` to JDK 21 and configure the SDK in Android Studio or `local.properties`. The checked-in `app/google-services.json` selects the existing Firebase project; for your own project, follow **Firebase setup** first. Installing an APK does not deploy Firestore rules or indexes: the project owner must separately ensure that the reviewed server configuration is compatible with this app version.
2. On the phone, open **Settings > About phone** and tap **Build number** seven times. Return to **Developer options** and enable **USB debugging**.
3. Connect the unlocked phone with a data-capable USB cable and accept its RSA debugging prompt.
4. In PowerShell at the repository root, locate ADB and list devices:

   ```powershell
   $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
   & $adb devices -l
   ```

   For a custom SDK location, replace `$adb` with that SDK's `platform-tools\adb.exe`. Copy the phone's serial from the first column and use it below as `<PHONE_SERIAL>`; do not choose an `emulator-...` entry. Its state must be `device`, not `unauthorized` or `offline`. Explicit serial selection lets the emulator remain running without accidentally installing on it.

5. Build the ordinary debug APK and display its signing fingerprints:

   ```powershell
   .\gradlew.bat :app:assembleDebug :app:signingReport
   ```

   The APK is `app\build\outputs\apk\debug\app-debug.apk`. For Google login, the Firebase project owner must register the **debug variant's actual SHA-1 and SHA-256** in Firebase and the corresponding package/certificate restriction on the client API key. Use fingerprints from this machine's signing report, not another developer's or CI's certificate. The Android package is `pl.bargor.thesaurus`. See **Firebase setup**; do not remove API-key restrictions to make login work. Download an updated client configuration and rebuild if the Firebase setup requires it.

6. Install or update the APK while keeping its existing app data:

   ```powershell
   & $adb -s '<PHONE_SERIAL>' install -r 'app\build\outputs\apk\debug\app-debug.apk'
   ```

7. Open the application explicitly (or tap **Thesaurus** in the phone launcher):

   ```powershell
   & $adb -s '<PHONE_SERIAL>' shell am start -W -n pl.bargor.thesaurus/pl.bargor.thesaurus.MainActivity
   ```

   Sign in with Google and create or join a household. This is the real configured Firebase backend, not the synthetic DEV household. Data entered here is not imported automatically from the emulator.

For later updates, repeat the build and `install -r` with the **same signing key**. Do not uninstall or run `pm clear` as an update workaround: both erase local app data and may lose unsynchronized entries. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means the installed app and new APK have different signing certificates; recover/use the original signing key or resolve the data-migration plan before uninstalling. `assembleRelease` alone produces an unsigned release APK with this repository's current configuration; use the debug APK above for direct personal testing, or separately configure release signing before distributing a release build.

On macOS/Linux, replace `.\gradlew.bat` with `./gradlew` and use `adb` from your SDK's `platform-tools` directory with the same `-s <PHONE_SERIAL>` selection. The USB steps follow [Android's hardware-device guide](https://developer.android.com/studio/run/device) and [ADB documentation](https://developer.android.com/tools/adb).

`adb` is in the Android SDK's `platform-tools` directory (for example, `%LOCALAPPDATA%\Android\Sdk\platform-tools` on a default Windows installation). `./gradlew installDebug` is an equivalent convenience command when one device is connected. If `adb devices` shows `unauthorized`, unlock the phone and accept the prompt; if it shows no device, try a data-capable cable, another USB mode/port, or restart `adb` with `adb kill-server` followed by `adb start-server`. Windows devices may also require the manufacturer's OEM USB driver.

### Android Studio installation workflow

1. Open the repository root and wait for Gradle sync to finish with JDK 21.
2. Select the `app` run configuration.
   In **Build Variants**, select **debug** for a phone, not **devDebug**. Google login still needs the correct Firebase signing fingerprints above.
3. Select the connected phone or the running API 31+ emulator.
4. Click **Run**. Android Studio builds, installs, and launches the same debug application.

## CI

GitHub Actions runs the standard **API 31** verification for pull requests targeting `master` and for pushes to `master`. It validates the wrapper, runs lint and JVM tests, Rules and Hosting tests, assembles the APK, and runs the Android suite inside Auth/Firestore emulators.

The scheduled run at 02:00 UTC performs the same checks and also runs the **API 37** job. A maintainer can request API 37 through **Run workflow** with `run_api_37` selected. Daily/API 37 jobs upload the debug APK and Android reports; the API 31 job uploads those artifacts on scheduled runs. CI is deliberately not a release-signing or Firebase-deployment workflow.

Instrumentation streams TestRunner start/finish events live and uploads a separate diagnostics artifact on every run, including filtered and recent logcat, activity/window/process snapshots, the last ANR report, and available Firebase logs. `scripts/ci-instrumentation.sh` limits Gradle to 20 minutes with a 15-second termination grace, fails if no TestRunner event arrives within 5 minutes or progress stops for 3 minutes, and bounds each diagnostic adb command to 10 seconds. The last started/finished event helps distinguish a stuck test from teardown. Positive-integer `CI_INSTRUMENTATION_TIMEOUT_SECONDS`, `CI_INSTRUMENTATION_START_GRACE_SECONDS`, `CI_INSTRUMENTATION_PROGRESS_TIMEOUT_SECONDS`, `CI_INSTRUMENTATION_TERM_GRACE_SECONDS`, `CI_INSTRUMENTATION_POLL_SECONDS`, and `CI_INSTRUMENTATION_ADB_TIMEOUT_SECONDS` overrides support short local checks. Set `ANDROID_SERIAL` explicitly; CI uses `emulator-5554`. The workflow step and job have additional 25- and 45-minute limits.

Run `npm run test:ci-instrumentation` on Linux to verify the CI wrapper with mocked adb and launcher processes; both CI jobs run this check after installing the Node dependencies.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Google sign-in says configuration is required | Confirm `google-services.json`, package name, OAuth provider, and the installed APK's SHA-1/SHA-256 in Firebase. Uninstall old builds signed with a different key if necessary. |
| Emulator tests cannot connect | Use `firebase emulators:exec` with `demo-thesaurus-integration` for normal Android integration tests, `demo-thesaurus-rules` for Rules, or `demo-thesaurus` for focused DEV tests. Never use a production project. Android Emulator reaches the host at `10.0.2.2`. |
| `npm ci` or emulator startup fails | Use Node 22, remove only the generated local `node_modules` directory if needed, run `npm ci` again, and ensure Java is available for Firebase Emulator Suite. |
| Gradle cannot find SDK/API 37 | Install Platform 37 and Build Tools 37.0.0, set `ANDROID_HOME`/`ANDROID_SDK_ROOT` if using the CLI, then run the wrapper again. |
| Instrumented test has no device | Start an API 31 Google APIs emulator in Device Manager and confirm it appears in `adb devices`. |
| App links open in a browser | Deploy `hosting/.well-known/assetlinks.json` as JSON without redirects and include the SHA-256 of the APK signing certificate. |

## Family invitation links

Invitation URLs have the form `https://thesaurus-cef84.web.app/zaproszenie/{household}/{token}`. The manifest's HTTPS intent filter becomes a verified Android App Link only after `https://thesaurus-cef84.web.app/.well-known/assetlinks.json` is deployed with `application/json`, no redirects, and every relevant package/SHA-256 pair. Never invent a fingerprint: retrieve it from the actual signing certificate.
