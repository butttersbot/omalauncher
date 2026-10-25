#!/bin/bash
# Tests of `oma` (app/src/main/assets/oma.sh), the dispatcher of the launcher's small guest tools. Run in the guest
# with `bash app/src/test/shell/test_oma.sh`; OmaCommandTest runs it too. The dispatcher is laid out in a temporary
# folder as it is installed (bin/oma beside lib/oma), first with stand-ins that print what they were given, then
# with the real tools: nothing reaches a network, and the only keeper is one under a temporary OMA_KEEP_DIR. `oma drop`
# runs against stand-ins for adb, oma-dev and curl put first on PATH: the phone they show is a temporary folder.
set -u -o pipefail
assets="$(cd "$(dirname "$0")/../../main/assets" && pwd)"
root=$(mktemp -d "${TMPDIR:-/tmp}/oma-test-XXXXXX")
keeper=
cleanup() {
  [ -n "$keeper" ] && kill "$keeper" 2>/dev/null && wait "$keeper" 2>/dev/null
  rm -rf "$root"
}
trap cleanup EXIT
failed=0

# expect NAME CODE OUT ERR_PART -- COMMAND...: COMMAND exits with CODE, prints exactly OUT and an error holding ERR_PART.
expect() {
  local name=$1 code=$2 out=$3 err=$4
  shift 5
  local got_out got_err got_code
  got_out=$("$@" 2>"$root/err")
  got_code=$?
  got_err=$(cat "$root/err")
  if [ "$got_code" != "$code" ] || [ "$got_out" != "$out" ] || [[ $got_err != *"$err"* ]]; then
    printf 'FAIL %s\n  exit %s (wanted %s)\n  out: %s\n  wanted: %s\n  err: %s\n  wanted in it: %s\n' \
      "$name" "$got_code" "$code" "$got_out" "$out" "$got_err" "$err"
    failed=1
  else
    echo "ok   $name"
  fi
}

mkdir -p "$root/bin" "$root/lib/oma" "$root/home"
cp "$assets/oma.sh" "$root/bin/oma"
chmod 755 "$root/bin/oma"
for tool in thread keep agents drop; do
  printf '#!/bin/sh\necho "%s $*"\n' "$tool" >"$root/lib/oma/$tool"
  chmod 755 "$root/lib/oma/$tool"
done
oma="$root/bin/oma"

usage_head='usage: oma threads [list]'
help=$("$oma" help)
[[ $help == "$usage_head"* ]] || { echo "FAIL help does not start with the usage: $help"; failed=1; }
expect 'help prints the usage' 0 "$help" '' -- "$oma" help
expect '--help is help' 0 "$help" '' -- "$oma" --help
expect 'no subcommand is the usage, on the error' 2 '' "$usage_head" -- "$oma"
expect 'an unknown subcommand is named, then the usage' 2 '' 'oma: no subcommand "frob".' -- "$oma" frob x
expect 'an old name is no subcommand' 2 '' "$usage_head" -- "$oma" oma-thread list
expect 'threads alone lists' 0 'thread list' '' -- "$oma" threads
expect 'threads list' 0 'thread list' '' -- "$oma" threads list
expect 'threads new passes every word' 0 'thread new FF4 mod ~/p --kind codex' '' -- "$oma" threads new 'FF4 mod' '~/p' --kind codex
expect 'keep passes every word' 0 'keep run web -- python3 -m http.server 8765' '' -- "$oma" keep run web -- python3 -m http.server 8765
expect 'keep alone is the keeper'"'"'s own usage' 0 'keep ' '' -- "$oma" keep
expect 'status asks the status reader' 0 'agents status' '' -- "$oma" status
expect 'status passes more words on, to be refused there' 0 'agents status --kill' '' -- "$oma" status --kill
expect 'drop passes every word' 0 'drop send a b --to mac' '' -- "$oma" drop send a b --to mac

# The real tools behind it.
cp "$assets/oma-thread.sh" "$root/lib/oma/thread"
cp "$assets/oma-keep.py" "$root/lib/oma/keep"
cp "$assets/oma-agents.sh" "$root/lib/oma/agents"
chmod 755 "$root/lib/oma/"*
export HOME="$root/home" OMA_KEEP_DIR="$root/keep"
expect 'threads, with no Mac set up, asks nothing' 1 '' 'No Mac is set up on this phone' -- "$oma" threads
expect 'threads new with a kind it does not know is its usage' 2 '' 'usage: oma threads list' -- "$oma" threads new n p --kind gemini
expect 'status before the launcher wrote anything' 1 \
  'Nothing written yet: the launcher writes this while the builder works and whenever Oma is asked something.' '' -- "$oma" status
