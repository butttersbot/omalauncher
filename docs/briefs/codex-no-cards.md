# Brief: Codex with no approval cards (Mac threads and Phone B)

From builder A (Phone A), 2026-10-21, for the Codex thread Boss points here. Boss wants:

1. Codex threads on the Mac Pro in **auto** mode to run with **full access**: no sandbox, no approval cards, as a
   Claude thread in auto mode works. (A Codex thread stopped for cards on adb, Tailscale and network calls today.)
2. **Phone B**, the phone's second builder, to run **Codex** on **`gpt-6.1-sol`**, also with no cards. Phone A stays
   on Claude Code.

Builder A could not make either change: its safety check blocks setting an agent to run without a sandbox and without
approvals, and a "yes" in chat doesn't lift it. Boss chose to have Codex do it. Manual mode keeps asking about everything.

Repo: `/Users/jackkim/Projects/omalauncher-fold` on the Mac (the phone's checkout is `/root/Projects/omalauncher`,
branch `phone/fold-development`). Commit on that branch; the phone's builders pick it up with `oma-sync`/a pull.

## 1. Mac runner: Codex auto = full access

File `tools/mac-runner/oma-runner`, class `Codex`, method `rules()` (search `def rules`). Today:

```python
return {'cwd': meta['project'], 'approvalPolicy': 'on-request' if mode(meta) == 'auto' else 'untrusted',
        'approvalsReviewer': 'user', 'sandbox': 'workspace-write', **({'model': meta['model']} if meta.get('model') else {})}
```

Wanted: auto → `approvalPolicy: 'never'`, `sandbox: 'danger-full-access'`; manual unchanged (`untrusted`,
`workspace-write`). Update the class docstring above it (it describes auto as "on-request" in the workspace sandbox)
and the `mode()` docstring if it still says Codex auto asks for more than the sandbox gives.

Test: `tools/mac-runner/test-runner.py`, the check `'codex: auto when asked for by name, ...'` (about line 623)
expects `policy == 'on-request'` and `sandbox == 'workspace-write'`; make it expect `never` and `danger-full-access`.
Run `python3 tools/mac-runner/test-runner.py`.

Deploy: the live runner is `~/.local/bin/oma-runner` on the Mac Pro (`http://100.120.219.46:8478`). Install the new file
there and restart the runner **only while no thread is working** (`GET /threads` shows each status); a restart mid-turn
cuts that thread off, possibly the one doing this. New Codex turns use the new rules; check one with a command that
needed a card before (e.g. `tailscale status`) and see that no card comes.

## 2. Phone B on Codex, gpt-6.1-sol, no cards

On the phone, both builders run the default agent: `app/src/main/kotlin/dev/omalauncher/assist/AgentTasks.kt`,
`val agent = runtime.agents.default` (about line 370), for builder `id` (`BuilderId.A` / `BuilderId.B`; the letter is
`id.letter`). Claude goes through `claudeStreamCommand(...)` (stream-json, resumable, `--permission-mode auto`); any
other agent through `backgroundAgentCommand(agent.bin, fullPrompt, images)` (about line 789), whose Codex line today is

```kotlin
"codex" -> "codex exec --skip-git-repo-check " + images... + "-- ${shellQuote(prompt)} < /dev/null"
```

Wanted:

- Builder B uses the agent `codex` and the model `gpt-6.1-sol`; builder A keeps the default (Claude). Simplest is a
  per-builder choice (a small setting or a file such as `~/.config/omalauncher/builders.json`
  `{"B": {"agent": "codex", "model": "gpt-6.1-sol"}}`) read where `agent` is picked; fall back to the default when the
  chosen agent isn't installed, and say so in the entry.
- Codex for a builder runs with no cards: `codex exec --dangerously-bypass-approvals-and-sandbox -m gpt-6.1-sol ...`.
  Codex's own sandbox does **not** work inside the phone's Linux (proot): `codex sandbox linux -- true` fails with
  "error building bubblewrap command: cannot establish app-server socket mount isolation", so `--full-auto` /
  `workspace-write` would fail every command; bypass is the only no-cards setting there.
- Keep what builders get: the phone-control instructions are already in `fullPrompt`; images go as `--image` flags (already
  there). Tests: `app/src/test/kotlin/dev/omalauncher/assist/` (e.g. a test that B's command carries the model and the
  bypass flag, and A's is unchanged).
- Builds: never Gradle on the phone; build and test on the Mac with `tools/fold-development/soma-macbuild COMMIT VERSION`
  as usual, or tell builder A to run `oma-macbuild` and Boss promotes.

State on the phone: the Codex CLI is installed (`codex-cli 0.163.0`, `/root/.local/share/mise/installs/node/lts/bin/codex`)
but **not signed in**; Boss signs in himself (`codex login`, or `codex login --device-auth` and a code on the OpenAI page).
Nothing works for Phone B on Codex until then.

Already done by A (in the next build): Phone A and Phone B tabs are always on the chat's strip, so B can be written to
while idle (commit 0d0740a).

## Note for Boss

With both changes, Codex in auto mode can run anything on the Mac Pro or the phone as you, with no review before it
acts; Claude's auto mode has a safety check that blocks risky actions, Codex has none. Manual mode stays the careful one.
