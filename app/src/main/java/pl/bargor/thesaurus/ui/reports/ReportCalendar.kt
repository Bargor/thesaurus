package pl.bargor.thesaurus.ui.reports

import java.time.Clock
import java.time.Duration

/** Calendar boundaries use the injected zone, including short and long DST days. */
internal fun nextReportMidnightDelayMillis(clock: Clock): Long {
    val now = clock.instant()
    val midnight = now.atZone(clock.zone).toLocalDate().plusDays(1).atStartOfDay(clock.zone).toInstant()
    val remaining = Duration.between(now, midnight)
    // Round up so a fractional millisecond never wakes the timer before midnight.
    return (remaining.toMillis() + if (remaining.nano % 1_000_000 != 0) 1 else 0).coerceAtLeast(1)
}
