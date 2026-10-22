# Remembered command approvals

On an eligible Codex command card, **Always allow this command** allows that
execution and saves its exact scope for the same thread. **Approve once** and
**Deny** keep their existing meanings. Nothing is saved until the owner chooses
the new action on an unlocked device and the runner validates the live request's
nonce and successfully delivers the allow decision.

The scope includes the original command, arguments, working directory, resolved
project/directory, and all permission-bearing request fields. It is not a command
prefix: changed arguments, working directory, requested access, or another thread
ask again. The approval card shows the command, directory and requested access.
One hundred scopes can be saved per thread. File edits and other agent providers
cannot be remembered through this control.

Only simple literal commands are eligible. Compound commands, shell programs,
expansions and globbing continue to use one-time approval. A command may invoke a
build script whose contents and inputs change; saving it authorizes that exact
invocation, not a frozen copy of the script or files it reads.

Saved scopes persist in the runner's private thread state across restarts. Each
reuse appears as **Saved command approval** in the event log. Long-press the
thread's tab and choose **Forget saved commands** to clear its grants. This is
immediate for subsequent requests; it does not cancel an execution already
allowed. Stop handles work already running.

Older runners advertise no `can_remember` capability, so upgraded clients show
only one-time approval. Older clients continue to work against the new runner.
Both the runner and client must be upgraded to enable saving; an APK update alone
does not change a Mac runner that is already running. No saved grants are shipped
with a build. The saved-command feature itself does not change the sandbox or
underlying approval policy. The later owner-requested full-access change below
changes Codex auto mode separately.

## Verification — 2026-10-21

- Isolated runner protocol/HTTP suite: 488 checks passed, including exact reuse,
  one-time approval, nonce rejection, changed arguments/directory/access, another
  thread, unsupported shell syntax, real runner restart, and revocation.
- App unit tests: 1,717 tests, zero failures/errors, six skipped.
- Shared chat UI: 34 tests, zero failures/errors. The new test taps the save action,
  expands the tabs, opens the thread menu and revokes saved commands.
- Desktop tests: 134 tests, zero failures/errors, three skipped.
- Android debug APK and Soma desktop distributable built. The cover-width card
  was rendered and visually inspected at `chat/build/render/remembered-command-cover.png`.
- Not verified: the upgraded control on the physical Fold or against a live Codex
  approval request. No live runner restart or app installation occurred. The
  runner tests use a protocol stand-in, not a model account.

## Deployment and source reconciliation — 2026-10-21

The first verification above predates deployment. The Mac Pro runner was later
reconciled with its newer installed source, with Boss's direct approval, to retain
native Codex session discovery, question/permission handling and installed app/MCP
tools. That deployment used the reconciled runner, SHA-256
`1c1217f87f9ed61b9cfe1991fb3ef9382248462ea7bb94a27f4c780c7756590d`.
The sync preserves the installed default modes and bot policy; it introduces no
new live policy change or restart.

Live evidence: `tailscale status` produced approval event 1107 with
`can_remember=true`. Boss's phone decision (1108) was `allow, remembered=true`.
The exact repeat completed without another card; event 1114 records
`Saved command approval: tailscale status`. Phone A independently confirmed those
events and authenticated `/health` returning OK with 17 threads. Soma 119 was
reported installed on the Fold. Both temporary deployment jobs were removed.

The focused, isolated reconciliation tests are runnable with
`python3 tools/mac-runner/test-codex-approvals.py`. They exercise saved-scope reuse,
changed scope, nonce/Stop, and coexistence with native question/permission flows.
The broader protocol fixture disables native discovery because its stand-in
models owned turns rather than a native-session catalog, and follows the deployed
Codex defaults and installed tool availability.

Source-sync validation: all 438 protocol/HTTP integration checks and all five
focused reconciliation tests passed. Legacy auto-policy expectations were updated
to the existing deployed routine/critical boundary; manual-mode rejection and
one-time approval checks remain. The runner file itself matches the live hash
above byte for byte.

## Full-access auto mode — 2026-10-21

Boss subsequently asked to finish `briefs/codex-no-cards.md`. Codex auto mode now
requests `never` approvals and `danger-full-access`, while manual mode keeps
`untrusted` and `workspace-write`. Saved grants and revocation remain available
when Codex sends an approval request; auto execution no longer needs a grant.
Interactive questions and tool requests still reach Soma. Source verification:
438 protocol/HTTP checks and six focused native/approval tests passed. Deployment
of this later policy change must be recorded separately from the earlier hash.
