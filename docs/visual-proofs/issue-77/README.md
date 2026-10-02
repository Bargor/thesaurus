# Issue #77 — newest summary periods first

Raw screenshots use the API 31 emulator at 1080 × 2400 pixels, 420 dpi, with
default font scaling. The installed DEV app is backed only by local Firebase
emulators and the preserved synthetic ledger of 100 entries in August–October
2026. No production account or data is involved. Images are not edited or cropped.

`before-months.png` shows the original January-first current-year overview.
`after-months.png` shows the same year with October first, followed by September
and August. November and December are absent because the capture date is
2 October 2026.

`historical-months.png` shows the selected historical year starting with December;
empty monthly cards are retained. `yearly.png` shows switching to annual mode;
this manual ledger contains entries in 2026 only. Multiple-year descending order
is verified by automated tests rather than implied by the single-year screenshot.

Automated coverage additionally checks numeric ordering (including month 10 vs 9
and year 1000 vs 999), leap/year boundaries, Europe/Warsaw rollover, future/deleted/
foreign entry exclusion, new-scope scroll reset, atomic matching scope/card state,
and returning from a detail view to the previous scrolled card.
