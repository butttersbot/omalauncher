# Soma desktop

Soma's chat, the same one as on the Fold, in a window on Boss's Macs and Omarchy boxes (ClaudeSync
`projects/omalauncher/cockpit/DESIGN.md` §6; cockpit slice 6). It is the `:desktop` module; everything it draws and
most of what it does is shared with the phone from `:chat`.

## What is shared, and what is the desktop's own

| Shared (`:chat`) | Desktop's own (`:desktop`) |
|---|---|
| The chat screen, `OmaChatScreen`, with the theme packs and ui pieces | The window, the tray, one instance (`Main.kt`) |
| Following the computers' threads: `MacThreadsEngine` (the phone's `MacThreads` is now a facade over it) | `DesktopThreadsPlatform`: files under `~/.config/soma`, the tray's notices |
| The runner client, the thread rules, the front agent's rules (FrontAgent.kt, QuickLaneRules.kt) | `DesktopFrontAgent`: the front agent on this computer's `claude`, with `quickDesktopPrompt` and the web alone |
| `ChatHost`, the seam | `DesktopChatHost` |
| | `RunnerKeys`: runner keys in the Keychain, `Looks`: packs and fonts from the jar, `Login`: start at login |

## How it behaves

- **The Soma tab** talks to the desktop front agent (Haiku through `claude -p`), which answers or hands his words to a
  thread on a computer. With no builders here, work for no listed thread starts a new thread, as a new chat's first
  words do. Without Claude Code on this computer, the Soma tab's words start a new thread directly (the desktop host
  always names a background agent, so the send rules never refuse; `frontReady` false sends them straight on).
- **New chats and threads' tabs** are exactly the phone's: the thread is made on the computer and agent picked
  (slice 5), followed, approved and stopped through the shared engine.
- **No pictures and no microphone** (`ChatHost.attaches`, `dictates`): a computer's own dictation (Wispr Flow, macOS
  dictation) types into the field, and pictures can't reach a thread on another computer from here, so neither button
  shows.
- **One Soma at a time.** Starting it again, or `soma --toggle`, shows or hides the one that runs. Bind that to a key:
  Hyprland `bind = SUPER, A, exec, soma --toggle`; on the Mac, a Shortcuts or Raycast hotkey. Closing the window hides
  it; the tray's Quit ends it. Escape closes a menu first, then hides the window.
- **Looks:** the phone's packs ship in the jar (copied from `app/src/main/assets/packs` at build time); the tray's Look
  menu picks one, kept in `settings.json`. The Nerd Font symbols come from the phone's own font file.
- **Notices:** a thread's request for his OK, and a request that ended while the window wasn't in front, come as the
  tray's notifications.
- `soma --start-at-login` / `--no-start-at-login`: a LaunchAgent on the Mac, an autostart entry on Linux.
- **Links** (slice 10): `soma://thread/<computer>/<thread>` opens that thread's tab (or the chat that lives on it),
  `soma://chat/<id>` a new chat's, `soma://soma` the Soma tab. A link only picks a tab; it sends nothing. Soma.app
  registers the scheme in its Info.plist and takes links as Apple events; on Linux `soma --register-links` writes an
  application entry and makes it the default with xdg-mime (Boss runs it; it is persistent desktop config). A running
  Soma is handed the link the same way `--toggle` is. Anything that is not a well-formed link opens nothing.

## Runner keys

A computer's Soma never keeps a runner key in a file. `oma-runner pair <this machine>` (allowed once this exists; see
MAC-RUNNER-SPEC "a key per device") delivers `soma-runner-<runner>.json`, into `~/.config/soma/runners/` when the runner
is this machine and by Taildrop otherwise. Soma takes each one, at start and every ten seconds, into the macOS Keychain
(on Linux, the Secret Service, through java-keyring), keeps only the runner's id, name and address in `runners.json`,
and deletes the file. `soma --keychain-check` keeps, reads back and removes a throwaway key: run it before pairing a
machine.

Before the Macs are paired, the gate set in the runner's spec: a Codex thread on that Mac tries to read a Soma key and
must not get it. On the Mac, a Keychain item Soma made is Soma's: another program asking for it gets a prompt, not the
key. On Linux the Secret Service has no such per-program lock, so pairing an Omarchy box is Boss's call with that known.

## Building

- On the Mac Pro: `./gradlew :desktop:createDistributable` gives `desktop/build/compose/binaries/main/app/Soma.app` (with
  its own Java runtime, 139 MB); `:desktop:packageDmg` a disk image.
- For Linux, built on the Mac Pro: `./gradlew -PsomaOs=linux-x64 :desktop:packageUberJarForCurrentOS` gives one jar with
  Linux's Skia (the file is named after the machine that built it). It runs with `java -jar` on a Java 17+ runtime, which
  the Omarchy boxes don't have yet.

## Proven (2026-10-16)

- `DesktopEndToEndTest`: a real `oma-runner` on the loopback (with its fake agent), and the desktop's real chat host and
  engine. A new chat is opened, typed into and sent; the thread is made on the runner (`testhost/…`), and its answer
  shows in the chat.
- `soma --keychain-check` from `Soma.app` on the MacBook Pro: the Keychain kept a key, gave it back, and the test key is
  gone again.
