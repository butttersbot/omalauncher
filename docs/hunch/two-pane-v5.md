# Hunch unfolded: landscape v5, upright v6

Boss approved the design directly in Phone B's chat on 2026-10-26 for this run,
explicitly allowing real bets to remain disconnected and asking for the HUNCH title.
This direct instruction supersedes waiting for the Canvas approval click.

Design source: `/root/Inbox/hunch-2pane-v5.html`; Canvas cards `hunch-today`,
`hunch-market`, `hunch-bets`, `hunch-today-portrait`. Cover stays on the installed
bac2d18d rendering. Inner landscape has markets left and bets/details right; upright
uses v6 enlarged cover-style Today, detail, Bets and Money pages, with pinned
bottom TODAY/BETS/MONEY tabs and about 1.3x typography/spacing. This correction
was relayed by Phone A in mail 20261026-153044-31b3b3. Landscape alone has
independent panes; cover remains unchanged.

The production view uses actual public listings and validated depth previews. It
has no fictional positions, balances, order submission, funding or withdrawal.
Cash/in-play/could-pay/P&L show unavailable; the wallet opens the existing native
wallet flow. The slip refreshes depth and explicitly says betting is not connected.

Implementation: a custom Paper scene retains the mounted market list, search input
and independent scroll positions while the detail pane changes. All provider strings
use text nodes. The approved stadium SVG is bundled author content. New CSS is
scoped to the inner scene; original exported templates and cover binding are unchanged.

Validation before the Mac checkpoint: `node tools/hunch/check-paper.mjs` PASS,
including manifest hashes, cover layouts, inner selection/back and list identity/scroll
retention, offline state, pagination, quote expiry, and account/wallet checks.
Android build, emulator screenshots/walk and real Fold checks remain pending.

Mac checkpoint 4a6e3c27: Android build + 16 tests and static check PASS; walk
79 PASS / 0 FAIL / 17 LOOK, but visual review FAIL for upright. CSS zoom percentages
were incorrectly divided by 1.3, shrinking both dimensions to 77%. Corrected to
width/height 100% while retaining zoom 1.3; the corrected visual walk is pending.
Landscape and cover passed that review; no 4a6e3c27 APK was staged.

Final b18de027 checkpoint: build PASS, 16 unit tests PASS, paper check PASS;
walk 79 PASS / 0 FAIL / 17 LOOK, all reviewed by the Mac lane. Upright fills
width/height with tabs pinned; independent landscape panes, offline and physical
cover rendering passed. Extra checks: upright scroll access to all sections and
detail terms, hardware Back retaining list position, query across tab/rotation,
keyboard visibility, long live titles. Rotation closes the keyboard but keeps query.

Staged (not installed): `/root/Inbox/hunch-b18de027-13-signed.apk`, package
dev.omalauncher.hunch, 0.6.0 / 13. Signed SHA256
`f0bdb7f3095a342bc386fef258f16e611bb5c6fb0f339d9b731d8bfbfc8201aa`;
signer matches installed certificate `85a7a713…76a18`. Source Mac SHA256
`4d7961bd061249e3bea8d071b36a948da8974d9a999553bfb8ea6e87fa0e72cc`.
Real Fold checks and Canvas Shipped publication wait for promotion.

Phone A installed the verified signed b18de027 APK on the real Fold at 16:10
(20261026-161011-a00294), adb install Success, vc13/0.6.0. Boss is trying it;
we are not controlling Hunch while he uses it. Physical Fold walk remains pending
until the app is free; reviewed screenshot publication to Canvas is now requested.

### Shipped Canvas publication

Published from the Fold builder CLI as version 0.6.0, build b18de027, then re-read Canvas and verified all 10 Shipped states carry that version/build. Input archive `hunch-b18de027-canvas.zip` SHA256 `467febfec2236d84eb78099a6463a2338b095d59c8e14e02e56c3fae50932ce7` contains the critic-reviewed walk screenshots and mapping: landscape Today/Market/Bets; upright Today/Market/Bets/Money; cover Today/Market/Money. The cover Bets screenshot was absent, so that card remains on its earlier Shipped state. No `--matches` or owner approval operation was used; Canvas retains drift against older Approved cards. The Mac lane's attempted external write was refused; the documented builder publication path succeeded on the Fold. Real physical-fold checks remain pending owner availability.

### Comparison after Canvas owner approval of v5/v6

Re-read Approved: landscape Today v5 `afd309a8…`, Market v5 `c40f3ffd…`, Bets v5 `76fdea4f…`; upright Today v6 `7802d567…`. Downloaded exact approved HTML, rendered each at unfolded size with soma-look and personally viewed it beside the reviewed 0.6.0 PNGs. The approved layout relationships are present: one HUNCH masthead, market list left / bets or slip right, right-pane Back and amount controls, and upright enlarged cover pages with pinned bottom tabs. Independent scrolling and state preservation were already walked.

No blanket `--matches` was applied: quote percentages/YES prices in approved list/hero are absent in 0.6.0 (VIEW BOOK and verified selected-side ask only); Market replaces BET with read-only depth refresh; Bets approval depicts a BET IN/JUST PLACED populated state while installed build intentionally has no connected trading account, funds or orders. The latter is within Boss's explicitly permitted disconnected-betting scope. Today/Market/upright quote display remains a visible capability difference rather than a change in demo names/counts. The older standalone landscape Money v3 card is superseded by the v5 money strip and v6 upright Money page; do not falsely clear its drift against a screen that no longer exists.

