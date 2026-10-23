# Native Claude Code sessions in Soma

The runner discovers local Claude Code transcripts from
`$CLAUDE_CONFIG_DIR/projects/*/<session-uuid>.jsonl` (default `~/.claude/projects`).
Native Claude sessions appear alongside native Codex sessions. Phone replies use
Soma's existing Claude stream-json driver with `--resume <same session ID>`;
they are not read-only mirrors and no fork or replacement session is requested.

On first activation, sessions whose transcript was active in the preceding 24
hours are eligible, including the conversation already open when installed.
The boundary is retained in `STATE/claude-discovery.json`; older transcripts become
eligible if used again. Scans run every 15 seconds. Set
`OMA_RUNNER_CLAUDE_DISCOVER=0` to disable or `OMA_RUNNER_CLAUDE_SCAN_EVERY` to tune.

Only owner prompts and visible assistant text are imported. Sidechains, subagent
files, metadata, tool results, thinking and compact summaries are excluded.
Malformed complete records are skipped, unfinished records retried, and large
payloads skipped without blocking later text. Offsets and message IDs persist
across restarts, including IDs emitted through the phone reply driver. Discovery
pauses for a conversation while its runner turn or kept process is active and
rechecks that condition before committing a scan. Runner-owned sessions are not
duplicated. Custom session titles are used when present; otherwise the first
prompt is the title.

Close the native Mac conversation before replying from the phone, even when idle. This resumes the
conversation through another CLI process, just as existing runner-owned Claude
threads do; it is not keystroke injection into the Mac terminal. Native activity
and native permission prompts are not forwarded. New discovered threads use
manual approval through Soma's existing stdio permission tool. CLI resume errors
are shown without falling back to a new conversation; an unexpected init session
ID is rejected while preserving the original ID. No hooks or permissions in the
owner's native Claude configuration are changed. As with native Codex discovery,
local conversations can be outside the roots used for creating new Soma threads.

## Validation and rollout

Run `python3 tools/mac-runner/test-claude-discovery.py` and
`python3 tools/mac-runner/test-codex-approvals.py`, then the broad runner fixture.
Install the updated runner on the MacBook using normal runner deployment.
Restart only after confirming no Soma-owned thread is working or waiting: runner
restart interrupts its own agents. It does not stop native Claude Code.
No Android APK is needed for discovery, reply submission, or transcript events.

Run `tools/walkthrough/scripts/claude-discovery.walk` against the owner's paired
MacBook and Soma after deployment. Until then, live appearance and replying are
unverified.

Implementation verification on the Fold, 2026-10-22:
- PASS: 13 isolated discovery/reply tests, including a real fake-agent subprocess
  round trip through the existing Claude driver, same-session resume, manual
  stdio approval configuration, HTTP submission, transcript deduplication, and a tool approval that pauses until
  the phone decision and then completes in the same conversation.
- PASS: 6 existing Codex approval tests.
- PARTIAL: broad runner fixture previously passed 203 checks, then stopped at its
  bot shell `made.txt` assertion (line 818, FileNotFoundError). Unchanged HEAD
  reproduced it in a separate temporary copy. Remaining checks require a working
  Mac test environment.
- NOT WALKED: actual MacBook deployment and phone screenshots pending live rollout.

## MacBook deployment and live Fold verification, 2026-10-22

MacBook agent deployed commit 273a50a with its existing per-device PHONE default
`galaxy-z-fold8`; launchd environment unchanged and all 11 existing threads idle
before restart. It reports 13/13 discovery, 6/6 Codex approval tests and all 438
broad runner checks PASS on the Mac. Source now matches the documented PHONE
default; no key changes or re-pairing required.

Fold verification:
- PASS: live GET /threads lists four external Claude conversations, read_only=false.
- PASS: POST to the agent-created spare thread t92a627a2c4a4ef95 returns a real
  `pong` text/result and idle status. No tools requested.
- PASS: opened the discovered conversation in Soma and sent a second prompt through
  its actual composer asking it to remember the previous phone answer. It returned
  `pong`, proving conversation context. Screenshot viewed:
  /root/.local/share/omalauncher/attachments/phone-screen-4a013067-fd8b-4298-a5a3-117c470d5329.png
