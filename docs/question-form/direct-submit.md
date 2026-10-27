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
