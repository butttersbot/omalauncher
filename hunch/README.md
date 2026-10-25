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
Hunch now has its own development Privy app and Android client; public identifiers
are recorded in `privy-public-config.json`. Boss selected passkey sign-in. The
treasury's wallet inventory must never be reused. Real money actions require
separate explicit authorization and fresh provider/region verification.


## Passkey setup pending

Sign-in is not yet implemented. Native Android passkeys require an owner-controlled
HTTPS relying-party domain serving `/.well-known/assetlinks.json`, associated with
`dev.omalauncher.hunch` and the certificate signing the installed app. Register the
Android key hash in Hunch's Privy settings and enable passkey authentication there.
Do not change treasury authentication or policies.

The already-tested vc4 APK is signed with an Android Debug certificate (public
SHA256 `099e8e5897e126eec2c9ca5ef45ea4a1749895945ec2ccb9f1997e932bad6d3c`).
This is an observation about that artifact, not a chosen permanent signing identity.
Verify the intended stable signer before generating the final association file;
never extract a private key or publish a placeholder domain.

Once domain/association is ready, use Privy's Android SDK passkey `signup` for
first registration and `login` for returning users, passing the current Activity
for Credential Manager prompts. The owner completes passkey creation/biometrics.
Verify cancellation, failed login, restart and returning-user login on the Fold.
No money movement or order submission is authorized by enabling sign-in.

Official setup: https://docs.privy.io/basics/android/advanced/setup-passkeys
API: https://docs.privy.io/authentication/user-authentication/login-methods/passkey
