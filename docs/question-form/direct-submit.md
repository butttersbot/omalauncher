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
sent. The current local submission UUID is explicitly NOT Canvas-verifiable proof.
Phone C has been asked for the receipt endpoint and native owner-auth contract;
that integration remains pending and must be completed before claiming that agents
can record mockup-approved screens with a verified receipt.

Checks so far: Python generator syntax and git diff checks pass. Generated template
and updated form were rendered with clean soma-look receipts, personally reviewed
at cover 475x751, unfolded 932x704 and side 466x704. Mac compile/unit checks and the
new isolated question-form.walk are pending. Real phone/runner delivery is deferred
until the launcher is promoted; no real messages were sent during implementation.
