# Hunch: first owner-placed order, 2026-10-28

## Current owner decisions (supersedes earlier provisional limits)

Phone A mail 20261028-182714-80b5fd relays Boss: “ok just go with ur recs
dont need limits on bet size tho”. Tonight he uses the venue’s own site himself;
Hunch continues without a tonight deadline. Hyperliquid first, Hunch’s existing
embedded wallet, funded by Boss himself. No per-order or nightly application
caps and no separate budget field. The ticket takes his exact size; off-step or
below-minimum sizes refuse instead of silently changing it. Fresh venue cash
must cover price × size plus the fee ceiling. Review shows size, limit price,
fees and maximum loss before his own signature. Journal-before-send and
reconciliation without automatic retry remain required. No agent funding,
signing or orders. The older foundation notes below are historical.

The model now prepares exact shares through prepareFunded; its wallet wrapper
has no cap. A fictional 1000-share regression verifies no inherited $1 cap,
while insufficient cash including fees still refuses. No live UI or signing
adapter has been connected by this change. Existing read-only depth preview
amount controls are not the order ticket.


Verified installed on Fold by read-only package metadata: 0.6.1, version code14.
Wallet creation was owner-confirmed in the earlier checkpoint. Existing app reads
Arbitrum native USDC/ETH and Hyperliquid spot USDC, but has no transfer, signing,
exchange submission, positions or order journal wired into the UI.

Phone A request161525: Boss wants one real order tonight. All actual funds,
wallet signatures and orders remain Boss's actions. No agent real-money tests.

Smallest native slice: the existing Hyperliquid venue, a single outcome buy,
explicit side and capped limit IOC; owner review and separate confirm; durable
pre-submit record and unique cloid; ambiguous response reconciled by cloid before
any further send. No delegated agent trading or best-price routing is needed.
Owner decisions pending through A: exact venue/market/side, existing external
wallet versus Hunch embedded wallet and funding route, hard maximum loss/order.
Provisional $1 is only a draft starting value, not permission or a verified venue
minimum; refuse rather than silently raise it. Confirm route/market minimums,
precision, fees and eligibility from current primary sources before integration.

Estimate sent to A: 1–2h for foundation/review fixtures; another3–5h for wallet
signing/venue adapter and checks if straightforward. No promise of live readiness
tonight until those uncertainties and owner funding are resolved. A manual order
on the venue's own UI may be a faster owner fallback if his wallet is supported.

## Owner-confirmed exchange slice in progress

Boss tightened the goal via A mail 20261028-182924-c47d0f: a tryable standalone
Hunch build tonight, Hyperliquid buy/sell with limit or IOC, existing embedded
wallet, no agent real-money actions. Current source 1485444d (0.7.0/vc15)
contains a native exact-size ticket/review/Confirm, pinned bundled signing SDK,
private journal-before-send and readback by cloid. Compile/tests/review and
cover/unfolded walks are pending for the current head; do not call it ready.

Runtime uses @nktkas/hyperliquid 0.33.3, the community TypeScript SDK linked by
Hyperliquid's official API docs, plus viem EIP-712 JSON serialization. This is
not falsely described as a Hyperliquid-published Java/Kotlin SDK. No bespoke
MessagePack, hash, EIP-712 scheme or signature splitting exists in Hunch.
Privy 0.17 ethSignTypedDataV4 is called only after native Confirm. The selected
full market identity, wallet, side, direction, size, price, time-in-force, nonce,
cloid and expiry bind preparation/signing/submission. No builder fee is added.

Sells consume owned available outcome shares only, with no short/mint route.
Review shows a conservative fee ceiling from user spot fees × outcome feeScale,
not an assertion of the eventual actual fee. IOC remainder cancels; GTC can
rest and currently blocks another order until a venue status settles it. Owner-confirmed cancellation uses the same SDK cancelByCloid path and records
its nonce/expiry before signing. The original remains Unknown until a readback
confirms its actual status; cancelled signing never locally clears a resting order.

A known pre-HTTP cancellation (including a Signing-only crash) becomes Cancelled.
HTTP cannot start until Submitting is durably recorded; interrupted Submitting
becomes Unknown. Any terminal clearing after submission requires an explicit
venue-confirmed result. unknownOid never permits resubmission. Specific venue
rejection statuses are terminal; generic transport uncertainty stays blocked.

The exact generated Android bundle additionally passed native RPC roundtrip,
EIP-712 serialization, recovered disposable signer, order envelope and cancel
envelope tests (two fictional signatures/submissions; zero HTTP).

