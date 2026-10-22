#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
sdk=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
mkdir -p out
javac --release 11 -classpath "$sdk/platforms/android-35/android.jar" -d out SomaDisplay.java Probe.java LoopbackProbe.java
"$sdk/build-tools/36.0.0/d8" --min-api 31 --lib "$sdk/platforms/android-35/android.jar" --output out out/*.class
