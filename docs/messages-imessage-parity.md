# Soma Messages: iPhone iMessage usability parity

2026-10-29 — Luna. AUDIT + PROPOSED BUILD PLAN ONLY. Phone A review and Boss approval must precede implementation. No aesthetics changes; retain the active Soma theme.

Requested scope: smoothness/animations/snappiness; rich links in both directions (especially X); immediate sending and truthful status/typing; reply/time/list/back gestures; send/tapback/bubble/screen effects; inline threads, edit/unsend, pins, conversation search. No unrelated product additions are included.

## Evidence and limits

- Plan branch `messages/imessage-parity`, isolated worktree `/Users/jackkim/Projects/omalauncher-imessage-parity`, created from `fork/phone/fold-development` at `b4fe1f035108c264807d03d67b0d4b1c0e66a308`. Active Mac checkouts preserved.
- That integration snapshot does **not** contain the Messages module. Audited latest Messages history at `4b6283e03f4a30a0e7f6383e7ba6bc846a181027`, version 0.8.6/code 27, in the existing separate read-only source worktree `/Users/jackkim/Projects/soma-messages-086-build`. An eventual implementation must first reconcile the Messages branch with the integration branch, preserving shared composer/theme changes. This document does not merge app code.
- Current-run Fold8 demo evidence: `/Users/jackkim/Projects/soma-imessage-parity-evidence/`. Uses the already-installed 0.8.6 APK, temporary demo credential/data and a spare port, never production port 8765. Demo sends are synthetic, with fake responses; not iMessage transport acceptance.
- iOS Simulator: `xcrun simctl list devices booted` has no booted iPhone; runtimes contains only watchOS 26.4. Xcode has iOS SDKs, which are not a runtime. simctl reports package installation requires authorization. No runtime installed or benchmark fabricated.
- Mac Messages: computer-control discovery reports `com.apple.MobileSMS` running; both display-name and bundle-ID inspection fail `cgWindowNotFound`. No accessible native window; no Mac UI benchmark, account interaction, or message send performed.
- Apple documentation below establishes functional behavior. Exact iPhone timing, spring curves, haptics, keyboard choreography and ProMotion feel remain unobserved. The requested no-jump behavior is an acceptance target, not a claimed measured iPhone baseline.
- Backend: read `~/ClaudeSync/codex-memory/soma-messages-current.md`; do not infer current health from that checkpoint. Read frozen `e32d0ec` contract using Git in `/Users/jackkim/Projects/soma-imessage-service`, plus code-only Direct source staged at `~/Soma/iMessage-Direct/source`. No production endpoint probed, restarted, re-registered, paired or messaged. Local Direct code shows adapter paths, not current deployed live acceptance.
- Metrics and visual results are recorded below after the current demo run. No existing historical tests or screenshots are treated as this audit's measurements.

## Ranked plan

Sizes are relative: S = localized UI wiring; M = several app paths plus fixtures; L = cross-layer behavior or new protocol/media support. Risk describes implementation uncertainty. Each item requires cover **1248×1972** and unfolded **2448×1848** walks. Named walks below are proposed acceptance scripts, not implemented tests. Every changed visible state needs an Approved-reference Soma Canvas card later, with `shot ... card=...`; preserve current tokens/type/color/bubble look.

### 1. Keep the viewed message fixed through opening, history, keyboard and incoming messages