- PASS: native Claude session visible in the Agents page. Screenshot viewed:
  /root/.local/share/omalauncher/attachments/phone-screen-59b522fa-7868-429a-aecf-a90d7cdc89aa.png
- PENDING: Mac agent verification of original transcript UUID, no fork/duplicate
  after scans, desktop reload/continuation and restart after phone turns.

Known limitation: native activity is not detected. A native Mac turn may still be
working even when Soma says idle. Close the native Mac conversation before sending from Soma;
do not drive the same session simultaneously from the desktop and phone. The
Claude desktop view may require reopening to see phone turns; continuing a stale
view may select an older conversation leaf. These are not claimed fixed by the
successful phone round trips. The owner's live Mac agent was not used for tests.


## Open-session guard (supersedes earlier finish-turn advice)

The Mac's stale-leaf test proved a long-lived process does not learn about a
phone process's turns. Typing in that old process branches around the phone
turn. Finishing a native turn is therefore insufficient: close that native
conversation before resuming from Soma, and reopen/resume it afresh when
returning to the desktop.

For discovered sessions, `take` now reads only numeric `sessions/<pid>.json`
metadata and refuses before queueing if another live process holds the UUID,
whether busy or idle. HTTP returns 409 with the explanation. Claude launch
rechecks after queueing and before a new process starts. The runner's own kept
process is excluded, preserving serial phone turns. Dead records and verified
PID reuse are ignored; unknown process identity is conservative and refuses.
Linux compares /proc start ticks; Darwin compares normalized C-locale `ps lstart`
when the recorded format is recognized. No `.key` files, peer tokens, or sockets
are read, and no native process is stopped. This guards observed holders; it
cannot atomically prevent somebody opening a Mac window immediately after a
check, so simultaneous desktop/phone use remains unsupported.

The first two phone round trips passed because the spare native session was
closed, not because overlapping native and phone processes were safe. The Mac
agent independently confirmed one transcript UUID and no duplicate imported
phone turns. The repeated native-process stale-leaf probes then intentionally
forked the spare session only. Those probes are not owner work.

Guard tests: PASS 19 discovery/reply/holder tests on the Fold; deployment and
open-refusal/closed-reply checks on the Mac pending.

Darwin follow-up: a start-text mismatch does NOT permit a phone reply. Local vs
UTC conventions are still being verified on the actual Mac. Until then every
alive matching Darwin PID is conservatively a holder, even if the record might
be stale from PID reuse. Dead PIDs still permit handoff. This can produce a false
refusal, but avoids a false permission and a split conversation. The Mac agent
has been asked to validate and deploy this conservative revision.

### UTC fix and deployed guard verification

Mac agent verified Claude's Darwin procStart is UTC while plain ps uses local
Hawaii time. Source now explicitly runs ps with `TZ=UTC, LC_ALL=C`, matching the
Mac agent's verified one-line deployment patch. A regression test asserts UTC
regardless of the runner environment. Linux PID-reuse fixture explicitly models
Linux so its expected exclusion does not fail on Darwin's conservative policy.

Deployed on MacBook: b0d0402 plus Mac-local UTC patch 27613d0, installed SHA1
b31c57f4501426f294f641c32595e9e84a15010e. Source additionally retains the conservative
Darwin mismatch fallback from 9c2d54a. Mac agent reports 20 discovery tests, six
Codex tests and 438 broad checks PASS for its installed revision. Final phone
source: 20 discovery/guard tests PASS and six Codex approval tests PASS.

- PASS: actual Fold HTTP request to held native session returns 409 with
  close-on-Mac explanation. Verified zero queued turns and zero guard-test user
  messages in the remote event log afterward.
- PASS: actual Soma composer attempt is refused and its tab changes to
  `needs attention`; no native turn starts. Screenshot viewed:
  /root/.local/share/omalauncher/attachments/phone-screen-b6f615dc-b186-4af3-9a49-c828d78d70b1.png
