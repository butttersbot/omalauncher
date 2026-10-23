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
