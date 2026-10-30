# Messages parity build record

2026-10-29 — Luna. Plan 239045af authorized via Phone A's relay of Boss's 20:28 native card. Build groups remain 1–4, 5–7, 8–10, then effects after reference/transport feasibility. Pin/mute are phone-local. No phone install, backend deployment, registration, service restart or real message send.

## Group 1–4: incremental implementation; incomplete

Reconciled Messages 4b6283e0 into the isolated integration branch first: 725414e1. Initial implementation published at 188ddac2; subsequent cache fix is recorded in branch history.

- Stable row identities across optimistic send → server echo → later updates/restart. Persist ID/offset reading anchors. Batch history merges; JSON serialization runs on the disk queue.
- Follow intent changes after a reader's drag. Programmatic scroll/keyboard resize cannot silently switch following off. This fixes a demonstrated keyboard-open send/reply visibility failure.
- Normalize native Apple link and X payloads to image/title/site. Same themed card in conversation, composer and unfolded Shared. Authenticated local images; fixed image space; same-attachment downloads serialized to prevent cold-cache races. Removed preview persists phone-locally across echo and restart.
- Sending/Sent/Delivered/Read facts retained and monotonic. HTTP acceptance is Sent; delivered/read require service facts. Existing failure/retry retained.
- Typing uses a separate queue, bounded refresh, stop on clear/idle/background/exit, own-handle filtering and incoming TTL expiry. Presence does not create a conversation message or unread count.

Backend branch `messages/imessage-parity-api`, isolated worktree `soma-imessage-parity-api`: code-only baseline f20b597; native metadata 8e6bfdf; public metadata resolver e2e00a5. Additive API contract in `docs/rich-links-v1.md` there. Existing authenticated attachment route serves local preview bytes. Public resolver pins vetted public IPv4, rechecks redirects, bounds request time/bytes/dimensions and caches. Tests inject HTML/images; no real X fetch or account.

### Checks and reviewed evidence

Evidence directory: `~/Projects/soma-imessage-parity-evidence/`. Dedicated `Fold8-ParityBuild`, emulator-5560, debug APK only. Virtual cover 160 Hz/render 53.3 Hz; inner 60 Hz. No 120-Hz physical-device claim.

- Messages JVM: 64 tests, zero failures. Debug APK assembled. Reconciled baseline also passed shared composer desktop tests.
- Backend Swift: 46 tests, zero failures. Python runtime: 22 collected, 21 passed, one adapter suite skipped (external Hermes checkout unavailable). All transport clients are fakes.
- Motion walk: 38 PASS / 0 FAIL / 9 LOOK, all 21 captures inspected. Reading-region pixels `(0,300)-(1248,1750)` before leave and after reopen identical. Covers settled latest, older reading, return, fold endpoints; intermediate animation continuity remains unmeasured.
- First rich UI runs: failed keyboard-open reply assertion, with actual unintended “1 new” pill. Retained under `group1-ui` and `group1-final`; not relabeled PASS.
- After follow-intent fix: `group1-follow` main walk 38 PASS / 0 FAIL / 6 LOOK; incoming/outgoing metadata, read label, cover/unfolded, draft, send/reply with keyboard open. Separate typing on/off each 1 PASS / 0 FAIL / 1 LOOK. Screenshot review found a remaining shared-image download race despite text assertions passing; fixed and retested below.
- `group1-images`: 35 PASS / 0 FAIL / 6 LOOK; cold-cache images, draft removal, send, echo and restart. Separate typing on/off each 1 PASS / 0 FAIL / 1 LOOK. All 29 captures (21 UI and 8 warm-send frames) inspected, including both thumbnail frames. No claim of receiver wire acceptance.
- Warm stalled DEMO send: one bubble with Sending before server resumes, one echo + synthetic reply afterward; keyboard closes at latest. Bubble visible in second capture, host dispatch-to-capture-return 302.8 ms (first capture returned 169.7 ms without bubble). n=1, coarse ADB/capture bound, not input-to-present latency or evidence of improvement over the earlier 319.5 ms observation.

