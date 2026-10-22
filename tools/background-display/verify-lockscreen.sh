#!/bin/bash
set -euo pipefail
cd /root/Projects/soma-bgdisplay
oma-phone --display bg status > evidence/lockscreen-before.json
jq -e '.locked and .awake and .active' evidence/lockscreen-before.json >/dev/null
if oma-phone screen > evidence/lockscreen-main-refusal.json; then exit 1; fi
oma-phone --display bg screen > evidence/lockscreen-initial.json
printf 'press 2\npress +\npress 2\n' | oma-phone --display bg run - > evidence/lockscreen-run.json
oma-phone --display bg read > evidence/lockscreen-read.json
oma-phone --display bg screenshot > evidence/lockscreen-screenshot.json
oma-phone status > evidence/lockscreen-after.json
jq -e '.locked and .awake' evidence/lockscreen-after.json >/dev/null
python3 - <<'PY'
import json
x=json.load(open('evidence/lockscreen-read.json'))
print('Calculator nodes:', [{k:n.get(k) for k in ['id','text','description']} for n in x['nodes'] if n['id'] in ['root.1','root.2']])
assert any(n.get('text')=='4' and n.get('id')=='root.2' for n in x['nodes']), 'Calculator preview did not show 4'
PY
oma-phone --display bg press C > evidence/lockscreen-clear.json
echo 'PASS: main stayed locked, main read refused, hidden Calculator 2+2 previewed 4, screenshot succeeded; test input cleared.'
