# Native Claude Code sessions in Soma

The runner now mirrors local Claude Code transcripts from
`$CLAUDE_CONFIG_DIR/projects/*/<session-uuid>.jsonl` (default `~/.claude/projects`).
This fixes the mismatch where new native Codex sessions appeared but native
Claude sessions did not. No hook changes, model calls, or transcript writes.

On first activation, sessions whose transcript was active in the preceding 24
hours are eligible, including the conversation already open when this is installed.
The boundary is retained in `STATE/claude-discovery.json`; older transcripts become
eligible if they are used again. Scans run every 15 seconds. Set
`OMA_RUNNER_CLAUDE_DISCOVER=0` to disable or `OMA_RUNNER_CLAUDE_SCAN_EVERY` to tune.

Only owner prompts and visible assistant text are imported. Sidechains, subagent
files, metadata, tool results, thinking and compact summaries are excluded.
Malformed complete records are skipped, unfinished records retried, and large
payloads skipped without blocking later text. Offsets and message UUIDs persist
across runner restarts. Existing runner-owned Claude session IDs are excluded.
Custom session titles are used when present; otherwise the first prompt is the title.

These conversations are **read-only mirrors**. The first note says to reply in
Claude Code on the Mac. The API reports `external: true, read_only: true` and
rejects messages with HTTP 409; internal `take` also refuses. This prevents a
second CLI from writing to a conversation still open on the Mac. No remote
approval forwarding or native activity detection is claimed; mirrored threads
show idle. Native Claude interactive control is separate work.

## Validation and rollout

Run `python3 tools/mac-runner/test-claude-discovery.py` and
`python3 tools/mac-runner/test-runner.py`, plus existing Codex approval tests.
Install the updated runner on the MacBook using the normal runner deployment.
Restart only after confirming no Soma-owned thread is working or waiting: runner
restart interrupts its own agents. It does not stop native Claude Code.
No Android APK is needed for discovery or transcript events.

Run `tools/walkthrough/scripts/claude-discovery.walk` against the owner's paired
MacBook and Soma after deployment. Until then, live appearance is unverified.

Implementation verification on the Fold, 2026-10-22:
- PASS: 10 isolated discovery tests, including native import, incremental append,
  incomplete writes, restart/deduplication, equal-size rewrite, metadata filtering,
  owned-session exclusion, old/recent session eligibility, title, oversized tool
  record, symlink exclusion, and read-only HTTP/internal reply rejection.
- PASS: 6 existing Codex approval tests.
- PARTIAL: broad runner fixture passes 203 checks, then stops at its bot shell
  `made.txt` assertion (line 818, FileNotFoundError). The unchanged HEAD runner
  reproduces the same failure in a separate temporary copy. This is not a full
  suite pass; the remaining checks need a working Mac test environment.
- NOT WALKED: actual MacBook deployment and phone screenshots; no live rollout
  or discovery success on the owner's machine is claimed.
