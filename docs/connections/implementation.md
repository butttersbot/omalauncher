# Connections — native v6 implementation

Approved reference: Soma Widgets / Soma Connections v6; Boss's approval relayed
by Phone A in mail 20261028-214126-19f35c. Package /root/handoff/connections/,
including the eight cover/unfolded variants and shared modal shell v1. This does
not approve unrelated design-system proposals or modify Deco.

Connections is a native module (module:connections), default half-page card.
Half-width small: Network/Agents/Canvas; one-page medium adds Phone/Sync. Full
size adds service rows including core Design Canvas; size is bounded at 180dp
for compact cards and 300dp for large. The header and bottom-left 44dp Restore
utility surround the status band. Restore consumes its own tap. Body opens a
native modal with equal 44dp header slots, fixed header and scrolling content.
All 12 groups are present; inline disclosures expose real check evidence.

Monitoring starts with Runtime, independently of home visibility. Android local
notifications do not traverse the tailnet, but process suspension/death can delay
checks; this is not an independent always-on external watchdog. Alerts are
explicitly enabled in the detail UI after Android permission/channel checks.
They debounce for 60 seconds, persist deduplication across process restarts,
replace an incident notice on verified recovery and suppress explicit one-hour
monitor pauses and neutral disabled/sleeping checks. A gap exceeding 90 seconds
restarts an unannounced sustained-failure window. Dismissal does not reset dedup.

## Checks and exact remaining dependencies

Authenticated /health + read-only /threads on every configured runner. 401/403
means reachable, auth unverified, never healthy. Agent/build dependency failures
share a runner incident; missing VPN collapses dependent failures to one network
incident. Android VPN evidence does not claim relay/direct path health.

Existing Whisper and embedding configured endpoints, Herd inventory and Pocket
listing are read. Design Canvas uses its own CANVAS_URL/token files and /api/apps,
not the unrelated Soma service. Workspace service discovery tries authenticated
/services then the existing /health soma field; credentials never leave the
paired runner's host. Redirects are refused; response size/time is bounded.

Unknown remains explicit where existing APIs cannot prove provider sign-in,
build locks/toolchain/source sync/delivery, index freshness, receive-worker and
delivery acknowledgments, Messages permissions/freshness, board contents and
workspace sync freshness, or trusted wireless ADB. No synthetic timestamps or
success data are used. Phone accessibility binding, intentional pause, screen
sleep and authenticated background-helper freshness are read directly.

Restore currently does a new check and reports blocked/guided repair. Existing
runners have no supported authenticated service-control API. It never reboots,
never invokes arbitrary shell/SSH, never enables permissions/debugging, and
never restarts an unknown or busy job inventory. Closing the modal does not
cancel checks. Real successful service restart requires a supported host helper
with service-specific controls, atomic active-job guard and authenticated agent
and build verification; that infrastructure is not claimed implemented here.

Optional explicit health endpoint overrides live in launcherConfig/connections.json:
`{"checks":[{"capability":"Messages","url":"http://HOST:8765/health",
"schema":"ok","enabled":true}]}`. Capability is an enum name; schemas are
voice/search/herd/pocket/canvas/ok. Missing optional configuration is neutral.
Keys, if required, belong to that endpoint only and never appear in UI/logs.
No configuration, real alerts, restoration or security settings were changed
on Boss's running phone by this source work.

## Verification

First compile/unit checkpoint pending. Slice walks will cover all native sizes,
cover/unfolded, body versus utility tap, inline inventory, stale/auth/paused and
unknown evidence, guided/blocked Restore, active-job guard and notification
state-machine dedup/recovery. Real-phone checks follow the combined install;
there is no separate launcher install from this lane.

First critic checkpoint 609ede5b: compile stopped at a BoxWithConstraints DSL
scope error; no tests ran. Fixed by capturing the height condition before the
Column scope. Review also found waiting_turns/pending/background work was not
counted correctly and an unverified idle helper could page. The work inventory
now parses the actual runner wire fields, refuses ambiguous input, and counts
waiting approvals/questions and background jobs as active. Idle helper state is
Unknown, never a proactive outage. Added regression coverage for these guards.
The preview-only Connections fixture and connections.walk accumulate native
cover/unfolded, size, disclosure, restored/failed/blocked and neutral-state checks.
Its synthetic success is explicitly fictional; production has no restart path.
Canvas read was attempted but 100.105.137.74:5860 refused the connection; approved
appearance comes from the handed-off v6 package, with no approval changes made.

Second compile checkpoint 00ccf168: app, desktop and preview compile PASS.
Tests stopped on the new test imports (kotlin.test was not configured here)
and the store's exact module-name expectation. Converted new tests to the
repository's JUnit imports and included Connections in the store assertion.
The unfolded modal now follows v6's repair/inventory columns. Fresh route and
authenticated-runner observations are retained independently of Unknown
end-to-end subchecks, allowing honest connection-recovery notices without
claiming full build/provider/sync health. Multiple unreachable services on the
same configured host share an incident without guessing sleep or kernel panic.

