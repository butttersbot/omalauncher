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
