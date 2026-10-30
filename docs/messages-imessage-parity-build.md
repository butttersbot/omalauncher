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

### Open gates

1. Native outgoing rich-link transport: pinned RustPush supports `NormalMessage.link_meta`, but Corten does not expose it. Corten repository at pin a8514467 returns 404/unavailable. Historical local files are evicted cloud placeholders; reads and normal download request failed. No FileProvider repair. Phone A requested a code-only source archive from the MacBook Direct thread. `richLinkSend` stays false until extension/encoding tests pass. Phone-local cards are not proof an iPhone receiver gets a rich card. Transport preview suppression also remains dependent on this extension.
2. Current Canvas proposals: `messages-parity-status`, `messages-parity-links`, `messages-parity-typing`, `messages-parity-fallback` version parity-g1-v2; unfolded card `messages-parity-links-unfolded` is a separate emulator screenshot proposal. All DESIGN UNAPPROVED at last check. Never self-approve or mark matched without an approved reference. Generator under `docs/design/messages-parity`; offline artifact messages-parity-group-1.html v1.
3. Boss will restore the iOS Simulator. Exact gesture/spring/IME/effect motion and physical 120-Hz feel remain pending. No clips requested, per Boss's decision.
4. Real backend deployment, recipient-visible rich links, actual typing/receipt behavior and phone acceptance require a separate authorized phase. Live Direct service untouched.

Groups 5–7, 8–10 and 11 have not started while the first group's native transport prerequisite is outstanding.
