#!/bin/sh
# soma-artifact (the phone's own): shows a file beside this conversation in Soma, as an artifact Boss can look at and use.
#
#   soma-artifact put FILE [--name NAME] [--title TITLE] [--note WHAT]   a new version (FILE "-" reads stdin; then --name)
#   soma-artifact editing NAME [--note WHAT]                            "working on it", before you start changing one
#   soma-artifact list                                                  this conversation's artifacts
#
# One self-contained file per artifact (HTML with its CSS and JS inline: it is shown offline). Each put of the same name is
# its next version; every version is kept. The same command as on the Macs (features/artifact-panel/PLAN.md, slice 3):
# here it writes the thread's own folder of the store, /root/.local/share/soma/artifacts/<thread>/<name>/vN.ext and
# meta.json, and adds a line to <thread>/events.jsonl, which Soma reads. Written by Soma; changes here are replaced.
set -eu

STORE="${SOMA_ARTIFACT_STORE:-/root/.local/share/soma/artifacts}"
MOST=4194304
KEEP=100
NAMES=100
EXTS='html|htm|md|markdown|txt|json|csv|diff|patch|css|js|ts|py|kt|xml|yaml|yml|svg|png|jpg|jpeg|webp|gif'

usage() { sed -n '4,6p' "$0" | sed 's/^# *//' >&2; }
fail() { printf 'soma-artifact: %s\n' "$1" >&2; exit "${2:-2}"; }
help() { sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }

command -v jq >/dev/null 2>&1 || fail "jq is missing from this Linux; Soma installs it with its core packages." 1
thread="${SOMA_THREAD:-}"
[ -n "$thread" ] || fail "this shell is not a Soma conversation (no SOMA_THREAD); only an agent Soma runs can show artifacts."
printf '%s' "$thread" | grep -Eq '^phone(-[a-z])?$' || fail "this conversation ($thread) can't keep artifacts here."
[ $# -gt 0 ] || help
verb="$1"; shift
case "$verb" in -h|--help|help) help ;; esac

name="" title="" note="" file=""
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) help ;;
        --name|--title|--note)
            [ $# -ge 2 ] || fail "$1 needs a value"
            case "$1" in --name) name="$2" ;; --title) title="$2" ;; --note) note="$2" ;; esac
            shift 2 ;;
        --name=*) name="${1#--name=}"; shift ;;
        --title=*) title="${1#--title=}"; shift ;;
        --note=*) note="${1#--note=}"; shift ;;
        --*) usage; fail "unknown option $1" ;;
        *) [ -z "$file" ] || { usage; fail "one FILE or NAME only"; }; file="$1"; shift ;;
    esac
done

good_name() {
    [ "${#1}" -le 80 ] && printf '%s' "$1" | grep -Eq "^[A-Za-z0-9][A-Za-z0-9_-]*(\.[A-Za-z0-9_-]+)*\.($EXTS)\$"
}
one_line() { printf '%s' "$1" | tr '\000-\037\177' ' ' | sed 's/  */ /g; s/^ //; s/ $//' | cut -c1-"$2"; }

folder="$STORE/$thread"
for part in "$STORE" "$folder"; do
    [ ! -L "$part" ] || fail "a link stands where the artifact store goes." 1
done
mkdir -p "$folder"
chmod 700 "$STORE" "$folder" 2>/dev/null || true

# One writer at a time in this thread's folder: a directory made atomically, taken back if its holder died long ago.
lock="$folder/.lock"
tries=0
until mkdir "$lock" 2>/dev/null; do
    tries=$((tries + 1))
    if [ "$tries" -gt 100 ]; then rmdir "$lock" 2>/dev/null || true; tries=0; fi
    sleep 0.1
done
trap 'rmdir "$lock" 2>/dev/null || true' EXIT INT TERM

meta_of() { if [ -f "$1/meta.json" ] && [ ! -L "$1/meta.json" ]; then jq -c 'if type == "object" and (.versions | type) == "array" then . else {versions: []} end' "$1/meta.json" 2>/dev/null || echo '{"versions":[]}'; else echo '{"versions":[]}'; fi; }
latest_of() { printf '%s' "$1" | jq '[.versions[] | select((.v | type) == "number") | .v] | max // 0'; }
count_names() { find "$folder" -mindepth 1 -maxdepth 1 -type d ! -name '.*' | wc -l | tr -d ' '; }
event() {  # state version title note
    jq -cn --arg name "$name" --arg state "$1" --argjson v "$2" --arg title "$3" --arg note "$4" --argjson time "$(date +%s)" \
        '{name: $name, version: $v, state: $state, title: $title, note: $note, time: $time}' >> "$folder/events.jsonl"
}
write_meta() { tmp="$1/.meta.$$"; printf '%s\n' "$2" > "$tmp" && chmod 600 "$tmp" && mv -f "$tmp" "$1/meta.json"; }

