#!/bin/sh
# Omalauncher: `oma status`, which prints how the phone's builder and the project threads on the Mac stand, as the launcher last
# wrote it to ~/.cache/oma-agents/status.json (while the builder works, every minute; whenever Oma is asked
# something; and whenever what the phone knows of the Mac's threads changes). It only reads that file: it asks nothing of the Mac and changes nothing, so the front agent may
# run it. The file holds no key, no nonce and nothing of an approval but how many wait.
status="${HOME:?}/.cache/oma-agents/status.json"

if [ "$#" -ne 1 ] || [ "$1" != status ]; then
  echo "usage: oma status" >&2
  exit 2
fi
if [ ! -s "$status" ]; then
  echo "Nothing written yet: the launcher writes this while the builder works and whenever Oma is asked something."
  exit 1
fi
echo "Written $(( $(date +%s) - $(date -r "$status" +%s) )) s ago; times in it are minutes before then."
cat "$status"