- PARTIAL UI: the selected tab did not visibly show the detailed refusal reason
  at its current scroll position; the API reason is explicit. Do not claim the
  reason's inline presentation passed.
- PASS: after guarded rollout, closed spare accepts another Fold connection check
  and returns pong: events 24 working, 25 message, 26 text, 27 result, 28 idle.
- PARTIAL UI: that API-originated (rather than composer-originated) check appeared
  with two pong lines in Soma, although remote events contain only one text plus
  normal result. Earlier actual-composer reply appeared once. No duplicate
  transcript import is claimed from this UI rendering observation.

No real native conversation was closed or interrupted, and no peer credential
or socket was accessed. Closed-session phone work is verified; shared concurrent
Mac/phone conversation state is unsupported. A fresh Mac session with a handoff
is a separate safe workflow; automatic desktop handoff is not implemented.

Follow-up UI observation: after the final result settled, screenshot
/root/.local/share/omalauncher/attachments/phone-screen-706b0d8a-5bd5-485b-9f65-134eaccbf335.png
was viewed and shows one final pong line, so the earlier duplicate observation
was transient; no persistent duplicate was reproduced. The native Darwin ps
regression now also runs against real ps under a Honolulu parent timezone on
Mac hosts, and skips on Linux. Final Fold test count: 20 passed, 1 Darwin-only
check skipped; Mac execution of this final added check is pending (the Mac agent
already ran its equivalent native timezone regression successfully).

## Refusal explanation in Soma (app update)

Boss's installed app showed only "The Mac could not take this (HTTP 409)".
The runner had correctly returned the reason, but macUnsentReason discarded it.
Commit e6864ae preserves a nonblank 409 reason as bounded plain text in the unsent
entry; malformed/blank bodies retain the generic fallback. Prompt, thread and
unsent status stay intact. No change to the guard or automatic retry policy.

Validation:
- PASS (Mac): targeted MacThreadsTest regression parses HTTP409, retains reason
  through dropped-task persistence, and checks blank/malformed fallback and bound.
- PASS (Mac): ChatScreenRenderTest emits cover render and matching emulator fixture.
- PASS: all 13 emulator walkthrough steps for mac-refusal.walk, including cover
  and inner layout. Both LOOK screenshots reviewed by phone B, full explanation
  visible and wrapping without clipping:
  /root/handoff/mac-refusal-e6864ae8a/10-mac-refusal-cover.png
  /root/handoff/mac-refusal-e6864ae8a/13-mac-refusal-inner.png
- PENDING: real Fold refusal/retry walkthrough after owner promotion.

A pre-existing broad-test failure was the TYPE_ACCESSIBILITY_OVERLAY window
inventory, not RecordPill reading ring preferences. Commit 5ed8653 adds the already
owner-requested recording window to that inventory and asserts one occurrence;
all setting-isolation checks stay intact. No runtime/security behavior changed.
Full app build 133 requested at 5ed8653; no phone installation authorized yet.


## Automatic phone copy (Boss confirmed, 2026-10-22)

This supersedes the earlier requirement to close an idle Mac conversation. A verified idle native holder now causes an explicit `--resume SID --fork-session`. Only that deliberate fork may adopt a new init session UUID. The same Soma thread persists the new ID and its original IDs; discovery ignores the originals and deduplicates inherited history. The thread explains that its phone copy will not appear in the Mac window.

A verified busy holder waits before starting Claude, with a visible note, cancellation, and a 30-minute timeout. If the holder exits while waiting, the original session resumes normally; if it becomes idle, the phone forks. Cancellation never signals the native holder. Unknown process identity, unknown status and unreadable metadata still refuse before queueing. No peer keys or sockets are read.

Isolated regression coverage includes idle fork and persisted discovery aliases, busy-to-idle fork, busy-to-closed resume, cancellation, timeout, unchanged original transcript, strict init UUID checks and metadata refusals. Real Mac/Fold walkthrough remains pending; use `tools/walkthrough/scripts/claude-discovery.walk` and record its results before calling the rollout done. Process metadata is a snapshot: a person can start a new Mac turn after the check, so simultaneous editing of the same project remains a limitation.


