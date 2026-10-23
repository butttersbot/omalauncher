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


The first real busy-copy test passed, but exposed late inherited history: native busy-step messages not yet imported could arrive after the phone result and replace its list preview. Fork setup now imports the inherited tail before starting the phone turn. A persisted pending-history flag allows safe retry if that read fails; discovery also clears it only after importing the history. This preserves the latest phone reply as the latest event. Targeted tests include failed-history retry before any model turn.


### Real MacBook/Fold verification

Base runner f0bde9d passed the real native-writer cases; follow-up65477f5 imports inherited history before replying. These are runner-only changes; no phone app installation was needed. Evidence is saved at `/root/handoff/codex-phone-copy-evidence/`.

| Walk step | Result | Evidence |
| --- | --- | --- |
| 1. Idle open writer creates a phone copy | PASS | XA`t18fe4c5c` returned retained context with a copy notice; the original stayed unchanged. |
| 2. Second actual Soma composer message stays on the copy | PASS | XA events10–12 returned `XA ready` then `XA follow-up pong`; screenshot inspected. Early UI launch attempts failed before Send and produced no extra messages. |
| 3. Busy writer waits then copies | PASS | XB`t7705bd53` emitted waiting then copy notes and `XB phone pong`. XF`t632fa906` repeated this on65477f5: native completion event11 precedes phone text12/result13, with `XF phone pong` the latest reply. |
| 4. Stop cancels only the phone wait | PASS | XE`tb426d16e` actual Soma composer/Stop showed stopped; Mac peer confirmed native holder survived and the cancelled prompt never reached its transcript. Waiting/stopped screenshots inspected. |
| 5. Closed/crashed native writer resumes normally | PASS | XC`t7aa169d1` and XD`t7d2a6cc3` returned `XC phone pong` / `XD phone pong`; no phone copy was required. |
| 6. Restart/discovery preserves one mapping | PASS | Mac peer confirmed no duplicate source/copy threads. On65477f5 XF remained at14 events across multiple discovery scans, with `XF phone pong` still last; event-array equality checked from the Fold. |

Local targeted checks after the follow-up:16 Codex PASS; Claude27 PASS and one Darwin-only skip on the Fold. The full30-minute timeout, unknown-log formats, corrupted/missing metadata, and crash-during-wait variants were checked automatically rather than walked for30 minutes or by corrupting owner data. The earlier misleading cross-process RPC `interrupted` status and file-quiet heuristic were rejected before deployment in favor of raw lifecycle markers.

Boss's original `ok lets build it` message in`t68bc8fc8` remains retained and was not replayed during connection tests. The actual failing desktop session was checked read-only for the expected rollout format. Only disposable spare sessions received test messages; Boss's running desktop writers were not stopped.


Final65477f5 runtime confirmation: Mac peer reported an exact source match, all focused/broad tests passing, all live cases passing, and only its disposable test holders closed. Local runtime SHA1:`a1d075c9210d0f2f8569af066c98588d47a54124`. XF's event array stayed unchanged after multiple scans; native finish11 precedes phone text12/result13. No late native progress overwrote the phone reply. Actual Soma follow-up and wait/Stop screenshots were inspected. The rollout is complete; no owner build message was replayed and no phone binary was installed for this fix.
