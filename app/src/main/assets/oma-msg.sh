#!/bin/bash
# Omalauncher: `oma msg`, agent mail from the phone to an agent on one of Boss's computers (Boss, 2026-10-16; the
# Tailshare session's spec, ~/ClaudeSync/projects/tailshare/docs/AGENT-MAIL.md on the MacBook). It POSTs to
# tailshare-serve on that computer, port 8480 on the tailnet, which takes mail only from his own devices and keeps it
# for the session it names. A computer's address comes from any computer's GET /v1/devices, asked of the Macs the
# phone already knows (the addresses in ~/.config/omalauncher/mac-runner*.json; no key is read). Mail coming the
# other way arrives by Taildrop and Tailshare hands it to the launcher, which shows it in the chat as agent mail.
set -u -o pipefail
dir="${HOME:?}/.config/omalauncher"
port=8480

usage() {
  cat >&2 <<USAGE
usage: oma msg send DEVICE[:SESSION] TEXT [--reply-to ID] [--title NAME] [--as SESSION] [--task ID] [--supersedes MAIL_ID]
       oma msg devices
       oma msg receipts
send     writes TEXT to the agent SESSION on the computer DEVICE; phone:A, phone:B, phone:C is a builder on this phone (a Claude Code session id,
         six characters or more of it, or a runner thread id); without SESSION, to that
         computer in general. --reply-to names the mail being answered, --title what this
         is about, --as the phone builder writing (A or B), which a reply comes back to.
receipts local system delivery receipts; reading them never starts an agent.
--task ID names a task. --supersedes MAIL_ID replaces only pending mail from you for that task.
devices  the computers that take mail, with their addresses.
It is labeled as an agent's on the other side, never as Boss's.
Work for a Mac goes to a runner thread (oma threads list; oma threads new NAME PATH --mode auto):
the runner is always on and hands a thread its mail. A Claude Code session gets mail only while
its program is running, so mail to one that has ended waits, unread and unannounced.
USAGE
  exit 2
}

die() {
  echo "$1" >&2
  exit 1
}

# The tailnet hosts of the Macs set up here, one per line.
known_hosts() {
  local file
  for file in "$dir"/mac-runner.json "$dir"/mac-runner-*.json; do
    [ -f "$file" ] || continue
    jq -r '.url // empty | tostring' "$file" 2>/dev/null | sed -E 's#^[a-z]+://##; s#[:/].*$##'
  done | awk 'NF && !seen[$0]++'
}

# The device list from the first computer that answers.
devices() {
  local host list
  while IFS= read -r host; do
    list=$(curl --silent --fail --proto '=http' --connect-timeout 3 --max-time 8 "http://$host:$port/v1/devices" 2>/dev/null) || continue
    printf '%s' "$list" | jq -e '.devices | type == "array"' >/dev/null 2>&1 && { printf '%s' "$list"; return 0; }
  done < <(known_hosts)
  return 1
}

[ $# -ge 1 ] || usage
case $1 in
  receipts)
    [ $# -eq 1 ] || usage
    if [ -f "$HOME/.cache/oma-agents/mail-receipts.json" ]; then
      jq . "$HOME/.cache/oma-agents/mail-receipts.json"
    else printf '[]\n'; fi
    exit 0 ;;
  devices)
    [ $# -eq 1 ] || usage
    list=$(devices) || die 'No computer answered. Is Tailscale on, and tailshare-serve running on the Macs?'
    printf '%s' "$list" | jq -r '.devices[] | "\(.name)\t\(.address)"'
    exit 0 ;;
  send) shift ;;
  help | -h | --help) usage ;;
  *) usage ;;
esac

# Options may come before or after the address and the text (the wrapper agents are given puts them first).
words=()
reply_to=''
title=''
task=''
supersedes=''
# A phone builder signs as itself (OMA_BUILDER, set when it starts), so replies find their way back to it.
as=${OMA_BUILDER:-oma}
while [ $# -gt 0 ]; do
  case $1 in
    --task) [ $# -ge 2 ] || usage; task=$2; shift 2 ;;
    --supersedes) [ $# -ge 2 ] || usage; supersedes=$2; shift 2 ;;
    --reply-to) [ $# -ge 2 ] || usage; reply_to=$2; shift 2 ;;
    --title) [ $# -ge 2 ] || usage; title=$2; shift 2 ;;
    --as) [ $# -ge 2 ] || usage; as=$2; shift 2 ;;
    --) shift; words+=("$@"); break ;;
    -*) usage ;;
    *) words+=("$1"); shift ;;
  esac
