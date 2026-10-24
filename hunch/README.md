# Hunch · read-only edition

Standalone Android package `dev.omalauncher.hunch`. Optional Gradle module:

    ./gradlew -Phunch :hunch:testDebugUnitTest :hunch:assembleDebug

This edition reads public Hyperliquid metadata and order books. It has no wallet,
account storage, signing, deposit, withdrawal or order endpoint. Bets and Money
show honest disconnected states. No market pictures or proprietary assets ship.
Zen Kaku Gothic New is bundled with its OFL; display and mono fonts come from the
existing licensed Manga pack.

## Read semantics

- Source: https://api.hyperliquid.xyz/info, HTTPS only, bounded responses/timeouts.
- Outcome coin encoding is `#(10 * outcome + side)`, side 0 or 1.
  Reference: https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/asset-ids
- Parent question descriptions are inherited before child-specific terms. The
  terms screen preserves the supplied values; it does not claim independently
  verified settlement rules.
- Only exact template/terms/side/quote/question identity groups venues. Unknown
  descriptions remain separate. Venue selection is explicit; no best-price
  routing or trades occur.
- A book is executable depth only where price is between 0 and 1 and size is
  positive. No empty-book midpoint is presented. Bid and ask stay separate.
- Hypothetical gross winning payout sums shares purchasable at the displayed ask
  levels. Fees are excluded; incomplete depth gives no full-budget estimate.
- Books older than 60 seconds, future timestamps beyond 5 seconds or crossed
  best prices cannot produce an estimate. Refresh reads a new book. There is no
  auto-refresh price guarantee or claim of an advantage.

## Validation

`tools/walkthrough/scripts/hunch.walk` covers live launch and debug-only fictional
market detail, empty book, tabs, cover/inner sizes and offline state. `fixture=demo`
and `fixture=error` launch extras are honored only in debug builds and labelled on
screen. Debug builds must be left in live mode after the walkthrough.

This is slice 1, not the completed Hunch design. Further work includes richer
market-specific explanations, price history, editorial discovery using real
volume, artwork, home panels, verified cross-venue semantics and rule review.
No wallet work can connect until Boss supplies a NEW Privy app/client identifier;
the treasury's wallet inventory must never be reused. Real money actions require
separate explicit authorization and fresh provider/region verification.
