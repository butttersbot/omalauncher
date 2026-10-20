#!/bin/bash
# Omalauncher: `oma threads`, which lists the project threads on the Macs and makes a new one, by asking the runner on
# each Mac (docs/MAC-RUNNER-SPEC.md) with the address and key in ~/.config/omalauncher: mac-runner.json for the first
# Mac, the Mac Pro, and mac-runner-ID.json for each other, such as mac-runner-macbook-pro.json (MacMachines.kt). A
# thread of another Mac is known here, as in the launcher, by ID/ and the runner's id; one of the first Mac by the
# runner's id alone. With only the first Mac set up everything reads exactly as it did before there were two.
# The key is read by jq and piped to curl as a config on its standard input. It is never an argument of any
# command, never in a variable of this script and never in a file of its own, so nothing here can print it and
# `ps` cannot show it. The runner decides which folders may hold a thread; this makes no folder anywhere.
set -u -o pipefail
dir="${HOME:?}/.config/omalauncher"
config="$dir/mac-runner.json"

usage() {
  cat >&2 <<USAGE
usage: oma threads list
       oma threads new NAME PROJECT_PATH [--kind claude|codex|grok|cursor|venice] [--mode auto|manual] [--mac MAC]
       oma threads macs
       oma threads add-mac FILE [--name NAME]

list prints one line per thread on the Mac: id, state, name and project folder;
with more than one Mac, the Mac it is on as well. A Mac out of reach is said
and the others are listed all the same.
new makes a thread called NAME for the folder PROJECT_PATH on the Mac (~ is the
Mac's home there) and prints its id. The folder must already exist under one of
the roots the Mac allows; otherwise the Mac refuses and its roots are printed.
--kind chooses the agent that works in it; without it the Mac starts Claude.
--mode auto lets its agent run its steps without asking; manual sends each step to
Boss to approve. Without it the Mac decides: auto, but manual for Codex.
--mac chooses the Mac, by its name or id; without it the first, the Mac Pro.
macs prints each Mac set up here: name, id and address, never its key.
add-mac sets up a Mac from the {"url","key"} file its runner sent (oma drop get
puts it in ~/Inbox): one whose address is already set up gets the new key,
any other is added, called NAME or the name in the file. The file is removed.
USAGE
  exit 2
}

die() {
  echo "$1" >&2
  exit 1
}

# The Mac a file describes: empty for mac-runner.json, the id for mac-runner-ID.json; fails for any other name.
mac_id() {
  local base=${1##*/}
  [ "$base" = mac-runner.json ] && return 0
  base=${base#mac-runner-}
  base=${base%.json}
  [[ $base =~ ^[a-z0-9][a-z0-9-]{0,31}$ ]] || return 1
  printf '%s' "$base"
}

# What a Mac is called: the file's "name", else from its id as MacMachines.kt has it (macbook-pro is MacBook Pro).
mac_name() {
  local named id
  named=$(jq -r '.name // empty | tostring' "$1" 2>/dev/null | head -c 32)
  [ -n "$named" ] && { printf '%s' "$named"; return; }
  id=$(mac_id "$1")
  [ -z "$id" ] && { printf 'Mac Pro'; return; }
  printf '%s' "$id" | tr '-' '\n' | awk 'NF {
      w = $0
      if (w == "macbook") w = "MacBook"; else if (w == "imac") w = "iMac"; else if (w == "mac") w = "Mac"; else if (w != "mini") w = toupper(substr(w, 1, 1)) substr(w, 2)
      printf "%s%s", (n++ ? " " : ""), w
    }'
}

# Every Mac's file, the first Mac's first, then the others by id.
mac_files() {
  local file
  [ -r "$dir/mac-runner.json" ] && printf '%s\n' "$dir/mac-runner.json"
  for file in "$dir"/mac-runner-*.json; do
    [ -r "$file" ] && mac_id "$file" >/dev/null && printf '%s\n' "$file"
  done | sort
}

# How a Mac out of reach is said: as it always was while it is the only one, by its name beside another.
away() {
  if [ "$count" -gt 1 ]; then printf 'The %s is unreachable.' "$name"; else printf 'Mac unreachable.'; fi
}

# The Authorization header as a line of curl's config. Prints nothing for a file with no usable key.
auth() {
  jq -er 'select((.key | type) == "string" and (.key | test("^[!-~]+$")))
    | "header = \"Authorization: Bearer " + (.key | gsub("\\\\"; "\\\\") | gsub("\""; "\\\"")) + "\""' "$config" 2>/dev/null
}

# call METHOD PATH [BODY]: prints the Mac's answer, then the HTTP status on a line of its own.
call() {
  local body=()
  [ $# -ge 3 ] && body=(--header 'Content-Type: application/json' --data-binary "$3")
  auth | curl --silent --show-error --config - --proto '=http,https' --connect-timeout 3 --max-time 15 \
    --request "$1" ${body[@]+"${body[@]}"} --write-out '\n%{http_code}' "$url$2"
}

# What went wrong, said from the Mac's answer. The answer never holds the key.
refused() {
  local code=$1 answer=$2 error
  error=$(printf '%s' "$answer" | jq -r '.error // empty' 2>/dev/null)
  case $code in
    401) die "${who}turned this phone's key down." ;;
    403)
      echo "${who}refused: ${error:-not allowed}." >&2
      if printf '%s' "$answer" | jq -e '(.roots // []) | length > 0' >/dev/null 2>&1; then
        echo 'It takes an existing folder under one of:' >&2
        printf '%s' "$answer" | jq -r '.roots[] | "  " + tostring' >&2
      fi
      exit 1 ;;
    *) die "${who}answered HTTP $code${error:+: $error}." ;;
  esac
}

