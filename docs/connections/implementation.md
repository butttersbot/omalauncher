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
