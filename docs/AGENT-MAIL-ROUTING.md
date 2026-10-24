# Agent mail handoffs

Routine mail to phone builders and Mac runner threads waits until the current turn ends. Consecutive mail is delivered
as one handoff, preserving each sender, mail ID, reply address and peer warning. Owner requests remain separate and keep
their queue position. A one-second collection window also combines bursts to idle recipients. Mac collection is capped at
three seconds so continuous arrivals cannot postpone delivery indefinitely. Handoffs are bounded to 192,000 characters.

Replacement is explicit, never guessed from a title or text. From the phone:

    oma msg send macbook-pro:t12345678 --task launcher-build "Build commit X"
    oma msg send macbook-pro:t12345678 --task launcher-build --supersedes OLD_MAIL_ID "Use commit Y instead"

Only a pending message with that ID, from the same device/session and task, is replaced. Active work is never silently
cancelled or restarted. Unrelated mail survives and is bundled. Old senders remain compatible: without replacement
metadata their mail is bundled without being discarded. Archived original mail and chat records retain earlier text.
Mac senders can carry the same metadata through unmodified tmsg/Tailshare by prepending this exact first line to text:

    [Oma mail routing v1] {"task":"launcher-build","supersedes":"OLD_MAIL_ID"}

The metadata carries no authority or approval. The peer boundary remains unchanged. Do not send a correction as an owner
message to bypass that boundary. This phase does not implement task ownership, task completion routing or peer interruption.
Owner Stop remains available. Corrections that affect active work still need explicit coordination.

## Receipts without agent turns

Mac runners emit `mail_receipt` events (`queued`, `superseded`, `delivered`) on the existing authenticated thread event
stream. They are system records, never prompts or outbound acknowledgment messages. Pending/recent IDs are deduplicated
across restart (last 512 per thread). On the phone, `oma msg receipts` reads the system ledger at
`~/.cache/oma-agents/mail-receipts.json`, with `queued`, `superseded`, `delivered`, `not-delivered` states, last 512 records.
Receipt delivery means handed to the agent, not that requested work succeeded. Results still require a real result reply.
Bundled entries in chat say they were delivered together; the primary entry holds the agent's response. Existing owner
Stop/error/restart behavior for pending phone follow-ups is preserved; mail does not bypass it.

Phone builders accept up to 64 pending mail entries within the text bound; owner follow-ups keep their existing limit.
No auto-acknowledgment loops, LLM summarization or inferred message deletion are introduced. Bundles retain original words.
Grok's optional front-agent inbox and native terminal hook delivery are not changed by this phase; runner-addressed mail
and phone-builder mail are the paths covered here.

## Validation and deployment

- `python3 tools/mac-runner/test-mail-routing.py`: deterministic policy, replacement isolation, owner order, duplicate IDs,
  persisted queue serialization and receipt events.
- `python3 tools/mac-runner/test-runner.py`: loopback fake-agent integration, including a busy five-message burst that
  becomes one four-message handoff after explicit supersession. No production agents or sends.
- App unit tests `FollowUpsTest`, `AgentMailTest`; isolated Android `MailRoutingInstrumentation` and `agent-mail.walk`.
- Deploy only after 20 seconds with no runner turns, queued work, or active/waiting runner-owned or adopted native
  processes. Classify ownership from verified PID/start time, full ancestry and process group. Unknown ownership blocks.
  An independent Claude Desktop session paused at a verified user question may remain open only when its app ownership
  and sleeping infrastructure are proved separate; preserve its PID, start time, state, marker hash and question before
  and after the runner restart. Do not answer or dismiss that question to deploy an unrelated service.
- Back up the installed runner, enforce the exact baseline hash, preserve unrelated functions and auth/config, and verify
  the installed hash, actual PID change and authenticated health after restart. Upgrade the phone through normal
  build/sign/install gates when Phone A is idle. Never reset source or version code.

### Surgical update compatibility

Run the full loopback suite against the **exact assembled service candidate**, in addition to policy tests and the source
branch's tests. Audit referenced globals and include their helper/constant dependencies. The older Mac Pro baseline lacked
`claude_holder_state`, `claude_process_start` and `CLAUDE_PROJECTS`; transplanting `collect_mail` alone left a healthy HTTP
service whose collector raised `NameError` and kept mail unread in `mail/new`. Add the dependencies without overwriting
existing functions, retest that assembled module, and verify a real burst reaches the deployed runner. HTTP health alone
is not delivery proof. Do not resend accepted mail during diagnosis: preserve its IDs and let the corrected collector
process the originals.
