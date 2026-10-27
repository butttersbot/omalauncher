# Wallet setup slice — next owner test

Boss authorized building remaining slices until an owner test is required; Boss
will perform actual orders. First dependency is verified account/wallet setup.
This increment makes Money > Add money open the original Flow/step2 drawing,
with real authenticated Privy embedded Ethereum wallet create/reopen, public
address/QR/copy and independently refreshed Arbitrum native USDC/ETH plus
Hyperliquid spot total/available balances. No transfer or signing API is called.
No address or token is accepted from the JavaScript bridge for balance requests;
reads use the authenticated SDK wallet only. Keys/tokens never enter the UI.

Reuse existing wallets; creation uses allowAdditional=false. Refresh user state
before selection. If creation times out/fails, check current wallet before retry.
Unknown/error balances are Unavailable, not zero. Clear balances while checking.
USDC units use BigInteger/BigDecimal, no floating point; Hyperliquid holds are
removed from available balance. Spot balances are labeled spot, not total wealth.

Owner checkpoint after this build passes emulator checks and is installed:
1. Money > Add money > Sign in to Hunch. Verify actual email OTP and optionally
   linked passkey; email remains recovery. No agent reads/enters owner OTP.
2. Return to the wallet screen; Create Hunch wallet or reopen the existing one.
3. Compare full displayed address with clipboard and QR. Refresh/reopen must
   keep the same address. Compare Arbitrum and Hyperliquid balances independently.
4. Stop before funding. Arbitrum wallet funds are not Hyperliquid trading cash;
   funding/bridge and agent-key/order tests are subsequent owner checkpoints.

This is a testable wallet dependency, not completion of all six planned slices.
Next: user-tested wallet -> bridge/deposit transaction preparation -> authorized
agent key and durable order journal -> owner tiny-order test -> real positions,
cash-out, settlement and withdrawals -> edge/discovery and Soma integration.
Never auto-place an order or initiate owner funding to skip a checkpoint.

Sources verified while implementing:
- https://docs.privy.io/basics/android/quickstart.md (createEthereumWallet,
  embeddedEthereumWallets); exact0.17.0 public PrivyUser source in Maven Central.
- https://developers.circle.com/stablecoins/usdc-contract-addresses
  (Arbitrum native USDC0xaf88d065e77c8cC2239327C5EDb3A432268e5831).
- https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint/spot
  (spotClearinghouseState, token0USDC, total/hold).

Validation: wallet-specific fixture walk, updated paper navigation walk, auth
regression walk, WalletAmountsTest, and local HTML renderer checks. Real SDK/
Credential Manager and real wallet creation must be owner tested after install.

## Launcher165 walkthrough-runner correction

Launcher164 evidence `/root/Inbox/hunch-wallet-164-36-expect.png` was personally reviewed: Copy wallet address, balances and Back to markets were visible while the walk reported them missing. LaneB had35 PASS/0 FAIL/4 reviewed LOOK at Hunch b18de027, and rotation diagnostics retained wallet identity/balances. Correct `tools/walkthrough/walk.py`, not the wallet: discard old dump files, retry UiAutomation errors/empty or WebView-shell trees, wait for readable controls after launch/rotation/fold, and refuse to pass `absent` or swipe without a fresh tree. Ten runner tests pass, including stale-file refusal and empty-tree negative assertions. The wallet/emulator rerun is pending; include the eventual result in the165 report without claiming a phone or app fix.

### cc2139a3 runner checkpoint for165: PASS

Mac laneB ran the corrected runner against its emulator-installed Hunch d78d0b6f0.6.1 vc14 (not the real Fold, and not b18de027). Runner unit tests10/10 passed. Wallet walk repeated twice: each35 PASS,0 FAIL,4 LOOK, all screenshots reviewed,127 seconds per run. Both rotations kept WalletActivity, the same fictional0x111… address and12.50USDC balances; cold Back to markets reached MainActivity with PUBLIC MARKETS visible. No Hunch crash or UiAutomation Bad-file-descriptor crash occurred in either run. Listing-quotes walk with the new runner18 PASS,0 FAIL,5 reviewed LOOK. Screenshots retained on Mac `/tmp/walk/cc-wallet1`, `cc-wallet2`.

Limits: no native dialog over WebView was exercised; tree-unavailable/absent-failure behavior was covered by unit tests rather than injected on-device failures. No app rebuild, real account, real-phone promotion, runner-service restart or Canvas write was performed for this checkpoint. This is the completed runner correction coverage to cite in the165 report; it does not establish real-Fold behavior.
