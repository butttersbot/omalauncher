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

## Verification — 2026-10-25

Final app source: `11d6f047` (vc9 / 0.4.0). Mac Pro built that exact commit once;
MarketsTest passed 8/8. The final APK's bundled paper assets were compared byte
for byte against this checkout after signing with the existing phone key.

| Walk | Source/build | PASS | FAIL | Screenshots reviewed |
| --- | --- | ---: | ---: | ---: |
| hunch-auth | 11d6f047 | 51 | 0 | 7 |
| hunch-paper | 11d6f047 | 46 | 0 | 12 |
| hunch-paper-reference | e5779200; reference sources unchanged | 90 | 0 | 15 |

Mac Pro and Phone B reviewed the screenshots: cover/unfolded layouts, chapter
navigation, lower feed, search, market detail/depth, rotation, email validation,
wrong/correct demo OTP, keyboard scrolling, recovery email, passkey linking,
sign-out and reference gallery. Account walks use the fictional backend; no real
email/passkey action or wallet transaction was performed. Happy DOM checks cover
all 15 source hashes/templates, live data escaping, pagination, quote expiry,
keyboard resize focus, mounted input submission and account state scroll reset.
Four walkthrough-runner regression tests pass.

Unsigned APK SHA-256:
`769317ba424d5fe09eb0cab7cd60e6162535cc4e0807d4776407f36bbdb12f4b`.
Phone-signed APK SHA-256:
`d86e38ce35182014834a1f88737a1417cd2ae071358ca3c9fa2c3447936ec89d`.
Pocket evidence: final APK `20261025002752-fa66b6`, final walks
`20261025002754-2c84b5`, reference walk `20261025001655-3644f6`.

Phone installation and physical fold/owner email/passkey checks remain pending:
the phone was locked/asleep and the background helper unavailable. No main-screen
fallback or permission change was attempted. Original mockups depicting deposits,
orders, cash-out, settlement, curves and assistant actions remain reference-only
until their real backend slices exist. Empty live account panels are truthful;
mock balances never become user data.

Independent initial-state comparison: all 15 rendered DOM trees match the
MacBook standalone HTML conversion (archive SHA-256
`9b3364c1c27843529fd4597fc3eef2c9ff29e90d5635407e32e555e79b091671`), including
text, attributes, inline styles and SVG. Only host root ID/scale and equivalent
whitespace/CSS quote formatting are normalized. Reproduce with
`tools/hunch/compare-export.mjs`. This establishes template fidelity, not live
layout/font/interaction readiness.
