#!/bin/bash
# Omalauncher: curl/jq are already included in the Linux setup; no TCP listener.
set -euo pipefail
command=${1:-help}
shift || true
case "$command" in
help)
  cat <<'HELP'
oma-phone apps | open PACKAGE_OR_settings
oma-phone status | read | screenshot | back | home | recents | notifications | stop
oma-phone pages | page NAME | where WIDGET_FILE
oma-phone click SNAPSHOT NODE_ID
oma-phone type SNAPSHOT NODE_ID TEXT
oma-phone scroll SNAPSHOT NODE_ID [forward|backward]
oma-phone tap X Y
oma-phone swipe X Y END_X END_Y [DURATION_MS]
pages lists home's pages, page opens one by name, where says which page a widget file is on.
Read after each action. Use screenshot's path with your agent's image tool.
Works automatically after Accessibility is enabled, on an unlocked phone.
Stop pauses access until the user resumes it.
HELP
  exit 0;;
click|type|scroll)
  [ "$#" -ge 2 ] || { echo 'Snapshot and node id required' >&2; exit 2; }
  request=$(jq -cn --arg command "$command" --arg snapshot "$1" --arg id "$2" --arg value "${3:-}" \
    '{command:$command,snapshot:$snapshot,id:$id} + (if $command=="type" then {text:$value} elif $command=="scroll" then {direction:(if $value=="" then "forward" else $value end)} else {} end)');;
open)
  request=$(jq -cn --arg package "${1:?Package required}" '{command:"open",package:$package}');;
page)
  [ "$#" -ge 1 ] || { echo 'Page name required' >&2; exit 2; }
  request=$(jq -cn --arg name "$*" '{command:"page",name:$name}');;
where)
  request=$(jq -cn --arg widget "${1:?Widget file name required}" '{command:"where",widget:$widget}');;
tap|swipe)
  request=$(jq -cn --arg command "$command" --arg x "${1:?X required}" --arg y "${2:?Y required}" --arg ex "${3:-0}" --arg ey "${4:-0}" --arg ms "${5:-400}" \
    '{command:$command,x:($x|tonumber),y:($y|tonumber)} + (if $command=="swipe" then {endX:($ex|tonumber),endY:($ey|tonumber),duration:($ms|tonumber)} else {} end)');;
status|pages|read|screenshot|back|home|recents|notifications|stop)
  request=$(jq -cn --arg command "$command" '{command:$command}');;
*) echo 'Unknown command. Run oma-phone help.' >&2; exit 2;;
esac
response=$(curl --silent --show-error --max-time 12 --abstract-unix-socket '__SOCKET__' \
  --header 'Content-Type: application/json' --data-binary "$request" http://localhost/phone) || {
  echo 'Phone bridge unavailable or timed out. Open Omalauncher Boss. Read the screen before retrying actions.' >&2; exit 1;
}
printf '%s\n' "$response" | jq .
printf '%s\n' "$response" | jq -e '.ok == true' >/dev/null
