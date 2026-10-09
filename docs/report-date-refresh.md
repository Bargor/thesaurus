# Reports calendar refresh

Reports use the injected `Clock` and its `zone` as their defined calendar time zone. Accounting dates remain `LocalDate` values; no entry date is rewritten or converted through a timestamp. Other tabs and global-balance semantics are unchanged.

## Selected-period policy

The selected month, year, or inclusive custom range stays fixed across midnight, month-end, and year-end. A report that was displaying September remains September on October 1; the next-month action becomes available, and resetting filter defaults now selects October. A selected current year likewise remains selected after New Year rather than silently changing the user's scope.

The refreshed day caps the effective report end. Within the same selected month/year, newly eligible entries appear in totals, category shares, trends, balance viewport, and entry list together, without a new repository emission. Historical periods, category/subcategory/member/type filters, sort direction, pending dialog drafts, and explicit custom text are preserved. A custom end date does not automatically extend with every new day.

Custom-date validation, next-period limits, default reset values, and the state exposed to the screen use the refreshed day. If the clock moves backward, the effective range is capped at the new today; an entirely future selected range returns empty financial results and no balance chart. Selection/input remains intact, but applying a new future custom range is still rejected.

## Lifecycle and ownership

`ReportsCalendarEffect` registers a lifecycle observer using `DisposableEffect`, keyed by lifecycle, household, and ViewModel. The actual Reports destination owns a timer only while **RESUMED**. Pausing, stopping, leaving composition, or clearing the ViewModel cancels its work; disposal also removes the lifecycle observer. Household changes cancel the previous household's timer and source subscriptions.

Foreground ownership is separate from the existing ledger observation. Its household intent can be recorded before the route's `LaunchedEffect` calls `start`, preventing an initial-registration ordering race. Returning to a retained screen refreshes the same ViewModel immediately; retry/source startup also refreshes before a same-household early return.

The foreground coroutine waits until the next local midnight computed from `clock.instant()` and `clock.zone`, then refreshes and calculates a new deadline. It does not poll from recomposition or run a fixed 24-hour schedule: daylight-saving days can be 23 or 25 hours. Sub-millisecond waits round upward to avoid waking before the boundary. No unrelated direct system-clock calls, alarms, new permissions, services, or backend writes are introduced.

Ordinary `start(householdId)` does not activate the timer. This keeps source-only consumers and existing fixed-clock JVM tests free from an endless scheduled loop. User actions involving date bounds refresh before validation, so they do not wait for the timer if the injected clock has changed. Lifecycle cancellation ends the timer; existing ViewModel-scoped source observation may remain available for retained-screen state, as before.

## CI verification

New controllable-clock JVM tests cover midnight, month/year/leap-day boundaries, historical selection and drafts, refreshed validation/defaults/navigation, signed aggregates/charts, clock rollback, DST deadlines, foreground restart, household ownership, and ViewModel clearing. A regression verifies that malformed financial snapshots stay unavailable through calendar refresh, resume, and midnight until a valid snapshot recovers the refreshed scope. Existing tests and assertions are preserved.

Three new Android tests use the real Reports route, a retained ViewModel, a controlled lifecycle owner, and synthetic repositories. They verify background/resume without replacing the source subscription, foreground midnight eligibility, and cancellation on pause/stop/disposal/household change. They change only the injected clock, never the emulator's date or a Firebase account.

The foreground test records painted Compose-root PNGs before midnight, after midnight, at the updated balance chart, and at the newly eligible entry under `Pictures/ThesaurusTestEvidence/issue97/`. CI retrieves this explicit synthetic-only folder for visual review. No local builds, tests, or emulators are run for this change; the normal complete CI suites remain authoritative.