- **iMessage:** Boss's required benchmark is opening at the intended place, stable keyboard/arrival behavior, smooth scrolling and back/list transitions. Apple documents inline reply/navigation but does not specify frame budgets. Exact motion needs reference capture.
- **Soma today (source):** `Screens.kt:329–415` restores an index/offset, tracks whether at latest, compensates prepended history and scrolls on viewport changes. It already avoids forcing readers to bottom and has a Latest/new-count pill. However `itemsIndexed(c.messages)` at line 440 has no stable key; index restoration and several independent scroll effects can conflict during history/IME/echo updates. `Messages.publish` serializes the entire book synchronously before queuing disk IO; history publishes one message at a time. These are credible profiling candidates, not proven root causes of all jank.
- **Change:** persist a stable message identity + offset; keep a stable rendering identity across clientId→server-ID reconciliation, including multipart rows. One owner of scroll intent for initial layout, history prepend, viewport resize and arrivals; preserve user scroll when reading. Coalesce history publishes and move expensive serialization off the UI path after profiling. Reuse transition/predictive-back behavior; fix confirmed conflict rather than replacing working motion wholesale.
- **Ownership / size / risk:** app; **L / high**, because IME, paging, media dimensions and folding interact. No `/v1` addition required.
- **Proof:** proposed `messages-parity-motion.walk`: 120-message+variable-media fixture; cold/warm open at latest; read older message; prepend history; keyboard up/down repeatedly; incoming burst both at latest and scrolled up; pending→echo replacement; fold/unfold; back cancel/commit. Compare message ID and offset before/after, not just screenshot text. No accidental scroll to another message or one-frame flash of the wrong end. Phone phase after promotion measures touch/IME/120-Hz feel. **Canvas:** chat, keyboard-open chat, new-message pill, list/chat transition endpoints.

### 2. Rich link cards: image, title and site, incoming and outgoing

- **iMessage:** Boss reports that the same X link has an image there. Apple's Link Presentation model carries original/canonical URL, title, icon/image/video, and can pass already-fetched metadata into Messages. This does not establish that every public/private/deleted X post can be previewed.
- **Soma today:** `Parts.Link` contains URL only; `PartContent` (`Screens.kt:748`) draws host/path/arrow. Composer preview (`FieldTiles`) and unfolded shared table also draw text-only cards. `MacWire.Msg` and `/v1` Message have no rich-link metadata. Thus a different image loader alone cannot fix the missing data. Demo card inspection below confirms the current rendering, not live X extraction.
- **Change:** preserve an incoming iPhone rich-link payload and assets first; normalize it to a card without making the phone scrape X. For outgoing links, asynchronously resolve/cache metadata on the Mac, show a stable placeholder in composer/sent bubble, and never delay send. Make preview removal explicit and persistent. Add transport encoding for rich links if necessary so an outgoing Soma link also reaches iPhone as a rich link. Support no-image/failure/offline states and ordinary URLs; use Boss's exact failing public URL as the regression case.
- **Ownership / size / risk:** **both; L / high**. Proposed additive `/v1` fields: `Message.richLinks:[{url,canonicalURL?,title?,site?,imageAttachmentId?,width?,height?,state}]`, available in history and `message.updated`; reuse authenticated attachment serving. Proposed `POST /v1/link-previews {url}` for draft resolution and optional send `richLinks`/preview-suppression fields, preserving old send semantics. Backend review must prove payload decoding/transport encoding before committing to these field names. Cache; bounded timeouts/bytes/redirects; reject local/private network fetch destinations at every redirect. No third-party proxy or new X login is assumed.
- **Proof:** `messages-parity-links.walk`: incoming payload fixture and outgoing draft/send/echo on X, article, image-free page, redirected URL, failure and offline cached link. Inspect actual thumbnail/title/site in both directions and reopen/restart; missing metadata must not move the reading anchor or block send. Later explicitly authorized iPhone receive test verifies wire behavior. **Canvas:** composer preview, incoming/outgoing card, unfolded shared card, fallback states.

### 3. Immediate sending with truthful Sending / Delivered / Read / failure