Local SDK test passed canonical field order, testnet typed-data domain/source,
signature recovery, exact size/cloid/expiry and sell IOC. A testnet-only signed
probe used a generated, disposable, unfunded key in memory, never Boss's wallet.
Hyperliquid HTTP200 returned status err: user/API wallet
0xf64e10193c1dea2e48c0cacbb0bc5c4c83138078 does not exist; that exactly matched
the disposable signer recovered by the exchange. Readback returned unknownOid.
This proves signing/network request formation, not a funded accepted order,
partial/full fill, resting cancellation, or real embedded-wallet compatibility.
No keys/signatures were logged or persisted by the probe. No mainnet order.

The debug tradeFixture=demo uses fictional balances, a dummy signature, fixture
HTTP responses, testnet labelling and a separate orders-fixture.json. It does
not call Privy or the exchange. hunch-order.walk covers its review/Confirm on
cover and unfolded; screenshots must be reviewed before staging an APK as
tryable. Owner wallet signing and funding remain owner-only [phone] checks.

## First source slice

OrderTickets: explicit wallet/cap and fresh venue constraints, fee-inclusive
budget/available cash, size floors to venue step, price ticks, minimum refusal,
30-second review. OrderJournal: private app file with stable cloid, file fsync,
atomic rename and directory fsync before a future send; Signing/Submitting on
reopen becomes Unknown, no resubmit transition, block another active send until
reconciled, corrupt file blocks. These are foundations, not a trading feature.
One journal instance per private file is required by the future owner controller;
no HTTP/Binder/exported order command exists. Concurrency/Android durability
must be reviewed before the live adapter is connected.

FundingChecks compares the signing wallet and fresh venue-available cash; an
Arbitrum wallet balance never counts as trading cash. Owner funding instructions
and signed transaction construction are not implemented until route verification.

Canvas lookup was attempted but the configured service did not respond within
10seconds. No new unapproved screen was shipped. Review screen must start from
Hunch's approved card and display wallet, market+side, limit price, shares,
fee ceiling, maximum debit, IOC/partial/no-fill behavior and final owner confirm.

Primary references checked 2026-10-28:
- https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/asset-ids
- https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/exchange-endpoint
- https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/signing
- https://hyperliquid.gitbook.io/hyperliquid-docs/onboarding/how-to-start-trading

Public outcomeMeta was read without an account or signature. No private balance
or wallet was accessed. Live market minimums/signing compatibility are not yet
verified. All new tests use fictional values; funding/order walks must use fixtures.

Follow-up hardening: the journal now holds an OS file lock for its whole lifetime;
a second controller/process fails closed instead of overwriting a stale snapshot.
The app controller must retain that instance and close it on shutdown. Any write
failure poisons the instance: close/reopen and reconcile, never continue from an
in-memory rollback after a possibly completed rename.

The current OwnerOrderLimit is explicitly per order; no aggregate/nightly loss
permission is implied. Boss must decide the cap scope before a live controller.
Clock rollback still blocks stale signing, but read-only reconciliation/cancellation
may settle a prior Unknown with a nondecreasing journal timestamp; it never sends
another order or refreshes the original review.

Review-driven lock hardening: a canonical-path process registry now refuses a
second journal before opening the lock inode. Closing a duplicate descriptor can
release a POSIX process lock, so the OS lock alone was insufficient. The registry
and OS lock jointly guard construction and are released on close/failure.
OrderTickets.prepareFunded now consumes the exact FundingSnapshot verified by
FundingChecks, so the future owner controller cannot substitute an unchecked
available-cash number. The lower-level pure calculation remains a testable primitive.

Checkpoint bf9ba291: lane B compile and all27 Hunch tests passed; independent
second-process probe confirmed exclusion after repeated duplicate opens and a
canonical-path alias, release on close and exclusion after reopen. No UI/walk or
live money checks were performed. Follow-up makes the unchecked cash calculation
private: prepareFunded is the only preparation entry point for app code.

Final foundation checkpoint7a5ef588: lane B compile/all27 Hunch unit tests PASS.
Private unchecked calculation verified; every tested preparation uses a fresh
FundingSnapshot. The unchanged journal retains the independently verified
cross-process lock result frombf9ba291. No APK, UI walk, install, funds, signature,
order or testnet transaction was produced. Not a ready-to-trade checkpoint.
Live controller must bind final owner presence to the exact reviewed immutable
ticket/wallet and recheck freshness before signing; being signed into Privy alone
must not count as owner confirmation. UI, SDK compatibility, venue constraints,
funding route and owner decisions remain unresolved.
