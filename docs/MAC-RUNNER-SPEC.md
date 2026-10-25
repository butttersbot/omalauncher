# A runner on the Mac for Oma's builders

For the Mac session, to build on the Mac Pro with Boss. Written 2026-10-13 by the agent on the Fold.
The Mac side is built and running (see "As built on the Mac" at the end, which wins where it differs from
the text above it). The phone side that would call it is not built: say what you change in this file.

## Start here (Boss's request to the Mac session, 2026-10-13)
Boss could not paste this in, so it stands here for him. He wants this runner built on the Mac Pro.
1. Before writing any code, take him through "To decide with Boss on the Mac" one question at a time, with your
   recommendation for each.
2. Build it, and prove it locally with the "First proof" list as far as you can without the phone.
3. Write what you decide and change back into this file and push, so the phone agent can build its side to match.
4. Find the handoff for his Final Fantasy 4 better-menu mod (on the laptop; he believes it is in ClaudeSync) and
   tell him where it is.

## Why
Boss talks to one agent, Oma, from the phone. A fast front agent on the phone answers quick things and hands
work, word for word, to a builder in a project thread. Builders on the phone are slow and he wants at most two
there. The Mac Pro is always on and reachable over Tailscale, so builders should run on it by default and on the
phone only when the Mac cannot be reached or the work can only be done on the phone.

## What stays on the phone
- The front agent, so quick questions are answered with no signal.
- Building, testing and installing the Oma launcher (the Mac has no Android SDK) and anything that drives the
  phone (`oma-phone`). The home of the `omalauncher` thread is the phone.
- Every approval. The runner never approves anything itself.

## What the runner is
A small service on the Mac Pro, listening only on its Tailscale address, that runs a coding agent (Claude Code
first; Codex later) in a named project thread and streams what it says back to the phone.

A thread is: an id, a project directory on the Mac, the agent's session id so the conversation continues, and a
status (idle, working, waiting for approval, interrupted). One task at a time per thread. Boss decides how many
threads may work at once on the Mac; start with three.

## What the phone asks of it
Plain HTTP with JSON over the tailnet is enough; server-sent events or a kept-open response for the stream.
- `GET /health`: answers at once; the phone asks before every hand-over and falls back to itself after about
  two seconds of silence.
- `GET /threads`: each thread's id, name, one-line summary, status, when it last worked, and the last 300
  characters it said (the front agent reads this to route and to answer "what are you working on").
- `POST /threads`: make one, given a name and a project (a directory under an allowed root).
- `POST /threads/ID/messages`: the owner's words, exactly as he said them, and nothing added but what the
  runner itself documents. While a task works, a message waits its turn and is taken when the turn ends, as on
  the phone; the answer says which happened.
- `GET /threads/ID/stream`: the agent's events as they come: text, each tool step in a line, a request for
  approval, the result, an error. The phone can ask again from an event id after a dropped link.
- `POST /threads/ID/approvals/APPROVAL`: allow or deny one request, once, with the nonce the request came
  with. See "Approvals".
- `POST /threads/ID/stop`: stop the task; what was said stays.

## Approvals
The phone runs Claude Code with `--permission-prompt-tool stdio` and shows each request to Boss as a card that
names the tool and what it would do; he allows or denies that one call. The runner must keep exactly this: an
approval request is streamed to the phone, the task waits, and only an answer that carries that request's nonce
is taken. No blanket allow, no auto mode that skips the card for anything the phone would have asked about, and
no answer accepted from anything but the phone's signed request. If the link drops while a request waits, it
keeps waiting; it is not allowed by timeout.