- **iMessage:** send is locally responsive; delivery/read are network/recipient facts. Apple supports retry on failure and read receipts; exact visible timing and latest-run placement need iPhone capture.
- **Soma today:** `Messages.send:213` publishes `real.waiting(...)` before enqueueing network send. Client IDs reconcile echoes and failure has a retry line already. `/v1` supplies `delivered`, `read`, `failed`, but `MacWire.msg` drops these fields; the app cannot display delivery/read status. HTTP success must not mean Delivered. Current-send latency is measured separately below.
- **Change:** retain existing optimistic path; persist explicit pending/accepted/delivered/read/failed facts, render status under the appropriate outgoing run, reconcile once without duplicate bubbles, and retain retry semantics when an apparently failed request is later echoed. Do not claim group Read from the existing false/unknown group field.
- **Ownership / size / risk:** **app first, backend qualification; M / medium**. Existing `/v1` fields and `message.updated` suffice for basic booleans. Add optional `deliveredAt`, `readAt`, status knowledge/capability and per-recipient state only if source transport can substantiate them; no fabricated timestamps. Confirm Direct receipt→history→event propagation before live readiness.
- **Proof:** `messages-parity-send.walk`: fake server deliberately stalls acknowledgement; bubble appears locally; then ordered delivery/read updates, out-of-order/duplicate events, timeout→late echo, retry and reconnect. Trace input→first bubble independently of HTTP completion. Proposed target: warm local send visible within **100 ms** on physical Fold, with observed distribution reported; this is a proposed threshold, not measured iPhone timing. **Canvas:** sending, delivered/read, failure/retry.

### 4. Typing indicator and outgoing typing lifecycle

- **iMessage:** shows counterpart typing; exact onset/expiry and group attribution need a real reference.
- **Soma today:** frozen `/v1` already has `/chats/{id}/typing {on}` and SSE `typing {chatId,sender,senderName,on}`. Direct code polls transport events every 750 ms. `MacLink.take` ignores typing, and the composer sends none. This is an app gap with a backend latency floor to measure, not a missing endpoint.
- **Change:** typing state per chat/person, refreshed expiry, ignore own stale indicators, clear on stop/send/background/disconnect. Debounce outgoing typing and send stop on lifecycle exits; do not put presence behind the serialized attachment-send queue. Reserve indicator space without pushing a reader's anchor.
- **Ownership / size / risk:** **app + backend verification; M / medium**. No required new `/v1` route. Optional event expiry/server time is additive if necessary; profile the existing 750-ms poll rather than promise instant remote presence.
- **Proof:** `messages-parity-typing.walk`: deterministic on/refresh/off, message arrival, two participants, missing stop, background/reconnect and switching chats. Indicator never remains stuck or scrolls older reading. Measure event→UI separately from remote typing→server latency. **Canvas:** typing row and group attribution.

### 5. Send animation and Tapback response

- **iMessage:** Tapback is attached to its message; double-tap or hold opens reactions, and own reactions can change/remove. Exact pop/send trajectories remain capture-dependent.
- **Soma today:** bubble insertion has no explicit entry animation; `Tapbacks` draws a static row beneath the bubble. Long-press menu offers six kinds; `Messages.tapback` already updates locally before network acknowledgement, including own change/removal. Preserve that responsiveness. No double-tap reaction entry. Sender identity is reduced to `me:`+kind, losing the full attribution already present in `/v1`.
- **Change:** animate local send from composer into its final bubble without delaying state publication; preserve stable bubble bounds during echo. Animate reaction add/change/remove, double-tap entry and reaction attribution using existing payload. Keep the theme, respect system reduced motion, and avoid replaying effects on reconnect/history.
- **Ownership / size / risk:** **app; M / medium**. Existing `/v1` tapback fields/routes cover basic parity; arbitrary sticker Tapbacks need separate transport qualification within this scope, not an unsupported menu promise.
- **Proof:** `messages-parity-reactions.walk`: send while typing/attachment preview, add/change/remove own reaction, incoming reaction, duplicate/replayed SSE, menu cancel; cover/unfolded and reduced motion. Compare recordings to approved reference after capture. **Canvas:** reaction menu, badge stack/attribution, send endpoints plus motion reference.

### 6. Finish message gestures: time reveal and conflict-free reply/back

