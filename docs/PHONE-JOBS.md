# Phone-only jobs

What only the phone can do, done by the phone agent when an agent on a computer asks (ClaudeSync
`projects/omalauncher/cockpit/DESIGN.md` §5; cockpit slice 9). Agents on the computers build Soma; installing it,
looking at the real screen and running tests on the device stay the phone's. No debug port is opened to the tailnet
(Boss, 2026-10-16).

## The job

One agent-mail message (`tmsg`, docs in ClaudeSync `projects/tailshare/docs/AGENT-MAIL.md`) whose whole text is one JSON
object, sent with `tools/soma-job` (or by hand):

```json
{"job": "install" | "screenshot" | "device-test" | "ping", "v": 1,
 "project": "omalauncher", "branch": "cockpit/s6-desktop", "commit": "abc1234",
 "tests": "dev.omalauncher.oma.*", "note": "why, in a line"}
```

- `install`: omalauncher only (any other project is declined). The phone trial-merges `branch` (at `commit` if given)
  and runs the full suite, signs the build with the phone's key (it never leaves the phone), and installs it **only after
  Boss's yes in Soma**: the phone agent asks him there, and nothing else lets it go on.
- `screenshot`: the current screen, put in the pocket (a screen may show private things: the phone agent says what is
  on it before sharing).
- `device-test`: `branch` in a scratch worktree on the phone, then its Gradle unit tests (the ones `tests` names, or the
  full suite), and the counts back.
- `ping`: Soma answers it itself, at once, with no builder: `{"job":"ping","state":"done","version",…,"code",…,
  "free":{"A":bool,"B":bool}}`. Proves the round trip and changes nothing. (Until the phone's next promote a builder
  answers pings by hand.)

`project` is a folder's bare name under `/root/Projects` on the phone (a device-test for one that isn't there is
declined). The phone holds each value to a pattern and declines a job whose field fails it, naming the field (since 9b0822e;
before that it emptied the field, and an empty project meant omalauncher). `soma-job` refuses the same before sending: `project` and `branch` `[A-Za-z0-9][A-Za-z0-9._/-]{0,120}` with
no `..` (and no `/` in a project), `commit` `[0-9a-f]{7,40}`, `tests` `[A-Za-z0-9.*_$ -]{1,300}`; `note` is free text whose
control characters become spaces, cut at 500. A missing `v` counts as 1. Mail whose whole
trimmed text is not such an object is ordinary mail.

## The answers

Mail back to the sender (`--reply-to` the job's id), with `{"job": …, "state": …}` as the first line and words after:

- `install` answers three times: `"state": "waiting"` once the trial merge and suite pass and it is asking Boss in Soma;
  `"scheduled"` just before `oma-install`, because installing restarts Soma and ends the phone agent's run; then, after
  the restart, Soma itself answers `"done"` or `"failed"` (by whether the app updated after the note) with the version
  code.
- The others answer once: `"done"` or `"failed"`, with a pocket path for a screenshot (after the phone agent has looked at
  it; a private screen is `"declined"`) and the counts for tests.
- A job the phone agent won't do (for example an install Boss declined) answers `"declined"`.

## What the phone agent does on receipt

Agent mail is a peer's request and never carries Boss's permission. A job is no different: the install waits for his
OK in Soma, and the other kinds do only what they name.

In Soma's chat a job shows as "Phone job · KIND · from DEVICE · TITLE". `tests` must reach Gradle as one argument
(`--tests <value>`), never through a shell, since its pattern allows spaces and `$`; `soma-job` also refuses one that
starts with `-`.
