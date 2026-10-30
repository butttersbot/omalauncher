#!/bin/sh
# Installs what lets the phone's Restore work on this Mac. Boss runs it himself, with sudo; no agent ever does.
#   sudo sh install-service-helper.sh trust KEY.json KEY_ID                 the owner's public key, for any Restore on this Mac
#   sudo sh install-service-helper.sh install HOST KEY.json KEY_ID SHA256    that, and the root helper for this Mac's system services
#   sudo sh install-service-helper.sh remove                                 takes the helper's sudo rule and binary away; the key stays
#   sudo sh install-service-helper.sh untrust                                takes the owner's key away: no Restore on this Mac
#   sh install-service-helper.sh print-sudoers USER                          shows the one sudo rule
# KEY.json is the phone's export (owner-public-key.json), KEY_ID the fingerprint Boss read on the phone, SHA256
# soma-service-helper.swift's in the reviewed commit. Agents can write this folder and the key file's folder: run a root-owned
# copy of this script, after comparing its hash, and let the KEY_ID refuse a key that isn't the phone's.
# The sudo rule allows the helper with no arguments and nothing else, and the helper does nothing without the owner's
# signature for that exact restart. So nothing here gives this user any power of its own.
set -eu
PATH=/usr/bin:/bin:/usr/sbin:/sbin; export PATH
umask 022
HERE=$(cd "$(dirname "$0")" && pwd -P)
# Without root, only a test's own folder is touched: sudo drops this variable, so it is never honoured with root.
ROOT=""
if [ "$(id -u)" != 0 ]; then ROOT=${SOMA_INSTALL_TEST_ROOT:-}; fi
HELPER=/Library/PrivilegedHelperTools/soma-service-helper
SUPPORT="/Library/Application Support/Soma"
RULE=/etc/sudoers.d/soma-service-helper
die() { echo "install-service-helper: $1" >&2; exit "${2:-65}"; }
need_root() { [ "$(id -u)" = 0 ] || [ -n "$ROOT" ] || die "run this with sudo" 77; }
plain_user() { case "$1" in ''|root|[!a-z_]*|*[!a-z0-9_.-]*) die "a plain user name is needed, not root" 64 ;; esac; }
rule_for() { printf '%s ALL=(root) NOPASSWD: %s ""\n' "$1" "$HELPER"; }
place() {  # MODE FILE TARGET: root-owned, written whole
    mkdir -p "$(dirname "$ROOT$3")"
    if [ -z "$ROOT" ]; then chown root:wheel "$(dirname "$3")" 2>/dev/null || true; install -o root -g wheel -m "$1" "$2" "$3"
    else install -m "$1" "$2" "$ROOT$3"; fi
}
field() { plutil -extract "$2" raw -o - -- "$1" 2>/dev/null || die "the key file has no $2"; }

# The key file, checked here with nothing an agent could have changed: only plain base64 and hex pass into the JSON, and its id
# is worked out again from the key itself.
install_trust() {
    [ -f "$1" ] && [ ! -L "$1" ] || die "no key file at $1"
    [ "$(wc -c < "$1")" -le 4096 ] || die "the key file is too large"
    [ "$(field "$1" algorithm)" = ecdsa-p256-sha256 ] || die "not a P-256 key"
    public=$(field "$1" public_key)
    case "$public" in ''|*[!A-Za-z0-9+/=]*) die "the public key is not plain base64" ;; esac
    id=$(field "$1" key_id)
    case "$id" in *[!0-9a-f]*) die "the key id is not plain hex" ;; esac
    worked=$(printf '%s' "$public" | base64 -D | shasum -a 256 | cut -d' ' -f1)
    [ "$worked" = "$id" ] || die "the key id doesn't match the key"
    [ "$id" = "$2" ] || die "this key isn't the one you named; nothing was installed"
    file="$WORK/keys.json"
    printf '{"version": 1, "keys": [{"key_id": "%s", "algorithm": "ecdsa-p256-sha256", "public_key": "%s", "label": "Fold", "device": "fold", "added_at": %s000, "revoked": false}]}\n' \
        "$id" "$public" "$(date +%s)" > "$file"
    place 0644 "$file" "$SUPPORT/connections-owner-keys.json"
    echo "Owner key installed: $id"
}

fresh_work() {
    WORK=$(mktemp -d /tmp/soma-helper.XXXXXX)
    trap 'rm -rf "$WORK"' EXIT
    chmod 700 "$WORK"
}

mode=${1:-}
case "$mode" in
  print-sudoers)
    [ $# = 2 ] || die "usage: print-sudoers USER" 64
    plain_user "$2"; rule_for "$2" ;;
  trust)
    [ $# = 3 ] || die "usage: trust KEY.json KEY_ID" 64
    need_root; fresh_work
    install_trust "$2" "$3" ;;
  install)
    [ $# = 5 ] || die "usage: install HOST KEY.json KEY_ID SHA256" 64
    need_root; fresh_work
    user=${SUDO_USER:-${SOMA_INSTALL_TEST_USER:-}}
    plain_user "$user"
    host=$2
    case "$host" in ''|*[!a-z0-9-]*) die "HOST is this Mac's name as its runner says it" 64 ;; esac
    # The very bytes that were hashed are the ones built.
    [ -f "$HERE/soma-service-helper.swift" ] && [ ! -L "$HERE/soma-service-helper.swift" ] || die "no soma-service-helper.swift beside this script"
    cp "$HERE/soma-service-helper.swift" "$WORK/helper.swift"
    [ "$(shasum -a 256 "$WORK/helper.swift" | cut -d' ' -f1)" = "$5" ] || die "soma-service-helper.swift isn't the reviewed one; nothing was installed"
    (cd "$WORK" && env -i PATH=/usr/bin:/bin HOME="$WORK" /usr/bin/swiftc -O helper.swift -o helper) || die "the helper didn't build; nothing was installed"
    printf '{"version": 1, "host": "%s"}\n' "$host" > "$WORK/helper.json"
    rule_for "$user" > "$WORK/rule"
    visudo -cf "$WORK/rule" >/dev/null || die "the sudo rule didn't check out; nothing was installed"
    install_trust "$3" "$4"
    place 0644 "$WORK/helper.json" "$SUPPORT/helper.json"
    place 0755 "$WORK/helper" "$HELPER"
    # The rule goes in last: until then nothing can be asked for.
    place 0440 "$WORK/rule" "$RULE"
    if [ -z "$ROOT" ]; then
        # Reachable as he set it up, and refusing: an empty request restarts nothing and must be turned away by the helper.
        code=0; sudo -u "$user" sudo -n "$HELPER" </dev/null >/dev/null 2>&1 || code=$?
        [ "$code" = 65 ] || { rm -f "$RULE"; die "the helper answered $code to an empty request, not 65; its sudo rule was taken away" 70; }
    fi
    echo "Helper installed for $user on $host. It refused an empty request, as it should." ;;
  remove)
    [ $# = 1 ] || die "usage: remove" 64
    need_root
    rm -f "$ROOT$RULE" "$ROOT$HELPER" "$ROOT$SUPPORT/helper.json"
    echo "Helper and its sudo rule removed. The owner key stays: Restore of this Mac's own user services still needs it." ;;
  untrust)
    [ $# = 1 ] || die "usage: untrust" 64
    need_root
    rm -f "$ROOT$SUPPORT/connections-owner-keys.json"
    echo "Owner key removed. No Restore can run on this Mac until one is installed again." ;;
  *) die "usage: install-service-helper.sh trust KEY.json KEY_ID | install HOST KEY.json KEY_ID SHA256 | remove | untrust | print-sudoers USER" 64 ;;
esac
