# Hunch: first owner-placed order, 2026-10-28

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