- Older-reading arrival probe: 9 PASS / 0 FAIL / 3 LOOK, all 6 captures inspected. Reading region `(0,320)-(1248,1600)` pixel-identical after two demo sends and two synthetic replies, with a 4-new pill and working Latest. An initial probe used the wrong fixture ID and returned 502; corrected before the retained successful comparison.
- Outgoing typing lifecycle: `messages-parity-typing.walk`, 29 PASS / 0 FAIL / 4 LOOK, all 11 captures inspected. A second DEMO SSE observer recorded on/start, refresh 4.243 seconds later, off 5.047 seconds after refresh, another on, then off when backgrounded. Exactly 3 on and 2 off events; idle and resumed screens have no stuck indicator. These are local app→DEMO facts, not live remote typing latency.

### Open gates

1. Native outgoing rich-link transport: pinned RustPush supports `NormalMessage.link_meta`, but Corten does not expose it. Corten repository at pin a8514467 returns 404/unavailable. Historical local files are evicted cloud placeholders; reads and normal download request failed. No FileProvider repair. Phone A requested a code-only source archive from the MacBook Direct thread. `richLinkSend` stays false until extension/encoding tests pass. Phone-local cards are not proof an iPhone receiver gets a rich card. Transport preview suppression also remains dependent on this extension.
2. Current Canvas proposals: `messages-parity-status`, `messages-parity-links`, `messages-parity-typing`, `messages-parity-fallback` version parity-g1-v2; unfolded card `messages-parity-links-unfolded` is a separate emulator screenshot proposal. Late-delivered Phone A mail confirms Boss approved the four parity-g1-v2 designs at ~20:35. Treat those four revisions as approved for building; preserve them. The Canvas signed receipt is pending the next launcher bundle; Phone A will record it. Server checks currently say no-approved because the receipt is not recorded. Do not ask Boss to approve those four again or fabricate a receipt. The separate unfolded screenshot proposal remains unapproved. Generator under `docs/design/messages-parity`; offline artifact messages-parity-group-1.html v1.
3. Boss will restore the iOS Simulator. Exact gesture/spring/IME/effect motion and physical 120-Hz feel remain pending. No clips requested, per Boss's decision.
4. Real backend deployment, recipient-visible rich links, actual typing/receipt behavior and phone acceptance require a separate authorized phase. Live Direct service untouched.

5. Design comparison explicitly against the relayed approved v2 proposal found inherited Soma details that the mock simplified (tails, outlined avatar, plus-button tile, timestamps/alignment) plus card border/status differences. Initial links shot also used the wrong composer state. The approved revisions are intact. A matching composer-state capture is being checked; Phone A has the request to resolve remaining differences with Boss while honoring the original no-aesthetics scope. Do not claim design match or silently restyle the existing app.

Phone A clarified at 21:10 that rank is priority, not a hard blocker. Native outgoing rich-link encoding is the one deferred group-1 piece while the code-only export is pending. Group 2 proceeded independently.

## Group 5–7: implemented and emulator checked; design review pending

- New local sends have a short scale/24-dp entrance using the shared Motion spring. A bounded, single-use marker prevents history/reconnect/echo replay; system reduced motion skips the entrance. This is provisional motion, not a measured reconstruction of the iPhone composer trajectory.
- Double-tap and hold open reactions for text/photo/video/voice. Existing optimistic add/change/remove remains. Reaction attribution survives persistence and uses contacts or explicit participant names, never a custom conversation title. Badges animate without replaying unchanged state.
- Horizontal gesture arbitration preserves vertical scrolling and the navigation edge. Left pull reveals per-message times while held; release restores the bubbles. Right pull keeps threshold/cancel reply behavior. A single photo no longer installs an unnecessary horizontal scroll interceptor.
- Menus use a real modal window with accessible focus and Back. Underlying inbox semantics are hidden while covered; a modal drag cannot navigate the chat. Host focus is cleared when opening a menu so dismissal cannot reopen an already closed keyboard or lose its draft. Existing Manga styling is retained; `soma-theme` confirmed Manga.
- Phone-local ordered pins (up to nine) and mute choices persist separately from history. Only one conversation action row stays revealed. Swipes, hold menu and accessible actions provide read/unread, pin/unpin and mute/unmute. Mute gates notifications without clearing unread. Read/unread updates immediately, preserves new arrivals during a request, rolls back visibly on refusal and supports retry. No Apple preference synchronization.

### Checks and reviewed evidence

