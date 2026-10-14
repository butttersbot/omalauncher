#!/bin/bash
# Tests of `oma` (app/src/main/assets/oma.sh), the dispatcher of the launcher's small guest tools. Run in the guest
# with `bash app/src/test/shell/test_oma.sh`; OmaCommandTest runs it too. The dispatcher is laid out in a temporary
# folder as it is installed (bin/oma beside lib/oma), first with stand-ins that print what they were given, then
# with the real tools: nothing reaches a network, and the only keeper is one under a temporary OMA_KEEP_DIR.
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
for tool in thread keep agents; do
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

[ "$failed" = 0 ] && echo 'All passed.'
exit "$failed"
