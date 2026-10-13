# A runner on the Mac for Oma's builders

For the Mac session, to build on the Mac Pro with Boss. Written 2026-10-13 by the agent on the Fold.
Nothing here is built. The phone side that would call it is not built either: say what you change in this file.

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
