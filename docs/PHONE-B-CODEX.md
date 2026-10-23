# Phone B provider selection

Phone A keeps the launcher's selected default agent. Phone B selects an installed
Codex and passes `--model gpt-6.1-sol`. If Codex is absent, B uses the default agent
and records that fallback in the request's steps. Installation detection does not
establish that the provider is signed in.

Both attached images and the phone-control instructions still reach Codex. Its
process is marked `OMA_BUILDER=B`, so replies from phone tools route to B. The
watchdog uses each running builder's actual event protocol, rather than assuming
both use the launcher's default provider.

Phone B launches Codex with `--dangerously-bypass-approvals-and-sandbox`, as
requested in `briefs/codex-no-cards.md`. This removes Codex execution approval
prompts and avoids its incompatible bubblewrap sandbox inside the phone's proot.
Phone A and non-builder assistant commands retain their existing launch policy.

The Mac runner's Codex auto mode uses `approvalPolicy: never` and
`sandbox: danger-full-access` for both new and resumed sessions. Manual mode
retains `untrusted` approvals and the `workspace-write` sandbox. Native questions,
interactive tool requests, Stop, installed integrations and saved command grants
remain supported. Full access does not disable operating-system restrictions or
guarantee that external tools will never request input.

## Verification limits

Unit tests exercise provider selection, fallback, the full-access flag, and the
real shell argument boundary for model, prompt and image paths. Runner tests check
auto/manual settings on both new and resumed sessions. A phone-side reply and a
tool request through the Phone B UI still require verification after an
owner-approved build promotion. The launch flag addresses the reported sandbox
failure; a successful phone tool journey remains unverified until tested there.
Do not describe phone-side tool use as verified based on a successful login or a
plain text model reply.

## Native Mac Codex continuation

A native desktop conversation can retain a writer even while idle. Its runner resume then fails with `already has an active writer`. For discovered native Codex conversations only, that exact conflict now checks native lifecycle records and creates a phone copy using `thread/fork`. Other errors preserve the original and do not silently start a new conversation.

Cross-process `thread/turns/list` can label a genuinely active turn interrupted on the installed Mac version. The driver instead obtains the native rollout path from `thread/read`, validates the session header, and reads a bounded tail for `task_started`, `task_complete`, or `turn_aborted`. A quiet file with an unmatched start remains busy. Incomplete records wait; unreadable or unknown lifecycle state refuses safely. No peer credentials or messaging sockets are used.

Busy work waits with a visible note, Stop, and a thirty-minute timeout. Each recheck retries normal resume first, so a desktop writer that closes or crashes permits same-session continuation despite an orphaned start marker. An idle open writer forks through the explicit finished-turn ID with Soma's existing permission rules. The new session and its origins stay on the same Soma thread; discovery excludes origins and deduplicates inherited history. The copy notice explains that the Mac window will not show phone messages. As with Claude, an owner starting a new Mac turn immediately after the snapshot can still race; this is not a distributed project-edit lock.

Official protocol reference: [Codex App Server](https://learn.chatgpt.com/docs/app-server), `thread/read`, `thread/fork`, and `lastTurnId`.

Regression coverage includes writer-conflict handling, unknown/other errors, completed-turn anchoring, busy wait/cancel/timeout, writer closing during a wait, strict session IDs, source marker parsing, alias discovery/deduplication, and two messages through actual driver pipes. Local Codex checks:15 PASS. Claude discovery:27 PASS, one Darwin-only skip. Real Mac deployment and the live steps in `tools/walkthrough/scripts/codex-phone-copy.walk` remain pending.