case "$verb" in
put)
    [ -n "$file" ] || { usage; fail "put takes one FILE"; }
    if [ "$file" = "-" ]; then
        [ -n "$name" ] || fail "reading stdin needs --name NAME.html"
        source="$folder/.in.$$"
        head -c $((MOST + 1)) > "$source"
    else
        [ -f "$file" ] && [ -r "$file" ] || fail "could not read $file"
        source="$file"
        [ -n "$name" ] || name="$(basename "$file")"
    fi
    good_name "$name" || fail "a name is letters, numbers, - _ . and one of these endings: $(printf '%s' "$EXTS" | tr '|' ' ')"
    size="$(wc -c < "$source" | tr -d ' ')"
    [ "$size" -gt 0 ] || { [ "$file" != "-" ] || rm -f "$source"; fail "that file is empty"; }
    [ "$size" -le "$MOST" ] || { [ "$file" != "-" ] || rm -f "$source"; fail "that file is over 4 MB, the most an artifact can be"; }
    place="$folder/$name"
    [ ! -L "$place" ] || fail "a link stands where this artifact goes." 1
    if [ ! -d "$place" ] && [ "$(count_names)" -ge "$NAMES" ]; then fail "this conversation already has $NAMES artifacts, the most it can keep" 1; fi
    mkdir -p "$place"; chmod 700 "$place" 2>/dev/null || true
    meta="$(meta_of "$place")"
    version=$(( $(latest_of "$meta") + 1 ))
    ext="${name##*.}"; ext="$(printf '%s' "$ext" | tr 'A-Z' 'a-z')"
    tmp="$place/.v$version.$$"
    cat "$source" > "$tmp"; chmod 600 "$tmp"; mv -f "$tmp" "$place/v$version.$ext"
    [ "$file" != "-" ] || rm -f "$source"
    sha="$(sha256sum "$place/v$version.$ext" | cut -c1-12)"
    title="$(one_line "$title" 80)"; note="$(one_line "$note" 200)"
    meta="$(printf '%s' "$meta" | jq -c --argjson v "$version" --argjson size "$size" --arg sha "$sha" --arg note "$note" --arg title "$title" \
        --arg name "$name" --argjson time "$(date +%s)" --argjson keep "$KEEP" \
        '.name = $name | .versions += [{v: $v, time: $time, size: $size, sha: $sha, note: $note}] | del(.editing)
         | (if $title != "" then .title = $title else . end)')"
    # Only the newest are kept; the oldest go, file and entry both.
    printf '%s' "$meta" | jq -r --argjson keep "$KEEP" '.versions[:-$keep][]?.v' | while read -r gone; do rm -f "$place/v$gone.$ext"; done
    meta="$(printf '%s' "$meta" | jq -c --argjson keep "$KEEP" '.versions |= .[-$keep:]')"
    write_meta "$place" "$meta"
    event ready "$version" "$(printf '%s' "$meta" | jq -r '.title // ""')" "$note"
    echo "Saved $name v$version. It shows beside the chat in Soma."
    ;;
editing)
    name="$file"
    [ -n "$name" ] || { usage; fail "editing takes one NAME"; }
    good_name "$name" || fail "a name is letters, numbers, - _ . and one of these endings: $(printf '%s' "$EXTS" | tr '|' ' ')"
    place="$folder/$name"
    [ ! -L "$place" ] || fail "a link stands where this artifact goes." 1
    if [ ! -d "$place" ] && [ "$(count_names)" -ge "$NAMES" ]; then fail "this conversation already has $NAMES artifacts, the most it can keep" 1; fi
    mkdir -p "$place"; chmod 700 "$place" 2>/dev/null || true
    meta="$(meta_of "$place")"
    version=$(( $(latest_of "$meta") + 1 ))
    meta="$(printf '%s' "$meta" | jq -c --arg name "$name" --argjson time "$(date +%s)" '.name = $name | .editing = {since: $time}')"
    write_meta "$place" "$meta"
    event editing "$version" "$(printf '%s' "$meta" | jq -r '.title // ""')" "$(one_line "$note" 200)"
    echo "Soma shows $name as being edited (v$version next) until you put it."
    ;;
list)
    found=0
    for place in "$folder"/*/; do
        [ -d "$place" ] && [ ! -L "${place%/}" ] || continue
        meta="$(meta_of "${place%/}")"
        [ "$(latest_of "$meta")" -gt 0 ] || continue
        found=1
        printf '%s' "$meta" | jq -r --arg name "$(basename "$place")" '"\($name)  v\([.versions[].v] | max)  \(.title // "")" + (if (.versions[-1].note // "") != "" then "  (\(.versions[-1].note))" else "" end)'
    done
    [ "$found" = 1 ] || echo "No artifacts in this conversation yet."
    ;;
*)
    usage; fail "unknown command $verb" ;;
esac