- **iMessage:** swipe right on a bubble replies; swipe left reveals all message timestamps (Apple Send and reply guide). Back is navigation, not a message action.
- **Soma today:** 64-dp right-drag reply with threshold haptic and spring-back exists (`Balloon:1314`); `Sliding` has predictive back and right-drag navigation, 35%-width or 500-dp/s commit. No left-drag timestamp implementation. Text, media and blank space use overlapping gesture ownership; tactile quality is unverified.
- **Change:** add reversible timestamp reveal with consistent per-message time positioning, explicit gesture arbitration and cancel behavior. Preserve working swipe-reply and predictive back; reserve edge navigation vs bubble reply to prevent accidental actions, using captured iPhone behavior as reference while respecting Android navigation.
- **Ownership / size / risk:** **app; M / medium**. Message `at` already exists; no `/v1` change.
- **Proof:** `messages-parity-gestures.walk`: drag/cancel/commit in both directions on own/incoming text/link/photo; hold vs swipe; scroll diagonally; edge-back cancellation and completion with keyboard/menu open. Mid-drag screenshots plus input trace establish reveal; a screenshot after release alone cannot prove it. Phone phase checks threshold/haptics. **Canvas:** time reveal, reply affordance, navigation endpoints.

### 7. Conversation actions: read/unread, pin/unpin, mute/unmute

- **iMessage:** swipe action can mark unread; pinned conversations stay at the top; Hide Alerts mutes a conversation. Exact action placement depends on iOS version; confirm reference.
- **Soma today:** `ConversationRow` is clickable only; sorting is descending lastTime; model has no pin/mute. Opening clears local unread and calls `/read`. Frozen backend already supports `/read` and `/unread`. `Alerts` currently has no per-chat mute gate.
- **Change:** add reversible list swipes and equivalent accessible menu actions; persist pin order and mute choice on phone, apply mute before notifications, keep unread independent of delivery/read receipts. Pin ordering must survive new messages/restart. Do not claim Apple-device synchronization from local preferences.
- **Ownership / size / risk:** **app; M / medium** for phone-local pin/mute and existing read/unread. If Boss requires Apple-device pin/mute sync, **both / L / high**: propose optional chat `pinned`, `pinOrder`, `muted` plus `POST /v1/chats/{id}/preferences`, only after proving native sync support. This is an open behavior decision, not included infrastructure work by default.
- **Proof:** `messages-parity-list.walk`: read/unread swipe and undo/reversal, pin incoming/outgoing reordering, mute receive notification suppression and unmute, restart/fold, accidental short swipe. Read/unread endpoint failures roll back visibly. **Canvas:** actions, pin section, mute marker.

### 8. True inline reply threads with context navigation

- **iMessage:** replies quote their target and can focus a thread; tapping the blurred background returns to the main conversation (Apple Send and reply guide).
- **Soma today:** outgoing reply sends a genuine `replyTo` when `Messages.quoting` has the ID. Incoming reply renders a quoted text snippet from loaded history. The UI stores display quote text separately; message model does not retain a structured reply target, and persisted draft quote can outlive the in-memory target map. No focused thread/context jump.
- **Change:** retain/persist reply target ID and message part with draft and message; resolve unloaded target via backend; tap quote/thread affordance to focused context, return to exact prior anchor, preserve draft and keyboard. Do not silently send a restored quoted draft as unthreaded text.
- **Ownership / size / risk:** **both; L / medium-high**. Existing `replyTo` and send fields remain. Proposed additive `GET /v1/messages/{id}?chatId=...` and context/thread paging (`GET /v1/chats/{id}/threads/{root}?before=...&limit=...`) for complete uncached context; optional explicit `replyPart`/`threadRootId`. Backend review must confirm part mapping and full-history traversal.
- **Proof:** `messages-parity-threads.walk`: reply to loaded/unloaded/photo/multipart target, nested replies, target edit/unsend, draft then process restart, back to exact parent reading place. Server records expected target ID and part; visible quote alone is insufficient. **Canvas:** quote, focused thread, context return.

### 9. Edit and Undo Send with eligibility/history

