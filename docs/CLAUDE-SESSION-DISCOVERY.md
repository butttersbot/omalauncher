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

Finish any active Mac turn before replying from the phone. This resumes the
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
