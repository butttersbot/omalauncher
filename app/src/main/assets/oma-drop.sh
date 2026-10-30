#!/bin/bash
# Omalauncher: `oma drop`, files between the Macs and this phone without Boss's hands (Boss, 2026-10-14: "so that I
# could just tell you or the other agents to send something, and then you guys can just do it with the command").
# Mac to phone is Taildrop: a Mac agent runs `tailscale file cp FILE galaxy-z-fold8:` and the phone's Tailscale saves
# it in Download/Taildrop, which this Linux cannot see; `get` and `list` reach it through the phone's own adb over
# loopback (the port oma-adb keeps in ~/.config/oma-development/install-port) when Wireless debugging is on, and else
# through Oma itself on the phone bridge, which reads that folder with All files access: receiving never needs
# Wireless debugging, which Android turns off away from Wi-Fi (Boss, 2026-10-24). Phone to Mac goes to the runner on the
# Mac (docs/MAC-RUNNER-SPEC.md, POST /files), because the phone's Tailscale app has no command line to Taildrop
# with. The runner's key is read by jq and piped to curl as a config on its standard input, as `oma threads` does:
# it is never an argument of any command, never in a variable of this script and never in a file of its own.
set -u -o pipefail
config="${HOME:?}/.config/omalauncher/mac-runner.json"
port_file="$HOME/.config/oma-development/install-port"
# Where Tailscale saves what it receives: Download/Taildrop, or Taildrop at the storage's top, as it was set on the
# new Fold (2026-10-17, when Android's folder picker there would not let it move). Both are read.
taildrops='/sdcard/Download/Taildrop /sdcard/Taildrop'

usage() {
  cat <<USAGE
usage: oma drop get [--to DIR] [--keep]
       oma drop list
       oma drop send FILE... [--to mac]
       oma drop help

get   moves every file the Macs Taildropped to this phone (Download/Taildrop) into
      DIR, ~/Inbox if none is given; --keep copies them and leaves them on the phone.
list  shows what waits in Download/Taildrop.
send  puts each FILE in the Mac's Downloads/From Fold, through the runner there.
A Mac sends to this phone with: tailscale file cp FILE galaxy-z-fold8:
USAGE
}

bad() {
  usage >&2
  exit 2
}

die() {
  echo "$1" >&2
  exit 1
}

# A size as people read it.
size() {
  awk -v b="$1" 'BEGIN { split("B KB MB GB TB", u); i = 1; while (b >= 1000 && i < 5) { b /= 1000; i++ }
    printf(i == 1 ? "%d %s" : "%.1f %s", b, u[i]) }'
}

# NAME as a word of the phone's shell, which every adb shell line goes through.
quoted() {
  printf "'%s'" "${1//\'/\'\\\'\'}"
}

# The phone's own adb, over loopback, in $at, when Wireless debugging is on; fails (quietly) when it is not, and
# receiving goes through Oma instead (via_oma). adb's server lives only as long as this script, so it connects here
# every time. A port that went stale (Wireless debugging was turned off and on) is looked for again once, by oma-adb
# (or oma-dev --status where there is no oma-adb), which writes the new one where it is read.
adb_call() {
  timeout --kill-after=1s 3s adb "$@"
}

connect() {
  command -v adb >/dev/null || return 1
  adb_call start-server >/dev/null 2>&1 || return 1
  local tries=1 find=
  if command -v oma-adb >/dev/null; then find=oma-adb; elif command -v oma-dev >/dev/null; then find='oma-dev --status'; fi
  [ -n "$find" ] && tries=2
  for try in $(seq "$tries"); do
    [ "$try" = 2 ] && timeout --kill-after=1s 3s $find >/dev/null 2>&1
    local port
    port=$(cat "$port_file" 2>/dev/null)
    if [[ $port =~ ^[0-9]+$ ]]; then
      at=127.0.0.1:$port
      # A refused connect is a stale port, looked for again on the next try; only a hung adb (timeout's 124, or 137
      # once killed) gives up on adb here and leaves receiving to Oma.
      adb_call connect "$at" >/dev/null 2>&1
      [ $? -ge 124 ] && return 1
      [ "$(adb_call -s "$at" get-state 2>/dev/null)" = device ] && return
    fi
  done
  return 1
}

