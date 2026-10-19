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
usage: oma msg send DEVICE[:SESSION] TEXT [--reply-to ID] [--title NAME] [--as SESSION]
       oma msg devices
send     writes TEXT to the agent SESSION on the computer DEVICE (a Claude Code session id,
         six characters or more of it, or a runner thread id); without SESSION, to that
         computer in general. --reply-to names the mail being answered, --title what this
         is about, --as the phone builder writing (A or B), which a reply comes back to.
devices  the computers that take mail, with their addresses.
It is labeled as an agent's on the other side, never as Boss's.
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
as='oma'
while [ $# -gt 0 ]; do
  case $1 in
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
