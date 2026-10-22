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
