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