expect 'status takes no more words' 2 '' 'usage: oma status' -- "$oma" status --kill
mkdir -p "$HOME/.cache/oma-agents"
printf '{"builder": {}}\n' >"$HOME/.cache/oma-agents/status.json"
status=$("$oma" status)
[[ $status == 'Written '*' s ago; times in it are minutes before then.'$'\n''{"builder": {}}' ]] ||
  { echo "FAIL status prints the file: $status"; failed=1; }
if command -v python3 >/dev/null; then
  expect 'keep with no keeper says so' 1 '' 'oma keep:' -- "$oma" keep list
  expect 'keep with a bad name is its usage' 2 '' 'oma keep: a name is' -- "$oma" keep stop BAD
  "$oma" keep serve 2>/dev/null &
  keeper=$!
  for _ in $(seq 100); do [ -S "$OMA_KEEP_DIR/keep.sock" ] && break; sleep 0.05; done
  expect 'keep serve answers keep list' 0 'Nothing is kept.' '' -- "$oma" keep list
  socket_mode=$(stat -c %a "$OMA_KEEP_DIR/keep.sock")
  [ "$socket_mode" = 600 ] || { echo "FAIL the keeper's socket is $socket_mode, not 600"; failed=1; }
else
  echo 'skip keep: no python3'
fi

# `oma drop`, with the phone's adb, oma-dev and the Mac's runner stood in for. The phone is $phone: its port is in
# $phone/port, its shared storage under $phone/sdcard. Every call is written to $calls; what curl reads on its
# standard input (where the key belongs) to $root/curl-stdin.
cp "$assets/oma-drop.sh" "$root/lib/oma/drop"
chmod 755 "$root/lib/oma/drop"
phone="$root/phone"
calls="$root/calls"
mkdir -p "$root/fake" "$phone/sdcard/Download/Taildrop" "$HOME/.config/oma-development" "$HOME/.config/omalauncher"
echo 41234 >"$phone/port"
cat >"$root/fake/adb" <<'FAKE'
#!/bin/bash
printf 'adb %s\n' "$*" >>"$CALLS"
online="127.0.0.1:$(cat "$PHONE/port")"
case $1 in
  start-server | connect) exit 0 ;;
  -s) [ "$2" = "$online" ] || { echo "error: device '$2' not found" >&2; exit 1; } ;;
  *) exit 1 ;;
esac
case $3 in
  get-state) echo device ;;
  # Only a path that starts a word is the phone's: one already under $PHONE (as waiting() lists them) is left as it is.
  shell) cmd=${4//\'\/sdcard/\'$PHONE/sdcard}; exec sh -c "${cmd// \/sdcard/ $PHONE/sdcard}" ;;
  pull) exec cp -- "${4/#\/sdcard/$PHONE/sdcard}" "$5" ;;
  *) exit 1 ;;
esac
FAKE
cat >"$root/fake/oma-dev" <<'FAKE'
#!/bin/sh
echo "oma-dev $*" >>"$CALLS"
cp "$PHONE/port" "$HOME/.config/oma-development/install-port"
FAKE
cat >"$root/fake/curl" <<'FAKE'
#!/bin/bash
printf 'curl %s\n' "$*" >>"$CALLS"
# The phone bridge (Oma itself, which reads Taildrop's folders with All files access): stood in for here.
if [[ " $* " == *" --abstract-unix-socket "* ]]; then
  [ -n "${BRIDGE_DOWN-}" ] && exit 7
  [ -n "${NO_FILES_ACCESS-}" ] && { echo '{"ok":false,"error":"Oma has no All files access"}'; exit 0; }
  data=
  while [ $# -gt 0 ]; do [ "$1" = --data-binary ] && data=$2; shift; done
  list=$(jq -r '.list // false' <<<"$data"); to=$(jq -r '.to // ""' <<<"$data"); keep=$(jq -r '.keep // false' <<<"$data")
  out='[]'
  for d in "$PHONE/sdcard/Download/Taildrop" "$PHONE/sdcard/Taildrop"; do
    for f in "$d"/*; do
      [ -f "$f" ] && [[ $f != *.partial ]] || continue
      n=${f##*/} b=$(stat -c %s "$f") p= st=listed
      if [ "$list" != true ]; then
        p="$to/$n"; [ -e "$p" ] && p="$to/${n%.*} (2).${n##*.}"
        cp "$f" "$p"
        if [ "$keep" = true ]; then st=copied; else rm "$f"; st=moved; fi
      fi
      out=$(jq -c --arg n "$n" --argjson b "$b" --arg p "$p" --arg st "$st" '. + [{name: $n, bytes: $b, state: $st} + (if $p == "" then {} else {path: $p} end)]' <<<"$out")
    done
  done
  jq -cn --argjson f "$out" '{ok: true, files: $f}'
  exit 0