done
[ ${#words[@]} -eq 2 ] || usage
target=${words[0]}
text=${words[1]}

device=${target%%:*}
session=''
[ "$device" = "$target" ] || session=${target#*:}
[[ $device =~ ^[A-Za-z0-9][A-Za-z0-9.-]{0,62}$ ]] || die "\"$device\" is not a device name."
[ -z "$session" ] || [[ $session =~ ^[A-Za-z0-9._-]{1,80}$ ]] || die "\"$session\" is not a session id."
[ -z "$reply_to" ] || [[ $reply_to =~ ^[A-Za-z0-9._-]{1,80}$ ]] || die "\"$reply_to\" is not a mail id."
[[ $as =~ ^[A-Za-z0-9._-]{1,40}$ ]] || die "\"$as\" is not a session."
[ -n "${text//[[:space:]]/}" ] || die 'Nothing to send: the text is empty.'

for routing in "$task" "$supersedes"; do
  [ -z "$routing" ] || [[ $routing =~ ^[A-Za-z0-9._-]{1,80}$ ]] || die 'Invalid task or supersedes id.'
done
[ -z "$supersedes" ] || [ -n "$task" ] || die '--supersedes requires --task.'
if [ -n "$task" ]; then
  route=$(jq -cn --arg task "$task" --arg supersedes "$supersedes" '{task:$task} + (if $supersedes == "" then {} else {supersedes:$supersedes} end)')
  text="[Oma mail routing v1] $route"$'\n'"$text"
fi
# Another agent on this phone (Boss, 2026-10-26: "agent mail lanes between phone agents, so you can get them to keep
# working"): phone:B, or this phone's own name, goes straight to Soma through the phone bridge, no network.
if [ "$device" = phone ] || [ "$device" = "${OMA_PHONE_NAME:-galaxy-z-fold8}" ]; then
  [ -n "$session" ] || die 'Name the phone agent: phone:A, phone:B or phone:C (or grok for the front agent).'
  agent=oma
  [ "$as" = grok ] && agent=grok
  # A mail without --title is labeled by who sent it ("Phone B"), not "null".
  [ -n "$title" ] || title="Phone $as"
  id="$(date +%Y%m%d-%H%M%S)-$(od -An -N3 -tx1 /dev/urandom | tr -d ' \n')"
  body=$(jq -n --arg id "$id" --arg phone "${OMA_PHONE_NAME:-galaxy-z-fold8}" --arg session "$session" --arg as "$as" --arg agent "$agent" \
      --arg title "$title" --arg text "$text" --arg reply "$reply_to" \
    '{v: 1, id: $id, to: {session: $session},
      from: {device: $phone, agent: $agent, session: $as, title: (if $title == "" then null else $title end)},
      text: $text, reply_to: (if $reply == "" then null else $reply end)}')
  answer=$(printf '%s' "$body" | oma-phone --display main mail-deliver 2>&1) || die "Soma didn't take it: $(printf '%s' "$answer" | jq -r '.error // .' 2>/dev/null)"
  echo "Sent to phone:$session, mail id $id."
  exit 0
fi
list=$(devices) || die 'No computer answered. Is Tailscale on, and tailshare-serve running on the Macs?'
address=$(printf '%s' "$list" | jq -r --arg name "$device" '.devices[] | select(.name == $name) | .address' | head -n 1)
[ -n "$address" ] || die "No computer called $device takes mail. These do: $(printf '%s' "$list" | jq -r '[.devices[].name] | join(", ")')."
[[ $address =~ ^[0-9a-fA-F.:]+$ ]] || die "$device's address is not one this sends to."

# The front agent signs as grok (AgentMail.kt's FRONT_MAIL_SESSION) and is labeled as Grok; the builders as oma.
agent=oma
[ "$as" = grok ] && agent=grok
body=$(jq -n --arg session "$session" --arg as "$as" --arg agent "$agent" --arg title "$title" --arg text "$text" --arg reply "$reply_to" \
  '{to: {session: (if $session == "" then null else $session end)},
    from: {agent: $agent, session: $as, title: (if $title == "" then null else $title end)},
    text: $text, reply_to: (if $reply == "" then null else $reply end)}')
host=$address
[[ $address == *:* ]] && host="[$address]"
answer=$(printf '%s' "$body" | curl --silent --show-error --proto '=http' --connect-timeout 3 --max-time 15 \
  --header 'Content-Type: application/json' --data-binary @- --write-out '\n%{http_code}' "http://$host:$port/v1/msg") ||
  die "$device did not answer."
code=${answer##*$'\n'}
answer=${answer%$'\n'*}
[ "$code" = 200 ] || die "$device answered HTTP $code: $(printf '%s' "$answer" | jq -r '.error // empty' 2>/dev/null)"
id=$(printf '%s' "$answer" | jq -r '.id // empty | tostring' 2>/dev/null)
echo "Sent to $target${id:+, mail id $id}."
# A runner thread id is t and eight hex digits; anything else names a session, which reads mail only while it runs
# (2026-10-20: work mailed to an ended Mac Pro session sat unread for hours). Say so, so the sender can choose.
if [ -n "$session" ] && ! [[ $session =~ ^t[0-9a-f]{8}$ ]] && [ "$agent" != grok ]; then
  echo "Note: $session is a session, not a runner thread. It gets this only while its program is running; if it has" >&2
  echo "ended, the mail waits unread. For work to be done, use a runner thread: oma threads new NAME PATH --mode auto." >&2
fi