- **iMessage:** Undo Send within 2 minutes; edit up to five times within 15 minutes; Edited opens prior versions; unsend leaves a transcript notice. Compatibility limitations apply (Apple guide).
- **Soma today:** hold menu exposes Reply/Copy/Tapbacks, no Edit/Undo Send. `MacWire` ignores `edited`; unsent removes the row. Backend already has `/messages/{id}/edit {text,chatId?}` and `/unsend {chatId?}`; Direct source maps both to transport calls. No live test in this phase.
- **Change:** show eligible own-message actions, inline edit/cancel/save, Edited/history, and unsent notice; preserve stable scroll position; handle expired, unsupported or failed operations explicitly. Server authoritatively enforces eligibility; do not infer success from a locally removed bubble.
- **Ownership / size / risk:** **both; M–L / high** due compatibility/history. Keep existing endpoints; additive fields `editedAt`, `editCount`, `editHistory`, `unsentAt`, and server-provided eligible actions/deadlines (or a capabilities object) where transport/history can substantiate them. Preserve the existing `unsent` meaning while rendering a tombstone locally.
- **Proof:** `messages-parity-edit.walk`: before/after each deadline, fifth/sixth edit, cancel, offline/server rejection, incoming edit/unsend of a quoted target, old client parsing. Later owner-authorized iPhone checks verify recipient result. **Canvas:** actions, inline editor, Edited/history, unsent notice/error.

### 10. Search within a conversation, including older uncached history

- **iMessage:** search can be limited to a person/conversation and opens the matching message (Apple Search guide).
- **Soma today:** no search UI in inbox/thread. Phone only caches fetched history; a local filter alone would silently miss older messages. Direct backend wraps history search, and the legacy raw debug search exists, but no normalized `/v1` search contract.
- **Change:** conversation search entry, clear/loading/no-result/error states, result snippets and match highlighting; open match with surrounding context, next/previous, cancel restores the old anchor/draft.
- **Ownership / size / risk:** **both; M / medium**. Proposed additive `GET /v1/chats/{id}/search?q=...&cursor=...&limit=...` → normalized hits and opaque nextCursor, plus context loading from item 8. Do not couple production phone UI to `/raw` debug formats.
- **Proof:** `messages-parity-search.walk`: hit older than first 50 cached messages, unicode/emoji, several hits, no hits, edited/unsent result and interrupted query. Assert exact message ID and surrounding context; never label a partial local search complete. **Canvas:** search input/results/highlight/no-result.

### 11. Bubble and full-screen effect fidelity

- **iMessage:** hold Send to preview bubble effects (Slam, Loud, Gentle, Invisible Ink) or swipe through screen effects; recipient sees the effect. Apple documents replay/motion preferences and several automatic phrase effects. Exact repertoire/choreography needs version-matched reference.
- **Soma today:** neither app message model nor `/v1` exposes effects, picker or playback. Direct sidecar can observe `effect`, but the Soma `poll_events` filter drops it and DirectBackend largely acknowledges events after native history appears. Adding animation only in Android would lose received/sent protocol meaning.
- **Change:** first preserve incoming effect identity/history; then deterministic one-shot playback, replay, Invisible Ink reveal, reduced-motion behavior and outgoing preview/send of supported wire effects. Do not equate an arbitrary local confetti animation with iMessage parity. Agree an exact named bubble/screen matrix after reference capture; every requested built-in effect must be tracked supported/blocked rather than silently omitted.
- **Ownership / size / risk:** **both; L / high**. Proposed additive `Message.effect:{id,kind,...}` in history/events and optional `effect` on existing sends; optional capabilities listing IDs genuinely supported. Runtime bindings/patch may be needed for send encoding; inbound metadata must survive native-history merging. No backend deployment or binding change now.
- **Proof:** `messages-parity-effects.walk`: fixture per effect, initial arrival vs history/reconnect/reopen, replay, background pause, Invisible Ink concealed/revealed, reduced motion, preview cancel and send. Frame trace under effect load; later authorized iPhone receive/send comparison. **Canvas:** picker/previews/playback endpoints with motion recordings.

## Execution sequence after approval

