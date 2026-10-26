#!/bin/bash
# Omalauncher: curl/jq are already included in the Linux setup; no TCP listener.
set -euo pipefail
# Without --display: the hidden background display when its helper is up, else the main screen (Boss, 2026-10-25:
# "default phone control … background", and when the helper is down, the main screen rather than nothing).
display=auto
if [ "${1:-}" = --display ]; then
  display=${2:?Display required: main, bg or auto}
  shift 2
fi
case "$display" in main|bg|auto) ;; *) echo 'Display must be main, bg or auto.' >&2; exit 2;; esac
timing=false
if [ "${1:-}" = --timing ]; then timing=true; shift; fi
no_look=0
if [ "${1:-}" = --no-look ]; then no_look=1; shift; fi
command=${1:-help}
shift || true
if [ "$display" = auto ]; then
  case "$command" in
  # Home's own pages, notes, stickers and looks, status and help: the main screen's, as before.
  help|status|stop|stay|pages|page|page-*|canvas|board|stickers|stick|sticker|ink|note|looks|look|where|widget-install|plan-id|plan-receipt) display=main ;;
  *)
    # Asked once every few seconds at most: one status call costs a round trip.
    seen=/tmp/oma-phone-bg-$(id -u)
    if [ -f "$seen" ] && [ $(( $(date +%s) - $(stat -c %Y "$seen") )) -lt 5 ]; then up=$(cat "$seen")
    else
      up=$("$0" --display bg status 2>/dev/null | jq -r '.backgroundAvailable // false' 2>/dev/null || echo false)
      printf '%s' "$up" >"$seen" 2>/dev/null || true
    fi
    if [ "$up" = true ]; then display=bg
    else
      display=main
      echo 'oma-phone: the background helper is not running, so this goes to the main screen.' >&2
    fi ;;
  esac
fi
self=("$0" --display "$display")
if [ "$timing" = true ]; then self+=(--timing); fi
expect=0
# A script of steps, run one after another with no agent turn between them: the speed comes from planning once
# (Boss, 2026-10-21, after ego lite's one-pass scripts). Each step is an ordinary oma-phone call with every check it
# has; the run stops at the first step that fails and answers once, with what was done and the screen it ended on.
if [ "$command" = run ]; then
  global=()
  while :; do
    case "${1:-}" in
    --in) global+=(--in "${2:?Package required: --in PACKAGE}"); shift 2;;
    --any) global+=(--any); shift;;
    *) break;;
    esac
  done
  source=${1:--}
  if [ "$source" = - ]; then script=$(cat); else [ -f "$source" ] || { echo "No such script: $source" >&2; exit 2; }; script=$(cat "$source"); fi
  step=0; done_steps=0
  while IFS= read -r line || [ -n "$line" ]; do
    line=${line#"${line%%[![:space:]]*}"}
    case "$line" in ''|'#'*) continue;; esac
    step=$((step + 1))
    [ "$step" -le 40 ] || { echo "A run is at most 40 steps. Stopped before step $step; $done_steps done." >&2; exit 1; }
    # Words as a shell would split them, quotes and all, but nothing in them is ever run.
    split=$(printf '%s\n' "$line" | xargs printf '%s\036' 2>/dev/null) \
      || { echo "Step $step can't be read (unbalanced quotes?): $line. $done_steps done." >&2; exit 1; }
    words=()
    while IFS= read -r -d $'\036' word; do words+=("$word"); done <<<"$split"
    [ "${#words[@]}" -ge 1 ] || continue
    verb=${words[0]}; rest=("${words[@]:1}")
    case "$verb" in
    wait)
      ms=${rest[0]:-500}
      [[ "$ms" =~ ^[0-9]+$ ]] && [ "$ms" -le 5000 ] || { echo "Step $step: wait takes milliseconds, at most 5000. $done_steps done." >&2; exit 1; }
      sleep "$(awk -v m="$ms" 'BEGIN{printf "%.3f", m/1000}')";;
    press|fill|submit|back|tap|swipe|expect|screen|open|url|home|page|stay)
      case "$verb" in press|fill|submit|back|tap|swipe) args=("${global[@]}" "${rest[@]}");; *) args=("${rest[@]}");; esac
      if ! out=$("${self[@]}" "$verb" "${args[@]}" 2>&1 </dev/null); then
        why=$(printf '%s\n' "$out" | jq -r 'select(type=="object") | .error // empty' 2>/dev/null | head -1)
        echo "Step $step failed: $line" >&2
        echo "${why:-$(printf '%s\n' "$out" | tail -1)}" >&2
        echo "$done_steps of $step done before it. The screen now:" >&2
        "${self[@]}" screen </dev/null || true
        exit 1
      fi;;
    *) echo "Step $step: $verb can't go in a run (press, fill, submit, back, tap, swipe, expect, screen, open, url, home, page, stay, wait). $done_steps done." >&2; exit 1;;
    esac
    done_steps=$step
  done <<<"$script"
  echo "{\"ok\":true,\"steps\":$done_steps}"
  exec "${self[@]}" screen
