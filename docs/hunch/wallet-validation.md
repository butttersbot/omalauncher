# Hunch 0.5.0 wallet checkpoint — 2026-10-25

Installed and verified in Samsung Settings: Version 0.5.0, versionCode11.
Product source4fe65bf42ca2cecef2f1fb9d920f1568a2f3f0a8. Auth walkthrough
c23e105a changes only cold-start waits; no product change/rebuild.

Build:16 unit tests,0 failures,0 skipped. Original Mac APK SHA256
60870eb69027c384b7d7bf5af82467769e6dcc7880424403ed8c42e60549925f.
Phone-signed APK SHA256
05d9e32b75a9a83e6c2de007674d682897ffa338f4ef64e917a04bb40107d876.
Certificate SHA25685a7a713578814a4b779ce4fa7d0ed13aa48a81b61cc8e91b139f32dad576a18.
All23 paper asset files matched committed source byte-for-byte before signing.

## Emulator results

| Walk | PASS | FAIL | LOOK reviewed |
|---|---:|---:|---:|
| hunch-wallet (run2,4fe65bf4) |30|0|4|
| hunch-paper (run2,4fe65bf4) |47|0|12|
| hunch-auth (auth3,c23e105a; same APK) |51|0|7|

These are separate runs of the same APK, not one uninterrupted suite. Earlier
attempts failed around cold starts/rotation. Probes showed a node-less WebView
accessibility tree, then complete children at~5.2s; screenshots already showed
the correct controls. Wallet state persisted. Auth startup waits increased
3000->6000ms, then all51 checks passed. Wallet/paper passed unchanged on rerun;
their startup/rotation timing can still flake and should be hardened before
future automated checkpoints. No failed assertion was silently counted PASS.

Phone B independently reviewed the23 final LOOK screenshots, decoded the demo
QR to the full displayed0x1111111111111111111111111111111111111111 address,
and checked APK hash/assets/version/certificate. Scrolling reaches controls;
keyboard inset/above-keyboard verification and folded/unfolded layouts work.

Evidence Pocket IDs:
- APK20261025152157-58a402, metadata20261025152157-65c2e2.
- Build, both runs, diagnostic XML/logs/screens:20261025152159-c47b15.
- Final auth log/results/screens/script:20261025152618-121160.
Local evidence under /root/Inbox/hunch-4fe65bf42-11-evidence and
/root/Inbox/hunch-auth-c23e105a-evidence. Raw first metadata says ready:false;
this record supplements it with the later clean auth pass and phone promotion.

## Phone checks and owner boundary

PASS: normal Brave download/Android update completed; Samsung App info shows
Version0.5.0. PASS: launch actual app > Money > Add money opens real wallet setup.
Existing Privy session is signed in; screen shows Create Hunch wallet and no
address yet. Left that screen open with phone stay. Install server stopped.

NOT WALKED: real wallet creation, real QR/copied address comparison, persistence
across actual app reopen, live balances for that wallet, real email/passkey
re-authentication and physical fold transitions. Boss tests those next. No agent
created a real wallet or initiated a transfer, signature, deposit, or order.

Owner: tap Create Hunch wallet; compare address/QR/copy, refresh and reopen to
confirm same address, check balances. Stop before funding. Next slices depend
on this result: deposit preparation, agent authorization/order journal and
owner orders, positions/settlement/withdrawals, edge/discovery and integration.