# Without adb: Oma itself reads Taildrop's folders (it has All files access) and copies into DIR, through the phone
# bridge, the socket only this app's own processes can open. MODE is get or list.
phone_socket=${OMA_PHONE_SOCKET:-dev.omalauncher.boss.phone}
via_oma() {
  local mode=$1 dir=${2:-} keep=${3:-} request reply
  command -v curl >/dev/null && command -v jq >/dev/null || die 'Neither adb nor the phone bridge can be used (curl and jq are needed).'
  if [ "$mode" = list ]; then
    request='{"command":"taildrop","list":true}'
  else
    request=$(jq -cn --arg to "$dir" --argjson keep "$([ -n "$keep" ] && echo true || echo false)" '{command: "taildrop", to: $to, keep: $keep}')
  fi
  reply=$(curl --silent --show-error --max-time 900 --abstract-unix-socket "$phone_socket" --header 'Content-Type: application/json' \
    --data-binary "$request" http://localhost/phone 2>/dev/null) ||
    die "Can't reach the phone's own adb (Wireless debugging is off) nor Oma on the phone bridge. Is Oma running?"
  printf '%s' "$reply" | jq -e '.ok == true' >/dev/null 2>&1 ||
    die "Wireless debugging is off and Oma could not read Taildrop: $(printf '%s' "$reply" | jq -r '.error // "no answer"' 2>/dev/null)"
  [ "$(printf '%s' "$reply" | jq '.files | length')" -gt 0 ] || { echo 'Nothing waiting.'; return 0; }
  local failed=0 state name bytes path got error
  while IFS=$'\t' read -r state name bytes path got error; do
    case $state in
      listed) printf '%s\t%s\n' "$name" "$(size "$bytes")" ;;
      moved) echo "Moved $name ($(size "$bytes")) to $path." ;;
      copied)
        if [ -n "$error" ]; then echo "Copied $name ($(size "$bytes")) to $path, but could not remove it from the phone." >&2; failed=1
        else echo "Copied $name ($(size "$bytes")) to $path; it stays on the phone."; fi ;;
      partial) echo "Copied $name only in part ($got of $bytes bytes, kept as $path); it is still on the phone." >&2; failed=1 ;;
      *) echo "Could not copy $name; it is still on the phone." >&2; failed=1 ;;
    esac
  done < <(printf '%s' "$reply" | jq -r '.files[] | [.state, .name, (.bytes|tostring), (.path // ""), ((.got // "")|tostring), (.error // "")] | join("\t")')
  return "$failed"
}

# What waits, one "SIZE<tab>NAME" line per file. A name still ending in .partial is a file Tailscale is receiving.
waiting() {
  # Each file as its size and its full path on the phone, from every Taildrop folder there is.
  adb_call -s "$at" shell "for d in $taildrops; do [ -d \"\$d\" ] || continue; for f in \"\$d\"/*; do [ -f \"\$f\" ] && stat -c '%s	%n' -- \"\$f\"; done; done; exit 0" </dev/null |
    tr -d '\r' | awk '!/\.partial$/' | awk -F/ '!($NF ~ /^msg-.*\.json$/)'  # agent mail is Soma's own (TaildropMail)
}