fi
# Every touch takes --in PACKAGE (refuse unless that app is in front) and --any (not held to the app last opened).
front=''; any=false
case "$command" in
press|fill|click|type|scroll|submit|tap|swipe|back)
  while :; do
    case "${1:-}" in
    --in) front=${2:?Package required: --in PACKAGE}; shift 2;;
    --any) any=true; shift;;
    *) break;;
    esac
  done;;
esac
case "$command" in
plan-id)
  printf '%s-%s\n' "$(date +%s%3N)" "$(cat /proc/sys/kernel/random/uuid)"
  exit 0;;
plan-receipt)
  request=$(jq -cn --arg id "${1:?Plan id required}" '{command:"plan-receipt",id:$id}');;
plan)
  file=${1:?JSON plan file required: oma-phone plan FILE.json}
  [ -f "$file" ] && [ "$(wc -c < "$file")" -le 60000 ] || { echo 'Plan must be a file of at most60000 bytes.' >&2; exit 2; }
  request=$(jq -ce 'if type=="object" and (.id|type)=="string" and (.steps|type)=="array" then . + {command:"plan"} else error("Plan requires id and steps") end' "$file");;
help)
  cat <<'HELP'
oma-phone [--display main|bg|auto] COMMAND ...  (default auto: background when its helper is up, else main)
Background commands stay on the hidden display; unsupported navigation fails without touching main.
Start the shell display helper first. Stop pauses all phone control.
oma-phone apps | open PACKAGE_OR_settings | url [--app PACKAGE] URL
oma-phone status | read | screenshot | back | home | recents | notifications | stay | stop
oma-phone submit
oma-phone run [--in PACKAGE | --any] [SCRIPT_FILE | -]
oma-phone screen [--until TEXT | --since SNAPSHOT [--delta]]
oma-phone expect TEXT
oma-phone press [--in PACKAGE | --any] LABEL
oma-phone fill [--in PACKAGE | --any] LABEL TEXT
oma-phone pages | page NAME | where WIDGET_FILE
oma-phone page-add [NAME] | page-rename NAME --to NEW NAME | page-delete NAME
oma-phone board [PAGE NAME]
oma-phone looks | look NAME
oma-phone widget-install FILE.owl
oma-phone canvas help | board [PAGE] | modules | place MODULE [SIZE] [WHERE] [--page NAME] | move | resize | remove | page-make | undo | layout.*
oma-phone lockstar [capture NAME | apply NAME]
oma-phone note [--near COL,ROW] TEXT
oma-phone stickers
oma-phone stick [--page NAME] [--near COL,ROW] STICKER
oma-phone sticker [--page NAME] ID turn DEGREES | size HUNDREDTHS | move COL,ROW | remove
oma-phone ink clear [--page NAME]
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
ink clear wipes the pen marks off the page showing, or the one named, at once and as one step its undo brings back; the answer says how many went. Only pen marks are touched, and only when the user asked.
Read after each action. Use screenshot's path with your agent's image tool.
screen is read cut down to what you act on: the app in front and each control that says something,
with whether it takes a press, text or a scroll. --until TEXT waits up to 5 seconds for TEXT to show.
--since SNAPSHOT answers {"unchanged":true} when nothing on the screen changed since that snapshot was read, so a
second look costs nothing; otherwise the whole screen as usual. With --delta as well, a changed screen comes back as
only what changed since that snapshot: changed (controls new or different, in full), gone (ids no longer there)
and same (how many stayed as they were); the whole screen still when that snapshot is not the last one this
display answered.
press finds the one control that says LABEL (its whole label first, then part of it), presses it and
answers with the screen that follows: one call per step, no numbers read off a picture. A LABEL that is
not on the screen is scrolled to first, a few pages down and then up; scrolled says how many. fill does the
same for a text field, found by its hint or contents; LABEL "" is the screen's only text field.
fill is not ok unless the field then shows TEXT.
--in PACKAGE refuses unless that app is in front. Two controls that answer to
LABEL are never chosen between: the error lists their ids for click or type.
url opens a web link (http or https) in the phone's browser, or in --app PACKAGE: never type a link into an
address bar. It and open answer once the app is in front (inFront says whether it came).
After open or url, every touch (press, fill, click, type, scroll, submit, tap, swipe, back) is for that app:
if another app has come to the front, nothing is done and the error says which. Name the app you mean with
--in PACKAGE, or add --any. home, recents, notifications and page lift that hold.
submit presses the keyboard's Enter, Go or Search on the field that has the keyboard: use it after fill.
run takes several steps in one call, one to a line (from a file, or stdin with -), with no turn of yours
between them: press, fill, submit, back, tap, swipe, expect, screen, open, url, home, page, stay and wait MS.
Quote words as in a shell; # starts a comment. It stops at the first step that fails, says which and why,
and shows the screen then; otherwise it answers once with the screen it ended on. --in or --any applies to
every touch in it. At most 40 steps. Plan the steps you are sure of, run them, then look:
  printf '%s\n' 'url https://x.com/i/bookmarks' 'expect Bookmarks' 'press "Add to Folder"' 'press "AI Agents"' | oma-phone run -
