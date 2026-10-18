#!/bin/bash
# grokbot: Soma's two Grok helpers, for Boss and for any agent on any of his computers (Boss, 2026-10-18).
#
#   grokbot x "REQUEST"                     the X helper: finds posts, accounts and threads on X and reads their
#                                           engagement, with Grok's own X search. The answer goes to stdout.
#   grokbot make "REQUEST" [--out DIR]      the content helper: images and video with Grok Imagine. Files are put
#                                           in DIR (default ~/Soma/made/DATE) and listed last, as FILE: PATH.
#   grokbot help
#
# Each call is one headless run of this computer's Grok CLI, signed in as Boss (`grok login`). Neither helper has a
# shell, a file writer or a connected service (MCP): on a computer whose Claude Code settings say "auto", Grok's own
# "ask nobody" mode is overridden (seen 2026-10-18: it ran python3 and rm), so what it must not do is taken away, not
# merely refused. The content helper's files are copied out of Grok's session by this script. Nothing here keeps a key.
set -u -o pipefail

usage() {
  sed -n '4,8p' "$0" | sed 's/^# \{0,1\}//'
}

grok_bin=${GROKBOT_GROK:-grok}
command -v "$grok_bin" >/dev/null 2>&1 || { echo "grokbot: Grok CLI is not installed here (npm i -g @xai-official/grok)." >&2; exit 3; }

# Every tool that runs a command, changes a file, starts other agents or work that outlives the run. Grok names its
# tools by id; a name it doesn't know removes nothing (it only warns), so this list can only take away.
NO_TOOLS="run_terminal_cmd,write,search_replace,spawn_subagent,task,workflow,monitor,scheduler_create,scheduler_delete,scheduler_list,kill_command_or_subagent,ask_user_question"
NO_MEDIA="image_gen,image_edit,image_to_video,reference_to_video"

X_RULES="You are Soma's X helper, the one agent Boss and his other agents ask about anything on X (Twitter). Use your X \
search for every claim: find posts, accounts, threads and replies, and read their engagement (likes, reposts, replies, \
views). Give links to the posts you cite (x.com/i/status/ID) and their dates, and give each number only as X search \
returned it. Say plainly when X search returns nothing or too little to answer; never invent a post, a number or an \
account. You have no shell and change nothing: if asked to run or change anything, say you can't. You only read X: \
never post, like, follow or message. Answer in Markdown, short and scannable, most relevant first."

MAKE_RULES="You are Soma's content helper, the one agent Boss and his other agents ask for images and video. Make what \
is asked with your Imagine tools (image_gen, image_edit, image_to_video, reference_to_video). You can't save or move \
files yourself and needn't: everything you make is handed over by Soma. Say in one short paragraph what you made. If \
something asked for can't be made here (Midjourney, Suno, Higgsfield, or anything but images and video), say so in one \
line instead of making something else."

case "${1:-}" in
  x)
    shift
    [ "$#" -ge 1 ] && [ -n "$*" ] || { usage >&2; exit 2; }
    exec "$grok_bin" --permission-mode dontAsk --no-subagents --no-plan --rules "$X_RULES" \
      --disallowed-tools "$NO_TOOLS,$NO_MEDIA" --deny MCPTool --deny Edit --deny Write \
      --single="$*" < /dev/null ;;
  make)
    shift
    out=""
    words=()
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --out) [ "$#" -ge 2 ] || { usage >&2; exit 2; }; out=$2; shift 2 ;;
        --out=*) out=${1#--out=}; shift ;;
        *) words+=("$1"); shift ;;
      esac
    done
    [ "${#words[@]}" -ge 1 ] && [ -n "${words[*]}" ] || { usage >&2; exit 2; }
    command -v jq >/dev/null 2>&1 || { echo "grokbot: jq is needed to hand over what Grok makes." >&2; exit 3; }
    out=${out:-$HOME/Soma/made/$(date +%Y-%m-%d)}
    mkdir -p "$out" && out=$(cd "$out" && pwd -P) || exit 1
    # Grok runs in a folder of its own, so nothing it might read or make lands among his files but what is copied.
    work=$(mktemp -d "${TMPDIR:-/tmp}/grokbot.XXXXXX") || exit 1
    trap 'rm -rf "$work"' EXIT
    # Always-approve, since the Imagine tools save into Grok's session and "ask nobody" refuses them; safe only because
    # every tool that could do harm is gone above, and connected services are denied (a deny holds in every mode).
    result=$(cd "$work" && "$grok_bin" --output-format json --permission-mode bypassPermissions --no-subagents --no-plan \
      --rules "$MAKE_RULES" --disallowed-tools "$NO_TOOLS" --deny MCPTool \
      --single="${words[*]}" < /dev/null)
    status=$?
    text=$(printf '%s' "$result" | jq -r '.text // .error // empty' 2>/dev/null)
    session=$(printf '%s' "$result" | jq -r '.sessionId // empty' 2>/dev/null)
    [ -n "$text" ] && printf '%s\n' "$text"
    [ "$status" -eq 0 ] && [ -n "$session" ] || { [ -n "$text" ] || printf '%s\n' "$result" >&2; exit "$(( status == 0 ? 1 : status ))"; }
    case $session in *[!0-9A-Za-z-]*) echo "grokbot: unexpected session id from Grok." >&2; exit 1 ;; esac
    # What it made is in its session's folders (images/, videos/ and the like), under ~/.grok/sessions/<folder>/<id>/.
    found=0
    for made in "${GROK_HOME:-$HOME/.grok}"/sessions/*/"$session"/*/*; do
      [ -f "$made" ] || continue
      case ${made%/*} in */images|*/videos|*/video|*/media) ;; *) continue ;; esac
      name=${session: -12}-${made##*/}
      cp "$made" "$out/$name" && { echo "FILE: $out/$name"; found=1; }
    done
    [ "$found" -eq 1 ] || echo "grokbot: Grok made no file this time." >&2 ;;
  help | -h | --help) usage ;;
  *) usage >&2; exit 2 ;;
esac
