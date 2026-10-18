#!/bin/bash
# Omalauncher: curl/jq are already included in the Linux setup; no TCP listener.
set -euo pipefail
command=${1:-help}
shift || true
expect=0
case "$command" in
help)
  cat <<'HELP'
oma-phone apps | open PACKAGE_OR_settings
oma-phone status | read | screenshot | back | home | recents | notifications | stay | stop
oma-phone screen [--until TEXT]
oma-phone expect TEXT
oma-phone press [--in PACKAGE] LABEL
oma-phone fill [--in PACKAGE] LABEL TEXT
oma-phone pages | page NAME | where WIDGET_FILE
oma-phone page-add [NAME] | page-rename NAME --to NEW NAME | page-delete NAME
oma-phone board [PAGE NAME]
oma-phone looks | look NAME
oma-phone widget-install FILE.owl
oma-phone canvas help | board [PAGE] | modules | place MODULE [SIZE] [WHERE] [--page NAME] | move | resize | remove | page-make | undo
oma-phone lockstar [capture NAME | apply NAME]
oma-phone note [--near COL,ROW] TEXT
oma-phone stickers
oma-phone stick [--page NAME] [--near COL,ROW] STICKER
oma-phone sticker [--page NAME] ID turn DEGREES | size HUNDREDTHS | move COL,ROW | remove
oma-phone click SNAPSHOT NODE_ID
oma-phone type SNAPSHOT NODE_ID TEXT
oma-phone scroll SNAPSHOT NODE_ID [forward|backward]
oma-phone tap X Y
oma-phone swipe X Y END_X END_Y [DURATION_MS]
pages lists home's pages, page opens one by name, where says which page a widget file is on.
page-add makes a new empty page after the last, page-rename gives one another name, and
page-delete deletes a page only while nothing is on it. Each answers with the pages there then are.
lockstar lists the lock-screen layouts kept; capture NAME keeps LockStar's layout as it now is, and
apply NAME hands one back to LockStar, which changes the lock screen: only when the user asked for it.
widget-install sends a widget file you wrote to the shared widget library on the Mac Pro, where it is checked and
waits for the user's OK on each screen before it loads or runs anything there; the answer says which screens can draw it.
canvas arranges home's cockpit modules (Needs you, Threads and the rest) only as the user asked: run
oma-phone canvas help for its words. Each request of his is one change that undo takes back whole.
looks lists the looks home can be drawn in and which is picked; look NAME picks one.
board says what is where on a home page (the one the request came from, or the one named): its
grid, each tile's cells, each note and sticker (page fractions and the cells it lies on), and
free, the biggest empty rectangles of cells. It only looks. Run it before placing anything.
note leaves a short sticky note (plain text, at most 280 characters) on the home page the request
came from, only when the user asked for one: not to say what was done. It goes
in free space by itself, never on a widget; the reply says which cells it lies on, and clear is
false with a reason only when the page is full. --near COL,ROW (a cell from board) says where to
start looking; the note still goes to the nearest clear place, not onto what is there.
stickers lists the stickers there are: the built-in ones and the pictures in the owner's library.
stick puts one on a home page (the one the request came from, or --page NAME), clear of what is
there, near the cell given; the answer has its id and the cells it lies on. sticker changes one
that is there, by its id from board: turn to DEGREES, size as hundredths of the page's width (4 to
50), move its middle to a cell, or remove. clear is false when it then lies on a tile or another one. Only stickers are touched, and only when the user asked.
Read after each action. Use screenshot's path with your agent's image tool.
screen is read cut down to what you act on: the app in front and each control that says something,
with whether it takes a press, text or a scroll. --until TEXT waits up to 5 seconds for TEXT to show.
press finds the one control that says LABEL (its whole label first, then part of it), presses it and
answers with the screen that follows: one call per step, no numbers read off a picture. A LABEL that is
not on the screen is scrolled to first, a few pages down and then up; scrolled says how many. fill does the
same for a text field, found by its hint or contents; LABEL "" is the screen's only text field.
fill is not ok unless the field then shows TEXT.
--in PACKAGE refuses unless that app is in front. Two controls that answer to
LABEL are never chosen between: the error lists their ids for click or type.
expect waits up to 5 seconds for TEXT to show and fails if it does not, so steps can be chained and
stop at the first screen that is not the one meant:
  oma-phone press --in PACKAGE "Search" && oma-phone fill "" "milk" && oma-phone expect "results"
Prefer press, fill and screen. Use read, click, tap and screenshot when they are not enough.
When your work ends, the screen the user was on before your first step comes back by itself, unless
the user took the phone meanwhile. stay leaves the screen you end on up instead: use it only when the
task was to open or show something for the user.
Works automatically after Accessibility is enabled, on an unlocked phone.
Stop pauses access until the user resumes it.
HELP
  exit 0;;