Nothing types into Soma's own chat box, where the user writes, unless --in names Soma.
status says locked, awake and active before you plan: a locked phone takes no steps.
expect waits up to 5 seconds for TEXT to show and fails if it does not, so steps can be chained and
stop at the first screen that is not the one meant:
  oma-phone press --in PACKAGE "Search" && oma-phone fill "" "milk" && oma-phone expect "results"
Open and verify in one call: open settings:display --until Display (also sound, wifi, bluetooth,
battery [Battery Saver], storage or apps). Unsupported destinations fail without a fallback.
Without --until, open confirms only the foreground package; destinationVerified is false.
With --until, a missing destination returns failure; the observed screen is returned on verification.
Optional --timing before the command adds service/read timings and search counts, without logging contents.
Optional --no-look (after --timing) leaves out the picture otherwise added as "look" when the controls cannot say
what is there: nothing readable, a label no control or several controls answer to, or only one or two controls.
Native plans: save a JSON object {id,steps,timeoutMs} to a file and use plan FILE.json.
Get a fresh id ONCE with plan-id, save it with the plan, and reuse that exact id to inspect/retry safely.
plan-receipt ID reads its durable outcome even while control is paused. A retry never reruns retained steps.
Plans: open/url, press/fill/submit/back with explicit in:PACKAGE, and screen with until:TEXT.
End with a screen assertion. At most40 steps/30seconds; first failure stops. No automatic retries.
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
  until=''; since=''; delta=false
  if [ "${1:-}" = '--until' ]; then until=${2:?Text required: --until TEXT}; shift 2; fi
  if [ "${1:-}" = '--since' ]; then since=${2:?Snapshot required: --since SNAPSHOT}; shift 2; fi
  if [ "${1:-}" = '--delta' ]; then delta=true; shift; fi
  request=$(jq -cn --arg until "$until" --arg since "$since" --argjson delta "$delta" '{command:"screen"} + (if $until=="" then {} else {until:$until} end) + (if $since=="" then {} else {since:$since} end) + (if $delta then {delta:true} else {} end)');;
press|fill)
  [ "$#" -ge 1 ] || { echo 'Label required' >&2; exit 2; }
  label=$1; shift
  if [ "$command" = fill ] && [ "$#" -lt 1 ]; then echo 'Text required' >&2; exit 2; fi
  request=$(jq -cn --arg command "$command" --arg label "$label" --arg text "$*" --arg front "$front" \
    '{command:$command,label:$label} + (if $command=="fill" then {text:$text} else {} end)');;
url)
  app=''
  if [ "${1:-}" = '--app' ]; then app=${2:?Package required: --app PACKAGE}; shift 2; fi
  [ "$#" -ge 1 ] || { echo 'URL required: oma-phone url https://...' >&2; exit 2; }
  request=$(jq -cn --arg url "$1" --arg app "$app" '{command:"url",url:$url} + (if $app=="" then {} else {app:$app} end)');;
open)
  name=${1:?Package or Settings destination required}; shift
  until=''
  if [ "$#" -gt 0 ]; then
    [ "$#" -eq 2 ] && [ "$1" = --until ] && [ -n "$2" ] || { echo 'Usage: open PACKAGE [--until TEXT]' >&2; exit 2; }
    until=$2
  fi
  request=$(jq -cn --arg package "$name" --arg until "$until" '{command:"open",package:$package} + (if $until=="" then {} else {until:$until} end)');;
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
ink)
  [ "${1:-}" = clear ] || { echo 'Usage: oma-phone ink clear [--page NAME]' >&2; exit 2; }
  shift; page=''
  if [ "${1:-}" = '--page' ]; then
    [ "$#" -ge 2 ] || { echo 'Page name required after --page' >&2; exit 2; }
    page=$2
  fi
  request=$(jq -cn --arg page "$page" '{command:"ink",action:"clear"} + (if $page=="" then {} else {page:$page} end)');;
canvas)
  if [ "${1:-help}" = help ] || [ "${1:-}" = --help ] || [ "${1:-}" = -h ]; then
    cat <<'CANVAS'
