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
with a build, and the sandbox and underlying approval policies remain unchanged.

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