click|type|scroll)
  [ "$#" -ge 2 ] || { echo 'Snapshot and node id required' >&2; exit 2; }
  request=$(jq -cn --arg command "$command" --arg snapshot "$1" --arg id "$2" --arg value "${3:-}" \
    '{command:$command,snapshot:$snapshot,id:$id} + (if $command=="type" then {text:$value} elif $command=="scroll" then {direction:(if $value=="" then "forward" else $value end)} else {} end)');;
expect)
  [ "$#" -ge 1 ] || { echo 'Text required' >&2; exit 2; }
  expect=1
  request=$(jq -cn --arg until "$*" '{command:"screen",until:$until}');;
screen)
  until=''
  if [ "${1:-}" = '--until' ]; then until=${2:?Text required: --until TEXT}; shift 2; fi
  request=$(jq -cn --arg until "$until" '{command:"screen"} + (if $until=="" then {} else {until:$until} end)');;
press|fill)
  front=''
  if [ "${1:-}" = '--in' ]; then front=${2:?Package required: --in PACKAGE}; shift 2; fi
  [ "$#" -ge 1 ] || { echo 'Label required' >&2; exit 2; }
  label=$1; shift
  if [ "$command" = fill ] && [ "$#" -lt 1 ]; then echo 'Text required' >&2; exit 2; fi
  request=$(jq -cn --arg command "$command" --arg label "$label" --arg text "$*" --arg front "$front" \
    '{command:$command,label:$label} + (if $command=="fill" then {text:$text} else {} end) + (if $front=="" then {} else {in:$front} end)');;
open)
  request=$(jq -cn --arg package "${1:?Package required}" '{command:"open",package:$package}');;
page)
  [ "$#" -ge 1 ] || { echo 'Page name required' >&2; exit 2; }
  request=$(jq -cn --arg name "$*" '{command:"page",name:$name}');;
page-add)
  request=$(jq -cn --arg name "$*" '{command:"pageedit",action:"add"} + (if $name=="" then {} else {name:$name} end)');;
page-delete)
  [ "$#" -ge 1 ] || { echo 'Page name required' >&2; exit 2; }
  request=$(jq -cn --arg name "$*" '{command:"pageedit",action:"delete",name:$name}');;
page-rename)
  name=''
  while [ "$#" -gt 0 ] && [ "$1" != '--to' ]; do name="${name:+$name }$1"; shift; done
  [ -n "$name" ] && [ "$#" -ge 2 ] || { echo 'Usage: oma-phone page-rename NAME --to NEW NAME' >&2; exit 2; }
  shift
  request=$(jq -cn --arg name "$name" --arg to "$*" '{command:"pageedit",action:"rename",name:$name,to:$to}');;
lockstar)
  case "${1:-list}" in
  list) request=$(jq -cn '{command:"lockstar",action:"list"}');;
  capture|apply) request=$(jq -cn --arg action "$1" --arg name "${2:?Name required}" '{command:"lockstar",action:$action,name:$name}');;
  *) echo 'Usage: oma-phone lockstar [capture NAME | apply NAME]' >&2; exit 2;;
  esac;;
looks)
  request=$(jq -cn '{command:"look"}');;
look)
  [ "$#" -ge 1 ] || { echo 'Look name required. Run oma-phone looks.' >&2; exit 2; }
  request=$(jq -cn --arg name "$*" '{command:"look",name:$name}');;
board)
  request=$(jq -cn --arg name "$*" '{command:"board"} + (if $name=="" then {} else {name:$name} end)');;
note)
  near=''
  if [ "${1:-}" = '--near' ]; then
    [ "$#" -ge 2 ] || { echo 'Cell required: --near COL,ROW' >&2; exit 2; }
    near=$2; shift 2
  fi
  [ "$#" -ge 1 ] || { echo 'Note text required' >&2; exit 2; }
  request=$(jq -cn --arg text "$*" --arg near "$near" '{command:"note",text:$text} + (if $near=="" then {} else {near:$near} end)');;
stickers)
  request=$(jq -cn '{command:"sticker",action:"list"}');;
stick|sticker)
  page=''; near=''
  while [ "${1:-}" = '--page' ] || [ "${1:-}" = '--near' ]; do
    [ "$#" -ge 2 ] || { echo "Value required after $1" >&2; exit 2; }
    if [ "$1" = '--page' ]; then page=$2; else near=$2; fi
    shift 2
  done
  if [ "$command" = stick ]; then
    [ "$#" -ge 1 ] || { echo 'Sticker name required. Run oma-phone stickers.' >&2; exit 2; }
    request=$(jq -cn --arg name "$*" --arg page "$page" --arg near "$near" \
      '{command:"sticker",action:"add",name:$name} + (if $page=="" then {} else {page:$page} end) + (if $near=="" then {} else {near:$near} end)')
  else
    [ "$#" -ge 2 ] || { echo 'Usage: oma-phone sticker [--page NAME] ID turn DEGREES | size HUNDREDTHS | move COL,ROW | remove' >&2; exit 2; }
    case "$2" in
    turn|size|move) [ "$#" -ge 3 ] || { echo "Value required for $2" >&2; exit 2; };;
    remove) ;;
    *) echo 'Unknown sticker action. Use turn, size, move or remove.' >&2; exit 2;;
    esac
    request=$(jq -cn --arg id "$1" --arg action "$2" --arg value "${3:-}" --arg page "$page" \
      '{command:"sticker",action:$action,id:$id} + (if $action=="remove" then {} else {value:$value} end) + (if $page=="" then {} else {page:$page} end)')
  fi;;
