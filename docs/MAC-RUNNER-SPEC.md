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
