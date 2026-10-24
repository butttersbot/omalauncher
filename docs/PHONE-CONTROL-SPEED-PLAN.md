# Phone control: ten-times-faster common workflows

Owner request: inspect today's implementation, write the complete plan, then execute it.
Scope: main and hidden display controls, CLI and Mac callers, Samsung Settings and ordinary apps.
Success means verified task completion faster; fewer milliseconds per tap alone is insufficient.

## Baseline and measurement

Initial real-phone read-only sample (5 calls per case, quiet screen; not an action benchmark):
- CLI status median130.2ms; direct socket23.1ms.
- CLI screen median145.9ms; direct socket57.5ms.
- Native settle already adapts: minimum150ms, quiet120ms, maximum350ms.
- CLI run currently forks the complete shell client per step and reads complete screens.
- Missing-label navigation may traverse6 forward and12 backward scrolls, with no repeated-page detection.
- Bridge handles requests serially; a long request can delay another caller.

Add opt-in per-request timing: dispatch/total, reads, nodes visited, action duration, settle,
scrolls, output bytes. Do not log screen text, typed values, screenshots or credentials.
Record command, package only where needed, outcome, and bounded durations. Use monotonic clocks.
Measure end-to-end task wall time and agent turns separately; include retries and failures.
Record cold/warm runs, screen size, source/APK version, device and network/model conditions.
Report median/p95, sample count and success rate. Small samples are exploratory, not p95 evidence.

Benchmark three repeatable workflows before/after:
1. Settings: open Display, read a setting, return. No security or permission changes.
2. App navigation: open our isolated fixture, navigate labelled controls and verify destination.
3. Search: known offscreen control; absent control; repeated pages; both scrolling directions.
Run emulator benchmarks with fixture reset identically, then Samsung [phone] checks after promotion.
Only approved reversible actions; no real sends, purchases, deletion or account changes.
Use at least20 timed trials for stable task comparisons and report distribution; larger samples
for meaningful p95 claims. Match success criteria and work, including verification, in both versions.

## Implementation order and completion gates

### 1. Observability and quick navigation (start here)
- Opt-in machine-readable timing on service responses; preserve normal response compatibility.
- Add named, allowlisted Settings destinations through Android public intents; verify target app.
- Keep existing `open settings`, labels and low-level commands compatible.
- Stop search on repeated visible pages and terminal-list outcomes; retain bounded reverse search.
- Verify with pure unit tests, emulator fixture and real Samsung Settings after promotion.

### 2. Native bounded plan execution
- Parse run scripts once, send one structured request and execute in Android, not recursively in Bash.
- Reuse the same existing action admission/targeting code; do not build a second permissive action path.
- Sequential actions only; revalidate pause, lock, display, expected package and approvals for every step
  and delayed continuation. Explicit app transitions update expected package intentionally.
- Preconditions/assertions/wait-for control or value; return only final screen plus per-step timings.
- Stop at first ambiguity, failure, unexpected package, takeover, cancellation or deadline.
- Bound steps, input size, execution time, memory and output. No unbounded loops or eval.
- Exact failed step, count completed, action outcome uncertainty, and fresh screen when safe.
- No automatic replay after timeout or disconnect. Retry ID/status must prevent duplicate mutations.
- Batch owns one display for its duration; reject competing actions. Owner Stop must remain responsive;
  bridge must admit cancellation/status without waiting behind a whole batch.
- Keep old CLI run available as compatibility fallback during rollout; report chosen execution mode.
- Test cancellation between steps, during waits, gesture completion, owner takeover, service restart,
  lock/sleep, wrong display, missing helper, unknown command, deadline and protected-app refusal.

### 3. Efficient observations and targeted waits
- Filter events by affected window/subtree; relevant changes invalidate snapshots.
- Reuse immutable observations only while their revision/window/package remain valid.
- Fresh target revalidation before every action; never cache stale node handles as authority.
- Compact control IDs, delta output with explicit base revision; full screen on cache miss or confusion.
- Queries for a label/control/value; event-driven waits with polling fallback and deadlines.
- Screenshot/visual fallback for graphics, WebViews, incomplete trees and ambiguous semantics.
- Benchmark tree traversal before using targeted Android APIs; preserve password redaction.
- Confirm target state, not merely quietness or performAction returning true.

### 4. Verified reusable routes
- Store explicit user/task-authorized successful routes: package, app version, starting screen,
  semantic selectors, assertions, expected destination, and allowed transitions.
- Routes are data, never shell code; scoped parameters and bounded execution.
- Read-only routes can be reused; mutations must be in current task authorization.
- App/version/layout/locale change invalidates or forces revalidation; never coordinate-only replay.
- No fuzzy choice of destructive controls. Unknown state returns control to the agent.
- Owner can inspect/remove routes; no sensitive text or secrets stored in route definitions.

### 5. Agent integration and rollout
- Update CLI help and injected phone instructions together; use plans for known sequences.
- Avoid redundant read after actions that already return the verified new screen.
- Use direct supported actions/intents before visual navigation; never infer success.
- Match capability negotiation to installed version; mixed old/new Mac callers fail clearly.
- Feature flags/fallback retain single-step control; no duplicate action after uncertain timeout.
- Bounded diagnostic storage and explicit route/cache invalidation; no new network listener.
- Document troubleshooting for unsupported Settings destinations, OEM differences, partial trees,
  unavailable hidden display, asleep controls, lock and permission gates.

## Performance targets (hypotheses until measured)

-10x median end-to-end completion for selected familiar routes vs current agent-driven baseline.
-No worse completion rate; no wrong-app actions or stale-control actions in adversarial checks.
-Reduce agent turns to one plan plus final verification for known routes.
-Transport/parser improvements measured independently; don't multiply unrelated speedups.
-Unknown screens retain interactive fallback and may not reach10x; report them separately.
-Do not claim improvements from shorter waits without verified correct destinations/state.

## Test, release and closure

Each behavior change has regression tests and committed tools/walkthrough/scripts/phone-speed.walk.
Emulator: app navigation, batches, input validation, malformed/oversized data, repeated search,
wrong package, timeout, cancellation, stale snapshots and full/compact response compatibility.
Samsung phone: actual Settings destinations, foreground transitions, normal app flow, main/hidden
isolation, Stop/Take over responsiveness, locked/asleep behavior and animation timing.
Never fall back from unavailable hidden display to main. No permission/security toggles by agent.

Run normal Mac build/test gates at exact source/version, inspect walkthrough results AND screenshots,
verify runtime bytes and phone certificate, stage signed APK. Promote only when PhoneA is idle under
standing owner instruction. Installation may restart agents; checkpoint before install.
After promotion, verify installed version and execute phone checklist without inventing PASS.
Record measurements, source, tests, screenshot review, remaining failures and rollout state in
/root/handoff/phone-control-speed.json. Leave every stage explicitly TODO/IN PROGRESS/PASS/BLOCKED.
Do not label the project done until real benchmark results and all applicable checks are recorded.
Rollback is a compatible forward-version build of last known implementation, never counter lowering,
uninstall/data clear, permission bypass or source reset. Keep existing single-step path usable.

## Current execution state

Plan committed with first implementation. Stage1 IN PROGRESS. Stages2–5 TODO.
No10x claim yet. No live Settings or app actions benchmarked yet.
