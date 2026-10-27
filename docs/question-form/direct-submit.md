# Direct answers from artifacts

Boss chose option 1 through Phone A's mail 20261026-160806-c794cf: the mockup
only supplies current answers; Soma draws the native Send bar below the WebView.
Cards: soma-question-form and soma-question-form-cover. Active Pack styling and
both Fold sizes apply. No extra sheet or approval card. The template owns no Submit.

soma.answer(value) sets untrusted pending answers; null/invalid data clears them.
It cannot send or sign, and setting a draft needs no gesture. Only the native Send
tap invokes host delivery. Its label comes from the parsed answers, showing the
fixed recipient; the full label can wrap and scroll for long answers. A draft
changed after drawing a label is not sent under that old label. A version change
clears the pending draft and token. Double taps cannot duplicate delivery.

Native delivery accepts only phone:A/B/C or an exact known Mac thread key. Phone
replies use its normal owner follow-up queue; Mac replies use normal chat send/
history with acknowledgement. Literal answer bodies do not switch models. Result
status stays in the artifact and native bar. soma.approve remains absent/disabled.

Submissions are persisted before handoff and deduplicated by source/version,
recipient and answer. An uncertain delivery is not retried automatically or called
sent. Native Keystore P-256 receipts bind the exact HTML bytes' hash, version,
fixed recipient, native submission UUID, millisecond timestamp, replyId and
structured answers. The serialized JSON STRING is signed once and delivered as
canvasReceipt.receipt with its DER signature; the server verifies those exact bytes. No private key is
exported and no owner key is copied from the Canvas browser session.

Canvas links the public key once in its owner menu. Only a main-frame gesture at
the configured Canvas origin can obtain publicKey() (Promise), never sign().
Only an approval answer requests proof, after the native Send tap. Non-approval
answers use that same native Send with no sheet and no receipt.

A successful /api/receipts response gives registered proof. Network failure after
previously confirmed registration gives signed-offline proof. Unknown/unlinked or
revoked devices give no proof but answers still deliver. Native GET /api/devices/:deviceId also confirms linkage. First offline submission
without a prior native confirmation is conservatively unlinked; no approval is
invented. Phone C owns server verification and record-approved.

Checks so far: Python generator syntax and git diff checks pass. Generated template
and updated form were rendered with clean soma-look receipts, personally reviewed
at cover 475x751, unfolded 932x704 and side 466x704. Mac compile/unit checks, Keystore signature instrumentation and the
new isolated question-form.walk are pending. Real phone/runner delivery is deferred
until the launcher is promoted; no real messages were sent during implementation.

## Waiting agent questions (native form)

AskUserQuestion and Codex `codex_request: questions` cards now open a native form in the artifact workspace rather than presenting Allow/Deny. The form uses the active Pack, one or several option buttons, optional Other text, a scrolling question area, and a separate pinned native Send bar showing the actual answers. Drafts survive Back and reopening that request. Send uses the request's existing nonce/ticket through the owning phone builder or Mac runner; it never sends a chat follow-up or mints a Canvas receipt.

Phone validation runs before claiming the one-time nonce. The Mac transport sends the answers object alongside the existing decision and nonce. The runner's question validation must be deployed before the real Mac path is walked; do not restart it while sessions work.

Coverage: `waiting-question.walk` uses isolated Claude and Codex fixtures with the production chat/workspace/form and fake completion only. It checks the cover draft, multi-select, native label, no delivery before Send, then unfolded Codex answers. Compile/unit and critic screenshot review are pending at this checkpoint; real runner and phone-builder nonce/lock steps remain `[phone]`.


### Native artifact Send checkpoint: 4e8b8353

Mac critic: compilation succeeded on Android and desktop, preview assembled, and all 60 focused tests passed (ArtifactReply 7, CanvasReceipt 4, ClaudeProtocol 5, MacThreads 44). Raw-string DER signature instrumentation passed. Cover and unfolded screenshots were reviewed: option changes updated the native label, no answer callback occurred before the native tap, and successful delivery removed Send; no second answer was logged. The committed walk reported 40 PASS, 1 FAIL, 4 LOOK. Its single failure was an ambiguous “Unfolded” substring tap; a manual tap verified both selecting and clearing it updated the native label. The selector now names that option's unique description; the corrected script has not yet been rerun.

Other typing with a long answer kept the keyboard up and wrapped the native label without sending. Critic noted that the bar obscured part of the focused HTML Other field. Not covered on device: script-only draft changes, null/invalid/version clears, unfolded keyboard, or a double-tap race. Real delivery, Canvas linking and receipt registration remain phone checks after promotion. This result does not cover the newer native waiting-question form at dd6a1078.

### Waiting-question checkpoint and rotation correction

The critic built c54699ac: Android/desktop/preview compile passed, 101 focused tests passed, and waiting-question.walk reported 36 PASS, 0 FAIL, 4 reviewed LOOK. Claude payload keys were the exact question text; Codex keys were ids. The Claude runner normalizes multi-select arrays to comma-joined text before updatedInput; the phone uses ClaudeProtocol's matching normalization. A separate Other + rotation check FAILED: rotating lost the selected form and its draft. This was a checkpoint blocker, not a pass.

