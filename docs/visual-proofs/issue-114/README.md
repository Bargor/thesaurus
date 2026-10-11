# Issue #114 — Settings and Android document selection

Captured and visually inspected on the API 31 DEV emulator at its normal font/display settings.

- [Settings](settings.png): the new **Dane** destination in the actual DEV household.
- [Owner actions](data-owner.png): all four transfer actions available to the owner.
- [Privacy confirmation](privacy-warning.png): the warning appears before destination selection.
- [Create document](create-document.png): Android's real document provider, with a dated JSON backup filename.
- [Open document](open-document.png): Android's real filtered JSON selector, displaying the exported file.
- [Selected JSON preview](selected-json-preview.png): the real exported DEV backup is read back through Settings. The preview recognizes the existing opening balance, 10 categories, 49 subcategories and 100 entries; no import writes were confirmed.
- [Member UI](member-preview.png): an explicitly labelled, isolated rendering fixture for member restrictions. It does not sign out, change membership or connect to Firebase. Import actions are absent while exports remain visible.

The real document flow used only restored synthetic DEV data. No production account or database was used, and the interactive app's cache was not cleared. A dated JSON file remains in the emulator's Downloads folder for manual testing. System document-provider text follows the emulator's system language; application text is Polish.

Automated verification additionally covers both formats, consent, role/synchronization gates, cancellation, access/write failures, bounded reads, scalar picker restoration, duplicate requests/results, queued CSV edits, and Settings back navigation. See the PR for the full local test counts.
