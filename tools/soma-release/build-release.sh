#!/bin/zsh -l
# The latest desktop build, for the update pill: every suite on this commit, then the Mac app (signed, when this Mac names
# somaSignIdentity) and the Linux jar, then soma-release publish. A suite that fails stops it here and nothing is published.
# Run it in Boss's GUI session (the signing key is in his login Keychain): on the Mac Pro, a one-shot gui/501 launchd job.
#   tools/soma-release/build-release.sh [CHECKOUT]
set -u
repo=${1:-${0:A:h:h:h}}
cd $repo || exit 1
if [[ -n $(git status --porcelain) ]]; then echo "the checkout has changes; only a committed build is published"; exit 1; fi
head=$(git rev-parse HEAD)
commit=${head[1,8]}
out=$repo/build/soma-release/$commit
rm -rf $out && mkdir -p $out
suites=()
suite() {  # NAME COMMAND...: runs it, logs it, and stops everything if it fails
  local name=$1; shift
  echo "== $name"
  "$@" > $out/$name.log 2>&1
  local code=$?
  suites+=("{\"name\": \"$name\", \"exit\": $code}")
  if (( code != 0 )); then echo "$name failed (exit $code); see $out/$name.log. Nothing was published."; exit 1; fi
}
suite desktop ./gradlew --no-daemon --no-configuration-cache :chat:desktopTest :desktop:test
for t in tools/mac-runner/test-*.py; do suite runner-${${t:t}%.py} python3 $t; done
suite soma-release python3 tools/soma-release/test-soma-release.py
print -r -- "{\"commit\": \"$head\", \"suites\": [${(j:, :)suites}]}" > $out/receipt.json

echo "== build"
./gradlew --no-daemon --no-configuration-cache :desktop:createDistributable > $out/app.log 2>&1 || { echo "the Mac app didn't build; see $out/app.log"; exit 1; }
app=desktop/build/compose/binaries/main/app/Soma.app
codesign --verify --deep --strict $app 2>> $out/app.log || { echo "the Mac app's signature doesn't verify; see $out/app.log"; exit 1; }
# Signed by this Mac's identity (somaSignIdentity), never ad hoc: every Mac's Keychain "Always Allow" for its runner keys rests on it.
codesign -dv $app 2>&1 | grep -q '^TeamIdentifier=[A-Z0-9]' || { echo "the Mac app is signed ad hoc (no somaSignIdentity here?); nothing was published"; exit 1; }
ditto -c -k --keepParent $app $out/Soma.zip
./gradlew --no-daemon --no-configuration-cache -PsomaOs=linux-x64 :desktop:packageUberJarForCurrentOS > $out/jar.log 2>&1 || { echo "the Linux jar didn't build; see $out/jar.log"; exit 1; }
jar=$(ls -t desktop/build/compose/jars/*.jar | head -1)
python3 tools/soma-release/soma-release publish --repo $repo --receipt $out/receipt.json \
  --artifact macos-$(uname -m | sed 's/x86_64/x64/')=$out/Soma.zip --artifact linux-x64=$jar