### Follow-up after the Fold rate limit

The real idle-holder test returned the first user prompt from inherited context and the phone-copy note; a second message sent through the actual Soma composer returned `phone continuation pong` with the same original context. The closed spare returned `phone C pong` in a normal resume. The busy spare showed the waiting note on the Fold, then safely stopped with a metadata-read error while another test process exited. It did not complete the busy-to-idle path, so that live step remains pending.

An accepted message now rechecks uncertain metadata for at most ten seconds, without starting any agent while uncertain. Initial HTTP admission still refuses unreadable metadata immediately; persistent uncertainty after admission still produces an error. Regression tests cover recovery from a truncated metadata file, persistent failure without starting an agent, and a second phone turn continuing the fork rather than forking again. Local discovery: 27 PASS, one Darwin-only skip; Codex approvals: six PASS. Updated Mac deployment and remaining live cancellation/wait tests are pending.


### Real Fold walkthrough after the metadata recheck

The installed phone stayed at version132; this feature is runner-only. Spare sessions were used throughout, never Boss's active desktop conversations. Evidence is saved on the Fold in `/root/handoff/claude-phone-copy-evidence/`.

| Walk step | Result | Evidence |
| --- | --- | --- |
| 1. Discover native conversations and visible history | PASS | Spare B and C appeared in Soma alongside existing Claude/Codex threads. |
| 2. Closed native session resumes normally | PASS | C returned `phone C pong`; Mac peer confirmed normal continuation. |
| 3. Idle open session copies with full history | PASS | A returned its first user prompt and `phone A pong`; peer confirmed original untouched. |
| 4. Copy notice and second phone reply | PASS | Actual Soma composer returned original context plus `phone continuation pong`; notice visible; screenshot inspected. |
| 5. Busy waits, then copies | PASS after fix | B retry events17–23: working, prompt, waiting note, copy notice, reply, result, idle. A second actual Soma composer prompt returned `phone busy retry pong` in events24–28. |
| 6. Cancel from Soma | PASS | C showed waiting and Stop; actual Stop produced event13 `stopped`, then idle. Mac peer verified holder41819 stayed alive and busy, zero cancelled-prompt transcript rows, and no fork. Screenshots inspected. |
| 7. Restart and avoid duplicate sessions/history | PASS | Mac peer checked all18 runner threads: one thread per session, no separate thread for either fork origin, no re-import after restart. |
| 8. Unknown metadata refuses safely | Isolated tests PASS; real corrupted-file step not walked | Admission returns409 for unverified metadata. Accepted waits recover from temporary corruption and stop on persistent uncertainty, without starting an agent. Owner metadata was never corrupted. |

The first busy test failed safely before the recheck fix (B event13, metadata read error). The retry passed. Its first assistant reply asked what the test's conditional phrase “after this phone copy starts” meant; an unambiguous follow-up through Soma then returned the exact test reply. The clarification was a model response, not a runner failure. Full30-minute wall-clock expiry was not waited out; timeout behavior is covered by the isolated regression.


Final Mac confirmation (agent mail20261022-154937-a124d2 and follow-up): installed runner equals source7a58f1e byte for byte, SHA1`54af4d8940ccc4fdef2d27522050e30d83528d0d` (also verified locally); no Mac-only patch. A original9a44554a is byte-identical to its pre-test hash; fork1edb4fd9 has77 rows, one sessionId and zero duplicate UUIDs. B fork6b2f6e00 has89 rows, one sessionId and zero duplicate UUIDs; original28e5d033 contains no phone text. B's second phone message resumed the same fork without another copy note. C's cancellation left its native holder alive and busy and wrote no cancelled prompt or fork. The peer closed only its own spare holders afterward; Boss's two native desktop sessions were untouched. Mac discovery/Codex tests passed, and broad runner checks passed438/438.

The runner rollout is complete. The separately built version133 HTTP409 reason UI still requires Boss's promote and its phone walkthrough; this runner feature was verified in the existing installed Soma132.
