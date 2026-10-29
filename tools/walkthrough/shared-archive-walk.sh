#!/bin/zsh
# Shared archive [emu] walk: the emulator's real Soma against a test runner on this Mac, archived and restored the way a
# desktop Soma does it. Holds the walks lock throughout; leaves the emulator as it found it (the test runner's file goes).
set -u
REPO=${0:A:h:h:h}
OUT=${1:-/tmp/walk-job/shared-archive}
WORK=$(mktemp -d /tmp/shared-archive-walk.XXXX)
PORT=$(python3 -I -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])')
KEY=$(python3 -I -c 'import secrets;print(secrets.token_urlsafe(32))')
SERIAL=emulator-5554
CONFIG=/data/data/dev.omalauncher.boss/files/arch/root/.config/omalauncher/mac-runner-walktest.json
python3 -I $REPO/tools/fold-development/soma-lanes hold $HOME/.local/state/soma-macbuild/walks.lock 3600 $$ shared-archive-$$ claude-shared-archive & HOLD=$!
# Waits its turn: another lane's walk finishes first.
until rg -q "\"pid\": $$" $HOME/.local/state/soma-macbuild/walks.lock 2>/dev/null; do
  kill -0 $HOLD 2>/dev/null || { echo "waited an hour for the emulator in vain"; exit 1; }
  sleep 5
done
cleanup() {
  adb -s $SERIAL shell run-as dev.omalauncher.boss rm -f $CONFIG
  kill ${RUNNER:-0} 2>/dev/null; kill $HOLD 2>/dev/null; rm -rf $WORK
}
trap cleanup EXIT
# The test runner: the emulator reaches this Mac's loopback as 10.0.2.2, and the runner sees it as 127.0.0.1.
mkdir -p $WORK/Projects
python3 -I -c "import hashlib,json,sys;print(json.dumps({'devices':[{'name':'walkfold','digest':'sha256:'+hashlib.sha256(sys.argv[1].encode()).hexdigest(),'added':0}]}))" $KEY > $WORK/keys.json
echo '{"127.0.0.1": "walkfold"}' > $WORK/whois.json
M=$REPO/tools/mac-runner
env OMA_RUNNER_PORT=$PORT OMA_RUNNER_BIND=127.0.0.1 OMA_RUNNER_STATE=$WORK/state OMA_RUNNER_LOGS=$WORK/logs OMA_RUNNER_ROOTS=$WORK/Projects \
  OMA_RUNNER_KEYS=$WORK/keys.json OMA_RUNNER_TOKEN=$WORK/token OMA_RUNNER_WHOIS_FILE=$WORK/whois.json OMA_RUNNER_PHONE=walkfold \
  OMA_RUNNER_SELF=walktest OMA_RUNNER_MAIL=$WORK/mail OMA_RUNNER_CODEX_DISCOVER=0 OMA_RUNNER_CLAUDE_DISCOVER=0 OMA_RUNNER_AGENT=$M/fake-agent \
  OMA_RUNNER_CODEX=$M/fake-codex OMA_RUNNER_CURSOR=$M/fake-cursor OMA_RUNNER_GROK=$WORK/no-grok OMA_RUNNER_BOTS=$WORK/bots HOME=$WORK \
  python3 -I $M/oma-runner serve > $WORK/runner.out 2>&1 & RUNNER=$!
sleep 2
call() { curl -s -X $1 -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' ${3:+-d "$3"} http://127.0.0.1:$PORT$2; }
TID=$(call POST /threads "{\"name\": \"Walk shared\", \"project\": \"$WORK/Projects\"}" | python3 -I -c 'import json,sys;print(json.load(sys.stdin)["thread"]["id"])')
call POST /threads/$TID/messages '{"text": "say hello"}' > /dev/null
# The emulator's Soma is given the test runner, as a pairing gives the phone a Mac (a test key, for this walk only).
printf '{"url": "http://10.0.2.2:%s", "key": "%s", "name": "Walk test"}' $PORT $KEY | adb -s $SERIAL shell run-as dev.omalauncher.boss sh -c "cat > $CONFIG"
adb -s $SERIAL shell run-as dev.omalauncher.boss ls $CONFIG > /dev/null || { echo "the emulator's Soma wasn't given the test runner"; exit 1; }
adb -s $SERIAL install -r $REPO/app/build/outputs/apk/debug/app-debug.apk | tail -1
adb -s $SERIAL shell cmd device_state state reset
rm -rf $OUT; mkdir -p $OUT
W=$REPO/tools/walkthrough
python3 -I $W/walk.py $W/scripts/shared-archive-1.walk --out $OUT/1 | tail -3
call POST /threads/$TID/archive '{"archived": true}' > /dev/null && echo "archived on the runner, as a desktop Soma does"
python3 -I $W/walk.py $W/scripts/shared-archive-2.walk --out $OUT/2 | tail -3
call POST /threads/$TID/archive '{"archived": false}' > /dev/null && echo "restored on the runner"
python3 -I $W/walk.py $W/scripts/shared-archive-3.walk --out $OUT/3 | tail -3