## Who may call it
- Listen on the Tailscale interface only, never on the LAN or the public address.
- A shared secret made on the Mac and given to the phone by Boss (as the debug door's key was), sent with every
  request and compared in constant time; without it, 401 and nothing else.
- Projects only under roots Boss lists (for example `~/Projects` and the ClaudeSync folder); a path outside
  them is refused. The runner starts no shell of its own for the phone: only the agent, in the thread's project.
- The phone's present read-only access (`oma-context`) is not widened by this and stays as it is.

## When the Mac is not there
- Not reachable when a request comes: the phone runs it itself if the project is in Git and it has a clone;
  otherwise it tells Boss the Mac is unreachable and keeps his words to send when it is back, if he says so.
- Drops in the middle of a task: the task goes on working on the Mac. The phone shows the thread as out of
  reach, does not start the task again anywhere, and picks the stream up from the last event when the link
  returns.
- Work the phone did while the Mac was away is brought back through Git (a branch per side, as
  `phone/fold-development` is now), never by copying files over each other.
- Tailscale on the phone does not come back by itself after a restart; the phone says "Mac unreachable, working
  here" and does not hang.

## What each side needs to know of the other
- Each thread has a home, Mac or phone, set when it is made, and the board the front agent reads says which.
- The front agent hands over the owner's words unchanged, with a receipt he sees ("→ FF4 mod"). A builder that
  is sent something that is plainly not its project says so and does not act on it.
- The FF4 better-menu mod lives on the laptop now and has a handoff there; Boss believes it is in ClaudeSync.
  Its thread's home should be the Mac once the project is under an allowed root.

## To decide with Boss on the Mac
The port and where the secret is kept; the allowed roots; how many threads may work at once; whether the runner
starts at login (launchd) and how it is stopped; whether Codex is a second kind of thread from the start; what
is logged and for how long.

## First proof
From the phone: `/health` answers; a thread on a scratch directory answers "what files are here"; a request
that needs a write shows a card on the phone and waits; deny refuses it; allow does it; the link is dropped in
the middle (turn Tailscale off) and the stream is picked up again without the task starting twice.

## As built on the Mac (2026-10-13, by the Mac session with Boss)
The runner is `tools/mac-runner/oma-runner` (Python, no dependencies), installed on the Mac Pro as
`~/.local/bin/oma-runner` and running now at `http://100.120.219.46:8478`. Its proof is
`tools/mac-runner/test-runner.py` (35 checks against `fake-agent`, a stand-in that speaks Claude Code's lines).

### What Boss decided
- Port 8478, on the Mac's Tailscale address only (8477 is taken on the Mac). The secret is in
  `~/agents/claude-code/oma-runner.token` on the Mac, readable by him only. He gives it to the phone himself.
  (Since 2026-10-14 that file holds only the key's SHA-256: see "As built: the key".)
- Allowed roots: `~/Projects` and `~/ClaudeSync/projects`. Not the rest of ClaudeSync.
- No limit on how many threads work at once. He does not want a number until a real situation asks for one.
- Starts at login and restarts if it dies (launchd, `dev.omalauncher.runner`). `oma-runner stop | start | status |
  uninstall` on the Mac. Stopping keeps the threads.
- Claude Code only. Each thread carries `kind: "claude"` so Codex can be a second kind later.
- Everything is kept until he clears it: each thread's events, and one line per request (time, caller, method,
  path, answer). The key and request bodies are never logged.

### What the phone sends
Every request carries `Authorization: Bearer KEY`. Without it: 401 `{"ok":false,"error":"no"}` after a second.
Bodies and answers are JSON; every answer has `ok`.
- `GET /health` -> `{ok, runner:"oma-runner", threads, working, waiting}`.
- `GET /threads` -> `{threads:[THREAD]}`; `GET /threads/ID` -> `{thread:THREAD}`. THREAD is `{id, name, project,
  home:"mac", kind, summary, status, last_worked, tail, waiting_turns, last_event, pending:[...]}`. `summary` is the
  first line of the owner's last message, `tail` the last 300 characters the agent said, `last_worked` Unix
  seconds, `status` one of `idle`, `working`, `waiting`, `interrupted`. `pending` lists the approval requests
  waiting now (same fields as the `approval` event), so a card can be drawn again without the stream.
- `POST /threads {name, project}` -> `{thread}`. The project must be an existing folder under a root (`~` is
  understood; links that lead out are refused): otherwise 403 with the roots. The runner makes no folders.
- `POST /threads/ID/messages {text}` -> `{taken:"now"}` or `{taken:"queued", position}`. The text goes to the
  agent exactly as sent; the runner adds nothing. Images are not passed on yet.
- `GET /threads/ID/stream` -> server-sent events, each `id: N`, `event: TYPE`, `data: {id, time, type, ...}`. Ids
  count from 1 per thread with no gaps. Ask from where you were with `?after=N` or the `Last-Event-ID` header. A
  comment line comes every 15 seconds while nothing happens. `GET /threads/ID/events?after=N` gives the same
  events as `{status, events:[...]}` without holding a link open.
- `POST /threads/ID/approvals/APPROVAL {decision:"allow"|"deny", nonce}` -> 200; 403 for a wrong nonce (the
  request goes on waiting); 404 once answered or gone.
- `POST /threads/ID/stop` -> `{stopped}`. Messages waiting their turn are dropped; the `stopped` event says how
  many.

Event types: `status {status}`; `message {text}` (the owner's words, when taken); `text {text}`; `step {text,
tool}` (one line, written as `ClaudeProtocol.step` writes it); `approval {approval, nonce, tool, input,
explanation}`; `approval_result {approval, decision}` (`allow`, `deny`, or `withdrawn` when the agent took the
request back); `result {text, failed}`; `stopped {dropped}`; `error {text, code}`.

### How it keeps the rules above
- The agent runs as the phone's does (`claude -p`, stream-json both ways, `--permission-prompt-tool stdio`,
  `--resume` with the thread's session), but in `--permission-mode manual` with no `--allowedTools`: no classifier
  allows anything. A request waits for the phone for as long as it takes; nothing is allowed by time.
- Claude Code's hooks are switched off for runner threads. The Mac has a hook (the notch) that can answer a
  permission request from the Mac's screen, and only the phone may. This also switches off Boss's other Mac
  hooks in these threads. His to change.
- Allow rules already written in the Mac's own Claude settings still apply and show no card. Today that is one
  rule, for a Clawffice restart script. A project's own settings file can hold more.
- "Signed" is, for now: the key on the request plus that request's nonce. Nothing more is checked.
- The key file and the runner's records are denied to the agent's file tools.
- If the runner itself stops (restart, crash, `oma-runner stop`), tasks end with it. Their threads come back as
  `interrupted` with their history; waiting approvals are gone; nothing is started again until the owner's next
  message, which continues the same conversation.

### Proved, and not
- Proved on the Mac with the stand-in agent: every line of "First proof" except the phone itself, and also wrong
  key, wrong nonce, an answer used twice, paths and links out of the roots, a message waiting its turn, stop, a
  runner restart under a waiting request.
- Seen on the real service: it listens on the Tailscale address only (nothing on loopback), refuses without the
  key, refuses `~/agents`, comes back by itself about ten seconds after being killed.
- Seen with the real Claude Code on the Mac (Boss signed it in again, 2026-10-13), hooks off: a thread on a
  scratch folder answers "what files are here"; asked to write a file it sends an `approval` for `Write` with
  the path and content and waits (still waiting after 30 seconds, no file); a wrong nonce gets 403; deny, and
  the agent says it was refused and no file is made; allow, and the file is there. The later turns continued
  the same conversation after a runner restart.
- A plain listing (`ls`) ran with no card: Claude Code's manual mode does not ask about read-only commands.
- Boss asked whether the notch hook could stay on in these threads, then chose off: this is for the phone.
- Tried from the phone's Linux (2026-10-13 16:49, a script sent over adb by the Mac session, calling the runner
  as 100.71.22.108 with the key from the phone's own config file; not the launcher, no card on the screen):
  `/health` answers in under two seconds; no key gets 401; a thread on the scratch folder answers "what files are
  here" over the stream; a write asks and waits; a wrong nonce gets 403; deny, and no file; allow, and
  `phone-allow.txt` is on the Mac. The runner's request log shows each call coming from the phone.
- Still to see: the card in the launcher itself, and the stream picked up after Tailscale is turned off and on
  in the middle of a task (Boss's hand; the pick-up itself is proved on the Mac).

### From the Mac session to the phone agent (2026-10-13 16:55)
- The key is where you asked: `~/.config/omalauncher/mac-runner.json`, `{"url","key"}`, mode 600, written over
  adb by the Mac session. The runner answers the phone with it.
- When a message is already waiting as a turn ends, the next `message` event follows the `result` with no
  `status idle` between them: the thread stays `working`. Do not wait for `idle` to know a turn ended; `result`,
  `error` or `stopped` ends it.
- `oma-mac script` gives up after 30 seconds though the script goes on running; it is not the runner.
- Boss has signed Claude Code in on the Mac, and chose hooks off in runner threads (the notch included).

### For the phone side
Build the caller to the list above. Ask `/health` with a two-second limit before each hand-over. Keep the last
event id per thread and ask the stream from it. Show `approval` events as the card you already have and answer
with the nonce. The FF4 mod is at `~/ClaudeSync/projects/ffiv-ds-better-menu-mod` on the Mac, under a root, so
its thread can be made there now.

## Asked next by Boss (2026-10-13 evening, from the phone) — for the Mac session
His words: "the idea is to never be bottlenecked by one agent that's working … that job should have been sent to a
new session on the Mac Pro … I also have codex and cursor so if we hit our limit on claude code concurrent
sessions on the mac pro, then we should have codex available … or cursor."
The phone now has two builders of its own and routes project work to Mac threads. What the runner needs:
1. **A thread kind per agent.** `kind` is already on every thread (`"claude"`). Add `"codex"` and `"cursor"` (the
   CLIs installed on the Mac), each run in the thread's project with the same rules: the owner's words unchanged,
   events in the same shapes (`text`, `step`, `result`, `error`), and every request that needs permission sent as an
   `approval` event and waited on. If a CLI cannot ask for approval per call, say so here instead of running it with
   blanket permission; Boss decides.
2. **The model on the events.** Put the model (from Claude Code's `init`, or the CLI's equivalent) on `result` and
   the first `text` of a turn, e.g. `"model": "claude-opus-5-5"`, so the phone can label replies "Opus", "Codex" and so
   on (the phone already reads a `model` if it is there; today it shows "Claude").
3. **Busy or limited.** When a thread is working, a new, unrelated request should not wait: the phone will ask for
   a fresh thread on the same project (`POST /threads {name, project, kind}`) rather than queue. When Claude Code
   answers with a usage or concurrency limit, end that turn with `error {code: "limit"}` so the phone can retry the
   same words on a `codex` thread (then `cursor`), telling Boss which one took it. Say here which error texts you
   match.
4. **Model choice per thread** (optional): `POST /threads` may take `model` for Claude threads.
Write what you build back into this file, as before, and push.

## As built: Codex and Cursor threads (2026-10-13 night, by the Mac session)
What changed in `tools/mac-runner/oma-runner`, answering "Asked next by Boss" above. Proof: `test-runner.py`, 74
checks (the 35 before, plus Claude at a limit, models, and the same list again for a `codex` and a `cursor` thread
against `fake-codex` and `fake-cursor` including stop during a card, stand-ins that speak each CLI's protocol). Also run against the real Codex
and Cursor on the Mac; see "Seen with the real agents".

### What the phone sends now
- `GET /health` adds `kinds`: the agents installed on the Mac, e.g. `["claude", "codex", "cursor"]`.
- `POST /threads {name, project, kind, model}`. `kind` is `claude` (the default), `codex` or `cursor`; another
  word is 400, an agent not installed is 503. `model` is optional, for any kind, a model name (starting with a letter or
  digit, then letters, digits and `_.:=,[]-`, up to 100); it is passed to the agent as asked. THREAD gains `model`: the model the agent last
  reported, else the one asked for.
- `text` (the first of a turn) and `result` carry `kind` and `model`, e.g. `{"kind": "codex", "model":
  "gpt-6.1-sol"}`. Claude's comes from its `init` line, Codex's from its thread, Cursor's from its session
  (`cursor-auto` when Cursor picks the model itself).
- A turn that ends on a limit ends with `error {code: "limit", text}` and no `result`. Other errors are now
  `error {code: "agent", text}` (the agent refused a request), `error {code: "runner", text}` (the runner itself
  failed in the turn; the thread still goes back to idle) or the exit status as before (the agent died). When a
  Codex or Cursor conversation cannot be continued, the turn ends with `agent` and says so, and the thread's next
  message starts a new conversation.
- When a turn ends while a card is waiting (stop, a crash), each waiting card gets `approval_result {decision:
  "withdrawn"}`. An answer the agent did not take (the turn had ended, or Cursor offered only "always" choices,
  which are never picked) is 409 and the card is withdrawn.
- Approval cards have the same fields for every kind. `tool` is `Bash` (with `input.command`, `input.cwd`) or
  `Edit` (Codex: `input.changes`, each `{path, kind, diff}`; diff cut at 4000 characters) for Codex; `Bash`,
  `Edit`, `Read` or `WebFetch` for Cursor, with Cursor's own input for the call.
- A busy thread is not waited on by the runner for a new, unrelated request: make another thread on the same
  project (`POST /threads`), as the phone planned. Nothing else was needed on this side.

### Limits: what is matched
- Claude Code: a `result` with `is_error` whose text matches the pattern below, or any failed `result` after an
  `assistant` line marked `error: "rate_limit"` or `"billing_error"`.
- Codex: an `error` notice with `willRetry: false` whose `codexErrorInfo` is `usageLimitExceeded`,
  `rateLimitExceeded`, `serverOverloaded` or `sessionBudgetExceeded` (or whose message matches the pattern), or a
  failed turn with one of those.
- Cursor: a refused `session/prompt` whose message matches the pattern.
- Any agent that dies without answering, when what it left on stderr matches the pattern.
- The pattern, case-insensitive: `usage limit`, `hit your … limit`, `limit reached`, `limit exceeded`,
  `rate limit`/`rate-limit`/`ratelimit`, `out of usage`, `out of extra usage`, `too many concurrent`, `too many
  requests`, `concurrency limit`, `concurrent session limit`, `overloaded`. Only errors are matched, never
  ordinary text. Overloaded counts as a limit so a busy service falls over to the next agent too.

### How each kind keeps the rules
- **Codex** (`codex app-server` from the ChatGPT app, one per turn; `--resume` is `thread/resume` with the thread's
  Codex id). Approval policy `untrusted` and sandbox `workspace-write`: everything Codex does not know to be
  read-only asks first, every file change asks first, and the asking comes to the runner (`approvals_reviewer` is
  set to `user`; Boss's Codex config has it on `guardian_subagent`, an auto-reviewer that would approve by itself,
  and that is overridden for these threads only). Switched off in runner threads, Boss's config untouched: the
  `notify` program, hooks, apps, plugins, computer use, the browser, the auto-reviewer, and every MCP server in
  `~/.codex/config.toml` (higgsfield there is set to run its tools without asking). If a server there has a name
  the runner cannot switch off from the command line, Codex is not started at all. Allow is `accept` for that
  call only, deny is `decline`.
  Known: Codex asks about more than Claude Code does. It asked before `rg --files` to list a folder, where Claude
  Code ran `ls` with no card. Known-safe reads (`cat`, `ls` and the like) run without a card, inside the sandbox,
  and the sandbox lets them read the runner's key file; Claude threads are denied that file by a rule, Codex
  threads are not. The key only reaches the phone if Codex is asked to say it.
- **Cursor** (`cursor-agent acp`, Cursor's CLI, installed 2026-10-13 at `~/.local/bin/cursor-agent` and signed
  in through the browser). Cursor asks before shell commands not on its allow list, and the runner gives it an empty one, so every
  command is a card (even `ls`). **It does not ask before editing files: it edits without telling anyone.** Telling
  it the runner would write files for it changed nothing. So in runner threads Cursor's own edits are denied
  (`Write(**)`), and the key and the runner's records are denied to its reads. Seen: asked to make a file, Cursor
  said the write was blocked and did not try another way, so a Cursor thread today reads, answers and runs approved
  commands but does not change files. To let it edit, Boss would have to accept edits with no card in Cursor
  threads; his decision, not made. These rules sit in the runner's own config folder (`CURSOR_CONFIG_DIR`), so his
  own Cursor settings are not touched. The sign-in is shared.
- Allow and deny answer that one call only for every kind; "allow always" options are never chosen.

### Seen with the real agents on the Mac (a second runner on loopback, scratch folder)
- Codex (`gpt-6.1-sol`): "what files are here" asked to run `rg --files`, allowed, answered `notes.md`. Asked to
  make `hello.txt`: an `Edit` card with the path and the diff; deny, no file and Codex said it was rejected; asked
  again, allow, the file holds `hi`. The second and third turns continued the same Codex thread.
- Cursor (`cursor-auto`): "what files are here" asked to run `ls -la`, allowed, answered. Asked to make the file:
  refused by the edit rule, no card, no file.
- Codex Stop: allowed `sleep 30`, then stopped; `stopped` came 7 s later and the thread was idle.
- Reviewed by the critic agent before install. Fixed from it: macOS answers EPERM when a just-exited agent's group
  is signalled, which could leave a thread "working" for good (now caught, and a turn that fails for any reason
  leaves the thread idle with an `error`); a failed resume made every later turn fail; a model like `-x` was
  accepted; an "always"-only Cursor card was reported as allowed. Its stress run (90 quick Codex-protocol turns
  across three threads) froze a thread before the fix and passes after it. No Codex helper was left running after
  the real turns.
- Found and fixed on the way: Codex starts a helper (`codex-code-mode-host`) in a process group of its own that
  holds the output pipe open, so a turn could look unfinished long after Codex answered. The runner now stops
  reading at the turn's end, and when the agent itself has exited.

### The phone's side of it (2026-10-14, by the agent on the Fold; unit tested, not yet run against the runner)
- Reads `/health` `kinds`, `kind`/`model` on `text` and `result`, and `error` codes as words. Only `code: "limit"` sends a
  turn on: the same words go to `POST /threads {name, project, kind}` with the next of claude, codex, cursor that is
  installed, named "NAME · Codex". A limit is remembered for 15 minutes, so new requests start on an agent not at one.
- A new chat (＋ in the chat) only ever makes Mac threads; it never sends `model`.

## Asked next by Boss (2026-10-14): the Mac does the building, the phone only installs
Boss agreed to simplify: real work happens on the Mac; the phone keeps one small builder for what must touch the
phone (`oma-phone`, the final install). For Oma's own code that means:
1. **Android toolchain on the Mac Pro**, matching the phone: JDK for Gradle 9.8, Android SDK platform 37 (compileSdk
   37), build-tools 36.0.0, NDK 29.0.14206865, AGP 9.4.1 / Kotlin 2.4.20 come from the build files. `./gradlew
   :app:testDebugUnitTest :app:assembleDebug` green in `~/Projects/omalauncher-fold` (or a dedicated checkout under
   `~/Projects`, so runner threads may work there) on branch `phone/fold-development`.
2. **An `omalauncher` thread on the Mac** in that checkout, so the front agent can send launcher code work there
   (`<<mac ID>>`) instead of to a phone builder. It commits and pushes to `phone/fold-development` as the phone does;
   it never force-pushes.
3. **The signing key stays on the phone.** The in-place update must be signed with the working launcher's key, and
   no key or credential is moved off the phone (standing rule). So the Mac builds and tests; installing stays a phone
   step: the phone pulls, `oma-build`, `oma-try` (about two minutes). If Boss ever wants the Mac to install too, that
   is his decision about his key, not ours.
4. Say here what you installed, the test count, and how long a clean build and a test run take on the Mac.

## As built: auto mode (2026-10-14, by the agent on the Fold)
Boss: "we need to default those sessions to start on auto mode. So I don't have to keep clicking approve a 100
times." This replaces, for auto threads, the rule under "Approvals" that no classifier may allow anything: routine
work in the project now runs without a card, and what the agent's own guard thinks risky still comes to the phone as
an `approval` event and waits for his answer, as before. No blanket bypass is used for any kind. Changed in
`tools/mac-runner/oma-runner`; proof: `test-runner.py`, now 82 checks (run on the Fold against the fakes).

- **Mode per thread.** `POST /threads {…, mode: "auto"|"manual"}`; `auto` is the default, another word is 400.
  THREAD gains `mode`, the mode in force. Threads saved before this have no mode and run in **auto from their next
  turn** (and show `auto`): Boss asked for these sessions, and auto keeps the same guard (hooks off, the key and the
  runner's records denied, risky actions carded). Make a `manual` thread for work where every step should ask.
- **Claude Code:** `--permission-mode auto` (was `manual`), still with `--permission-prompt-tool stdio` and hooks
  off, as the phone's own builder runs (`claudeStreamCommand`). The classifier allows routine actions; what it
  sends on reaches the runner through stdio and becomes a card. `manual` threads run as before.
- **Codex:** approval policy `on-request` with the `workspace-write` sandbox (was `untrusted`), which is Codex's own
  "Auto" preset: edits and commands inside the project run in the sandbox; writing outside it, the network, or
  leaving the sandbox is asked about, and the asking still comes to the runner (`approvals_reviewer="user"`, the
  auto-reviewer and add-ons off as before). Not used: `never` or `danger-full-access`. Known, as before: the sandbox
  lets Codex read the runner's key file; in auto mode a command that reads it runs with no card. `manual` threads
  keep `untrusted`.
- **Cursor:** unchanged and always `manual` (a Cursor thread asked for `auto` is made, and shows `manual`). Its only
  run-everything setting (`--force` / an unrestricted approval mode) asks about nothing at all, and its own edits are
  still unasked, so they stay denied; there is no mode that runs routine work and still asks about the rest.
- Not seen yet with the real agents: which actions Claude Code's classifier and Codex's sandbox send on in practice.
  The fakes stand in for both (a routine edit runs with no card in auto and asks in manual; a risky write asks in
  every mode).

### For the Mac session
Nothing changes on the Mac until the runner there is replaced: pull this branch, then `oma-runner install` from the
checkout (it copies itself to `~/.local/bin/oma-runner` and restarts), or copy it there and run `oma-runner stop &&
oma-runner start`. Running tasks end with the restart and their threads come back `interrupted`. Then, on a scratch
thread: a plain file edit should run with no card, and something risky (e.g. `rm` outside the project, a push)
should still show one.

## As built: stale results (2026-10-14, by the agent on the Fold)
Seen in thread t91462875 (claude, opus): a turn started a long copy with a background Bash command and answered at
04:05:39. At 04:09:51 Boss's next message ended 2 s later on `result {text: "", failed: false}` with nothing in
between; the phone said "Task completed." and his words were never answered (again at 04:12:41). Changed in
`tools/mac-runner/oma-runner` and the phone; proof: `test-runner.py`, now 88 checks, against `fake-agent`, which
now plays the same sequence.

- **The cause.** The runner runs one `claude -p --resume` process per turn. After the turn's `result` it closes
  stdin, waits 5 s and ends the process group. Claude Code stays alive while background work runs, so the group was
  ended 5 s later (`status idle` at 04:05:44), the copy with it, and nothing recorded that the command finished.
  The next `--resume` finds that command "orphaned by previous process exit" and, before reading the new message,
  queues a task notification of its own (priority next, no model call): a `system task_notification` (status
  `stopped`, "Background shell command didn't finish before the previous session ended") and a turn that ends in an
  empty `result` naming no user message. The runner took that result as the answer, ended the process, and his
  message was lost with it. Read from Claude Code 2.1.296 (`restoreBackgroundTasksFromMessages` in print mode);
  the second miss fits a background command left by the turn in between, which the log excerpt does not show.
- **Runner: a turn ends on its own result.** The owner's message now goes to Claude Code with a `uuid`; Claude Code
  names it on the result that answers it (`user_message_uuid`, `user_message_uuids`). A result naming other uuids,
  or none and with nothing in it (no assistant line, no text, not an error), is not his: it becomes a `note` and
  the turn goes on. A result naming none but with something in it still ends the turn, so a Claude Code too old to
  name uuids works as before.
- **New event `note {text}`**, for what the agent did that is not the owner's answer: each `task_notification`
  (its summary), a result Claude Code made for a turn of its own, and, when a turn's process ends while Claude Code
  still listed background work running (`background_tasks_changed`), "Stopped when the turn ended, unfinished: …"
  with the descriptions. The phone shows a `note` as a quiet line in the thread's tab (OPEN-BUGS #9).
- **Not changed:** background work still ends with the turn, because each turn is its own process. Keeping a
  thread's process alive while its background work runs (and sending the next message into it) would let a long
  copy finish; that is a larger change, not made here.
- **Phone:** a `result` with no words, not failed, ending a turn in which nothing happened (no text, step or card)
  no longer reads "Task completed.": the entry says "The Mac ended this turn without an answer.", needs attention,
  and has `[ try again ]`, which sends the same words to the thread again.

## As built: background work outlives the turn (2026-10-14, by the Mac session)
OPEN-BUGS #1 (the games copy, 04:05:44): the runner ended Claude Code 5 s after every answer, and background work
it had started ended with it. Probed on the Mac with Claude Code 2.1.299: (1) a user message written to stdin while
it is running is taken (mid-turn it joins that turn; the result names both uuids); (2) when background work ends it
says so (`task_notification`, `background_tasks_changed` with no tasks) and runs a turn of its own reporting it,
whose result names no message; (3) with stdin closed it waits for running background work, reports, and exits;
with nothing running it exits, sometimes only after ~30 s.
- **Claude threads keep their process while background work runs** (`Kept` in oma-runner). After an answer that
  leaves background work, stdin stays open and the thread goes idle with a note "Still running on the Mac: …".
  The owner's next message goes into the same process (no `--resume`, no orphan turn). What Claude Code says in
  between (its report, any card it asks for) reaches the phone as `text`, `approval` and a note "Claude Code
  reported on its background work, between your messages."; the thread is idle again after it.
- Once no message is being answered and no background work is left, stdin is closed and Claude Code ends by
  itself; with no report pending it is ended after 5 s as before, and a message arriving meanwhile ends it at once.
- **`/threads` and `/threads/ID` carry `background`**: the descriptions of the work still running, `[]` otherwise.
  The phone shows it on the thread's tile and tab (OPEN-BUGS #9).
- **Stop** on an idle thread with background work ends it (note "Stopped, unfinished: …"); the next message then
  resumes and Claude Code's orphan turn is a note, as in "stale results". Restarting the runner ends it too.
- stdin stays open through Claude Code's own report turn (it may ask for a card, which must be answerable); a report
  expected but never begun is waited for 30 s of quiet. A card up from that turn keeps the thread `waiting`, also
  when a new message arrives. A line the runner cannot read ends the turn with `error code=runner` (never a hang).
  Found by an independent review and covered by tests. Not handled: a Claude Code too old to name uuids may take a
  report that lands mid-turn as the answer.
- `oma-runner install` retries starting the service: right after stopping it launchd can refuse (EIO), which left
  the runner down once on 2026-10-14.
- Codex and Cursor are unchanged (one process per turn).
- Proved: `tools/mac-runner/test-runner.py` (fake-agent now behaves as probed: stays alive for background work,
  takes more messages, reports, exits); and on the Mac with real Claude Code on a loopback runner: a 40 s
  background command survived its turn and a second message, wrote its file, was reported, and the process was
  gone ~1 s after the next answer.

## As built: files (2026-10-14, by the agent on the Fold)
Boss: "It was really just the mac to phone that we needed. but yeah, if we can make a 2 way command that would be
nice. so that I could just tell you or the other agents to send something, and then you guys can just do it with the
command." The phone's command is `oma drop` (`app/src/main/assets/oma-drop.sh`).
- **Mac to phone:** Mac agents send to the phone with `tailscale file cp FILE galaxy-z-fold8:`, which lands in the
  phone's Download/Taildrop, and the phone agent picks it up with `oma drop get` (into `~/Inbox` in its Linux; moved
  off the phone once the copy is whole, `--keep` leaves it). It reaches Download/Taildrop through the phone's own adb
  over loopback; with Wireless debugging off it says to turn it on.
- **Phone to Mac:** `oma drop send FILE...` asks the runner. Run `oma-runner install` on the Mac to have it.
  `POST /files?name=NAME`, the body the file's bytes, `Content-Length` required (411 without), at most 2 GB (413,
  answered before reading). The same key as every other request. Written to disk as it arrives under a hidden
  `.partial-…` name in `~/Downloads/From Fold/` (made when needed), then moved to NAME; an existing file is never
  replaced: "NAME (2)", "NAME (3)"… before the extension. NAME loses `/`, `\`, `:` and control characters (each
  becomes `_`) and its leading dots, is cut to 200 bytes keeping its extension, and is `file` when nothing is left.
  A file that does not arrive whole is not kept (400). Answer: `{ok, path, bytes}`. The request log has its line
  (`POST /files 200`), never the name or the bytes.
- `GET /health` carries `files: true`; without it the phone says the runner is too old and sends nothing. If the
  runner cannot be reached it says so and points to Quick Share; it never drives the phone's screen.
- Proved: `tools/mac-runner/test-runner.py` (upload, no overwrite, names made safe, no key refused, over the cap
  refused, no length refused, cut short not kept, nothing half-written left, logged without the name) and
  `app/src/test/shell/test_oma.sh` (stand-ins for adb, oma-dev and curl: moving, copying, no overwrite in `~/Inbox`,
  a stale port refreshed once, Wireless debugging off, an old runner, the Mac unreachable, and the key only on curl's
  standard input). Also `oma drop send` against a loopback runner in the phone's Linux: a 3 MB file arrived byte for
  byte and a second file of one name became "a (2).txt". Not yet seen: the real Taildrop folder through the phone's
  adb, and an upload to the Mac Pro over Tailscale.

## As built: the key (2026-10-14, by the Mac session) — OPEN-BUGS #2
- Codex's sandbox can read any file this Mac user can, so moving the key would not hide it, and a Keychain item
  that the runner's `python3` may read is just as open to a `python3` Codex runs. Instead the Mac no longer holds the
  key at all: `~/agents/claude-code/oma-runner.token` holds `sha256:<hex>` of it, and the runner checks each request's
  key against that. Reading the file gets nothing that opens the runner (tested: the digest, with or without its
  `sha256:`, is refused).
- The phone's key is unchanged: on its first start the new runner turns a file still holding the key into its digest
  in place (mode 600). `oma-runner status` now only looks for the runner's refusal of a request without the key.
- A fresh install (no file) prints the new key once, for the phone; the Mac cannot show it again. To replace the key:
  delete the file, run `oma-runner install`, and put the printed key into the phone's
  `~/.config/omalauncher/mac-runner.json`. The phone loses the Mac until it has the new one, so do it with Boss there.
- Not closed by the digest alone: copies of the old key itself could sit on the Mac outside that file (transcripts of
  the sessions that set the phone up, shell history). `oma-runner rekey` replaces it: a new random key, sent once to
  the phone as `mac-runner.json` ({url, key}) by `tailscale file cp … galaxy-z-fold8:` from a temp folder, never
  printed; only then is its digest written and the runner restarted. A failed send changes nothing. The phone agent
  takes the file with `oma drop get`. Not run yet (2026-10-14): the Mac session's permission check refused to write
  a key, so it is Boss's to run or allow. Until then Codex threads stay manual unless auto is asked for by name;
  afterwards they can default to auto like Claude's (a thread keeps the mode it was made with).

## As built: the Mac builds Oma (2026-10-14, by the Mac session)
- Toolchain (already on the Mac from Homebrew, nothing new installed): Temurin JDK 26 runs Gradle 9.8 (17 is there
  too); Android SDK at `/opt/homebrew/share/android-commandlinetools` with platform 37 and build-tools 36.0.0. Its NDKs
  are 27/28, not 29: the app's own build does not pin one (only the phone's init script does) and builds without it.
  `local.properties` (not in git) in `~/Projects/omalauncher-fold` points at that SDK.
- Checkout: `~/Projects/omalauncher-fold` (branch `fold-sync`, tracking `fork/phone/fold-development`; push with
  `git push fork HEAD:phone/fold-development`, never force). It is under `~/Projects`, so a runner thread may work there.
- `./gradlew :app:testDebugUnitTest :app:assembleDebug` green: 1165 tests, 0 failed, 3 skipped. Clean build plus all
  tests, no build cache: 48 s; with Gradle's cache: 28 s.
- Two tests are skipped on the Mac on purpose: `OmaKeepTest.itsOwnTestsPass` and `OmaCommandTest.itsOwnTestsPass` run
  the guest's own scripts' tests, which need Linux (GNU `stat -c`, `/proc`); they now assume Linux and still run on
  the phone.
- The Mac's APK is for checking only: it lacks the phone-only native pieces (proot and friends live in jniLibs, which
  are not in git), and it is not signed with the launcher's key. Installing stays the phone's step: pull, `oma-build`,
  `oma-try`.
- Not done: the `omalauncher` thread itself. Making a thread needs the runner key, which only the phone holds now;
  the front agent can make it (`kind claude`, project `~/Projects/omalauncher-fold`).

## As built: a second Mac (2026-10-16, by the agent on the Fold)

Boss asked for a second runner, on the MacBook Pro (tailnet `macbook-pro`), beside the Mac Pro's. The runner is the
same; only the phone and how the key reaches it changed.

- **One file per Mac** in the guest's `~/.config/omalauncher`: `mac-runner.json` is the first Mac, the Mac Pro,
  exactly as before; `mac-runner-ID.json` is each other, such as `mac-runner-macbook-pro.json`
  (`{"url","key","name"}`, mode 600). `name` is what he is shown; without it the id says it ("MacBook Pro").
- **Thread ids.** A runner's ids are `t` and hex digits. The phone knows the first Mac's threads by those ids alone
  (nothing kept from before changes) and another Mac's as `ID/` and the id, e.g. `macbook-pro/t1a2b3c4d`, in the
  chat, tabs, tiles, approvals, cursors, the front agent's marks and `oma threads`. The id is split again only where
  a request goes out (MacRunnerClient), so each runner is only ever asked about its own ids.
- **Each Mac on its own.** `/health` and `/threads` are asked of each Mac in turn; one that does not answer keeps its
  last-heard threads, marked out of reach, and hides nothing of the other's. Work stays on its thread's Mac: a fresh
  thread, a limit fallback or separate work is always made on the same Mac. A new chat starts on the Mac Pro while
  it answers, else on the other; a new chat beside a thread the front agent named starts on that thread's Mac.
- **Where he sees which Mac.** With two or more Macs: the front agent's board has a "Macs:" line and "on the
  MacBook Pro" on each thread; chat tabs carry the Mac's name after the thread's; tiles read "MACBOOK PRO · 2 MIN";
  `oma status` has `mac.macs` and a `mac` on each thread; `oma threads list` adds a fifth column. With one Mac all
  of these read exactly as before.
- **Setting the MacBook up (Boss, on the MacBook):** `oma-runner install`, then
  `OMA_RUNNER_NAME="MacBook Pro" oma-runner rekey`, which Taildrops `mac-runner-macbook-pro.json` (with its name) to
  the phone without printing the key. On the phone: `oma drop get`, then `oma threads add-mac
  ~/Inbox/mac-runner-macbook-pro.json`, which puts it in place (mode 600), never prints the key, and removes the
  dropped file. A file whose address is already set up replaces that Mac's key instead, so the Mac Pro's own rekey
  can go the same way. `oma threads macs` lists the Macs; `oma threads new NAME PATH --mac "MacBook Pro"` makes a
  thread on the one named.
- **Not changed:** `oma drop send` still sends to the first Mac only.

## As built: a key per device (2026-10-16, by the Mac session; Soma cockpit slice 3)

Boss's decision for the cockpit (ClaudeSync `projects/omalauncher/cockpit/DESIGN.md` §2): every device he uses (the
Fold, each Mac, and the Omarchy boxes only if he adds them) talks to every runner, each with **its own key**. And a key
works **only from the device it was given to**.

- **The table.** `~/agents/claude-code/oma-runner.keys.json` (`OMA_RUNNER_KEYS`), mode 600:
  `{devices: [{name, digest: "sha256:…", added}]}`, where `name` is the device's Tailscale machine name. Digests only,
  as before (OPEN-BUGS #2): an agent that reads the file still can't use it. It's read again whenever it changes, so
  pair and unpair need no restart.
- **The check.** A request is let in when its key's digest matches a device in the table **and** `tailscale whois` of
  the caller's address names that same machine (`Node.ComputedName`), signed in as the runner machine's own Tailscale
  user (the owner check herd and tailshare-serve use). Each digest is compared in full. whois answers are kept a
  minute. Anything else gets the same 401 `{"ok": false, "error": "no"}` after a second. The request log now ends with
  the device's name (or `-`), never a key.
- **Moving in.** With no table yet, the one key from before (`oma-runner.token`) becomes the phone's
  (`OMA_RUNNER_PHONE`, `galaxy-z-fold8`), so the Fold's keys go on working with nothing changed on the phone. Pairing
  the phone again keeps `oma-runner.token` in step, so a runner put back to an older version still opens for it.
- **Commands.**
  - `oma-runner devices` lists who is paired (never a key).
  - `oma-runner pair DEVICE` makes DEVICE a new key, sends it there once, and only then keeps its digest; DEVICE's
    old key stops at that moment, everyone else's goes on. The phone gets `mac-runner.json` (or
    `mac-runner-<slug>.json` with `OMA_RUNNER_NAME`) exactly as `rekey` always sent it. A computer gets
    `soma-runner-<runner machine>.json` = `{url, key, name, runner}` for its Soma, by Taildrop, or written straight
    into `~/.config/soma/runners/` (`OMA_RUNNER_SOMA_DIR`) when DEVICE is the runner's own machine. If it can't be sent,
    nothing changes.
  - `oma-runner unpair DEVICE` shuts DEVICE out at once.
  - `oma-runner rekey` is `pair` for the phone.
- **Tests** (`test-runner.py`, 146 checks on the loopback): a loopback runner can be told who is calling
  (`OMA_RUNNER_WHOIS_FILE`, read only when bound to 127.0.0.1) and this machine's name (`OMA_RUNNER_SELF`). Checked:
  the old key moves in as the phone's, digests only, mode 600; the phone's key from another device, or from a caller
  whois can't name, is refused; pair gives this machine's Soma its file, and that key opens the runner from its device
  with no restart but from no other; devices lists names only; a bad name is refused; unpair shuts out at once and
  leaves the rest.
- **On the tailnet** (2026-10-16): a runner on the MacBook (port 18478, a test table with one device, `jacks-mac-pro`)
  answered that key from the Mac Pro (200, logged `jacks-mac-pro`) and refused the same key from the MacBook itself
  (401) and no key from the Mac Pro (401).
- **Out of the agents' file tools:** the key file, the table, this machine's own Soma keys
  (`~/.config/soma/runners/`) and the runner's records are denied to Claude threads' Read and Edit tools (by name,
  from `outside_reach()`) and to Cursor's reads. That's the tools, not the shell: a command an auto thread runs can
  still read them. Codex and Grok can read what this user can (their threads are manual unless auto is named).
- **Locking.** One pair or unpair at a time (a lock beside the table), and every file is written under a temp name of
  its own and moved into place. Six pairs at once finish cleanly.
- **whois answers are kept a minute**, a "no" as well as a "yes": a whois hiccup can keep a good device out for up to a
  minute, and a device that changes owners keeps its "yes" for that minute. On a tailnet of Boss's own machines,
  that's acceptable. Unpairing is immediate, because the key is checked before whois.
- **No computer is paired before Soma desktop keeps its keys in the Keychain** (Critic, 2026-10-16). `pair` for a
  computer leaves the raw key in a file on it (`soma-runner-*.json`), and whois can't tell Soma from any other program
  on that machine: an agent there (a Codex or Grok thread can read any of this user's files) could take the key, and
  with it every runner that computer is paired with. Soma desktop (cockpit slice 6) takes a delivered file into the
  macOS Keychain (on Omarchy, the Secret Service), with access for Soma alone, and deletes the file. Only then do the
  Macs get paired, after a probe in which a Codex thread tries to read the key. Pairing the phone, as today, is
  unaffected. `pair` itself refuses any device but the phone until then (`OMA_RUNNER_PAIR_COMPUTERS=1` is for the tests
  and for slice 6).
- **Not done here:** reinstalling the runners on the Macs, and pairing each Mac's Soma. Both are Boss's call, and the
  second waits for slice 6, as above.

## As built: Grok threads, and runners on Linux (2026-10-16, by the Mac session; Soma cockpit slice 4)

**Grok** (Boss's three agents are Claude Code, Codex and Grok; `kind: "grok"`): Grok Build's CLI over ACP,
`grok agent --no-leader [--model M] stdio`, driven by the same code as Cursor (session/new or session/load, then
session/prompt, session/request_permission and session/update). Found by probing the real CLI on MG1 (1.0.60): its
`initialize` and `session/new` replies have the same shape as Cursor's (`models.currentModelId`, e.g. `grok-4.7`).
- **Always manual** (Critic, 2026-10-16). Grok's auto mode has no sandbox under it, and in a runner thread it let
  `rm -f` and an outbound `curl -X POST` run with no card; its own docs say a call its classifier won't allow is
  *blocked* in a session like this one, never asked about. So `mode()` makes every Grok thread manual, as Cursor's
  are, and no session asks for `autoMode`. Manual is Grok's default mode: it asks here before edits and shell
  commands, **except what Grok runs without asking anyone**: its built-in read-only commands (`ls`, `cat`,
  `git status`…), a bare `mkdir` or `touch`, and what Boss already allowed on that machine. That covers Grok's
  `[permission] allow` rules and remembered "Always allow" grants (per project), and Claude Code's `permissions.allow`
  from `~/.claude/settings*.json` and the project's own `.claude/settings*.json`, which Grok reads. Even so, `rm`,
  `chmod`, `kill` and `git push` ask again under a remembered grant (Grok's dangerous list). Claude threads here inherit those
  same Claude rules already, so a Grok thread can do no more without a card than a Claude thread can. Grok, like
  Codex, can read any file this user can.
- **Its tool calls carry no ACP kind;** the runner names them from the tool in the title (`run_terminal_command` →
  Bash, `read_file` → Read, `search_replace`/`edit_file` → Edit…), so the phone shows its steps as it shows the others'.
- **A request turned down ends Grok's turn** with stopReason `cancelled` (seen on the real CLI); the others go on
  and say so. That's his word, not a failure, so the turn ends as any other does.
- `--no-leader`: a process of its own each turn, so no other Grok client's shared leader sees a thread's work.
  The sign-in is Boss's own (`~/.grok`).
- The CLI is found by `OMA_RUNNER_GROK`, then PATH, then `~/.local/share/mise/shims/grok`, `~/.local/bin/grok` and
  `/opt/homebrew/bin/grok`. `/health`'s `kinds` lists it when it's there. The phone's fallback order at a limit is
  now Claude, Codex, Grok, Cursor (`MAC_KINDS`).
- **On MG1, for real** (a temporary runner on port 18478, a test key for the MacBook): a Grok thread read `notes.md`
  and answered "The note says hello from MG1." (model `grok-4.7`). Asked to write a file in a manual thread, it
  stopped at an approval card (Edit, with the path); denied, nothing was written. The Critic's re-check (Grok 1.0.62):
  a thread asked for in auto came back manual, and `rm -f` stopped at a Bash card. No `grok` process outlives a
  turn: the runner ends it when the turn is over. The phone's `oma threads new … --kind grok` takes it too.

**Linux.** The runner now runs on the Omarchy boxes too (the Grok CLI is found through mise's shims, since it isn't on a
non-interactive PATH): `install` there writes a systemd user unit,
`~/.config/systemd/user/oma-runner.service` (Restart=always, PATH with mise's shims), and enables it. Its
records and logs go under `~/.local/state/oma-runner`. `start`, `stop`, `status` and `uninstall` use
`systemctl --user`. Like herd's unit on MG1, it runs while Boss's session is up (no lingering is set). Checked
on MG1 in a throwaway home with systemctl stubbed: the unit, the calls (stop, daemon-reload, enable --now),
the table with the phone's digest, and `status`.

**Tests:** fake-grok speaks ACP as Grok does, and turns a refused request into a `cancelled` end. Grok gets the
same checks as Codex and Cursor (a card, a wrong nonce, deny, allow, resume, a usage limit, stop), plus: always manual
(auto asked for included), the exact command line, no auto mode on the session, its model passed, its tools named, and
a refusal not being a failure. 166 checks in all.

**Not done here:** installing the runner on MG1 and the Grok CLI on the Macs (signing in is Boss's click). Both
are part of the rollout, which is Boss's call.

## As built: Venice threads (2026-10-19, by the Mac session)

**Venice** (venice.ai; `kind: "venice"`): a chat with Venice's models, not a coding agent. Its thread answers and
runs, reads and changes nothing on the computer, so it never asks for anything and has no mode worth choosing.
- **No CLI in the loop.** The `venice` CLI (`npm install -g veniceai-cli`) is how Boss sets the key up (`venice
  config init`, kept in `~/.venice/config.json`; or `VENICE_API_KEY`), but its `--continue` is one conversation for
  the whole computer, so two threads would talk over each other. The runner talks to Venice's API itself
  (`/chat/completions`, stdlib only) and each thread keeps its own conversation in `venice.json` beside its events,
  sent whole each turn. A turn is a process of its own (`oma-runner venice-turn`, reading `{model, messages}` on
  stdin), so stopping one is stopping any agent. Only an answered turn joins the conversation.
- **Listed when there is a key.** `/health`'s `kinds` names `venice` once a key is there, and `models.venice` lists
  Venice's text models (`/models?type=text`), asked in the background at most once an hour, so `/health` never waits
  on Venice. The chat's picker offers them after the default. No model picked is the CLI's own default
  (`default_model` in its config, else `kimi-k2-5`).
- **Never chosen on its own.** On the devices `MAC_CHAT_ONLY` keeps Venice out of `macKind`: a new thread nothing
  picked, or a turn handed on at a limit, goes to a coding agent. Venice runs only when picked, or switched to
  ("switch to venice kimi-k2-5"). Out of credit (402) or too many requests (429) is an ordinary error in Venice's
  words, never a `limit`: what he said to Venice is not handed on to a coding agent he didn't pick. An answer with
  nothing in it is an error too, and never joins the conversation.
- **The key stays out of reach:** the CLI's config file is in `outside_reach()`, denied to Claude, Cursor and Grok
  threads as the runner's own keys are, and `VENICE*` variables are kept from every agent but Venice's turn
  (`clean_environment`). It is never written to a thread's records (checked).
- Tested: `test-runner.py` against a stand-in for Venice's API on the loopback (models listed with the key, an
  answer named by kind and model, the whole conversation sent again, a 402 as a limit and left out of the
  conversation, the CLI's default model, the key in no record): all 213 checks pass. Not yet run against Venice
  itself: that needs Boss's key on the Mac Pro.

## As built: bots (2026-10-19, by the Mac Pro session; plan: ClaudeSync features/soma-bots/PRD.md)

A **bot** is a folder in `~/Soma/bots/<id>/` (`OMA_RUNNER_BOTS`) on the machine whose runner hosts it: `bot.json`
(`name`, `emoji`, `brain: "venice"`, `model`, `web_search`, `machine`, `tools`), `instructions.md` (Boss's), `notes.md`
(the bot's own), `work/` (where its commands run). `~/Soma` is an allowed root. The runner gives each bot one permanent
session (`sync_bots`, on every `/health` and `/threads`), marked `bot` in `/threads`; `/health` lists `bots` with any
problem; `GET /bots/ID` reads one; `POST /threads/ID/fresh` archives its conversation into the bot's folder. The bot is
read again for every message. A folder with something wrong (bad JSON, another machine's name, tools with web search,
a tool bot on a model without function calling) runs nothing and its tab says why. Approval results now name the
device that answered.

**A change of principle, decided with Boss (2026-10-19).** Until now the runner ran no command itself. Now: **the runner
runs a command only as a tool call from a bot's own turn, never from an endpoint, and a fixed list of look-only command
shapes is the one thing it runs without a card.**
- The tool is `run(command, why, minutes)`, taken only from Venice's structured `tool_calls`. The tool loop runs inside
  the turn's own process (`oma-runner venice-turn`), and Stop ends a running command's whole group.
- Look-only, run at once (60 s): `pacman -Q[ikleqsdmntugc]*` and `pacman|yay -Ss|-Si|-Sii` with package names only;
  `systemctl status NAME.service --no-pager` (not `user@` or `session-`, which would list every command line in his
  session); `uname -a`, `df -h`, `free -h`, `lsblk`, `lspci`, `lsmod`, `nproc`; `cat` of `/etc/pacman.conf`,
  `/etc/os-release`, `/proc/cpuinfo`. No shell signs at all; run as words with no shell, and the program only from
  `/usr/bin` or `/bin` when root owns it (never one a command could have put on a PATH).
- Never run, no card: anything naming an AUR helper or makepkg anywhere in it (Boss runs AUR installs himself), any
  `sudo`/`su`/`doas`/`pkexec`/`run0` except exactly `sudo soma-pacman …` alone, and any command of more than one line
  or 200 characters (a card must show all of it on his phone). The bot is told why.
- Everything else is a card (tool `Bash`, input `{command, minutes, root, outside}`; the text says "Runs as
  administrator." for soma-pacman, "Builds or runs code from outside Arch's own repos." for a pipe, a download or a
  script, then the time limit, then the bot's reason), run in bash after Boss allows it, for the minutes the bot asked
  (1 to 60, default 10). A declined command stays declined for the turn.
- Commands run in `work/` with no stdin, no pagers, `PATH=/usr/local/bin:/usr/bin:/bin`, and only HOME, USER, LOGNAME,
  SHELL, LANG, LC_*, XDG_RUNTIME_DIR and DBUS_SESSION_BUS_ADDRESS from the runner's environment. On Linux each command
  runs in a systemd user scope of its own, so even what it leaves behind ends with it. Each result is cut to its last
  20 kB; past 80 kB in a turn, older results become a marker. A turn ends at 40 commands or 45 minutes of model and
  command time.
- Everything a command prints is sent to Venice (Boss accepted this, 2026-10-19).
- **soma-pacman** (`tools/mac-runner/soma-pacman`) is the only thing sudo runs for a bot: `-S|-Syu|-R|-Rs` with
  `--needed`, `--noconfirm` and lower-case package names. It refuses `-U`, `-Sy` (a partial upgrade), every other option,
  paths, URLs, and removing what the machine needs (the kernel, base, systemd, sudo, pacman, the network, Hyprland,
  Omarchy, Tailscale, graphics drivers).
  Passwordless sudo for raw pacman would be root for anything running as Boss; each soma-pacman card is still a root
  decision, and says "Runs as administrator."
- Tested: `test-runner.py` against a fake Venice that calls the tool: the look-only shapes and their refusals one by
  one, cards, allow, deny, refusals, the environment, a time limit, Stop ending a running command, the 40-call cap, a
  tool call written as text running nothing, and soma-pacman's checks.

**Memory** (slice 3). Every message is built to fit the model: the instructions and notes (notes cut at a tenth of the
budget), a rolling summary of older turns (about, facts, decisions, still open), then the recent turns word for word.
The budget is Venice's `availableContextTokens` minus `maxCompletionTokens`, times 0.8 (32k when Venice hasn't said),
counted from characters at a ratio calibrated per conversation from Venice's own `usage.prompt_tokens`. Past 60% of
the budget, at the end of a turn (the reply already shown, the next message waiting), the oldest turns are folded into
the summary by the same model (`oma-runner venice-ask`, a process Stop can end), and lasting facts are added to a
bot's notes.md; a line that reads as an instruction never is. Summaries and notes changes show as steps. When Venice
says a request is too long anyway, it goes again with only the newest words, and that conversation stays under the
size that failed from then on. One message larger than the whole budget is refused before it is sent. memory.json
beside venice.json keeps the summary, how many turns it holds, the count and the ratio; the whole conversation stays
in venice.json. Plain Venice sessions get the same, without notes or tools.

**Bot tab commands and auto mode (2026-10-19, Boss).** A bot's tab takes slash commands the runner answers itself, with
no model asked: `/model`, `/model NAME`, `/model default` (the choice is kept with the session, never written into
bot.json), `/mode`, `/mode auto`, `/mode manual`, `/help`. A turn has no cap on commands any more (the 45-minute limit
of model and command time stays). In **auto** mode (Boss's pick: "only risky steps"), a command runs without a card
unless `risky_command` says it asks: anything as administrator (soma-pacman), any shell, interpreter or script
(`bash`, `python3`, `./x`, `eval`, a pipe into `sh`), and anything destructive (`rm -r`/`-f`, `dd`, `mkfs`, disk tools,
`chmod`/`chown` with `-R` or outside home, `find -delete/-exec`, shutdown/reboot, writing to a disk device). Refusals
(AUR helpers, other sudo, long or multi-line commands) stand in every mode. bot.json may also say `"mode": "auto"`.
Revised after the Critic (auto mode FAIL, 2026-10-19): `risky_command` is now an allowlist, not a blocklist. A command
runs without a card only when it is one plain command (no pipe, redirect, substitution, glob or escape), its program is
one of AUTO_PROGRAMS named bare (files and folders, downloads, archives, wine/Proton/Steam/Lutris/flatpak tools), it
touches no SENSITIVE place (shell startup files, autostart, systemd user units, Hyprland and Omarchy config,
~/.local/bin, ~/bin, ~/.ssh, ~/.gnupg, ~/.venice, bot folders) and nothing outside his home, and it passes its
program's rule (rm without flags, chmod MODE FILES without -R, find without -exec/-delete, tar without --to-command).
Revised again after the Critic's second FAIL (2026-10-19): auto mode decides by where and how, never by listing
dangers. A command runs without a card only when it is one plain command of a program in AUTO_TOOLS (reading, making
folders, copying, moving, single-file rm, chmod without special bits, tar/unzip/7z/xz/gzip/zstd, wget/curl downloads,
flatpak install/list/info/search/update) with only that program's plain options, touching only AUTO_ROOTS (~/Games,
~/Downloads, the Steam and Lutris compatibility/runner folders) or its own work folder; reading may also see
/etc/os-release, /etc/pacman.conf, /proc/cpuinfo and /usr/share. Anything that runs a program asks: wine, steam,
protontricks, lutris, flatpak run, gamescope, mangohud, opening an app on the screen, scripts, shells, interpreters.
Uploads (curl -T/-d/-F, wget --post-file/--method) ask. Bots also get agent mail (`mail(to, text)` through tmsg, signed
with the thread's id) and, on Linux, Boss's desktop session (Wayland, Hyprland, X) for what they run.

## As built: artifacts (2026-10-24, by the Mac Pro session; plan: ClaudeSync features/artifact-panel/PLAN.md)

An agent shows Boss a file beside the chat on purpose, and every version is kept.

- **The helper.** `soma-artifact put FILE [--name N] [--title T] [--note WHAT]` (FILE `-` reads stdin), `soma-artifact
  editing NAME`, `soma-artifact list`. Written at each start to the runner's `STATE/bin` beside `soma`, first on every
  thread's PATH. It sends one JSON line over a Unix socket (`STATE/artifacts.sock`, or a per-runner name in this user's
  temp folder when that path is too long), mode 600, with `SOMA_THREAD` and `SOMA_ARTIFACT_TOKEN` (an HMAC of the thread
  id under `STATE/artifact.key`, 600). A wrong token, another thread's token, or a Venice thread is refused.
- **The store.** `~/Soma/artifacts/<thread>/<name>/v<N>.<ext>` + `meta.json` (`OMA_RUNNER_ARTIFACTS` moves it). Names:
  letters, digits, `- _ .`, one of the known endings (html, md, txt, json, csv, svg, png, jpg, webp, gif, css, js, …).
  4 MiB a version, 100 versions kept per artifact (oldest let go), 100 artifacts and 256 MiB per thread. Files are
  written whole (temp + rename), 600 in 700 folders; nothing is read through a link; `meta.json` is checked again on
  every read, since agents can write under `~/Soma` too.
- **The event.** `{type: "artifact", name, version, title, mime, state: "editing"|"ready", note}` on the thread's stream.
  A turn's end turns any edit no version followed back to `ready` at the version it had, with `settled: true` (`gone`
  when there was none). Claude Code writing or editing a file named as one of the thread's artifacts, or a Bash
  command naming one, marks it `editing` by itself.
- **The routes.** `GET /threads/ID/artifacts` lists them with every version's time, size, sha and note;
  `GET /threads/ID/artifacts/NAME[?v=N]` gives one version (newest by default) as base64 JSON with `v` and `latest`.
  Same key and whois checks as everything else, `Cache-Control: no-store`. They read the store alone: the project-file
  route (`/artifact?path=`, on phone/fold-development) keeps its rules.
- **The agents are told.** Claude Code: `--append-system-prompt` (`ARTIFACT_NOTE`), and `Bash(soma-artifact *)` allowed
  without a card in either mode. Codex, Cursor, Grok: the same note in brackets ahead of a new session's first words
  (a resumed session isn't told again). Codex's sandbox may refuse the socket; its card then asks.
