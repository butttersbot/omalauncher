#!/bin/bash
# Omalauncher: `oma threads`, which lists the project threads on the Mac and makes a new one, by asking the runner on
# the Mac (docs/MAC-RUNNER-SPEC.md) with the address and key in ~/.config/omalauncher/mac-runner.json.
# The key is read by jq and piped to curl as a config on its standard input. It is never an argument of any
# command, never in a variable of this script and never in a file of its own, so nothing here can print it and
# `ps` cannot show it. The runner decides which folders may hold a thread; this makes no folder anywhere.
set -u -o pipefail
config="${HOME:?}/.config/omalauncher/mac-runner.json"

usage() {
  cat >&2 <<USAGE
usage: oma threads list
       oma threads new NAME PROJECT_PATH [--kind claude|codex|cursor]

list prints one line per thread on the Mac: id, state, name and project folder.
new makes a thread called NAME for the folder PROJECT_PATH on the Mac (~ is the
Mac's home there) and prints its id. The folder must already exist under one of
the roots the Mac allows; otherwise the Mac refuses and its roots are printed.
--kind chooses the agent that works in it; without it the Mac starts Claude.
USAGE
  exit 2
}

die() {
  echo "$1" >&2
  exit 1
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
    401) die "The Mac turned this phone's key down." ;;
    403)
      echo "The Mac refused: ${error:-not allowed}." >&2
      if printf '%s' "$answer" | jq -e '(.roots // []) | length > 0' >/dev/null 2>&1; then
        echo 'It takes an existing folder under one of:' >&2
        printf '%s' "$answer" | jq -r '.roots[] | "  " + tostring' >&2
      fi
      exit 1 ;;
    *) die "The Mac answered HTTP $code${error:+: $error}." ;;
  esac
}

[ $# -ge 1 ] || usage
command=$1
shift
case $command in
  list) [ $# -eq 0 ] || usage ;;
  new)
    [ $# -ge 2 ] && [ -n "$1" ] && [ -n "$2" ] || usage
    # The runner's own kinds; anything else would only come back as its 400.
    kind=
    case $#:${3-}:${4-} in
      2::) ;;
      4:--kind:claude | 4:--kind:codex | 4:--kind:cursor) kind=$4 ;;
      *) usage ;;
    esac ;;
  *) usage ;;
esac

[ -r "$config" ] || die 'No Mac is set up on this phone: ~/.config/omalauncher/mac-runner.json is missing.'
url=$(jq -r '.url // empty | tostring' "$config" 2>/dev/null) || die 'mac-runner.json could not be read.'
url=${url%/}
# A host and a port and nothing after them: the paths asked for are this script's own.
plain='^https?://[]A-Za-z0-9.:_[-]+$'
[[ $url =~ $plain ]] || die 'mac-runner.json has no usable "url".'
auth >/dev/null || die 'mac-runner.json has no usable "key".'

case $command in
  list)
    reply=$(call GET /threads) || die 'Mac unreachable.'
    code=${reply##*$'\n'}
    answer=${reply%$'\n'*}
    [ "$code" = 200 ] || refused "$code" "$answer"
    printf '%s' "$answer" | jq -r '.threads[] | [.id, .status, .name, .project] | map(tostring) | join("\t")' ;;
  new)
    request=$(jq -cn --arg name "$1" --arg project "$2" --arg kind "$kind" '{name: $name, project: $project} + if $kind == "" then {} else {kind: $kind} end')
    reply=$(call POST /threads "$request") || die 'Mac unreachable. Nothing was made.'
    code=${reply##*$'\n'}
    answer=${reply%$'\n'*}
    [ "$code" = 200 ] || refused "$code" "$answer"
    printf '%s' "$answer" | jq -er '.thread | "Made thread \"" + (.name | tostring) + "\", id " + (.id | tostring) + ", in " + (.project | tostring) + "."' ||
      die 'The Mac answered, but not with a thread.' ;;
esac