# Makes the Mac in FILE the one asked: its address in url, its id in mac and who it is in the words said ("The Mac "
# while it is the only one, "The MacBook Pro " beside another).
use_mac() {
  config=$1
  mac=$(mac_id "$config")
  name=$(mac_name "$config")
  who='The Mac '
  [ "$count" -gt 1 ] && who="The $name "
  local file=${config##*/}
  url=$(jq -r '.url // empty | tostring' "$config" 2>/dev/null) || die "$file could not be read."
  url=${url%/}
  # A host and a port and nothing after them: the paths asked for are this script's own.
  plain='^https?://[]A-Za-z0-9.:_[-]+$'
  [[ $url =~ $plain ]] || die "$file has no usable \"url\"."
  auth >/dev/null || die "$file has no usable \"key\"."
}

# One Mac's threads, each under the phone's id for it, with its name when there is more than one Mac.
list_one() {
  local reply code answer
  reply=$(call GET /threads) || die "$(away)"
  code=${reply##*$'\n'}
  answer=${reply%$'\n'*}
  [ "$code" = 200 ] || refused "$code" "$answer"
  printf '%s' "$answer" | jq -r --arg mac "$mac" --arg name "$name" --argjson many "$([ "$count" -gt 1 ] && echo true || echo false)" \
    '.threads[] | [(if $mac == "" then .id else $mac + "/" + (.id | tostring) end), .status, .name, .project] + (if $many then [$name] else [] end) | map(tostring) | join("\t")'
}

[ $# -ge 1 ] || usage
command=$1
shift
kind=
mode=
choose=
case $command in
  list | macs) [ $# -eq 0 ] || usage ;;
  new)
    [ $# -ge 2 ] && [ -n "$1" ] && [ -n "$2" ] || usage
    thread_name=$1 project=$2
    shift 2
    # The runner's own kinds; anything else would only come back as its 400.
    while [ $# -gt 0 ]; do
      case $#:$1:${2-} in
        *:--kind:claude | *:--kind:codex | *:--kind:grok | *:--kind:cursor | *:--kind:venice) [ -z "$kind" ] || usage; kind=$2; shift 2 ;;
        *:--mode:auto | *:--mode:manual) [ -z "$mode" ] || usage; mode=$2; shift 2 ;;
        *:--mac:?*) [ -z "$choose" ] || usage; choose=$2; shift 2 ;;
        *) usage ;;
      esac
    done ;;
  add-mac)
    [ $# -eq 1 ] || [ $# -eq 3 ] && [ "${2---name}" = --name ] || usage
    sent=$1
    given=${3-} ;;
  *) usage ;;
esac