Additional monitor hardening: persist dedup state synchronously before posting,
retaining the previous state if storage fails; a readable HTTP status followed
by an incomplete/oversized body is reachable-but-unverified, not a fabricated
connection outage. Endpoint string representations omit paths, query and keys.
A switch-enabled but disconnected accessibility binding uses the existing phone
startup-grace policy and becomes a real incident; owner-off, pause and sleep
remain neutral. Workspace service recovery has its own authenticated identity
observation while full sync freshness remains Unknown.
Sleeping observations age into Stale if checking stops; they never remain fresh
sleep evidence indefinitely. Added a loopback-only HTTP regression proving that
redirects do not forward the configured bearer credential and an oversized
HTTP 200 remains reachable/unverified. No remote account is used by that test.

Targeted advice selects owner-only Tailscale/Phone control inspection for network
or binding incidents and pairing inspection for auth refusal, rather than
suggesting a generic Mac restart. Expanded detail offers the matching existing
owner screen; opening it changes no setting. Runner incidents retain the active
job guard and can name another authenticated runner for a new chat, without
moving or stopping existing work. Automatic refresh clears stale repair advice
when incident evidence changes.

## Owner-authorized circuit breaker slice (in progress)

Phone A relayed Boss's approval in 20261028-215622-7fc8ec: build actual fixed
service controls and chase missing capability probes, without deploying runner
changes until Boss gives direct approval in the Mac lane at final install.
The Mac lane now owns runner GET /services, the fixed service allowlist,
approval/challenge lifecycle, active-work admission guards, audited launchd
mappings, real capability probes and durable operation status. Native Android
orchestration/owner-proof and phone recovery are being built on this lane.

Phone A's refinement 20261028-220739-4e46cc supersedes the long owner how-to:
blocked/failed cases produce redacted JSON for agents and a one-line summary.
Records live at ~/.local/share/soma/connections/incidents/ID.json and newest.json.
`oma connections incident [ID]` and `list` are read-only; `owner-key` reads only
the public-key export when available. There is deliberately no CLI restart or
approve verb. Journal writes are atomic, fsynced, process-serialized and file
locked; identical redacted evidence deduplicates across instances, updates
last_seen/occurrences, and retains at most 256 records. Checks include HTTP code,
allowlisted result fields and redacted stderr tails; next_steps distinguish agent
work from owner-required actions. Source sanitization is repeated at final JSON
serialization; keys/tokens, cookies, nonce/signature material, JWTs and private
key blocks never belong in incidents. Records never authorize operations.

The proposed owner boundary is stronger than a caller-supplied `owner:true` or
an approval nonce visible to any paired agent: each specific restart nonce is
bound to service/host/expiry and signed using a native auth-per-use Android
Keystore key. One signature may cover a bounded fixed plan. The Macs must pin
only the public key at the final owner-approved install; there is no remote key
enrollment or TOFU. The private key never leaves Android. Agent calls without
owner authentication fail closed, and fake-runner walks use injected test proof
with mocked service execution only. Implementation and verification remain
pending; no real restart, enrollment, permission or deployment was performed.

Primary platform reference for auth-per-use CryptoObject signing:
[Android Keystore key authorization](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/keystore/java/android/security/keystore/KeyGenParameterSpec.java).
Local checks for the new read-only diagnostic CLI: 2/2 Python tests PASS
(traversal/symlink rejection and no restart/approve verbs); Kotlin redaction,
canonical dedup and journal tests await the next compile checkpoint.

Prior UI checkpoint 20eec09d: app/desktop/preview compile PASS, 23 targeted tests
PASS (critic confirmed real-shape runner/Soma outage recovery is fixed). The
committed walk still failed 36/27 because of script errors, not app failures.
Critic proved four script adjustments on the unmodified app/tool: exact bracketed
Restore button label, key BACK (not unsupported back), scroll past Optional tools
before the Canvas disclosure, and scroll to the fixture alert copy. That scratch
run passed 64/64; it is recorded as scratch evidence, not an official-script PASS.
Phone B reviewed all 12 LOOKs in the contact sheet from Pocket
20261028223312-160d2b: compact/medium/large, fixed utility, core rows, centered
modal header and unfolded repair/inventory columns look correct. Those script
fixes are now in the repository; agent-details disclosure is added to the next
slice's accumulated walkthrough. New actual Restore/auth paths still need their
own fake-runner success/failure/blocked coverage; no phone install was performed.

Native owner-control protocol source added: fixed service IDs and request paths,
strict host/service/nonce/expiry/payload binding, bounded sorted approval plan,
no redirect or arbitrary command support, and auth-per-use P-256 Keystore signing
through the OS CryptoObject. The public export is separate from incidents and
contains no private key. New local-source tests exercise target substitution,
expiry and whole-plan signature binding using in-memory test keys; no live
service request is made. The controller is not wired into production Restore
until the Mac endpoint contract and guarded operation status are integrated.

### Owner-only service-control integration checkpoint
Native Restore now obtains fixed-service challenges only for fresh failed capability checks whose runner inventory advertises a supported repair. The non-exported Activity takes an opaque in-memory ticket; neither an exported intent nor the guest CLI accepts signing plans. OS auth-per-use signs the exact bounded host/service/nonce/expiry plan. Public-key export occurs on the owner's tap; it does not enroll trust on a Mac.