Approve scope/rank first, then integrate latest Messages into an isolated build branch. Implement 1–4 as the foundation; 5–7 next; 8–10 with normalized context/eligibility/search additions; 11 after effect transport feasibility and reference capture. Rich-link and effect backend investigation can proceed separately from app slices after authorization. Heavy builds stay on this Mac. Reuse demo fixtures and add meaningful parser/reconciliation/gesture/contract tests alongside each approved slice, not implementation-mirroring tests for this document.

For performance, record device/build/theme/refresh mode, scenario and sample counts. Capture Android FrameTimeline/Perfetto and warm/cold local send latency; report percentile frame duration, missed deadlines and worst stalls. Proposed physical target is sustained 120-Hz eligibility with an 8.33-ms frame deadline when the device actually runs at 120 Hz; 16.67 ms at 60 Hz. Emulator graphics statistics are diagnostic only, and its reported virtual refresh modes are not physical 120-Hz proof. Acceptance requires comparison and real Fold [phone] walk after Boss promotes; emulator success is not tactile/120-Hz or real-iPhone acceptance.

All `/v1` additions above are proposed, backward-compatible designs subject to backend-owner review. Existing fields/routes must not be renamed, removed or change meaning. Include old-client parsing, event replay/idempotency, unknown-field handling and unsupported-capability fallback in backend walks. Existing Direct API, Apple registration, pairing and fallback remain untouched until a separate authorized deployment phase.

## Needs a real iPhone / questions via Phone A

1. Which iPhone model and iOS version is the reference? Please provide short redacted clips of existing conversations: open/back/keyboard/arrival; send/status/typing; reply/times/list gestures; Tapback/thread/edit/unsend; effect previews/playback. Recordings establish choreography, not 120-Hz input latency; hands-on comparison/high-speed capture is needed for that claim. No new test message is requested in this audit.
2. Supply the exact public X URL that previews on iPhone, plus redacted incoming/outgoing screenshots or clips. Private/deleted/login-gated posts must have an honest fallback. Do not promise every X link will have an image.
3. Should pin/mute be phone-local initially, or must they synchronize with Apple devices? Recommend phone-local first, explicitly labeled in the plan; no unsupported sync claim.
4. iOS Simulator observation remains outstanding until an iOS runtime is available. Mac Messages observation remains outstanding until a window is accessible. Neither was replaced by guessed measurements. Phone A can arrange these or accept documentation + Boss clips as the interim reference before motion is authored.

## Suggestions (outside requested plan)

None. No aesthetics refresh, unrelated messaging features, new services, account migration or promotion is proposed here.

## Apple sources checked during this audit