# A name in DIR for NAME that no file has yet: NAME, or "NAME (2)", "NAME (3)"... before the extension.
free_name() {
  local dir=$1 name=$2 stem extension= number=2
  stem=$name
  if [[ $name == ?*.* ]]; then
    stem=${name%.*}
    extension=.${name##*.}
  fi
  local path="$dir/$name"
  while [ -e "$path" ]; do
    path="$dir/$stem ($number)$extension"
    number=$((number + 1))
  done
  printf '%s' "$path"
}

get() {
  local dir="$HOME/Inbox" keep=
  while [ $# -gt 0 ]; do
    case $1 in
      --keep) keep=1 ;;
      --to)
        [ $# -ge 2 ] && [ -n "$2" ] || bad
        dir=$2
        shift ;;
      *) bad ;;
    esac
    shift
  done
  mkdir -p -- "$dir" || die "Could not make $dir."
  dir=$(cd -- "$dir" && pwd)
  connect || { via_oma get "$dir" "$keep"; return; }
  local files
  files=$(waiting) || { via_oma get "$dir" "$keep"; return; }
  [ -n "$files" ] || { echo 'Nothing waiting.'; return; }
  local failed=0 bytes name path got
  while IFS=$'\t' read -r bytes name; do
    local from=$name
    name=${from##*/}
    path=$(free_name "$dir" "$name")
    # adb would read the list this loop reads, so it is given nothing to read.
    if ! timeout --kill-after=1s 120s adb -s "$at" pull "$from" "$path" </dev/null >/dev/null 2>&1; then
      rm -f -- "$path"
      echo "Could not copy $name; it is still on the phone." >&2
      failed=1
      continue
    fi
    got=$(stat -c %s -- "$path")
    # Only a copy as long as the phone's own is reason to remove the phone's.
    if [ "$got" != "$bytes" ]; then
      echo "Copied $name only in part ($got of $bytes bytes, kept as $path); it is still on the phone." >&2
      failed=1
    elif [ -n "$keep" ]; then
      echo "Copied $name ($(size "$bytes")) to $path; it stays on the phone."
    elif adb_call -s "$at" shell "rm -f -- $(quoted "$from")" </dev/null >/dev/null 2>&1; then
      echo "Moved $name ($(size "$bytes")) to $path."
    else
      echo "Copied $name ($(size "$bytes")) to $path, but could not remove it from the phone." >&2
      failed=1
    fi
  done <<<"$files"
  return "$failed"
}

list() {
  [ $# -eq 0 ] || bad
  connect || { via_oma list; return; }
  local files bytes name
  files=$(waiting) || { via_oma list; return; }
  [ -n "$files" ] || { echo 'Nothing waiting.'; return; }
  while IFS=$'\t' read -r bytes name; do
    printf '%s\t%s\n' "${name##*/}" "$(size "$bytes")"
  done <<<"$files"
}

# The Authorization header as a line of curl's config. Prints nothing for a file with no usable key.
auth() {
  jq -er 'select((.key | type) == "string" and (.key | test("^[!-~]+$")))
    | "header = \"Authorization: Bearer " + (.key | gsub("\\\\"; "\\\\") | gsub("\""; "\\\"")) + "\""' "$config" 2>/dev/null
}

unreachable='The Mac'"'"'s runner cannot be reached over Tailscale (is Tailscale on here, and the Mac awake?). Nothing was sent. Quick Share to the Mac works too.'

send() {
  local files=()
  while [ $# -gt 0 ]; do
    case $1 in
      --to)
        [ "${2-}" = mac ] || bad
        shift ;;
      -*) bad ;;
      *) files+=("$1") ;;
    esac
    shift
  done
  [ ${#files[@]} -gt 0 ] || bad
  local file
  for file in "${files[@]}"; do
    [ -f "$file" ] && [ -r "$file" ] || die "$file is not a file that can be read. Nothing was sent."
  done
  [ -r "$config" ] || die 'No Mac is set up on this phone: ~/.config/omalauncher/mac-runner.json is missing.'
  local url
  url=$(jq -r '.url // empty | tostring' "$config" 2>/dev/null) || die 'mac-runner.json could not be read.'
  url=${url%/}
  # A host and a port and nothing after them: the paths asked for are this script's own.
  local plain='^https?://[]A-Za-z0-9.:_[-]+$'
  [[ $url =~ $plain ]] || die 'mac-runner.json has no usable "url".'
  auth >/dev/null || die 'mac-runner.json has no usable "key".'

  local reply code answer
  reply=$(auth | curl --silent --config - --proto '=http,https' --connect-timeout 3 --max-time 10 \
    --write-out '\n%{http_code}' "$url/health") || die "$unreachable"
  code=${reply##*$'\n'}
  answer=${reply%$'\n'*}
  [ "$code" = 401 ] && die "The Mac turned this phone's key down. Nothing was sent."
  [ "$code" = 200 ] || die "$unreachable"
  printf '%s' "$answer" | jq -e '.files == true' >/dev/null 2>&1 ||
    die "The Mac's runner is too old for files; run oma-runner install there. Nothing was sent."

  local failed=0 name bytes error
  for file in "${files[@]}"; do
    name=$(jq -rn --arg name "$(basename -- "$file")" '$name | @uri')
    bytes=$(stat -L -c %s -- "$file")
    # --upload-file streams the file and gives its length; a stall of a minute gives up rather than hang.
    if ! reply=$(auth | curl --silent --config - --proto '=http,https' --connect-timeout 3 --speed-limit 1 --speed-time 60 \
      --request POST --upload-file "$file" --write-out '\n%{http_code}' "$url/files?name=$name"); then
      echo "$file was not sent: the Mac's runner stopped answering. Quick Share to the Mac works too." >&2
      failed=1
      continue
    fi
    code=${reply##*$'\n'}
    answer=${reply%$'\n'*}
    if [ "$code" = 200 ]; then
      echo "Sent $(basename -- "$file") ($(size "$bytes")) to the Mac: $(printf '%s' "$answer" | jq -r '.path')"
    else
      error=$(printf '%s' "$answer" | jq -r '.error // empty' 2>/dev/null)
      echo "$file was not sent: the Mac answered HTTP $code${error:+: $error}." >&2
      failed=1
    fi
  done
  return "$failed"
}

[ $# -ge 1 ] || bad
command=$1
shift
case $command in
  get) get "$@" ;;
  list) list "$@" ;;
  send) send "$@" ;;
  help | -h | --help) usage ;;
  *) bad ;;
esac
