# Hunch HTML implementation

Boss's source of truth is the original HTML export. The 15 files under
`hunch/src/main/assets/paper/mockup/` are copied byte-for-byte from the supplied
archive. `pages.json` records each source hash and canonical viewport.

The Android UI now hosts those templates offline. It uses their actual inline
CSS, SVG, typography, screen tones, panels and folder tabs. `runtime.js` adapts
the export's DCLogic/sc-if/sc-for/dc-import vocabulary because support.js was not
included in the archive. No remote scripts or fonts are loaded. Bundled fonts
retain their licenses. The native bridge accepts only explicit account/public
market actions. All non-bundled WebView requests are denied. Provider data is
inserted as text, never HTML or code. OTPs and tokens are not logged/persisted.

Live routes: CoverBets + TodayFeed on cover; Discover/Bets/Money unfolded; Main
for unfolded market/depth; Flow steps 6 and 1 for cover market and account access. Read-only account/trading
limitations require truthful empty states in the original panels. The exported
mock balances, positions, outside prices and fills never become real user state.

Debug `--es design NAME` shows each unmodified reference with a permanent sample
ribbon. This mode cannot send account or market requests. Original simulated
interactions are confined to that mode; it never enables trading.

## Verification still required

- Exact Android build/unit tests on Mac Pro.
- hunch-paper, hunch-paper-reference, and hunch-auth walks.
- Inspect each original/reference screenshot at the canonical viewport, plus
  live cover/unfolded/landscape, long provider text, search keyboard and OTP.
- Owner phone account/passkey verification remains separate from fixture walks.

Do not call this build visually ready until these checks have passed. Original
mockups depicting deposits, orders, cash-out, settlement, curves and assistant
actions are reference-only until their real backend slices exist.
