# Issue #113 — JSON backup merge

These API 31 screenshots use synthetic fixtures and the production JSON backup screen. No real household/account data is included.

- [Read-only preview including conflicts](preview-with-conflicts.png)
- [Per-kind summary including a partial result](completed-summary.png)
- [Scrollable confirmation at 2× font size](large-font-confirmation.png)
- [Live DEV preview of the full synthetic backup](live-preview.png)

The DEV-only **Symulacja kopii JSON** action offers full import, conflicts, partial failure, retry/reconfirmation, duplicate import and export scenarios. All writes are to synthetic memory. Production file pickers and Settings entry points follow in #114.
