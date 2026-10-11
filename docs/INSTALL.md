# Install Omalauncher Boss

Download **omalauncher-boss.apk** from the [latest preview release](https://github.com/butttersbot/omalauncher/releases/tag/boss-preview-2026-10-11). It contains image attachments,
automatic agent phone controls, and the system assistant prompt. The same APK
supports ARM64 phones running Android 10 or newer; no device-specific rebuild is
needed. Foldable inner/outer-screen behavior has not yet been tested on hardware.

1. Transfer the APK to the new phone and open it. Allow installation from the app
   you used to open the file when Android asks.
2. Open Omalauncher Boss and complete its Linux setup. Install Claude Code and/or
   Codex, then sign in on this phone. Existing conversations, files, and logins
   do not automatically transfer from your previous device.
3. For agent interaction with other Android apps, enable **Omalauncher Boss phone
   control** in Android Accessibility settings. No timed session or Start step is
   required. Stop/Pause remains an optional override.
4. Open **Oma assistant** from the app drawer, or Settings > assistant shortcut.
   Choose **Use Oma as my assistant** and confirm Android's default assistant choice.
5. Allow microphone access for voice input and notifications for progress/results.
   Type input also works without microphone permission.
6. In Android Settings > System > Gestures, select Digital assistant for Press and
   hold power button if that option is supported on the phone. Assistant invocation
   can also use the system's other assistant gesture.

The assistant prompt sends one Claude/Codex background request at a time. Speak
fills the prompt; Send dispatches it. Reopen the prompt or tap its notification to
read the result. These initial assistant requests do not carry model conversation
context from one task to the next. Home-composer prompts to Claude/Codex run in the background; the thin rail above
the composer shows progress and opens the task panel. Other providers retain
their interactive terminal flow.

Keep future updates under the same app package and signing key so they update this
installation while preserving app data. The custom package is dev.omalauncher.boss
and coexists with the official Play Store app.

The slim agent rail is built into the launcher. Claude tool approvals use Review approval → Approve once / Deny; Codex native approval forwarding is not yet implemented. The on-phone build toolchain is separate private Linux data and must be installed on the new device; see PHONE-BUILD-TOOLS.md.
