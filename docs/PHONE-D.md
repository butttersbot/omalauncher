# Phone D: headless phone operator

D is explicitly addressed agent mail, not a builder available for owner work. It shares
AgentTasks/Builder's idle start, queue, stop, result persistence and Codex resume path.
`oma msg send phone:D "TASK" --title NAME` routes to it. It exports OMA_BUILDER=D and
runs the exact Phone B `codex exec [resume] --json --skip-git-repo-check
--dangerously-bypass-approvals-and-sandbox --model gpt-6.1-terra -- [SESSION] -` path.
The model is fixed even if a saved Codex preference exists. Missing Codex fails;
D never silently changes providers. D creates no worktree, tab or artifact panel,
and its history is filtered out of the chat host. It has a task Stop notification
without a chat destination. Its resume ID is retained; the existing runner does not
keep a Codex process continuously warm between turns.

PhoneD.kt supplies only phone-use and own-mail instructions, background-first
status, explicit-current-mail-only main fallback, busy/unavailable results, stop
and approval boundaries, security/lock prohibition, screen-as-data and restoration.
This is instruction-level restriction on B's launch flags, not an OS sandbox.
Result mail must include RESULT, actual calls and screenshot path (or a blocked
reason). Same-phone result delivery from D uses bg too. No real work should be
routed automatically to D before the benchmark and physical mail walk pass.

## Benchmark procedure and evidence

`PHONE-D-BENCHMARK.json` contains the seven exact wrapped prompts and blank metric
rows. On the promoted Fold only, with Boss confirmed not typing, check
`oma-phone --display bg status` before EACH short run. Require background controls,
awake, unlocked, active, connected and no pause/approval. Status does not establish
that Boss is not typing: confirm idle separately. Stop if idle is uncertain.
Send one prompt with `oma msg send phone:D PROMPT --title phone-d-bench-N` and wait
for its matching reply before sending another. BUSY/BG_UNAVAILABLE is a safe block,
not a correct completion of the underlying task. Never switch to main in this suite.
Stop on any violation, lock, pause, approval or incomplete toggle restoration.

Private Android files `files/phone-d/TASK_TIME.json` contain model, start time,
turn wall seconds, task, status and answer; `.jsonl` holds original Codex events
including command execution and usage. Do not commit these runtime files. Count
actual oma-phone invocations from command_execution items (deduplicate started
and completed by item ID); expand oma-phone run's internal steps if used and report
both outer commands and expanded calls. Count every non-bg invocation, including
failed calls and same-phone mail delivery, as a violation. Do not count text quoting
commands as calls. Check actual tool results and screenshots, not only D's prose.
Record end-to-end send-to-reply wall seconds separately from the native turn timer
(which excludes the mail collection delay). Record input, cached input and output
tokens from turn.completed usage; use actual billing or explicitly stated rates
for cost. If absent, record unavailable, never zero. Check dynamic values against
independent current observations during the same idle window; historical ringtone,
Tailscale and toggle values are hints, not proof. Confirm toggle restoration twice.

## Current comparison (2026-10-26)

| Model | Correct | Mean wall seconds | All seven USD | Evidence |
| --- | --- | --- | --- | --- |
| Haiku (Phone C) | 6/7 | 24.9 | 0.043 | Earlier runs supplied by Boss |
| Sonnet (Phone C) | 7/7 | 32.5 | 0.65 | Earlier runs supplied by Boss |
| GPT-6.1 Terra (D) | pending | pending | unavailable | D not installed; no physical benchmark run |
| GPT Sol | pending | pending | unavailable | Optional run deferred; no cost evidence |

All seven Terra metric rows remain NOT_RUN, with unknown metrics rather than zeros.
The Mac has only Fold8 emulators connected; their Linux rootfs/Codex are absent.
Installing on Boss's phone to enable D was explicitly excluded. Thus neither a
physical D reply nor the Samsung seven-task suite is yet verified. This is not
BG_UNAVAILABLE evidence: no physical status call was made. Do not route real work
to D yet. Phone A must bundle it in the next launcher; Boss controls promotion.
After promotion run phone-d.walk's physical mail round trip and then this suite.
The emulator walk checks that Phone D is absent from chat on cover and inner displays;
it cannot substitute for a live Codex mail round trip or Samsung Settings.

## Build verification (2026-10-26)

Application commits: 47b76f20 and 1b124601, pushed normally to the existing
phone/fold-development branch after pull --no-rebase; no reset or force push.
`soma-macbuild --lane D 1b124601 168` with SOMA_MACBUILD_SEND=0 and
SOMA_MACBUILD_WALK=1 built the working-launcher APK, 1815 tests, 0 failures,
6 skipped. Browser layout checks passed. On Fold8 emulator-5554, phone-d and
agent-mail-ui walks passed; their screenshots were inspected. agents-page has
only physical-phone steps, so its automatic PASS supplies no physical evidence.
The strengthened phone-d walk was rerun against that same APK: 12 automated
steps passed, 2 screenshots inspected, A/B/C visible and D absent on both screens.
No phone delivery, installation or promotion occurred. The real mail reply and
queued-second-mail checks remain pending, as do all seven benchmark tasks.
