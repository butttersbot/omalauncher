# Approved in a mockup -> recorded on Canvas: signed receipts (contract v2.2, Phone C, 2026-10-26)

Boss's rule (15:37): designs that start on Canvas are approved on Canvas. Designs that start as a plain HTML
mockup are decided in the mockup (template question block, Submit via soma.answer), then the author agent
records the approved screens on Canvas. Agents must not be able to self-approve.

Parties: Soma native (Phone B), Canvas server (lane C), author agents (CLI).

## Threat model
- Linux (all agents) runs in Soma's own app UID: any secret Soma keeps in files or prefs is readable by agents.
  So native never holds the Canvas owner key, and no mint credential is ever reachable from HTML or Linux.
- Mockup HTML is agent-written: anything its JS says (answers, version, hash) is untrusted.
- The private key lives in Android Keystore (non-exportable). Proot code can't drive Keystore inside Soma's
  process, so only Soma's own code paths can sign.

## 1. Device key and link (once)
- Native key: Keystore alias `soma-canvas-receipts`, EC P-256 (secp256r1), PURPOSE_SIGN, digest SHA-256.
  Created on first use.
- deviceId = lowercase hex sha256 of the SPKI DER public key.
- `window.somaReceipts`, present ONLY in the artifact-panel WebView and ONLY while its top-level page origin
  equals Canvas's origin (CANVAS_URL in Soma's config). Native implements it without a general JS interface
  (main-frame gesture navigation + per-view token). One method:
  `await somaReceipts.publicKey()` -> Promise of `{deviceId: "<hex>", publicKey: "<base64 SPKI DER>", name?: string}`.
  The Link button must await it (and tolerate a JSON string, parsing it).
  It never signs anything. (The public key isn't secret; the bridge exists only so Boss's signed-in Canvas can
  register it.)
- Canvas shows "Link this Soma" in its owner menu when `window.somaReceipts` exists. The page POSTs
  `POST /api/devices {deviceId, publicKey, name}` with the owner auth the page already has. Agent token -> 403.
  Server checks deviceId == sha256(SPKI). `DELETE /api/devices/:deviceId` (owner) revokes. `GET /api/devices`
  (owner) lists.

## 2. Receipt (Soma native, on Boss's Submit)
Only when the submitted answers contain an approval (see 4). Other answers are delivered as today, no receipt.
- Approval confirm: native shows its own small sheet, "Approve <artifact name> v<version>? [Approve] [Cancel]",
  because the HTML's JS could call soma.answer without Boss. Only a tap there signs. (Non-approval answers
  need no sheet.)
- Native fills every field from its own state, never from the page's JS except `answers`:
  ```
  {"v":1,
   "deviceId":"<hex>",
   "nonce":"<the native per-answer UUID>",
   "at":<ms since epoch, integer>,
   "recipient":"<reply route of the artifact's author, e.g. phone:C or macbook-pro:t6260301f>",
   "replyId":"<the delivered answer's id, or null>",
   "artifact":{"name":"<artifact name>","version":<artifact version, integer>,
               "sha256":"<lowercase hex sha256 of the exact HTML bytes native loaded into the panel>"},
   "answers":<the submitted answers object, as delivered to the agent>}
  ```
- Serialize once to a UTF-8 string `receipt` (native uses sorted keys, no spaces; the server doesn't depend on it).
  `sig` = Keystore `SHA256withECDSA` over exactly those bytes, base64 of the DER signature. The server verifies
  the string's bytes as sent, then parses it; it never re-serializes.
- Deliver with the answer: `canvasReceipt: {"receipt": "<the string>", "sig": "<base64>"}`, plus
  `receiptStatus`: `registered` (with `canvasReceiptId`), `signed-offline` (Canvas unreachable: agents can
  register later), or `unlinked` (Canvas answered 409 unknown device: no device linked; still deliver the answer,
  without a receipt claim). Never invent one.
- Best effort: `POST {CANVAS_URL}/api/receipts {"receipt": "<string>", "sig": "<base64>"}`, no auth (the
  signature is the auth). Responses:
  - `200 {"status":"registered","id":"rc_<16>"}`, also on a repeat of the same (deviceId, nonce);
  - `409 {"status":"unknown-device"|"revoked-device"|"bad-signature"|"bad-receipt","error":"<words>"}`.
- `GET {CANVAS_URL}/api/devices/:deviceId`, no auth -> `200 {"deviceId","linked":true|false,"revoked":true|false}`
  (only that id's state; unknown ids answer linked:false). Native uses it to know a link is confirmed.
- receiptStatus rules for native: `registered` = POST answered 200; `signed-offline` = Canvas unreachable AND
  this deviceId was confirmed linked before (GET or an earlier 200); `unlinked` = never confirmed, or 409
  unknown/revoked: no receipt is delivered.

## 3. Record (author agent, agent token)
`soma-canvas record-approved CARD FILE.html --receipt RECEIPT.json [--frame F] [--version V]`
(RECEIPT.json = the delivered `{receipt, sig}`) -> `POST /api/cards/:id/approved {receipt, sig, html, kind, frame?, version?}`.
409 with the reason unless:
- sig verifies (DER, or raw r||s accepted) with a linked, unrevoked device's key;
- receipt.at is under 7 days old and not in the future (+5 min skew);
- the answers include an approval (4);
- sha256(html bytes) == receipt.artifact.sha256.
Registers the receipt if it wasn't. On success: approved state {kind, asset, version (default
"v<artifact.version>"), source "Recorder · rc_…", author = receipt.recipient, receipt id}, waiting cleared,
drift recomputed, owner note "Approved in mockup NAME vN (rc_…): <answers>". One receipt may record several
cards of that mockup; re-recording the same card with the same receipt is a no-op.
The owner-only /api/evidence route (ec0a073) is removed: receipts are the one path.

## 4. What counts as an approval
`answers.decision == "approve"` (the template's question block sets it when Boss picks its Approve option),
or any answer value starting "Approve", or an `approve`/`approved` key set to true. "Request changes" and
"not approved" don't count.
