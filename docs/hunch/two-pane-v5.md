# Hunch unfolded two-pane v5

Boss approved the design directly in Phone B's chat on 2026-10-26 for this run,
explicitly allowing real bets to remain disconnected and asking for the HUNCH title.
This direct instruction supersedes waiting for the Canvas approval click.

Design source: `/root/Inbox/hunch-2pane-v5.html`; Canvas cards `hunch-today`,
`hunch-market`, `hunch-bets`, `hunch-today-portrait`. Cover stays on the installed
bac2d18d rendering. Inner landscape has markets left and bets/details right; upright
stacks those panes, each scrolling independently below one HUNCH masthead.

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
