# Omalauncher Boss — agent preview

An experimental fork of [Simon Schubert’s Omalauncher](https://github.com/SimonSchubert/omalauncher), with image attachments, background agent tasks, a slim home-screen status rail, phone controls, and a system assistant shortcut.

**[Download the APK](https://github.com/butttersbot/omalauncher/releases/download/boss-preview-2026-10-11/omalauncher-boss.apk)** · **[Installation and setup](docs/INSTALL.md)**

For ARM64 Android 10+ phones. The APK installs as **Omalauncher Boss** alongside the original app. Complete the optional Linux setup, install/sign in to your own Claude Code or Codex, and enable the phone permissions described in the guide. No account credentials or personal device data are included.

Claude tool requests can pause for **Approve once / Deny** in the task panel. Codex has background progress/results; native approval forwarding is not implemented yet. One background task runs at a time. Completed results persist; model conversations and pending tasks do not survive app-process death. The Galaxy Z Fold8 cover and inner displays have had a basic hardware check; an inner-screen Claude task and native activity tile returned a real result. Broader foldable transitions remain untested.

The Android build tools installed on the development phone are separate Linux data, not bundled into this APK. [Build-tool notes](docs/PHONE-BUILD-TOOLS.md).

Upstream project documentation follows.

---

# Omalauncher

An Android home screen with the look of [Omarchy](https://omarchy.org), and an
optional Arch Linux ARM system next to your phone apps. Omalauncher runs the
system under proot, without root, and is its Wayland compositor: every Linux
window opens as its own Android task, with its own card in Recents.

<a href="https://play.google.com/store/apps/details?id=dev.omalauncher"><img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80"></a>

![The home screen in twelve Omarchy themes, each with different widgets](art/collage.png)

<p>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01.png" width="24%" alt="Screenshot">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/02.png" width="24%" alt="Screenshot">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/03.png" width="24%" alt="Screenshot">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/04.png" width="24%" alt="Screenshot">
</p>

- **Themes**: all 22 Omarchy themes, with their wallpapers, applied to the
  launcher and to the Linux apps, plus the community themes from
  [omarchy.org/themes](https://omarchy.org/themes/), or your own in
  `~/.config/omarchy/themes`
- **Widgets** written in OWL, a small declarative language you can edit on the
  phone, plus a [widget store](https://github.com/SimonSchubert/omarchy-widgets)
- **Linux, optional**: pacman and the AUR, Linux apps in their own windows,
  terminal widgets, a file manager, and opt-in GPU acceleration on Mali and Adreno 6xx+ GPUs
- **Coding agents**: the bar at the bottom sends a prompt to Claude Code, Codex,
  Copilot, opencode and others, which open in a terminal
- No account, no ads, no tracking

Needs an arm64 phone with Android 10 or newer. Without Linux, the launcher still
has the themes, wallpapers, Android apps and the widgets that don't run commands.

## Using it

The bar: the one input at the bottom of the home screen opens apps and asks
coding agents. Focusing it opens the list of Linux and Android apps; typing
narrows the list, and Enter opens the highlighted app or sends the text to the
agent. Short queries that name an app open it, sentences go to the agent; Tab
and the arrow keys move the highlight. Tap the agent's name to pick another
installed one; the choice is saved in `~/.config/omalauncher/agent`. Hold the mic to talk and release to send, or tap it to dictate
into the field. Speech is recognized by Android (on the device when the phone
supports it), so only text reaches Linux.

Touch input in Linux windows: tap is a left click, long press is a right
click, drag scrolls; apps with touch support (foot, GTK) get real touch.
Hardware keyboard shortcuts: Super+Enter (terminal), Super+Space or
Super+A (the bar), Super+1..9 (home pages) on the home screen; Super+Enter
and Super+W (close) in Linux windows.

## Image attachments (custom build)

Tap **+** in the prompt bar to select up to four JPEG, PNG, WebP or GIF images
(20 MB each). Preview or remove them before sending. With images selected, Enter
always asks the agent; an empty prompt asks it to describe the images. You can
select images before Linux is ready; install Linux to send, and choose Claude Code or Codex. Other agents show an explicit error
and keep the draft instead of silently dropping the images.

Only selected files are copied into the app’s private attachments folder, bound into the guest at
`~/.local/share/omalauncher/attachments`. Codex receives `--image` arguments;
Claude receives image paths and an instruction to read them. Removed draft images
are deleted; sent images stay there for follow-up questions and can be deleted
using Files when no longer needed. Drafts are kept while opening the picker and
launcher panels, but are not restored after the launcher process is killed.

The debug build installs as **Omalauncher Boss** (`dev.omalauncher.boss`),
alongside the Play Store app. It has independent Linux, settings and agent login
data. The official install is not replaced.

## Building

```sh
./scripts/fetch-proot.sh          # Termux's proot -> app/src/main/jniLibs (needs bsdtar)
./gradlew :app:installDebug
# optional: skip the 830 MB download on the device
adb push ArchLinuxARM-aarch64-latest.tar.gz /sdcard/Android/data/dev.omalauncher/files/
```

Tests: `./gradlew :owl:test :app:testDebugUnitTest`. `./gradlew :owl:owlJar`
builds `owl/build/cli/owl.jar`, the checker the widget store's CI runs.

In debug builds you can launch guest commands with
`adb shell am start -n dev.omalauncher/.MainActivity --es exec foot`.

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): the compositor, rendering, GPU
  acceleration, and how updates reach existing installs
- [docs/RELEASING.md](docs/RELEASING.md): Play releases and store assets
- [app/src/main/assets/widgets/README.md](app/src/main/assets/widgets/README.md):
  the widget language

## Layout

- `app/`: the Android app (Kotlin, Jetpack Compose, and the JNI bridge in `src/main/cpp`)
- `owl/`: the widget language and the `owl` command line, plain Kotlin
- `art/`: SVG masters for the app, agent and store icons
- `store/`: Play listing sources (screenshot frames, privacy policy)
- `fastlane/`: the Play listing and the upload lanes
- `scripts/`: fetching proot and theme colors, rendering icons, screenshots and widget previews

## License

MIT, see [LICENSE](LICENSE), for Omalauncher's own code and artwork. The
coding agents' logos, the symbol font and Omarchy's theme colors are not
covered; [THIRD_PARTY.md](THIRD_PARTY.md) lists them with their sources and
licenses. The app also ships proot, talloc and libandroid-shmem (GPL-2.0,
LGPL-3.0 and BSD-3-Clause, prebuilt by Termux), listed in the app under
settings › licenses.

Omalauncher is an independent project and is not affiliated with or endorsed
by Omarchy, Arch Linux, or the makers of the coding agents it installs.

### Agent phone control (Boss build)

Settings > agent phone control, or the **Phone control** app-drawer entry,
opens the access screen. Enable the service yourself in Android Accessibility
settings once. There is no manual Start step or timed session: phone tools work
on demand when agents use them for your requested task. A notification appears
when controls are used and provides Stop. Stop/Pause is an explicit persistent
user override; only the control screen's Resume button clears it. Agents cannot
resume paused access themselves. Screen-off and locked phones reject interaction.
The Linux `oma-phone help` command documents screen reading, screenshots, node
click/type/scroll, coordinate gestures and navigation, and home's own commands:
`pages`, `page NAME`, `where FILE.owl`, `board [PAGE NAME]` (what is where on a
page: grid, tiles, notes, stickers and the free cells) and `note TEXT` (a sticky
note that lands in free space, never on a widget). Bottom-bar agent prompts
include instructions to use these tools and verify the result. Existing chats
can read `~/.local/share/omalauncher/phone-control.md` or run `oma-phone help`.
The Unix socket accepts only the launcher's Android UID, never remote TCP clients.
All programs running inside the launcher share this permission while Accessibility
is connected and access has not been paused. Screen content may reach the chosen
AI provider. Password node text is redacted and password typing is rejected.
Screenshots stay in the private attachment directory. Protected screens may
block capture or expose incomplete controls. No root or ADB privileges are granted.

### Assistant shortcut

Settings > assistant shortcut (or the Oma assistant app-drawer entry) opens
Android's default assistant chooser. Once selected, the system assistant gesture
opens a compact voice/text prompt over the current app. Power-button invocation
requires the phone's supported Digital assistant gesture setting. No hotword
listening or lockscreen access is added. Speak is explicit and requires microphone
permission; recognition may use Android's configured online recognizer.
Send runs a single background Claude Code or Codex request using existing credentials.
Home Claude/Codex prompts use the same task runner, including selected image attachments;
other providers keep their interactive terminal flow. A slim home rail shows progress,
pending approvals, and results. Tap it, the notification, or hold power to open the panel.
Claude uses bidirectional stream-json: tool permissions pause the same live request and
appear under Review approval. Approve once permits only that call, Deny rejects it, and
Later leaves it paused. No persistent permission rule or bypass flag is added. Only
oma-phone and Read are automatically allowed for Claude. Agent phone actions are blocked
while approval is pending, so they cannot click their own approval UI. Codex still uses
exec: progress and results work, but native approval forwarding is not implemented for it.
Tasks stop on app-process death; completed results persist, but model context and pending
approvals do not. One task can run at a time.

The same arm64 APK installs on compatible Android 10+ phones, including Pixel
foldables; Linux setup, permissions, assistant choice and logins are per device.