oma-phone canvas board [PAGE]       what is on each page (modules by name, size and cells) and how much is free
oma-phone canvas modules            the modules there are, the sizes and the places on a page
oma-phone canvas page-make NAME     a new page after the last
oma-phone canvas page-rename NAME --to NEW | page-remove NAME (a page of modules only)
oma-phone canvas place MODULE [SIZE] [WHERE] [--page NAME]
    MODULE: needs, threads, computers, timeline, newthread, sessions, pocket, layouts, project:NAME, handoff:NAME
    SIZE: dot, line, card, card-wide, card-big, full. WHERE: top-left, top-right, bottom-left, bottom-right,
    top, bottom, any, or right-of/left-of/below/above MODULE. Home finds the cells: the spot asked for, a
    little smaller if need be, then anywhere on that page, then the next page with room, then a new page.
oma-phone canvas move MODULE WHERE [--page NAME] | resize MODULE SIZE | remove MODULE
oma-phone canvas undo               takes back the last arrangement an agent made, whole
oma-phone canvas layout.list | layout.save NAME [--replace] | layout.load NAME | layout.delete NAME | layout.rename NAME --to NEW
    saved layouts of the whole home; loading one is one change that undo takes back. Only when he asks.
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
status|pages|apps|read|screenshot|back|home|recents|notifications|stay|stop|submit)
  request=$(jq -cn --arg command "$command" '{command:$command}');;
*) echo 'Unknown command. Run oma-phone help.' >&2; exit 2;;
esac
request=$(jq -c --argjson timing "$timing" '. + (if $timing then {timing:true} else {} end)' <<<"$request")
request=$(jq -c --arg display "$display" '. + {display:$display}' <<<"$request")
request=$(jq -c --arg front "$front" --argjson any "$any" '. + (if $front=="" then {} else {in:$front} end) + (if $any then {any:true} else {} end)' <<<"$request")
limit=12
if [ "$command" = plan ]; then limit=35; fi
response=$(curl --silent --show-error --max-time "$limit" --abstract-unix-socket '__SOCKET__' \
  --header 'Content-Type: application/json' --data-binary "$request" http://localhost/phone) || {
  echo 'Phone bridge unavailable or timed out. Open Omalauncher Boss. Read the screen before retrying actions.' >&2; exit 1;
}
# Visual fallback (phone-control speed, stage 3): when the controls can't say what is there (nothing readable, a
# label nobody answers to or several do, a screen of one or two controls: a game, a picture, a web page drawn
# without its words), a picture of that display comes with the answer as "look", so the agent looks before guessing.
# --no-look leaves it out.
look_why=$(printf '%s\n' "$response" | jq -r --arg c "$command" '
  if (.ok != true) and ((.error // "") | test("No readable window|nothing readable is in front|Nothing on screen (says|is a text field)|Several controls answer")) then (.error | split(".")[0])
  elif (.ok == true) and ($c == "screen" or $c == "read" or $c == "expect") and ((.controls | type) == "array") and ((.controls | length) <= 2) then "only \(.controls | length) readable control(s) on screen"
  else empty end' 2>/dev/null || true)
if [ -n "$look_why" ] && [ "$no_look" != 1 ] && [ "$command" != screenshot ]; then
  shot=$(curl --silent --max-time 12 --abstract-unix-socket '__SOCKET__' --header 'Content-Type: application/json' \
    --data-binary "$(jq -cn --arg display "$display" '{command:"screenshot",display:$display}')" http://localhost/phone 2>/dev/null || true)
  path=$(printf '%s\n' "$shot" | jq -r 'select(.ok == true) | .path' 2>/dev/null || true)
  response=$(printf '%s\n' "$response" | jq -c --arg why "$look_why" --arg path "$path" \
    '. + {look: ({why: $why} + (if $path == "" then {screenshot: "unavailable (a protected screen?)"} else {screenshot: $path, note: "read this picture with your image tool before the next step"} end))}')
fi
# A screen's controls one to a line: the same JSON, about a quarter fewer characters to read after every step.
printf '%s\n' "$response" | jq -r 'if (.controls | type) == "array" then (del(.controls) | tojson | .[:-1]) + (if (del(.controls) | length) > 0 then "," else "" end) + "\"controls\":[\n" + (.controls | map(tojson) | join(",\n")) + "\n]}" else . end'
printf '%s\n' "$response" | jq -e '.ok == true' >/dev/null
# expect fails when the words did not show, so a chain of steps stops there.
if [ "$expect" = 1 ]; then printf '%s\n' "$response" | jq -e '.shows == true' >/dev/null || { echo 'Not on screen after waiting.' >&2; exit 1; }; fi