fi
cat >>"$CURL_STDIN"
[ -n "${MAC_DOWN-}" ] && exit 7
url=${!#}
upload=
while [ $# -gt 0 ]; do [ "$1" = --upload-file ] && upload=$2; shift; done
case $url in
  */health) printf '%s\n200' "$HEALTH" ;;
  */files\?name=*)
    name=${url#*name=}
    printf '{"ok":true,"path":"/Users/boss/Downloads/From Fold/%b","bytes":%d}\n200' "${name//%/\\x}" "$(stat -c %s "$upload")" ;;
  *) printf '{"ok":false}\n404' ;;
esac
FAKE
chmod 755 "$root/fake/"*
export PATH="$root/fake:$PATH" PHONE="$phone" CALLS="$calls" CURL_STDIN="$root/curl-stdin" HEALTH='{"ok":true,"files":true}'
taildrop="$phone/sdcard/Download/Taildrop"
drop_usage='usage: oma drop get [--to DIR] [--keep]'

expect 'drop help is its usage' 0 "$("$oma" drop help)" '' -- "$oma" drop help
[[ $("$oma" drop help) == "$drop_usage"* ]] || { echo 'FAIL drop help does not start with its usage'; failed=1; }
expect 'drop alone is its usage' 2 '' "$drop_usage" -- "$oma" drop
expect 'drop with a word it does not know is its usage' 2 '' "$drop_usage" -- "$oma" drop fetch
expect 'drop get with an option it does not know is its usage' 2 '' "$drop_usage" -- "$oma" drop get --all
expect 'drop get --to needs a folder' 2 '' "$drop_usage" -- "$oma" drop get --to
expect 'drop list takes no more words' 2 '' "$drop_usage" -- "$oma" drop list x
expect 'drop send needs a file' 2 '' "$drop_usage" -- "$oma" drop send --to mac
expect 'drop send goes only to the Mac' 2 '' "$drop_usage" -- "$oma" drop send a --to pc
[ -e "$calls" ] && { echo "FAIL a wrong command reached adb or curl: $(cat "$calls")"; failed=1; }

echo 1 >"$HOME/.config/oma-development/install-port"
expect 'drop list with a stale port asks oma-dev once, then finds nothing' 0 'Nothing waiting.' '' -- "$oma" drop list
[ "$(grep -c '^oma-dev' "$calls")" = 1 ] && grep -qx 'oma-dev --status' "$calls" || { echo "FAIL oma-dev was not asked once: $(cat "$calls")"; failed=1; }
expect 'drop get with nothing waiting' 0 'Nothing waiting.' '' -- "$oma" drop get
[ "$(grep -c '^oma-dev' "$calls")" = 1 ] || { echo 'FAIL oma-dev was asked again with a good port'; failed=1; }
rm -r "$taildrop"
expect 'drop get with no Taildrop folder yet' 0 'Nothing waiting.' '' -- "$oma" drop get
mkdir -p "$taildrop"

printf 'hello' >"$taildrop/it's a photo.jpg"
head -c 2500 /dev/zero >"$taildrop/notes"
printf 'half' >"$taildrop/arriving.zip.partial"
expect 'drop list shows each file and its size, not one still arriving' 0 $'it\'s a photo.jpg\t5 B\nnotes\t2.5 KB' '' -- "$oma" drop list
expect 'drop get --keep copies and leaves them' 0 \
  "Copied it's a photo.jpg (5 B) to $root/kept/it's a photo.jpg; it stays on the phone."$'\n'"Copied notes (2.5 KB) to $root/kept/notes; it stays on the phone." '' -- \
  "$oma" drop get --keep --to "$root/kept"
