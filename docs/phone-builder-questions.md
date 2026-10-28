# Phone-builder question replies, 2026-10-28

Owner report via Phone A163301: four native AskUserQuestion choices did not reach
Claude. Evidence: installed launcher172; transcript tool_result at23:32:44 has
interruptedByShutdown:true and User rejected tool use, followed by a new user
message23:32:47. Native task record says unanswered rather than denied. This does
not establish that Submit itself sent a denial; selecting options is not submission.
Do not claim the exact real-device cause proved until the post-install owner test.

The existing phone protocol already implements allow with updatedInput.answers,
including original questions, matching the Mac. Keep that validation and nonce-once
ledger. A dedicated AgentTasks.answerQuestions entry point now hard-codes the
question reply as allow+answers; malformed answers stay waiting.

Concrete UI hazards corrected: Send previously contained the entire selected
summary, which could become a huge wrapped control for four long answers inside
a capped scrolling footer. Send answers is now a short fixed action; a separate
bounded summary cannot push it behind that footer's scroll. Phone-builder question
notifications no longer show generic Approve/Deny; their Answer questions route
opens that builder's native form by its live nonce. Generic permission cards keep
their approval path. No response is sent merely by opening or dismissing a form.

BuilderQuestionSubmitTest covers four long answers in a short panel through
sendWaitingAnswers -> builder question host -> ClaudeProtocol -> real ApprovalLedger:
visible Send, allow/updatedInput with all four answers/questions, no generic deny,
one reply and expired replay. Isolated preview builder fixture exercises the same
protocol and nonce ledger without starting an agent, runner or real task.

Slice walks: builder-question.walk plus waiting-question and waiting-question-skip
regressions. Cover/unfolded/rotation screenshots need critic review before ready.
Native notification/open/submit/actual Claude result remains a phone step after
combined install; owner alone answers any real money questions. No actual question
was answered or replayed during this investigation.

Injected builder guidance now asks for AskUserQuestion or a question-form artifact
with Submit for owner decisions. Headless D passes structured choices to its
requesting builder for that card; silence/plain text never grants permission.

Phone A clarified163539: owner selected options, could not find Send, then typed
in chat; that new turn interrupted the tool. This confirms the missing/hidden
Submit problem rather than malformed allow/deny wire data. The fixed button is
always present, even before completeness, with an explicit remaining count and
helpful no-send feedback if incomplete. When selecting a single-choice answer
would finish the form, that option explicitly says Choose and send answers and
sends through the same once-only path. Multi-select and Other text retain explicit
Send so the first selection/keystroke cannot prematurely submit them. New unit
cases cover both, and the builder walk covers the final-choice shortcut at both
Fold sizes.
