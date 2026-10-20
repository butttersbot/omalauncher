#!/bin/sh
# Omalauncher: the one command for the launcher's small guest tools (Boss, 2026-10-14: "Fold the small tools into
# one `oma` command"). It only chooses: each subcommand is carried out by the tool that did it before, installed
# beside this in ../lib/oma, so what those tools keep safe (the Mac's key never on a command line, the keeper's
# socket that only root opens, no network listener) is unchanged. Found next to this file rather than at a fixed
# path so the tests can run the same script from a folder of stand-ins.
lib="$(dirname "$0")/../lib/oma"

usage() {
  cat <<USAGE
usage: oma threads [list]
       oma threads new NAME PROJECT_PATH [--kind claude|codex|grok|cursor|venice]
       oma keep run NAME [--] COMMAND [ARG...]
       oma keep list | log NAME [LINES] | stop NAME
       oma status
       oma drop get [--to DIR] [--keep] | list | send FILE... [--to mac] | help
       oma msg send DEVICE[:SESSION] TEXT [--reply-to ID] [--title NAME] [--as SESSION] | devices
       oma front [grok|haiku]
       oma help

threads  the project threads on the Mac: list them, or make one for a folder there
keep     keeps a server or other job running after the turn or terminal that started it
status   how the phone's builder and the Mac's threads stand, as the launcher last wrote it
drop     files between the Macs and this phone: get what they Taildropped, send to the Mac
msg      agent mail to an agent on one of the computers; theirs comes to the chat
front    who answers first in the chat: Grok or Haiku, and why
USAGE
}

[ "$#" -ge 1 ] || { usage >&2; exit 2; }
command=$1
shift
case $command in
  threads)
    [ "$#" -ge 1 ] || set -- list
    exec "$lib/thread" "$@" ;;
  keep) exec "$lib/keep" "$@" ;;
  status) exec "$lib/agents" status "$@" ;;
  drop) exec "$lib/drop" "$@" ;;
  msg) exec "$lib/msg" "$@" ;;
  front) exec "$lib/front" "$@" ;;
  help | -h | --help) usage ;;
  *)
    echo "oma: no subcommand \"$command\"." >&2
    usage >&2
    exit 2 ;;
esac
