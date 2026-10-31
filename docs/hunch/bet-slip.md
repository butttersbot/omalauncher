# Hunch 0.9.0 / code 19 — bottom sheet and multi-bet slip

2026-10-31 — Codex. Builds on 3577aaab (discovery and right-side summary rail).
Installed and tested on emulator-5554 only; owner's Fold installation untouched.
Existing active Manga appearance retained.

## Behavior

- Yes/No stays on the market. Selecting a side opens shared bottom controls on
  cover and inside the unfolded right pane; the left discovery list stays usable.
- Enter a stake and see maximum risk → estimated payout if right. Payout includes
  the stake; estimates precede fees and whole-share rounding. Final native review
  shows exact bounded debit including a conservative fee ceiling and gross payout
  before settlement fees. It never silently increases the entered stake.
- Place bet reviews just that selection. Add to slip returns to browsing. One
  saved selection has no table; multiple selections show editable/removable rows,
  decimal-exact totals and Place all. Saved drafts are inert private preferences.
- Place all refreshes canonical terms, executable quotes, fee schedule and available
  cash, checks aggregate affordability, then requires one native confirmation and
  one wallet signature for an SDK multi-order action. These are independent IOC
  orders, not an atomic transaction or parlay. Outcomes may fill separately,
  partially or be rejected; each is reported independently.
- Every child has its own durable cloid and draft identity. Journal transitions
  are atomic across the batch. Signing/submitting is persisted before the next
  side effect. Unknown results block further submissions; status reads never
  resend. A process restart preserves this protection. Terminal submitted drafts
  clear from the slip; unused drafts remain available.
- Review expires after 30 seconds. Available order-book depth can limit shares.
  Maximum 20 drafts is a resource bound, not a money/spending cap. Existing advanced
  exact-share IOC/GTC ticket remains available from the sheet.

## Verification performed

- APK build and 56 JVM tests: zero failures/errors. Native tests cover bounded
  fees, depth, precision, freshness, total affordability, exact SDK body matching,
  partial/rejected/malformed results, atomic journals and restart protection.
- HTML checks pass for original 15 template hashes, discovery/account layouts and
  new shared sheet: input focus, single vs multi mode, decimal totals, explicit
  draft IDs, native-only final confirmation and per-child results.
- Pinned offline SDK bundle: single order, cancel and two-order batch recovered
  the disposable signer and matched exact assets/prices/sizes/cloids. Three
  fictional signatures/submissions; zero HTTP submissions.
- Actual Android WebView + native-button emulator walkthroughs: cover stake
  sheet; two saved bets/table/totals; native batch review; one partial fill and
  one rejection; single bet without a table; unknown result then force-stop and
  restart, journal unchanged and Review disabled until explicit status check.
- Final unfolded and cover screenshots inspected. Sheet stays inside the right
  pane unfolded; summary rail remains above it. Long tables scroll internally.

## Remaining owner/venue verification

No funded trade, deposit, withdrawal or real embedded-wallet signature was made.
Production acceptance of multi-order outcome trading and actual venue fee/fill
behavior remain unverified. The build is a development candidate, not a claim of
funded trading readiness. Fictional mode uses its own preferences/journal, locked
fake test funds, dummy signatures and local responses. It never submits HTTP.

## 0.9.1 / code 20 — compact saved-bet list

Boss requested the saved slip header/Close and the copy below risk totals removed.
Replaced the header with an accessible handle: touch-drag down dismisses without
changing saved bets; tap/keyboard also dismisses. The list starts directly below
it and takes the space reclaimed from header/copy. Totals and actions stay below
an internally scrolling list on cover and unfolded right pane. Single slips keep
content height. Native confirmation/review terms are unchanged.

Interface checks and Android build pass. Actual emulator touch drag dismissed and
preserved both saved bets. Cover list grew from185px to387px; unfolded containment
and screenshots inspected. No phone install or real order.
