#!/bin/sh
# SOURCE CHECKPOINT ONLY. No privilege grant until the helper independently enforces owner signatures.
# Fixed future path: /Library/PrivilegedHelperTools/soma-service-helper
# Fixed service inventory: tailshare-serve tailshare-receive herd embeddings whisper tailscaled
set -eu
case "${1:-}" in
  print-sudoers)
    case "${2:-}" in ''|*[!a-z0-9_.-]*) echo "a plain user name is needed" >&2; exit 64 ;; esac ;;
  install|remove) ;;
  *) echo "usage: install-service-helper.sh install|remove|print-sudoers USER" >&2; exit 64 ;;
esac
echo "blocked: unsigned SERVICE sudo authority is not installable; owner-verifying helper required" >&2
exit 78
