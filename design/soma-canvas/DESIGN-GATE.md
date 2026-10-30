# Design gate: approved → shipped → diffed, before "done"

**Replaced, Boss, 2026-10-30: "We need to remove the blockers."** Nothing on Canvas ever holds a build, a take or an install, and
nothing is refused on propose. After a build's walks, each passed walk's screens are published as Shipped with one quick diff per
changed screen (one look), listed in the lane's result as advice. When he has a problem with a design: `soma-canvas focus CARD "the
feature"`. Approvals are records. The rest of this file is history.

Boss, 2026-10-29: "The approved designs should have been automatically added to Canvas. And the implemented screens
that got shipped also should have been added to Canvas. … some sort of automated way to diff implemented versus
approved … reports that back to the agent so he can make the required fixes before he marks it as done … What is the
point of having an approved design if the agents keep handing back work that doesn't follow the approved design."

Trigger case: Connections v6 was approved in chat on 2026-10-28 (handoff, not on Canvas), built into launcher 175, and
shipped looking different (colours, type, rules, incident block). Nobody's tooling noticed; Boss did.

**Existing screens (Boss, 2026-10-30):** whatever is shipped is approved. New work on an existing screen starts from its Shipped card on Canvas and changes only what the feature adds; keep header, pills, sizes, placement and type as shipped. If he has a problem with the shipped design, he will say.

## 1. Approvals only on Canvas (no more approvals that Canvas doesn't know about)

- A design is approved when, and only when, its Canvas card has an Approved state (Boss's owner tap). Chat "looks
  good" means: the agent asks Boss to approve on Canvas (page-scoped NEEDS YOU makes it a few taps), and waits.
- HTML-mockup approvals (question block + Send) are recorded on Canvas by the agent at once with
  `soma-canvas record-approved` (signed receipt), as already specified in canvas-record-approved.md.
- A build task names its cards. No Approved state on a named card = the builder stops and asks for approval; it
  does not build from a Proposed rev.

## 2. Every walk ships its screens to Canvas automatically

- A walk script declares which screenshot is which card: `shot NAME card=CARD-ID` (or the walk's canvas.json map).
- After a walk PASSES on a lane, the walk runner itself runs `soma-canvas publish APP DIR --version V --build B`
  with that map. Not an optional manual step. Shipped on Canvas = what the build really shows.

## 3. The diff: `soma-canvas check` (design check), run by the walk runner

For every shot with a card:
1. Get the card's Approved render at the shot's frame (cover / unfolded), same size as the screenshot.
2. Compare. Pixel diffs fail on fixture text, so the judge is a vision model given both images plus a fixed
   checklist: elements present/missing, order and grouping, colour roles (ok/warn/accent), type roles
   (display/body/mono), rules and dividers, spacing and empty gaps, alignment, truncation, states. Sample text and
   data are NOT differences.
3. Output per card: `match` | `drift` with a numbered list of differences (what, where, approved vs shipped),
   saved as design-report.json beside report.txt and attached to the card (drift flag + the list).

## 4. The loop and the gate

- Drift = the walk result is `DESIGN FAIL` (separate from functional PASS/FAIL). The report goes back to the builder
  in its walk mail with the list.
- The builder fixes and re-walks until `match`, or writes a one-line reason per remaining item and asks Boss on
  Canvas (comment on the card). Only Boss can waive a difference (owner action on the card).
- `oma-macbuild --take` and `oma-install` refuse a build whose walked cards are in drift without Boss's waiver,
  printing the card ids and the list. "Done" in any report means: functional PASS + design match (or waived).

## 5. Backfill

- List designs approved outside Canvas (handoffs, chat) whose cards have no Approved state; Boss approves them via
  NEEDS YOU. First: Soma Widgets › Soma Connections (8 cards, v6).
- Run the check on the current installed build (175) for every card that has Approved + Shipped, and put the drift
  lists on the cards.

## Owners

- Canvas side (jacks-mac-pro taebc3f66, live server ~/ClaudeSync/projects/soma-canvas): approved render per card at
  a given frame and size, `soma-canvas check`, drift + list on cards, owner waiver, record-approved enforcement.
- Walk side (Phone C, tools/walkthrough + lanes): `shot … card=`, auto publish after PASS, run the check, DESIGN
  FAIL in reports and walk mail, the take/install gate.
- Builders (all): build only from Approved cards; done = PASS + match.

## Amendment, 2026-10-29 ~16:50: approving must be easy, and forgetting must not block

Boss: "my approval in chat should absolutely work. So I should be able to say something like approve all in chat
and then that should reflect in the Soma Canvas … the solution isn't to force me into looking at like 10 different
screens and just clicking approve … I don't want things to be blocked if I forgot to click a few approve buttons …
I can click the buttons, but also I can tell the agents that I approve."

1. Chat approval counts. When Boss types an approval in a Soma chat ("approve all", "approve the Connections
   ones", "looks good, approve"), the agent records it on Canvas for the cards it refers to, as Approved, with his
   words quoted on the card. Proof it was Boss and not an agent: Soma signs his own typed messages (the same device
   key as the signed receipts in canvas-record-approved.md: message id, conversation, text hash, time) and the agent
   passes that receipt: `soma-canvas approve-from-chat CARD... --receipt R`. Canvas verifies the signature, that the
   text is recent (< 7 days) and reads as an approval, and records which cards it covered. Agents still can never
   approve on their own: no receipt, no approval. "All" means the cards the agent had just put in front of him (or the
   page/app he names); the card shows "approved in chat: '<his words>'" and he can undo it on the card.
   Native question cards (AskUserQuestion) answered "Approve" count the same way.
2. Unapproved never blocks. A carded shot with nothing Approved is `DESIGN UNAPPROVED`, not FAIL: take/install go
   ahead, print it, and the agent asks Boss once, in chat, with one native question card for the whole batch
   ("Approve these N designs? [ Approve all ] [ Let me look ] …"), linking the Canvas page. His answer is recorded as
   in 1. Nobody calls the build done while it shows UNAPPROVED without saying so.
3. Drift still blocks: a shipped screen that differs from an Approved design is the builder's job to fix, not his to
   click through. Only he can waive a difference, and he can do that in chat too ("that's fine, keep it" → waiver with
   receipt, same mechanism).