The correction saves the selected form's key/title/input as data and resolves its current nonce through ChatHost when Send is tapped, rather than retaining a callback across Activity recreation. Draft fields remain in the saveable state holder. The walk now checks draft and label across both rotations before Send. Multi-select uses checkboxes; the bar shows its recipient and incomplete-answer guidance; Other has a placeholder and requests scrolling into view after keyboard focus. This correction awaits compile and critic rotation/keyboard review.

### Live panel movement correction

The e8738206 checkpoint still failed rotation (37 PASS, 7 FAIL, 6 reviewed LOOK); all 101 tests and compiles passed, as did the separate artifact walk (41 PASS, 0 FAIL, 4 reviewed LOOK). The activity handles orientation itself, so full-screen and side-panel forms move between live composition branches. Saving the selected descriptor did not preserve the fields during that move. The correction now owns each draft above both branches in ChatArtifactWorkspace: the same object holds options, Other, sending, sent and errors keyed by request ticket/nonce. Its JSON saver also retains drafts through Activity recreation; no callback is serialized. Both panel branches receive that object directly, with no branch-local answer state or SaveableStateProvider lifecycle. New-nonce forms start empty. The expanded rotation walk and Other/IME review remain required before bundle go.

### Final emulator checkpoint: ed9c776a

PASS: Android and desktop chat compile, preview assemble, and 102 focused tests (ArtifactReply 7, CanvasReceipt 4, ClaudeProtocol 5, MacRunner 41, MacThreads 44, WaitingQuestionDraft 1). `waiting-question.walk`: 44 PASS, 0 FAIL, 6 LOOK, all screenshots reviewed by the critic in `/tmp/walk/wq-ed/`. Cover Claude choices, Back/reopen, rotation to the side pane and back, and native Send preserve the exact answer label and deliver once. Unfolded Codex fixture also passes. Extra checks (`wq-ed2`, `wq-ed3`) retain long Other text across rotation with IME, Back/reopen, unfold and fold; labels wrap on both sizes; no answer is logged before native Send.

The unchanged artifact native Send path retains its prior 41 PASS, 0 FAIL, 4 reviewed LOOK at e8738206, with receipt raw-string DER instrumentation previously passing. No page callback can send/sign: only the native tap delivers. Canvas approval-from-page remains disabled.

Limits: fixture-only completion, no real message, Canvas link/registration, launcher promotion or runner restart was performed by this checkpoint. Real phone builder/Mac waiting-call delivery and Canvas Link → Send → record-approved remain after promotion; A coordinates runner deployment only at /health working=0. Very long labels beyond the 180dp cap were not exercised. The native bar still partly obscures the focused Other field's lower edge with the keyboard; rotation closes the keyboard but retains text. These are recorded limitations, not claims of phone coverage.

Reviewed screenshot archive retained on the Fold: `/root/Inbox/question-forms-ed9c776a-shots.zip`, SHA256 `c01dc6369a0d4e051b35d1bc6dbbb7b1cbd7ebb63d64b536f59330b769b451e5`, verified after Taildrop. It contains 16 PNGs and a README covering ready/sent cover and unfolded, rotation, Other with IME, reopening and fold retention. These are isolated artifact-preview/fake-host screenshots, not installed-launcher Shipped evidence; no Canvas publication was made from them.

### Real Fold165 + deployed Mac runner preflight

After A confirmed runner deployed atd3a6ef22 (question support in tree) and Boss authorized the brief main-screen test, laneB invoked the actual Claude AskUserQuestion tool with Layout (Beside chat/Full screen) and Checks (Cover/Unfolded, multi-select). Read-only runner state showed `waiting`, the exact two-question input, and Soma165 showed Questions waiting / [ answer questions ] in the correct lane, not Allow/Deny. Phone connected/active/awake/unlocked; composer was empty. Arrival/card step PASS.

The first press to open [ answer questions ] was refused by phone control: “A human approval is pending. Phone actions cannot approve or dismiss it.” This is a helper approval guard, not evidence of an app form failure. No alternate control path, nonce submission, dismissal or retry was attempted. Opening via automation is BLOCKED; option/Other/native Send/tool-return verification remain NOT RUN until Boss taps manually. A notified with the pending lane and harmless test choices; asking agent remains waiting. No received-answer PASS claimed yet.

### Real Mac AskUserQuestion delivery: PASS

The same real laneB AskUserQuestion was completed manually after the phone-control guard refused automation. Asking Mac agent reported its actual tool returned exactly once: `Where should this test form open?` = `Beside chat`; `Which screens should this test cover?` = `Cover`. Independently re-read the deployed runner: thread t5d86a2c5 idle with0 pending; event806 is AskUserQuestion and event807 is a single approval_result from device galaxy-z-fold8 with decision allow and those exact question-keyed answer strings. The waiting tool resumed once; no queued chat message substituted for the tool answer. This establishes real Fold165 → deployed runner → asking Claude tool delivery, not just the isolated preview fixture.

Per-step: request arrival/native question card PASS; automated open BLOCKED by human-approval guard (no bypass); manual completion and exact-recipient tool delivery PASS; one recorded result/tool resume PASS. Native option/label interaction was not personally automated or visually observed after the guard. Real two-choice multi-select, Other, Codex, phone-builder roundtrip, expired/wrong nonce and locked-phone attempts remain NOT RUN on the Fold; their emulator/unit coverage is separate. No race/double-tap stress or Canvas receipt registration is inferred from this question response.
