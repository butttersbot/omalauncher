#!/bin/bash
set -euo pipefail
cd /root/Projects/soma-bgdisplay
mkdir -p evidence
phase=${1:?unlocked or locked}
oma-phone --display bg status > "evidence/$phase-status.json"
jq -e '.ok and .active and .connected and .backgroundAvailable and (.displayId > 0)' "evidence/$phase-status.json" >/dev/null
if [[ $phase == unlocked ]]; then
  oma-phone --display bg open settings > evidence/open-settings.json
  printf 'screen\nexpect Settings\n' | oma-phone --display bg run - > evidence/run.json
  echo 'PASS: CLI background app launch and batch screen/expect'
else
  jq -e '.locked == true and .awake == false' evidence/locked-status.json >/dev/null
  if oma-phone screen > evidence/main-locked-refusal.json; then echo 'FAIL: main display accepted a locked read'; exit 1; fi
  jq -e '.ok == false and (.error | test("locked"))' evidence/main-locked-refusal.json >/dev/null
  oma-phone --display bg press Search > evidence/locked-press.json
  oma-phone --display bg fill '' soma-background-probe > evidence/locked-fill.json
  oma-phone --display bg submit > evidence/locked-submit.json
  oma-phone --display bg swipe 800 1500 800 600 300 > evidence/locked-swipe.json
  echo 'PASS: main locked read refused; hidden display press, fill, submit and gesture accepted'
fi
oma-phone --display bg screen > "evidence/$phase-screen.json"
oma-phone --display bg read > "evidence/$phase-read.json"
jq -e '.ok and (.nodes|length > 0)' "evidence/$phase-read.json" >/dev/null
oma-phone --display bg screenshot > "evidence/$phase-screenshot.json"
jq -e '.ok and .width > 0 and .height > 0' "evidence/$phase-screenshot.json" >/dev/null
oma-phone status > "evidence/$phase-after.json"
if [[ $phase == locked ]]; then jq -e '.locked == true and .awake == false' evidence/locked-after.json >/dev/null; fi
echo "PASS: $phase background read and screenshot; main lock state preserved"
