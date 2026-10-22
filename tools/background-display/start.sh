#!/bin/bash
# Run with `oma keep run background-display -- bash /root/Projects/soma-bgdisplay/start.sh ADDRESS:PORT`.
set -euo pipefail
at=${1:?Paired wireless-debugging address required}
[[ $at =~ ^127\.0\.0\.1:[0-9]{4,5}$ ]] || { echo 'Use this phone’s loopback debugging address.' >&2; exit 2; }
adb connect "$at" >/dev/null
[[ $(adb -s "$at" shell getprop ro.serialno | tr -d '\r\n') == R5GL85HYY6E ]] || { echo 'Not the configured Fold.' >&2; exit 1; }
uid=$(adb -s "$at" shell cmd package list packages -U dev.omalauncher.boss | sed -n 's/.*uid:\([0-9]*\).*/\1/p' | tr -d '\r\n')
[[ $uid =~ ^[0-9]+$ ]] || { echo 'Soma UID unavailable.' >&2; exit 1; }
exec adb -s "$at" shell "CLASSPATH=/data/local/tmp/soma-display-v3.dex app_process / SomaDisplay 1248 1972 420 $uid"