[ -e "$taildrop/notes" ] && [ "$(cat "$root/kept/it's a photo.jpg")" = hello ] || { echo 'FAIL --keep did not copy and keep'; failed=1; }
mkdir -p "$HOME/Inbox"
printf 'older' >"$HOME/Inbox/it's a photo.jpg"
expect 'drop get moves them into ~/Inbox, never over a file there' 0 \
  "Moved it's a photo.jpg (5 B) to $HOME/Inbox/it's a photo (2).jpg."$'\n'"Moved notes (2.5 KB) to $HOME/Inbox/notes." '' -- "$oma" drop get
[ "$(cat "$HOME/Inbox/it's a photo.jpg")" = older ] && [ "$(cat "$HOME/Inbox/it's a photo (2).jpg")" = hello ] && [ "$(stat -c %s "$HOME/Inbox/notes")" = 2500 ] ||
  { echo 'FAIL the files in ~/Inbox are not what was sent'; failed=1; }
[ "$(ls "$taildrop")" = arriving.zip.partial ] || { echo "FAIL the phone still holds: $(ls "$taildrop")"; failed=1; }

# Wireless debugging off: the port written is stale and oma-dev finds no other.
echo 1 >"$HOME/.config/oma-development/install-port"
echo 99999 >"$phone/port"
printf '#!/bin/sh\necho "oma-dev $*" >>"$CALLS"\n' >"$root/fake/oma-dev"
expect 'drop get with Wireless debugging off and nothing waiting asks Oma' 0 'Nothing waiting.' '' -- "$oma" drop get
grep -q -- '--abstract-unix-socket dev.omalauncher.boss.phone' "$calls" || { echo "FAIL Oma was not asked on the phone bridge: $(cat "$calls")"; failed=1; }
printf 'apk' >"$taildrop/soma-abc-900.apk"
printf 'part' >"$taildrop/soma-abc-900.json.partial"
expect 'drop list with Wireless debugging off lists through Oma' 0 $'soma-abc-900.apk\t3 B' '' -- "$oma" drop list
expect 'drop get with Wireless debugging off still receives, through Oma' 0 "Moved soma-abc-900.apk (3 B) to $HOME/Inbox/soma-abc-900.apk." '' -- "$oma" drop get
[ "$(cat "$HOME/Inbox/soma-abc-900.apk")" = apk ] && [ ! -e "$taildrop/soma-abc-900.apk" ] && [ -e "$taildrop/soma-abc-900.json.partial" ] ||
  { echo "FAIL the bridge route did not move the file: $(ls "$taildrop" "$HOME/Inbox")"; failed=1; }
rm -f "$taildrop/soma-abc-900.json.partial"
printf 'x' >"$taildrop/later.txt"
NO_FILES_ACCESS=1 expect 'drop get without adb or All files access says what to allow' 1 '' 'All files access' -- "$oma" drop get
BRIDGE_DOWN=1 expect 'drop get with neither adb nor Oma says so' 1 '' 'Is Oma running?' -- "$oma" drop get
[ -e "$taildrop/later.txt" ] || { echo 'FAIL a failed receive removed the file from the phone'; failed=1; }
rm -f "$taildrop/later.txt"

: >"$calls"
expect 'drop send with no Mac set up asks nothing' 1 '' 'No Mac is set up on this phone' -- "$oma" drop send "$HOME/Inbox/notes"
key='SECRET-key-0123456789abcdefghijklmnop'
printf '{"url": "http://mac.test:8478", "key": "%s"}\n' "$key" >"$HOME/.config/omalauncher/mac-runner.json"
expect 'drop send of a file that is not there sends nothing' 1 '' 'is not a file that can be read. Nothing was sent.' -- "$oma" drop send "$HOME/Inbox/notes" "$root/missing"
[ -s "$calls" ] && { echo "FAIL a send with a missing file called: $(cat "$calls")"; failed=1; }
expect 'drop send puts each file on the Mac and says where' 0 \
  "Sent it's a photo (2).jpg (5 B) to the Mac: /Users/boss/Downloads/From Fold/it's a photo (2).jpg"$'\n'"Sent notes (2.5 KB) to the Mac: /Users/boss/Downloads/From Fold/notes" '' -- \
  "$oma" drop send "$HOME/Inbox/it's a photo (2).jpg" "$HOME/Inbox/notes" --to mac
grep -q "^curl .*--upload-file $HOME/Inbox/notes .*http://mac.test:8478/files?name=notes\$" "$calls" &&
  grep -q -- '--config -' "$calls" || { echo "FAIL the upload was not streamed with the config on stdin: $(cat "$calls")"; failed=1; }
grep -qF "$key" "$calls" && { echo 'FAIL the key was on a command line'; failed=1; }
[ "$(grep -cxF "header = \"Authorization: Bearer $key\"" "$root/curl-stdin")" = 3 ] || { echo 'FAIL curl did not read the key on its standard input'; failed=1; }
HEALTH='{"ok":true}' expect 'drop send to an old runner says to install the new one' 1 '' 'too old for files; run oma-runner install there' -- "$oma" drop send "$HOME/Inbox/notes"
MAC_DOWN=1 expect 'drop send with the Mac unreachable says so and points to Quick Share' 1 '' 'Quick Share' -- "$oma" drop send "$HOME/Inbox/notes"
grep -q '/files' <(tail -n 2 "$calls") && { echo 'FAIL a file was sent to a runner that could not take it'; failed=1; }

[ "$failed" = 0 ] && echo 'All passed.'
exit "$failed"
