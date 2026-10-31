> Current development checkpoint: **0.9.1 / code20**, emulator-tested bottom
> sheet, saved multi-bet table and one-confirmation place-all:
> [details](../docs/hunch/bet-slip.md). Includes the earlier
> [discovery and pane fixes](../docs/hunch/discovery-fixes.md).
> Funded wallet/venue acceptance remains unverified. The older slice descriptions
> below are historical, not a current feature inventory.

# Hunch · owner-confirmed order development slice

Current staged development build 0.7.2/code17 adds a separate exact-size Hyperliquid outcome ticket with
Buy / sell owned shares and IOC / GTC limit choices. Native review displays the
wallet, market, size, limit, conservative fee ceiling and maximum loss/debit
(or minimum sale proceeds and value relinquished). Only native Confirm starts
one journaled signing/submission attempt. Privy signs EIP-712 through the pinned,
offline SDK bridge. Unknown results never retry; a status check reads by cloid.
GTC resting orders also block a new ticket until resolved, conservatively.

No deposits, withdrawals, leverage, shorts, positions UI or delegated trading.
No application per-order/nightly caps. Exact size must meet venue precision and
balance checks; the venue may reject its own notional minimum, never enlarged.
The server remains authoritative for current minimums and fees; the displayed
fee is a conservative ceiling from the user's spot fee schedule and feeScale,
not a promise of the eventual charged fee. Sells require free owned tokens.
Portfolio-margin accounts are refused pending their separate balance accounting.

Validation is recorded in docs/hunch/owner-first-order.md. The standalone SDK
probe only used an ephemeral, unfunded **testnet** key. Testnet correctly recovered
that key's address and refused the nonexistent account; status was unknownOid.
This proves request signing/network wiring, **not a funded accepted order** or a
real embedded-wallet signature. The emulator order fixture has a separate
journal, fictional balances and dummy signatures: zero real SDK wallet calls,
HTTP submissions or funds. Its launch extra is debug-only.

Historical implementation notes follow; their old disconnected-state statements
are superseded by the above current source slice, not claims about installation.

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
Hunch now has its own development Privy app and Android client; public identifiers
are recorded in `privy-public-config.json`. Boss selected passkey sign-in. The
treasury's wallet inventory must never be reused. Real money actions require
separate explicit authorization and fresh provider/region verification.


## Account access slice

Privy Android SDK 0.17.0 is pinned. Money → Sign in to Hunch opens a separate
account screen. Email OTP creates/signs into the account first. An authenticated
user with a recovery email can link a passkey; returning users can log in with a
passkey or email. There is no passkey-only signup or email-removal action.
SDK credentials stay SDK-managed; codes are not logged, saved to instance state,
or included in app notes. The live account screen uses FLAG_SECURE. No wallet
creation, signatures, deposits, withdrawals or orders are implemented.

Relying-party origin: `https://hunch.neetware.io`. The HTTPS asset association was
independently read from the Fold and matches `dev.omalauncher.hunch` and the phone
signer SHA256 `85a7a713578814a4b779ce4fa7d0ed13aa48a81b61cc8e91b139f32dad576a18`.
Account setup must use an APK signed with that key. The earlier vc4 artifact has
the Mac's debug signature and is unsuitable for this association. Private keys
remain on the phone. The manifest includes Credential Manager asset statements.

`hunch-auth.walk` uses a debug-only fictional adapter (no SDK/network or actual
credentials) to cover email validation, bad code, correct code, retained OTP step
on rotation, linked recovery email, passkey addition, sign-out, cancelled passkey
login and cover/inner layouts. The sample `123456` is fictional only. Main's
`authFixture=demo` extra opens the non-exported account Activity in fixture mode;
release builds ignore it. No real code is sent by a walk.

The [phone] step requires Boss's own email-code/passkey interaction after promotion.
Live domain, Privy settings, persistence and real Credential Manager success are
not established by a fictional walk. Do not claim account access ready until
these have been tested. Run both `hunch.walk` and `hunch-auth.walk` on the emulator.

Official setup: https://docs.privy.io/basics/android/advanced/setup-passkeys
API: https://docs.privy.io/authentication/user-authentication/login-methods/passkey
Android association: https://developer.android.com/identity/credential-manager/prerequisites
