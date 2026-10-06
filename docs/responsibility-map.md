# Responsibility boundaries

This is a behavior-preserving decomposition of the single application module. Existing package
names, public repository/screen contracts, Hilt bindings, Polish resources and persisted schema
are retained. Shared household observation and exact PLN helpers remain the sources of truth.

## Firebase

- `RepositoryContracts.kt`: repository interfaces, onboarding identities/results and Firestore paths.
- `FirebaseFactories.kt`: authentication/Firestore setup and local emulator wiring.
- `FirestoreDocumentCodec.kt`: existing document serialization/deserialization policies.
- `FirestoreObservations.kt`: snapshot adapters and ordered taxonomy observation.
- `FirestoreRepositories.kt`: injected compatibility facade for all existing callers.
- `FirestoreUserRepository.kt` / `FirestoreHouseholdRepository.kt`: profiles and membership.
- `FirestoreLedgerRepository.kt`: entries, atomic ledger revision writes and tombstones.
- `FirestoreTaxonomyRepository.kt`: categories, subcategories and user category ordering.
- `FirestoreInvitationRepository.kt`: invitation observation and acceptance batch.
- `FirestoreOnboardingRepository.kt`: idempotent first-household transaction and taxonomy seeding.
- `FirestoreOpeningBalanceRepository.kt`: opening balance and revision-fenced current-balance preparation/commit.

Transaction and batch bodies retain their original boundaries. Balance preparation/commit hooks
remain available through the facade. Codec coercions, defaults and error behavior are intentionally
unchanged; stricter decoding is a separate issue (#96), not hidden in this structural change.

## App navigation

- `ThesaurusApp.kt`: authentication state, invitation dispatch and the single offline-status host.
- `HouseholdNavigation.kt`: household scaffold, main-tab stacks, transient settings/forms and navigation bar.
- `HouseholdFeatureRoutes.kt`: explicit feature actions, ViewModel observation and Hilt owner bindings.
- `AuthenticationContent.kt`: sign-in, onboarding and invitation screen bindings.
- `AppPlatformEffects.kt`: network monitor, Android share chooser and Activity lookup.

Existing root-package entry points used by previews and tests are retained. Route effects still
run at the same navigation destinations with the same keys. Creation navigation is required for
new entries; production edit routes explicitly pass no creation callback and stay on their form.

## Reports and taxonomy

- `ReportsScreen.kt` / `TaxonomyScreen.kt`: composition, lifecycle and explicit feature wiring.
- `ReportUiState.kt`: report state/draft contracts and existing date-bound calculations.
- `ReportsViewModel.kt`: filtering, aggregation, saved state and household orchestration.
- `ReportFilterDialog.kt` / `ReportPeriodNavigator.kt`: draft selectors, members, dates and period controls.
- `ReportCategoryChart.kt` / `ReportMonthlyTrendChart.kt`: existing chart/legend presentation.
- `ReportEntryCard.kt`: drill-down row presentation.
- `TaxonomyCategoryCard.kt`: category/subcategory rows and existing drag visuals/gesture hooks.
- `TaxonomyEditorDialog.kt`: name/direction/color editor.
- `TaxonomyDragState.kt`: screen-owned preview, nearest target, drop commit and cancellation reset.

No new module, framework or inherited base ViewModel is introduced. Rendering bodies, semantics,
test tags, chart geometry, saved-state keys and drag animations stay unchanged. Midnight refresh
behavior belongs to the separate date-boundaries issue (#97).

## Verification and visual evidence

Focused additions cover document serialization, navigation callback origins, Android share dispatch
and drag cancellation followed by a new drop. Existing repository/offline, navigation, report and
drag suites remain in CI. Local test/build execution is intentionally omitted at the user's request.

`ResponsibilitySplitVisualEvidenceTest` renders real components/navigation with deterministic,
synthetic data, without Firebase or real authentication. It captures six PNG frames: entries,
reports, report filters, settings, expanded categories and the category editor. The CI harness
optionally pulls these from the disposable emulator into `ci-instrumentation/visual-evidence/issue94`.
Collection is bounded and cannot hide a failing test exit status. API 31/37 diagnostics artifacts
include the images for review; the PR will attach the verified images after CI completes.
