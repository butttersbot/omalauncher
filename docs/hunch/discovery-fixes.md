# Hunch 0.8.0 / code 18 — discovery and pane fixes

2026-10-31 — Codex. Based on the 0.7.2/code17 source checkpoint 0ebaa926.
Built and installed in emulator-5554 only. Not installed on the owner's Fold.
Active Soma look independently read from herd: Manga, phone build188. Existing
paper/ink/font assets retained; layout changes follow Boss's direct bug list.

## Changes

- Wallet and account activities are pane-sized floating windows on unfolded
  landscape. Their top edge follows the actual right-pane header; the underlying
  market list and trading summary remain visible. Cover/portrait use full width.
- One discovery renderer serves cover, upright and unfolded landscape. Category
  pills never wrap and scroll horizontally when needed. Watchlist is a separate
  tab, with native private preferences for saves and separate fixture preferences.
- Cash to bet / In play / Could win / P&L moved into the landscape Hunch header;
  redundant cash box removed. Open Wallet sits beside My bets.
- Cover market Back is in its own navigation row above the banner.
- Both outcome asks are independently fetched, validated for exact coin identity,
  timestamp and crossed book. Detail prices no longer disappear when selecting
  the other side. Listing percentages no longer repeat the same ask; columns and
  individual side names label prices. Named template labels (e.g. shortNameA)
  resolve against venue fields. Missing asks, stale snapshots and failures are
  explicitly distinguished; no complement price is invented.
- 24h notional volume comes from spotMetaAndAssetCtxs coin-keyed outcome contexts,
  summing both sides only when both are available. Trending ranks positive fresh
  24h volume. Volume is not claimed to be executable liquidity. Detail also shows
  ask depth from the levels returned by each side's live book.
- Prices filter by Crypto / Stocks & macro, then asset; grouped by asset and
  deadline/threshold. Sports filter by competition and group by matchup. Parent
  sport terms classify fallback outcomes correctly, including soccer previously
  placed in Events. Companies is now Pre-IPO. Your teams and the disconnected
  feed sections (including Settles in minutes / More markets) are removed live.
- Listing rows are grid-bounded; long names and unavailable-price states wrap.

## Verification actually performed

- Hunch APK builds; 43 JVM tests, zero failures/errors. New checks cover sport
  fallback inheritance, asset and IPO grouping, side-name expansion, exact-side
  asks/depth, and coin-keyed volume mapping including missing/negative values.
- tools/hunch npm test passes: original 15 template hashes; cover/inner/upright
  discovery and navigation, both quote states/staleness, no obsolete sections,
  preserved search focus, pagination/scroll, wallet and account transitions.
- Actual Android WebView actuation with public live markets: category overflow
  and nowrap; BTC filter excludes other assets/stocks; Premier League filter
  excludes NFL/NBA; match headings; row scrollWidth <= clientWidth; header summary
  and inline wallet; independently returned side0/side1 books on cover and inner.
- Saved live outcome1473 to the emulator Watchlist and verified it survives force
  stop/relaunch. Fixture wallet is visibly labeled and uses no real account.
- Wallet window frame observed [1224,369]-[2448,1764] with the fictional ribbon;
  actual main right-pane top was88.88 CSS pixels plus system inset. Left pane and
  entire header visible in the reviewed native screenshot.
- Native screenshots reviewed: landscape public discovery and sports filter,
  cover market (Back outside art, both live asks), aligned fictional wallet,
  portrait unfolded discovery. No horizontal row overflow in those checks.

No funding, wallet creation, email-code request, passkey action, signature,
exchange submission, withdrawal or actual-owner trading test was performed.
Owner-funded trading and real-phone installation/physical acceptance remain
separate from this UI/discovery checkpoint. APK is locally debug-signed; preserve
and use the owner's existing signer for any Fold update.

Sources inspected: official Hyperliquid info-endpoint/spot and asset-ids docs;
public outcomeMeta and spotMetaAndAssetCtxs responses read2026-10-31. Unknown
source metadata remains unknown; the sole unclassified Events listing is retained.

## Header alignment follow-up

Boss clarified that the summary stays entirely on the right. The landscape
header now uses the same two equal columns as the panes: Hunch on the left,
all four summary cells inside the right half with18px side insets. Labels
wrap rather than extending outside their cells on narrower unfolded widths.
