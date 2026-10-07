# Build variants and Firebase test fixtures

## One canonical implementation, separate build outputs

`app/src/productionShared/java` holds the four implementations shared by `debug` and `release`: sign-in content, developer-tools content, the Hilt authentication binding, and the Firebase backend. These variants still use Google authentication and the existing production configuration; they expose no developer controls. Their application ID remains `pl.bargor.thesaurus`.

`devDebug` retains its independent implementations in `app/src/devDebug/java`, fake configuration, emulator-only email authentication, developer controls, and `pl.bargor.thesaurus.dev` application ID. `initWith(debug)` and dependency fallbacks do not substitute production sources for DEV sources.

Canonical Kotlin inputs are staged by a cacheable Gradle task into a distinct AGP-owned generated directory for each component. The task is registered through AGP's Kotlin source API, so compilation depends on its producer. KSP 2.3.9's built-in Kotlin integration reads static roots rather than these generated roots; the same producer-backed file tree is therefore added explicitly to the matching component's KSP processing/resolution inputs. This bridge never consumes KSP's own outputs or another variant's source tree. Canonical input directories are not registered as shared Android Studio content roots. Edit the canonical files, not `app/build/generated` copies; normal builds regenerate them. Pure fixture policy and cleanup code in `app/src/sharedTest/java` is similarly staged into each host/device test component, never into an application APK.

`npm run test:variants` checks source selection and developer/production isolation. It supplements, rather than replaces, real AGP/Hilt compilation. CI assembles all three APK variants, runs debug and DEV JVM tests, compiles DEV instrumentation, and executes the normal debug instrumentation suite. API 37 receives the same coverage on scheduled/manual runs.

## Emulator namespaces

| Consumer | Project | Access |
| --- | --- | --- |
| Interactive DEV app and focused DEV login test | `demo-thesaurus` | Auth and Firestore emulators; no real credentials |
| Normal Android integration fixtures | `demo-thesaurus-integration` | Named SDK instances; Auth `10.0.2.2:9099`, Firestore `10.0.2.2:8080` |
| Firestore Rules suite | `demo-thesaurus-rules` | Firestore emulator at `127.0.0.1:8080` |
| Hosting fallback test | `demo-thesaurus` | Static Hosting emulator only; no Auth/Firestore mutations |

The Android policy rejects every other project, endpoint, port, or credential. Its identifiers are explicitly fake. Each fixture gets a UUID-qualified app name; scenario users and households are independently created. It never selects the default Firebase app. Rules tests validate their project before initializing the test environment; an optional `THESAURUS_RULES_PROJECT_ID` may only select `demo-thesaurus-rules` or a lowercase alphanumeric/hyphen suffix beneath that namespace. Production, DEV, and Android integration projects are rejected.

Run each suite with the matching CLI project so the emulator loads the project's security rules. Do not run isolated suites while an interactive DEV emulator process already owns the same ports; stopping a DEV process or resetting its data is a separate, explicit user action. The fixtures do not clear an emulator database or delete another app's accounts/cache.

## Owned lifecycle and bounded waits

`FirebaseIntegrationFixture.open(label)` creates one named app, configures emulator-only Auth and persistent Firestore before exposing them, and owns a `ViewModelStore`. Use `scenario(label)` inside `try/finally`, with `close()` in `finally`. Scenario async work belongs to its structured coroutine scope, not an unrelated caller job. The migrated tests retain their existing assertions and data scenarios.

| Stage | Bound |
| --- | --- |
| Fixture setup | 10 seconds |
| Complete scenario | 90 seconds |
| Operation, network transition, or flow wait | 15 seconds by default; explicitly capped at 90 seconds |
| Each cleanup stage | 10 seconds |

Labelled operation timeouts fail with `FixtureTimeoutException`; external coroutine cancellation is preserved. Cleanup runs in a non-cancellable context but each suspending stage has its own timeout. It attempts every stage even if an earlier stage fails:

1. Clear owned ViewModels on the main dispatcher.
2. Restore the named Firestore connection and await queued writes.
3. Sign out the named Auth instance.
4. Terminate the named Firestore instance and clear only its persistence.

Cleanup errors are suppressed on the original scenario failure, or fail an otherwise successful test. Closing twice is harmless; subsequent fixture access is rejected. Timeouts cannot forcibly interrupt synchronous SDK calls, but suspending network operations and dispatcher waits are bounded.

Firebase Auth provides no public shutdown/join for its queued worker callbacks. Deleting a named `FirebaseApp` can race those callbacks. The signed-out, inactive registration therefore remains until the instrumentation process exits; this is an intentional lifecycle exception, not permission to retain active ViewModels or Firestore connections. Partial setup failures also clean only the named instance. No production rules are deployed by this workflow.

New JVM coverage checks project/endpoint/credential guards, fixture names, failure/timeout cleanup, cancellation, and sibling-job isolation. Real-emulator coverage checks independent Auth/cache/store ownership, household permission isolation, failed offline scenarios, pending-write cleanup, main-thread ViewModel disposal, and idempotent closure.