canvas)
  if [ "${1:-help}" = help ] || [ "${1:-}" = --help ] || [ "${1:-}" = -h ]; then
    cat <<'CANVAS'
oma-phone canvas board [PAGE]       what is on each page (modules by name, size and cells) and how much is free
oma-phone canvas modules            the modules there are, the sizes and the places on a page
oma-phone canvas page-make NAME     a new page after the last
oma-phone canvas page-rename NAME --to NEW | page-remove NAME (a page of modules only)
oma-phone canvas place MODULE [SIZE] [WHERE] [--page NAME]
    MODULE: needs, threads, computers, timeline, newthread, sessions, pocket, project:NAME, handoff:NAME
    SIZE: dot, line, card, card-wide, card-big, full. WHERE: top-left, top-right, bottom-left, bottom-right,
    top, bottom, any, or right-of/left-of/below/above MODULE. Home finds the cells: the spot asked for, a
    little smaller if need be, then anywhere on that page, then the next page with room, then a new page.
oma-phone canvas move MODULE WHERE [--page NAME] | resize MODULE SIZE | remove MODULE
oma-phone canvas undo               takes back the last arrangement an agent made, whole
Run board first. Arrange only what the user asked; the answer says what was done.
CANVAS
    exit 0
  fi
  # One word at a time, so a word that looks like an option (--page) is never taken for one of jq's.
  words='[]'
  for word in "$@"; do words=$(jq -cn --argjson words "$words" --arg word "$word" '$words + [$word]'); done
  # Who asks: the quick lane says so; a builder doesn't. Each request of his is one restore point, whoever made it.
  request=$(jq -cn --argjson words "$words" --arg actor "${OMA_CANVAS_ACTOR:-}" '{command:"canvas",words:$words} + (if $actor=="" then {} else {actor:$actor} end)');;
widget-install)
  file=${1:?Widget file required: oma-phone widget-install FILE.owl}
  [ -f "$file" ] || { echo "No such file: $file" >&2; exit 2; }
  [ "$(wc -c < "$file")" -le 262144 ] || { echo 'A widget file is at most 256 KB.' >&2; exit 2; }
  request=$(jq -cn --rawfile text "$file" --arg name "$(basename "$file")" '{command:"widgetinstall",name:$name,text:$text}');;
where)
  request=$(jq -cn --arg widget "${1:?Widget file name required}" '{command:"where",widget:$widget}');;
tap|swipe)
  request=$(jq -cn --arg command "$command" --arg x "${1:?X required}" --arg y "${2:?Y required}" --arg ex "${3:-0}" --arg ey "${4:-0}" --arg ms "${5:-400}" \
    '{command:$command,x:($x|tonumber),y:($y|tonumber)} + (if $command=="swipe" then {endX:($ex|tonumber),endY:($ey|tonumber),duration:($ms|tonumber)} else {} end)');;
status|pages|apps|read|screenshot|back|home|recents|notifications|stay|stop)
  request=$(jq -cn --arg command "$command" '{command:$command}');;
*) echo 'Unknown command. Run oma-phone help.' >&2; exit 2;;
esac
response=$(curl --silent --show-error --max-time 12 --abstract-unix-socket '__SOCKET__' \
  --header 'Content-Type: application/json' --data-binary "$request" http://localhost/phone) || {
  echo 'Phone bridge unavailable or timed out. Open Omalauncher Boss. Read the screen before retrying actions.' >&2; exit 1;
}
# A screen's controls one to a line: the same JSON, about a quarter fewer characters to read after every step.
printf '%s\n' "$response" | jq -r 'if (.controls | type) == "array" then (del(.controls) | tojson | .[:-1]) + (if (del(.controls) | length) > 0 then "," else "" end) + "\"controls\":[\n" + (.controls | map(tojson) | join(",\n")) + "\n]}" else . end'
printf '%s\n' "$response" | jq -e '.ok == true' >/dev/null
# expect fails when the words did not show, so a chain of steps stops there.
if [ "$expect" = 1 ]; then printf '%s\n' "$response" | jq -e '.shows == true' >/dev/null || { echo 'Not on screen after waiting.' >&2; exit 1; }; fi
