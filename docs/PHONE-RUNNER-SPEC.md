# The phone as a runner: its sessions reachable from Soma desktop

Boss, 2026-10-28: "the phone sessions need to be reachable from the Mac Pro too." Soma desktop lists and drives the
Fold's own sessions (the Phone A/B/C builder tabs, Phone D, the phone's other chats) as it does a Mac's threads: read,
send, see working/idle, answer approvals and question cards (his tap), stop. Today the phone has no such door: only
agent mail in, and the herd reporter on :8479.

Shape: the Fold serves the runner's own `/threads` protocol (MAC-RUNNER-SPEC.md, as `tools/mac-runner/oma-runner`
answers it), so the desktop's `MacThreadsEngine` treats the Fold as one more computer. The phone-side code is built by a
phone builder on the Fold; the desktop side and a contract test by the Mac session. This is the spec between them.

## Where it listens, and who may call it

- The phone's Tailscale address only, port **8478** (the runner's; 8479 stays herd's). Never the LAN or loopback-to-all.
- A key per device, as the Macs' runners (MAC-RUNNER-SPEC "a key per device"): the phone keeps only each key's digest,
  compares in constant time, and answers 401 and nothing else without one. Every request also has to come from one of
  Boss's own tailnet devices (Tailscale whois), as the runner checks.
- Pairing: in the phone's Soma settings, "Let a computer reach this phone's sessions" → picks a tailnet machine →
  makes a key, keeps its digest, sends `soma-runner-galaxy-z-fold8.json` (`{url, key, name: "Fold", runner:
  "galaxy-z-fold8"}`) to that machine by Taildrop, the same file `oma-runner pair` sends. Soma desktop already takes
  that file into the Keychain (RunnerKeys). Unpair drops the digest. The key is never shown, logged or mailed.
- It runs for as long as the phone's builders can run (whatever keeps them alive in the background now; the phone builder
  knows which). When the phone
  is asleep or off the tailnet the desktop shows "Fold unreachable" as for a Mac; nothing waits on it.

## What it answers (a subset of the runner's protocol, same JSON)

| Request | The phone's answer |
|---|---|
| `GET /health` | `{ok, runner: "oma-phone", threads, working, waiting, kinds}`; kinds = the agents its builders run (`claude`, `codex`). |
| `GET /threads` | Every phone session: id, name, summary, status (`idle`/`working`/`waiting`/`interrupted`), last_worked, tail, pending (approvals waiting, with nonce), kind, model, `home: "phone"`. |
| `GET /threads/ID` | One. |
| `GET /threads/ID/events[?after=N]`, `GET /threads/ID/stream[?after=N]` | Its events, ids from 1 with no gaps, kinds as the runner's: `message` (Boss's words), `text`, `step`, `result`, `status`, `approval`, `approval_result`, `note`, `error`, `stopped`, `artifact`. Built from the session's own history (the builder task log), not a second store. |
| `POST /threads/ID/messages {text}` | His words to that session, as typed in its tab: `{taken: "now"}` or `{taken: "queued", position}`. |
| `POST /threads/ID/approvals/APPROVAL {decision, nonce, answers?}` | Exactly the runner's rules (MAC-RUNNER-SPEC "Approvals", "Question forms"): only the request's nonce, once; 403 wrong nonce, 404 already answered, 400 bad form, deny without answers = Skip. The answer is his tap on the desktop; nothing on either side answers by itself (MacThreadsTest.nothingButHisTapSendsTheMacAnAnswer). The card on the phone closes when answered elsewhere. |
| `POST /threads/ID/stop` | As the tab's stop: the task and what waited its turn. |

Not served (404): `POST /threads` (no new phone sessions from the desktop in this slice), `/files`, `/projects`,
`/bots`, command-approval memory, `/model`. Can be added later on the same pattern.

## Which sessions, under which ids

- `galaxy-z-fold8/builder-a`, `-b`, `-c`: the builder tabs, named "Phone A/B/C", kind as the builder runs now.
- `galaxy-z-fold8/phone-d`: Phone D is agent-mail only by design (PHONE-D.md), so it is listed, read and stoppable, but
  `messages` answers 403 "Phone D takes agent mail only".