App JVM **71 tests, zero failures/errors/skips**, debug APK assembled. Python walkthrough/review generator syntax and `git diff --check` pass. Only the dedicated emulator-5560 received the APK; backend code and live services were untouched in this group.

Evidence is under `~/Projects/soma-imessage-parity-evidence/`:

- `group2-fixed/walk`: **78 PASS / 0 FAIL / 16 LOOK**, all **51 captures inspected**. Pin/mute/restart, manual unread, own reaction add/change/remove, attribution, reply/cancel, send, back cancel/commit and single-row list reveal. This precedes the targeted fixes below; it is not relabeled as a new run of the final APK.
- `group2-final/focused`: **44 PASS / 2 FAIL / 9 LOOK**, all **28 captures inspected**. Correct attribution and readable modal bars; photo/video/voice double-tap passes. Two failures exposed photo swipe-to-reply and its dependent cancel step. Retained as failures.
- After removing the single-photo scroll interceptor, `photo-fixed2`: **12 PASS / 0 FAIL / 2 LOOK**, all **7 captures inspected**. An earlier rerun was blocked by an old pairing intent restored after install; force-stop/clean launch resolved that fixture problem. Failed artifacts remain available.
- `times`: hidden → held-visible → released-hidden assertions pass; all **3 captures inspected**, with DOWN/MOVE/UP trace. This establishes actual held reveal, not only a settled screen.
- `focus`: keyboard initially shown, then dismissed; reaction change leaves it hidden, retains the draft, and modal drag keeps the chat. All **3 captures and the replacement native reaction recording inspected**. The first recording exposed keyboard reopening; that failure drove the host-focus fix.
- `unfolded-fixed`: **27 PASS / 0 FAIL / 3 LOOK**, all **13 captures inspected**. Same pinned/muted rail, actions and reactions beside existing Shared. First unfolded run selected the repeated header title instead of the avatar; retained, then targeted the rail explicitly.
- `notifications`: **0 notification records while muted, 1 after unmute**, pin stays above another chat's newer arrival. All **3 captures inspected**; synthetic DEMO events only.
- Authenticated local DEMO failure proxy: read refusal **19 PASS / 0 FAIL / 3 LOOK**, retry **6 PASS / 0 FAIL / 1 LOOK**; all **12 captures inspected**. Prior count restored, visible refusal, successful retry clears error and marks unread.
- Native send/reaction clips and reduced-motion send inspected. Recordings are approximately **5.7 fps** and establish behavior/endpoints only; they cannot judge fine spring timing or 120 Hz. Retained final gfxinfo has just **19 mixed startup frames, 12 janky, p50 25 ms, p90 350 ms** under debug/emulator/automation; this is diagnostic, not a scenario benchmark or evidence of improvement. Physical feel, haptics, 120 Hz and matching iPhone motion remain open.

The final DEMO harness stopped, deleted its temporary credentials/data and cleared the test pairing. The AVD can be reused for the next group. No phone install, real message, live Direct restart/deployment or registration.

### One Canvas review batch

**DESIGN UNAPPROVED**, version `parity-g2-v1`. Twelve capture-based proposals: `messages-parity-send-motion`, `messages-parity-reaction-menu`, `messages-parity-reaction-attribution`, `messages-parity-gesture-reply`, `messages-parity-gesture-times`, `messages-parity-chat-actions`, `messages-parity-pinned-muted`, `messages-parity-list-swipe`, `messages-parity-list-read`, plus unfolded size variants `messages-parity-pinned-muted-unfolded`, `messages-parity-chat-actions-unfolded`, `messages-parity-reaction-menu-unfolded`. The last three are size variants of existing behaviors, not new features. Group-1 approved proposals remain unchanged.

Offline artifact `messages-parity-group-2.html` embeds native captures and two recordings. Source manifest/generator: `docs/design/messages-parity/group2.json` and `review.py`. Desktop/mobile renders inspected; all twelve mobile selections and desktop navigation pass with network disabled, zero external requests or script errors, all images loaded and no mobile horizontal overflow. Phone A receives one batch for Boss review.

Groups 8–10 and 11 follow. Native rich-link encoding will resume when the code-only source export arrives. Exact iOS motion tuning and effects remain dependent on the restored reference and transport feasibility; no live deployment is authorized.