Replied to eight historical comment threads with the current approval/build and which old questions are superseded (Today `7dsp6c97ar`, `8cd4kgsdta`; Market `pbpev8tuka`; Bets `v4cqvntkre`, `qw26ddnbhe`; upright `a7sqz8xaak`; Money `rz66qh3rct`, `aqr5ubhc8z`). Their resolved flags remain false: the agent CLI supports replies but resolution is owner-only per design/soma-canvas/PLAN.md. Owner resolution/archive is needed to remove those stale NEEDS YOU entries. No owner credential or approval state was altered. Cover cards retain earlier comparison notes; their layout was unchanged by 0.6.0 and no fresh per-cover Approved render comparison was claimed here.

### Approved listing quotes follow-up (0.6.1 candidate)

Cards: hunch-today and hunch-market Approved v5, hunch-today-portrait Approved v6. Add the missing hero % and row % plus side-price cents from each outcome's verified side-zero public `l2Book` ask. Percentage means ask-implied chance, not a forecast or executable multi-share guarantee. Named outcomes keep their actual side label; binary outcomes show YES. Metadata, coin and book conventions follow the official Hyperliquid asset-id and info-endpoint documentation; no complement/last-trade fallback is used. Fresh matching-coin USDC books only: bad/missing time, old/future, crossed and empty books show unavailable; displayed quotes expire after 60 seconds.

Only visible listing ids are requested, in batches of at most16, bounded to24 in-flight/queued reads on a separate two-worker pool so detail requests are not behind the listing queue. Cache and retry windows avoid repeated per-row network calls from scroll/layout events. No funds or order APIs added. Candidate version0.6.1 vc14; not installed, not Shipped or marked matching until the build/walk is reviewed and owner promotes. Local JS syntax checks and check-paper.mjs passed, including fresh/empty/stale quote UI checks. ListingQuote unit tests and listing-quotes walk await the lane checkpoint. Canvas Bets thread notes that the post-placement illustration remains a Boss-authorized disconnected-betting difference.

Wallet regression diagnosis at installed b18de027: own laneB exact committed wallet walk35 PASS/0 FAIL/4 reviewed LOOK, two extra rotations kept same wallet address/balances and WalletActivity, no app crash or clipping. One final market expect flaked despite PUBLIC MARKETS visible in the screenshot; cold WebView accessibility tree appeared after~2 seconds. No wallet change justified yet; requested laneA failure screenshots/results to distinguish timing or scroll direction from a real regression.

### Listing quotes checkpoint and staged 0.6.1

Critic PASS at d78d0b6f: build successful, 19 tests (ListingQuote3, Markets8, WalletAmounts8), check-paper PASS. Reviewed walks: hunch79 PASS/0 FAIL/17 LOOK; listing-quotes18/0/5; wallet35/0/4. Live public data verified real row/hero quotes, including SKHY80.59% / YES80.59¢ matching detail's80.59¢ ask; unavailable Sunderland sides stayed unavailable, no complement was fabricated; offscreen quotes expired around60s and refetched on return. Detail opened in~2.5s while list requests were ongoing. Device crossed-book/failure states remain unit/node-only; a very long title with a live quote was not exercised. Precise live values retain up to2 decimals.

Retrieved APK SHA256 `5550a11eb7dd33345ae874f4e13df4740deb33d7f2f97655ed6013c08344988c` and screenshot archive SHA256 `ccd1138a70060274dacddacec8a926b05e86eb460d3f7f5ebd900d08f81bda06`, both verified. Phone-signed staged APK `/root/Inbox/hunch-d78d0b6f-14-signed.apk`: SHA256 `8c5b537ba7ec4b187c71e7ef96085e858e4100268b1aa991e0df8b4d137adb0e`, package dev.omalauncher.hunch0.6.1 vc14, signer85a7a713… matches the installed key. Not installed or published Shipped; A notified for promotion. Reviewed new10-card screenshot bundle retained at `/root/Inbox/hunch-061-reviewed/hunch-d78d0b6f-canvas` with two additional evidence-only live PNGs.

### Physical-cover drift and obsolete question proposals

Physical cover still uses CoverBets.dc.html and Flow.dc.html, not v6 upright. Downloaded current three cover Approved exports, rendered and personally compared against reviewed b18de027 cover screenshots. Layout/typography/stadium/tabs and panel geometry are preserved; live-data and explicitly authorized disconnected-money/read-only placeholders replace sample balances/betting. Re-shipped hunch-today-cover, hunch-market-cover, hunch-money-cover using the existing installed0.6.0 screenshots/buildb18de027 with `--matches`; re-read all three and verified drift=false. This does not publish the uninstalled0.6.1 candidate.

Archived obsolete `soma-question-form` and `soma-question-form-cover` proposals with superseded-by-native-Send/ed9c776a reasons (history preserved). Verified both are absent from Canvas needs afterward. Native functionality still awaits actual launcher promotion and phone delivery/receipt coverage; preview screenshots were not mislabelled as Shipped.
