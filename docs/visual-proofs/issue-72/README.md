# Issue #72 — author inside Summary detail cards

These unmodified screenshots were captured from the API 31 Android emulator at
1080 × 2400 pixels, density 420 dpi, and the default font scale. The DEV app uses
synthetic `owner@example.test` data; no real account or production data appears.

- `before-detail.png`: September 2026 period details before the change. Author
  metadata sits below each card, outside its category-colored boundary.
- `after-detail.png`: the same period, entries, totals, and scroll position after
  the change. Author metadata is inside each card, below the optional title/tags.
- `main-entries.png`: the updated main **Wpisy** list still omits author metadata.

The existing synthetic ledger was preserved, not cleared or reseeded for these
captures. Screenshots have not been cropped or otherwise edited.

Additional automated Compose coverage uses a 320 dp container, 180% font scale,
increased density, and a long author identifier. It checks bounded text with
ellipsis, the complete accessibility value, internal author/card geometry,
unchanged permissions and card actions, date separators, category colors, and
the main-list regression. This is automated coverage, not a large-font screenshot.