Before the only settle POST, native SharedPreferences durably records a pending marker without nonce/signature/token. Monitoring resumes operation GETs; a missing reply never retries a restart. A dropped submission needs the runner's read-only approval-to-operation lookup. Unknown/unsupported/stale checks do not infer a restart target. Restoration requires matching operation/approval/host/service plus verified=true and nonempty all-pass capability evidence. Mac server source is imported, never deployed here. Remaining work: complete Mac probes/privileged-helper installer source, admission/trust review fixes, phone keeper/helper recovery, fake-runner integration tests, compile and final cover/unfolded walkthroughs. No new walkthrough PASS is claimed.

Phone recovery is limited to starting a missing fixed native-supervised keeper or background watcher. Both are listed in the OS-authenticated plan. It refuses active/waiting/queued builder/front work, Linux windows and single-step/plan phone work, rechecks before starting, preserves intentional phone/background pauses and Linux-off state, and uses only existing AdbWifiHeal for Wireless debugging after owner authentication. It never kills a live process or restarts the whole Linux environment. Hung-but-live keeper/runtime restart remains blocked until an atomic idle shutdown/admission protocol exists; diagnostics must not claim this missing path works. A keeper protocol reply or mutually authenticated helper status is required to report the particular missing service restored; no display is created to prove it. Actual Android authentication and real Fold recovery remain [phone] validation after promotion; preview fake-runner tests do not prove those.

Security review blocked the first privileged-helper draft: passwordless `helper SERVICE` sudo rules would let an agent bypass the runner's owner signature and work guard. This checkout therefore refuses privileged execution, its helper refuses even valid service names, and its installer installs/grants nothing. No sudo rule was deployed. A replacement must independently verify the exact fresh signed plan and one-use nonce at the root boundary; source-only GUI/self-restart control remains separately owner-authenticated. Mac lane has the blocker and is preparing the replacement. This is not a deployment-ready checkpoint.

### Checked source milestone (before privileged broker)
At a63e492d, lane B compiled app/desktop/preview and targeted tests, then ran official Connections walkthrough 70/70 with 14 LOOK screenshots reviewed by the critic and Phone B (cover S/M/L, details JSON, actual portrait/landscape, unfolded two columns). Evidence is /root/Inbox/connections-a63-reviewed (Pocket 20261028234127-3041ba). The one-line card summary intentionally truncates with ellipsis; expanded detail and the agent journal carry the full result. A final portrait scroll assertion was added to demonstrate the fine print is reachable. Real Fold OS authentication, actual notifications, and real recovery remain deferred until the combined owner-approved promotion.

Imported Mac lane source through 66b5852a adds independently signed Swift helper code (not installed), durable nonce file+directory sync, bounded input, compile-time-only dry-run tests, per-host configured ports/launchd placement, explicit absent-service state, required missing-proof checks, and checking-stage crash reconciliation with zero retry. Local suite: 70 executed, 60 passed, 10 skipped because Swift helper execution requires a non-root Mac; no real service mutations. Current installer and LiveBackend privileged entry remain deliberately blocked pending trusted caller/admission enforcement. Mac audit reported all 12 Mac Pro services running; 11 superficial health checks passed and Soma needed noninteractive runner credentials. Those are not end-to-end proofs: incomplete service capabilities remain Unknown, and their restarts stay blocked. No new account migration is authorized or performed.


### Read-only probe import through Mac 6ab49081
Imported scoped provider credential metadata (remote validity stays unverified), JDK/SDK/Gradle distribution checks, source mirror evidence, live Herd timestamps and authenticated Canvas board reads. HTTP probes refuse redirects and environment proxies. Authenticated service inventory may lend only its paired device key to the fixed same-host Soma health probe; agent tokens are refused. The loopback HTTP integration tests cover key non-disclosure and redirect refusal. Source-mirror failures map to Sync, independently of Builds and Agents.

Local service tests: 79 executed, 69 passed, 10 Mac Swift helper tests skipped. The Mac lane reports 85/85 services and 518/518 runner tests at its upstream checkpoint. These counts cover different source configurations: this checkout deliberately retains the disabled privileged backend and installer. No root broker/installer import or deployment is claimed. Live provider/Canvas credential verification remains blocked by the Mac lane approval review. No login, refresh, service restart, account migration or install was performed.

Local full runner suite at 6f784cbd stopped before the service HTTP cases: test-runner.py line 1064 could not find the mocked bot shell output made.txt after an approved command. No local full-runner PASS is claimed; the Mac lane was asked to verify the merged source.


### Tonight’s bundle freeze (Phone A 20261029-005717)
Connections is frozen for the combined launcher bundle. Privileged helper execution and installation remain disabled in code. No root broker is imported/deployed; owner credential verification is deferred, and absent live provider/Canvas proof must remain UNVERIFIED. Do not request credential-reading permissions for tonight. The desktop addable-module expectation now includes Connections, matching the shared ModuleKind catalog. Full app/chat/desktop suites are required for this checkpoint; subsequent enhancements belong to the next build. No install from builder B.