- `galaxy-z-fold8/chat-<id>`: the phone's other chats that run on the phone (not the ones that live on a Mac thread:
  those the desktop already reaches on that Mac, and listing them twice would show one session as two).
- The phone's own front agent (the Soma tab) is not listed: the desktop has no front agent (Boss, 2026-10-19).

## What the desktop does (Mac session's part)

- Nothing new in the engine: the Fold is one more `MacMachine` from RunnerKeys; its threads show in the strip with the
  computer's name ("Fold"). New chats are not offered on it (no `POST /threads`).
- A contract test: a fake phone server answering this table, and the desktop's real host against it (as
  DesktopEndToEndTest does with the runner): list, read, send, an approval answered by a tap, a question with Skip,
  stop, and unreachable.

## Open for Boss

- Whether Phone D should take his words from the desktop (here: no, mail only, as PHONE-D.md says).
- The builders run with approvals bypassed (Phone B's Codex flags); a paired key lets that computer send them work.
  That is the same trust a paired phone has in the Mac's runner, now the other way.

## Phone implementation checkpoint (2026-10-28, not installed)

The phone implementation is in `dev.omalauncher.runner`. `Runtime` starts a process-lifetime listener alongside
its existing background runtime. With no paired devices it waits without polling or binding a socket. With a
pairing it binds only a concrete IPv4 address from Android VPN link properties, confirmed by authoritative
whois as owner device galaxy-z-fold8, port 8478 (never a carrier CGNAT interface just because it uses 100.64/10); an absent VPN leaves it unavailable.
No new wake lock or Android service is introduced. Ordinary HTTP requests and streams use separate bounded
worker pools so long-lived reads cannot occupy Stop/approval workers. Streams end after 25 seconds and reconnect
from their cursor; keys are rechecked each heartbeat.

Wire ids are **unprefixed** `builder-a`, `builder-b`, `builder-c`, `phone-d`, `chat-ID`; the desktop's machine key
adds `galaxy-z-fold8/`. An approval's wire id is the unique phone ticket nonce, also provided in `nonce`; the
adapter translates back to the agent's reusable tool id only inside the existing builder ledger. Decisions use
that ledger's validation/claim/reply/withdraw path, including its locked-phone refusal. `deny` of AskUserQuestion
becomes the existing canonical Skip delegation answer, sent via allow; ordinary tool denial remains denial.
Results carry normalized question answers and the authenticated computer's device name. There is no HTTP path
for pairing, new sessions, model changes or approval memory. `MacThreadsTest.nothingButHisTapSendsTheMacAnAnswer`
explicitly adds only PhoneSessions to its pinned approval-caller allowlist for Boss's 2026-10-28 request; front
agents remain forbidden, and an unauthenticated request with a valid ticket/nonce cannot reach that adapter.

`ChatStore` persists the cursor journal **inside the existing oma-chat.json task log** (`phoneRunner`), using the
same store lock and atomic replacement. Producer text, tool steps and artifact changes are appended before the UI tail is trimmed or rewritten; ledger outcomes
are recorded by their nonce rather than by an index into the last twenty UI approvals. Updates append immutable events; queued work, completed task edits,
pruned UI rows and restarts do not renumber ids. A read flushes unsaved live deltas before exposing their ids.
Like the existing finite phone history, this journal has finite retention (10,000 events / approximately 2 MB
of serialized characters across sessions); retained ids remain monotonic, not reset to 1 on pruning. Event pages explicitly mark `first_event`, `history_truncated` and `cursor_reset`; the shared
client's existing gap catch-up handles retained history. A lower last_event also triggers the existing client reset
after log loss. A generation token for the rare case where a lost log overtakes an old cursor before reconnect
remains a contract follow-up; app data clearing also removes pairing digests. Timestamps on the wire are Unix seconds. The native
front stream and Mac-backed chats are excluded. Phone-local chat sends/stops use that tab's existing routing,
with Stop dropping only that chat's queued front work and stopping its active front turn.

### Android Tailscale identity dependency

Android's Tailscale VPN supplies the address but no local CLI/daemon API accessible to this app. The phone therefore
asks an **already paired Mac runner** for authoritative whois, the owner's peer list and Taildrop delivery. It never
uses a claimed header, DNS name, IP-only allowlist or the selected pairing target as proof of caller identity.
Unknown/non-owner callers and wrong device/key are refused. Live whois lookups are cached for at most one minute.
If every Mac helper is unavailable, a paired key may authenticate only from the address last confirmed for that
exact key, for at most seven days since that confirmation (Boss's bounded-cache decision, 2026-10-28).
Cache hits never extend that timestamp. A reachable helper's refusal overrides and clears the matching cached
proof. An older runner's generic missing-route 404 is service unavailability, not an owner refusal; another configured Mac is tried. Invalid helper credentials or malformed responses likewise cannot make an identity assertion. An aged positive does not revoke or renew an existing lease. Address mismatch, expiry and clock rollback fail closed. Rekey/unpair removes that key's old proof.
Legacy digest-only pairings require an online confirmation before offline use. The phone's own VPN address
has the same bounded confirmation rule for rebinding; an absent VPN or a different unconfirmed address never binds.
Loopback and every local interface address are rejected from the raw socket peer before credential lookup,
including the phone's Linux connecting to its own tailnet address. Forwarding headers are not identity.
Unpair checks the live digest table each request/heartbeat. Private phone preferences retain only key digests
and confirmed address/timestamp metadata. Raw generated keys remain in memory for the pairing transfer,
never displayed, logged, mailed or written to a phone file. New pairing requires a reachable helper.

Confirmed helper contract implemented by desktop thread `jacks-mac-pro:taebc3f66` in f84f67a3 (merged; not yet deployed):

- Authenticated `GET /phone-runner/tailnet`: `{ok,self:{name,addresses,os,online},runner,devices:[{name,addresses,os,online}]}`, self is this phone; devices are the runner owner's other peers.
- Authenticated `GET /phone-runner/whois?address=IP`: `{ok,device,owner}`, derived from actual Tailscale whois and the runner owner's login.
- Authenticated `POST /phone-runner/taildrop`: `{device,name:"soma-runner-galaxy-z-fold8.json",content:JSON_STRING}`;
  bounded to that pairing filename and an owner's selected device; no credential-bearing logging.
  Delivery to the helper runner's own computer writes directly to `~/.config/soma/runners/` with mode 0600;
  another owned computer receives it by Taildrop. Only the existing phone credential from the phone's actual
  tailnet identity may use these helpers. Limit: 30 requests/minute; whois and tailnet identity use the runner's
  real Tailscale daemon. Helper tests: 7/7 passed on the Mac; no runner restart has happened in this slice.

A helper-equipped paired Mac must be reachable to pair, confirm a changed caller address, or renew an expired proof. This is an extra
availability dependency; it deliberately fails closed. The phone endpoint and helper belong in the same final installation; run live auth/pairing checks after promotion. The desktop health change is 33c3560f (MacRunnerTest 41/41 per its author): it narrowly accepts `oma-phone`
alongside `oma-runner`. Include that change in the final desktop bundle.

### Verification status

`PhoneRunnerTest`, `PhoneRunnerApprovalTest`, `MacPhoneTailnetTest`, `PhoneTaskEventsTest` and `PhoneRunnerSettingTapTest` cover auth
refusal/wrong device, unsupported routes, Phone D message refusal, nonce reuse, malformed strict forms, locked
ledger refusal, Skip as allow, cursor/restart/queue stability, bounded tail changes, artifact rewrites without replaying unrelated steps,
global task timestamp reservations, and no pairing before a native tap.

Final lane B checkpoint **e59b2f48: PASS** (contains a27dfc0b, f59d13a3 and the original 5370bb71 implementation).
Both `:app:compileDebugKotlin` and `:desktop:compileKotlin` pass. App unit tests: 111/111 (PhoneRunnerTest 6,
PhoneRunnerApprovalTest 3, MacPhoneTailnetTest 1, ApprovalsTest 14, ClaudeProtocolTest 5, PendingApprovalGuardTest 3,
ChatHistoryTest 32, MacThreadsTest 47). Shared desktop tests: 12/12 at a27dfc0b (PhoneTaskEventsTest 8,
PhoneRunnerSettingTapTest 1, WaitingQuestionDraftTest 3), reused unchanged by Gradle at e59b2f48. The strict
approval-caller guard passes with its explicit, documented paired-desktop adapter addition; front agents remain
forbidden. The isolated real-runner/fake-Tailscale helper suite passes 7/7 on both the Mac and Fold Linux.
The production MacPhoneTailnet client also passes its loopback HTTP contract test; none of these uses real keys.

`tools/walkthrough/scripts/phone-runner.walk` uses the production native pairing panel with a fictional backend in
artifact-preview for cover, unfolded and sideways layout/taps. It creates no key, sends no file and calls no tailnet.
The phone steps cover real pairing, listener binding/auth, desktop session traffic, streaming/reconnect, Stop,
nonce races, multi-device question closure and unavailable/asleep behavior.

Emulator checkpoint a27dfc0b, artifact-preview only on shared Fold8 / emulator-5554 under the walks lock:

- phone-runner.walk: 24 PASS, 0 FAIL, 4 reviewed LOOKs (cover, paired cover, unfolded, sideways).
- waiting-question-skip.walk regression: 26 PASS, 0 FAIL, 4 reviewed LOOKs.

The critic reviewed all eight screenshots; Phone B also inspected both contact sheets and a full-size unfolded
panel image. Native Pair/Unpair work in the fake backend, no pairing happens on viewing, text is readable without
clipping, and the close control draws the real Nerd Font glyph with the label "Close phone sessions". e59b2f48
changes only guard tests/documentation: its preview APK is byte-identical to the walked a27dfc0b APK, so no new
walk was required. Earlier checkpoint test/script failures are superseded by this result.

Evidence copied to `/root/Inbox/phone-runner-a27dfc0b-evidence.zip`, SHA256
`aade255526bfd1492ca8545b38182cf5e0eee14cbaa0b623a9b26a8b8f89c45d`; final compile/test report is lane B's
e59b2f48 result. These are fictional preview screenshots, not Shipped launcher records.

**NOT WALKED:** the three real-phone steps in phone-runner.walk and two in the Skip regression. Real Tailscale
identity/pairing, live desktop-to-phone sessions, nonce races/notifications across devices, sleep and real performance
wait for the coordinated launcher/helper promotion. Canvas settings cards could not be read (service timed out);
no Canvas state or Shipped record was changed. No launcher, desktop or runner was installed or restarted.

### Bounded identity follow-up checkpoint (2026-10-28)

Source `9ff68659` (including `fdbcccde`, `dbde38bd`, `9cb3d257`) passed lane B's compile/auth checkpoint:
82 requested tests, no failures: PhoneRunnerIdentityTest 7, PhoneRunnerTest 7,
PhoneRunnerApprovalTest 3, MacPhoneTailnetTest 1, MacThreadsTest 47, ApprovalsTest 14,
PendingApprovalGuardTest 3. The critic independently probed stale positive confirmations with a valid
lease, without a lease, and with a mismatched device; all behaved correctly. Review findings resolved:
read the clock after lookup; distinguish missing/invalid helper service from an authoritative identity
refusal; preserve but never renew a bounded lease on an aged positive. No UI changed in this follow-up;
the prior 24-step phone-runner and 26-step question-Skip emulator walks and eight reviewed LOOKs stand.
The added real-phone offline/local-source walk remains deferred until owner promotion and helper deployment.
No launcher install, runner restart, actual pairing or live work was performed by this checkpoint.

### Desktop reply compatibility follow-up

Approval success includes `{ok:true,decision:"allow"|"deny"}` echoing the wire decision after the existing
ledger succeeds; stop success includes `{ok:true,stopped:true}`. These fields are required by the shared
desktop parsers. The TCP adapter validates/authenticates `/stream` through the protocol and then writes
HTTP `text/event-stream`, not the protocol's internal JSON page. It emits id/event/data frames and
keep-alive comments for 25 seconds with per-second identity rechecks, then permits cursor reconnect.
PhoneRunnerContractTest exercises actual protocol replies through parseMacDecision/parseMacStopped
and the production stream writer through followMacStream, including a nonzero resume cursor.

Desktop contract checkpoint `d2da64b5`: lane B compile and 62/62 tests passed, including
PhoneRunnerContractTest and DesktopEndToEndTest.theFoldsSessionsAreReachedLikeAComputersThreads.
The production SSE writer was accepted by the actual shared desktop reader. Reviewer note: phone stop
currently acknowledges idempotently with stopped:true even for an idle session, rather than exposing
whether it had been running. No UI change, new walk, install, pairing or runner deployment occurred.
