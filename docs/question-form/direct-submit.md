# Direct answers from artifacts

Boss approved the question-form look and simplified the behavior through Phone A's
mail 20261026-153605-d13e83: questions live in the mockup, Submit itself authorizes
sending to its fixed soma-reply-to, and delivery status stays in the page. There is
no second review or separate approval card. soma.approve is parked; template copies
contain neither that method nor soma-canvas-cards metadata.

The static declaration, owner main-frame gesture and per-version bridge token are
still required. Native delivery accepts only phone:A/B/C or an exact known Mac
thread key. Phone replies use its normal owner follow-up queue; Mac replies use the
normal chat send/history path, with acknowledgement. Message bodies are literal
artifact answers, never parsed as model-switch commands. The page gets a delivery
result event, not credentials or access to a generic native API.

Submissions are persisted before handoff and deduplicated by source/version,
recipient and answer. An uncertain delivery is not retried automatically or called
sent. Native Keystore P-256 receipts bind the exact HTML bytes' hash, version,
fixed recipient, nonce/time and canonical structured answers. No private key is
exported and no owner key is copied from the Canvas browser session.

Canvas links the public key once in its owner menu. Only a main-frame gesture at
the configured Canvas origin can obtain publicKey() (Promise), never sign().
A successful /api/receipts response gives registered proof. Network failure after
previously confirmed registration gives signed-offline proof. Unknown/unlinked or
revoked devices give no proof but answers still deliver. First offline submission
without a prior native confirmation is conservatively unlinked; no approval is
invented. Phone C owns server verification and record-approved.

Checks so far: Python generator syntax and git diff checks pass. Generated template
and updated form were rendered with clean soma-look receipts, personally reviewed
at cover 475x751, unfolded 932x704 and side 466x704. Mac compile/unit checks, Keystore signature instrumentation and the
new isolated question-form.walk are pending. Real phone/runner delivery is deferred
until the launcher is promoted; no real messages were sent during implementation.