- [Send and reply: timestamps, swipe reply, inline thread](https://support.apple.com/en-ie/guide/iphone/iph82fb73ba3/27/ios/27). Functional reference; not an observed animation benchmark.
- [Link metadata and passing metadata to Messages](https://developer.apple.com/documentation/linkpresentation/lplinkmetadata). Supports metadata design, not a guarantee that X extraction succeeds.
- [Keep track: unread and pin](https://support.apple.com/en-lamr/guide/iphone/iphe9b48b89e/27/ios/27).
- [Read receipts](https://support.apple.com/en-mo/guide/iphone/iph5e713a045/ios) and [mute conversation](https://support.apple.com/en-ie/guide/iphone/iph62faab6a4/27/ios/27).
- [Tapbacks](https://support.apple.com/en-gb/guide/iphone/iph018d3c336/26/ios/26).
- [Edit and unsend](https://support.apple.com/en-gb/guide/iphone/iphe67195653/26/ios/26).
- [Conversation-scoped search](https://support.apple.com/en-gb/guide/iphone/iph17c111fb6/26/ios/26).
- [Bubble and screen effects](https://support.apple.com/en-euro/guide/iphone/iphe5c5af4d4/27/ios/27).

Docs span explicitly selected iOS 26/27 pages. Version-specific menu layouts and motion are not assumed identical to Boss's reference phone.

## Current demo measurements

Evidence directory: `/Users/jackkim/Projects/soma-imessage-parity-evidence/isolated/`. Dedicated disposable `Fold8-ParityAudit`, serial `emulator-5560`, cloned from Fold8 with a separate writable image; no other lane used it. Host GPU, Android API 37, active default Manga theme. Earlier attempts on shared `emulator-5554` overlapped another lane's `update-pill` walk; those failures/screens and graphics numbers are **excluded** from the app performance baseline. A second read-only instance was refused by the emulator; the dedicated clone was the successful fallback.

| Observation | Current result | Limit |
|---|---|---|
| Baseline scripted audit | 49 PASS, 1 FAIL, 13 LOOK; all 29 captures reviewed | FAIL 59 expected “Photos & files” in a chat without files; screenshot/source instead show the correct Shared empty state. Original result retained, not relabeled PASS. Fold back initially showed emulator lock screen; no app crash inferred. |
| Focused fold/empty-state check | 9 PASS, 0 FAIL, 2 LOOK; all 6 captures reviewed | Corrected expectation: Shared / Nothing shared yet; after explicit emulator wake/unlock, cover returns to Ana and the synthetic reply. Does not retroactively change the first run's verdict or prove physical folding. |
| Existing reply/reaction UI | Incoming quoted reply, static heart badge, six-reaction hold menu and right-swipe reply verified visually | Hold menu inspected on an incoming message; absence of Edit there is not alone evidence for own-message eligibility. Own-message UI gap is established by source. |
| Existing link card | Demo `tiktok.com` renders arrow + host + path, no title or image | No real X fetch, incoming iPhone payload or external page opened. X-specific extraction still needs the supplied failing URL/payload. |
| Reading-place restoration | Scroll to `Long #87` at top; leave/reopen preserves #87–#101 and their offsets | Pixel comparison of the message region `(0,300)–(490,1750)` in shots 49/52 is identical. One static-content case; no claim about every history/IME race. |
| Latest | `Long #120` visible on first open and after Latest | Settled screenshots; no precise open time or one-frame-jump proof. |
| One warm send with DEMO process stopped | Draft cleared before server response; first capture has no new bubble, second has it while server remains stopped; second capture returned **319.5 ms** after host dispatched Enter | n=1, coarse host/ADB/capture upper bound, not Android input→present latency or iPhone comparison. Input command returned at 29.6 ms; capture windows 29.6–174.4 and 174.4–319.5 ms. Demo resumed afterward; one echo + synthetic reply visible. |
| Keyboard | Sent bubble and fake reply remain latest after keyboard closes | Settled endpoints inspected; transition continuity and precise intermediate anchor offsets unmeasured. |
| Mixed audit gfxinfo | **396 frames, 68 janky (17.17%); p50 25 ms, p90 44 ms, p95 53 ms, p99 200 ms** | Debug emulator, mixed startup/menu/scroll/fold workload; host is running other builds. No per-scenario attribution or physical-device inference. |
| Send + reply + keyboard gfxinfo | **43 frames, 29 janky (67.44%); p50 28 ms, p90 46 ms, p95 57 ms, p99 73 ms** | Reset before send. Small mixed sample; virtual display deadlines affect jank classification. This does not establish that sending itself misses 67% of frames on the phone. |
| Refresh | Cover virtual mode reports 160 Hz, render rate 53.333; inner reports 60 Hz | These modes are not Boss's 120-Hz physical display. Do not call this a 120-Hz benchmark. |

Installed APK SHA-256 matches the existing exact-commit 4b6283e0 artifact: `bce7151820fa6c52ad640bf250708259ed7783bb817c7ffad2f49bcf6efde745`.

Raw supporting files: `walk/results.json`, `walk.log`, `walk-contact-sheet.jpg`, `unfolded-check/results.json`, `gfx-baseline.txt`, `gfx-send.txt`, `display-summary.json`, `send-measure.json`, `apk-identity.json`, and individual PNG captures. Only demo data appears in retained evidence. No new app/backend build or product source edit was performed. Future walks in the ranked items are acceptance plans, not claimed executed coverage.
