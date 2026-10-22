#!/bin/bash
set -euo pipefail
cd /root/Projects/soma-bgdisplay
tools=/opt/android-sdk/build-tools/36.0.0
candidate=candidate.apk
signed=soma-background-116-signed.apk
# Ensure the Linux executable libraries are identical to the app being updated.
python3 - <<'PY'
import zipfile
with zipfile.ZipFile('before.apk') as old, zipfile.ZipFile('candidate.apk') as new:
    names=[n for n in old.namelist() if n.startswith('lib/arm64-v8a/') and n.endswith('.so') and ('proot' in n or 'talloc' in n or 'shmem' in n)]
    assert len(names) >= 4, 'Installed proot libraries not found'
    for name in names:
        assert old.read(name) == new.read(name), 'Linux library differs: ' + name
print('Installed Linux runtime libraries match candidate.')
PY
"$tools/apksigner" -JXmx256m sign --ks /root/.config/oma-development/working-debug.keystore \
  --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android --out "$signed" "$candidate"
cert=$("$tools/apksigner" -JXmx256m verify --print-certs "$signed" | sed -n 's/.*certificate SHA-256 digest: //p' | head -n 1)
[[ $cert == "$(tr -d '[:space:]' < /root/.config/oma-development/working-cert.sha256)" ]] || { echo 'Wrong signer'; exit 1; }
[[ $cert == 85a7a713578814a4b779ce4fa7d0ed13aa48a81b61cc8e91b139f32dad576a18 ]] || { echo 'Signer does not match installed app'; exit 1; }
badging=$("$tools/aapt2" dump badging "$signed")
printf '%s\n' "${badging%%$'\n'*}"
sha256sum "$signed"
echo SIGNED_AND_VERIFIED