if [ "$command" = add-mac ]; then
  [ -r "$sent" ] || die "No such file: $sent"
  # Read only for its address and whether it has a key: the key itself is never printed, here or by jq.
  url=$(jq -r '.url // empty | tostring' "$sent" 2>/dev/null) || die "$sent is not a runner's file."
  url=${url%/}
  [[ $url =~ ^https?://[]A-Za-z0-9.:_[-]+$ ]] || die "$sent has no usable \"url\"."
  config=$sent
  auth >/dev/null || die "$sent has no usable \"key\"."
  mkdir -p "$dir"
  target=
  while read -r file; do
    [ "$(jq -r '.url // empty | tostring' "$file" 2>/dev/null | sed 's#/$##')" = "$url" ] && { target=$file; break; }
  done < <(mac_files)
  if [ -n "$target" ]; then
    named=$(mac_name "$target")
  else
    named=${given:-$(jq -r '.name // empty | tostring' "$sent" 2>/dev/null | head -c 32)}
    [ -n "$named" ] || die "Say what this Mac is called: oma threads add-mac $sent --name 'MacBook Pro'"
    id=$(printf '%s' "$named" | tr 'A-Z' 'a-z' | sed 's/[^a-z0-9]\{1,\}/-/g; s/^-*//; s/-*$//' | cut -c1-32)
    [[ $id =~ ^[a-z0-9][a-z0-9-]{0,31}$ ]] || die "\"$named\" makes no usable id; give a plainer --name."
    target=$dir/mac-runner-$id.json
    [ -e "$target" ] && die "A Mac called $named ($target) is already set up at another address."
    # With no first Mac yet, this one becomes it.
    [ -n "$(mac_files)" ] || target=$dir/mac-runner.json
  fi
  fresh=$target.new
  ( umask 077 && jq --arg name "$named" '{url: (.url | tostring | sub("/$"; "")), key: .key, name: $name}' "$sent" > "$fresh" ) || { rm -f "$fresh"; die 'The file could not be written; nothing changed.'; }
  mv -f "$fresh" "$target" && rm -f "$sent"
  echo "Set up the $named at $url ($(basename "$target")). Its key is in place and was not printed; $sent was removed."
  exit 0
fi

# A loop, not mapfile: the Macs run bash 3.2, which has none.
files=()
while IFS= read -r file; do files+=("$file"); done < <(mac_files)
count=${#files[@]}
[ "$count" -gt 0 ] || die 'No Mac is set up on this phone: ~/.config/omalauncher/mac-runner.json is missing.'

case $command in
  macs)
    for file in "${files[@]}"; do
      id=$(mac_id "$file")
      printf '%s\t%s\t%s\n' "$(mac_name "$file")" "${id:-first}" "$(jq -r '.url // empty | tostring' "$file" 2>/dev/null)"
    done ;;
  list)
    if [ "$count" -eq 1 ]; then
      use_mac "${files[0]}"
      list_one
      exit
    fi
    # Each Mac on its own: one out of reach or refusing is said, and the others are listed all the same.
    listed=0
    for file in "${files[@]}"; do
      ( use_mac "$file"; list_one ) && listed=1
    done
    [ "$listed" = 1 ] || exit 1 ;;
  new)
    target=
    if [ -z "$choose" ]; then
      target=${files[0]}
    else
      for file in "${files[@]}"; do
        id=$(mac_id "$file")
        lower=$(printf '%s' "$choose" | tr 'A-Z' 'a-z')
        [ "$lower" = "${id:-first}" ] || [ "$lower" = "$(mac_name "$file" | tr 'A-Z' 'a-z')" ] && { target=$file; break; }
      done
      [ -n "$target" ] || die "No Mac called \"$choose\" is set up; oma threads macs lists them."
    fi
    use_mac "$target"
    request=$(jq -cn --arg name "$thread_name" --arg project "$project" --arg kind "$kind" --arg mode "$mode" \
      '{name: $name, project: $project} + (if $kind == "" then {} else {kind: $kind} end) + (if $mode == "" then {} else {mode: $mode} end)')
    reply=$(call POST /threads "$request") || die "$(away) Nothing was made."
    code=${reply##*$'\n'}
    answer=${reply%$'\n'*}
    [ "$code" = 200 ] || refused "$code" "$answer"
    printf '%s' "$answer" | jq -er --arg mac "$mac" --arg on "$([ "$count" -gt 1 ] && printf ' on the %s' "$name")" \
      '.thread | "Made thread \"" + (.name | tostring) + "\", id " + (if $mac == "" then .id | tostring else $mac + "/" + (.id | tostring) end) + ", in " + (.project | tostring) + $on + "."' ||
      die "${who}answered, but not with a thread." ;;
esac
