# Phone B provider selection

Phone A keeps the launcher's selected default agent. Phone B selects an installed
Codex and passes `--model gpt-6.1-sol`. If Codex is absent, B uses the default agent
and records that fallback in the request's steps. Installation detection does not
establish that the provider is signed in.

Both attached images and the phone-control instructions still reach Codex. Its
process is marked `OMA_BUILDER=B`, so replies from phone tools route to B. The
watchdog uses each running builder's actual event protocol, rather than assuming
both use the launcher's default provider.

This change retains the existing Codex execution and permission settings. It does
not implement the blanket sandbox/approval removal in `briefs/codex-no-cards.md`,
and does not change the Mac runner or restart it.

## Verification limits

Unit tests exercise provider selection, fallback, and the real shell argument
boundary for model, prompt and image paths. A phone-side reply and a tool request
through the Phone B UI still require verification after an owner-approved build
promotion. The brief reports that Codex's Linux sandbox fails inside proot;
this provider/model change does not resolve that runtime compatibility issue.
Do not describe phone-side tool use as verified based on a successful login or a
plain text model reply.
