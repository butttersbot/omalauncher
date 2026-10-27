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
