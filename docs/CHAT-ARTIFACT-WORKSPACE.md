# Soma Chat artifact workspace

2026-10-22 — Codex. First native slice, based on phone/fold-development 2b38a3f.

The conversation stays in view while an output is inspected. This extends the existing Soma Chat; Soma Messages stays separate. The current composer, dictation, image picker, send routing, approval controls and theme packs remain in place. Swapping to SomaComposer is deliberately deferred until that component is ready and tested.

## Behavior

- File links, web links and fenced snippets in agent replies have an explicit Open action. File links and web links in the reply also open the pane directly. Earlier Mac conversation replies and subsequent agent updates participate too.
- At 680 dp and wider: conversation on the left, artifact on the right. Expand uses the full window; Split returns to two panes.
- Narrow windows: the artifact covers the chat. Close or Back returns to the same mounted conversation, draft and reading position. Unfolding reveals the conversation alongside the selected artifact.
- Preview, Source and Files switch representations/outputs. Refresh re-reads the file and displays a content version. An unreadable/offline/unsupported output has a specific error and Retry.
- Discuss adds the selected output's reference to the current draft. It does not send anything.
- No project scan or automatic file download. Outputs are discovered from the reply and read only when opened. Selection is kept per conversation.

## Implemented renderers

Android: self-contained HTML with JavaScript, reachable HTTP(S) pages, Markdown, plain text/code/diffs, and PNG/JPEG/WebP/GIF images. HTML uses WebView without a native bridge, file/content access or runner headers. Generated HTML has networking disabled; sibling CSS/JS/image files and external libraries are not loaded. Web pages use a separate uncredentialed web request and may be restricted by the destination service.

Desktop: the same workspace, Markdown, code and images. Interactive HTML/web rendering is a documented fallback to source/address in this slice; no browser dependency is added. The installed Soma.app is not replaced.

Remote files: `ChatHost.readArtifact` → shared MacThreadsEngine/MacRunnerClient → authenticated `GET /threads/ID/artifact?path=…` on the conversation's computer. The current device key and caller checks apply. The endpoint requires a regular file inside that thread's registered project, rejects dot/configuration paths, symlink escapes, hard links and pipes, and caps files at 4 MiB. Reads walk held directory descriptors. Responses are no-store JSON with base64 bytes, MIME and a content hash; URLs/headers containing runner credentials never enter the preview.

Inline snippets need no runner update. Linked remote files require the updated runner; an older runner displays an explicit error. Files produced only in the phone agent's guest filesystem are not wired into this endpoint yet. Remote file refresh is explicit; this is not a continuous filesystem watcher. Large text is visibly limited to 128,000 characters in the reader.

## Verification

Run with the existing Java 17 toolchain and Android SDK:

```
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew \
  :chat:desktopTest :app:testDebugUnitTest :desktop:test :app:assembleDebug \
  -Dorg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
python3 -u tools/mac-runner/test-runner.py
```

Focused tests cover output discovery, canonical link identity, source parsing, private/error responses, text limits, opening/closing a cover artifact without losing a draft, editing beside the unfolded pane, Discuss without sending, retry and cover-to-unfold continuity with Back. Existing chat/launcher/desktop tests also run.

An opt-in, separate Android fixture exercises the actual production OmaChatScreen and WebView with fictional replies and an in-memory artifact reader. It has its own app identity, no runner configuration and no real send implementation. It is excluded from normal builds:

```
./gradlew -PartifactPreview :artifact-preview:assembleDebug
adb -s EMULATOR_SERIAL install -r tools/artifact-preview/build/outputs/apk/debug/artifact-preview-debug.apk
adb -s EMULATOR_SERIAL shell am instrument -w dev.omalauncher.artifactpreview/.PreviewInstrumentation
```

The instrumentation checks native HTML rendering, an actual JavaScript interaction, then close → retained draft, Dictate and bracketed Send. It saves an emulator screenshot. Android fixture tested on a separate read-only herd-fold emulator instance. Real-device fold transitions, IME/back ordering and live runner-to-Fold file reading still need the normal promotion check.

## Promotion

No installed launcher, runner service, OpenBubbles installation, live agent session or desktop app is modified by this source change. Bundle the app and runner changes through the existing phone promotion process. Keep the working package, signing key, advancing version code, and the phone's matching proot libraries.

The Mac source build proves compilation/tests; its default debug key/version is not a phone release. The worktree has no cached proot binaries. The repository's pinned Termux fetch currently returns 404, so this Mac APK must not be offered as a working-launcher update. Use the established Mac Pro/phone build flow and runtime-library comparison; changing unrelated runtime pins is outside this slice.

## Agent-made artifacts (2026-10-24, features/artifact-panel/PLAN.md slices 1-3)

Agents can now make an artifact on purpose: `soma-artifact put FILE --title "…" --note "…"` (also `editing NAME`,
`list`). On the Macs and MG1 the runner keeps every version (docs/MAC-RUNNER-SPEC.md, "As built: artifacts") and sends
an `artifact` event; on the phone, a builder's `soma-artifact` (asset soma-artifact.sh) keeps the same store in the
guest, /root/.local/share/soma/artifacts/<phone|phone-b|phone-c>/, read by PhoneArtifactStore.

- The event becomes a step of its entry ("made x · v1", "updated x · v3"), which is what the card under the reply and
  the artifacts list are made from (SomaArtifacts.kt), so they survive restarts. Location `soma-artifact:NAME[?v=N]`.
- Live state (newest version, an agent editing) is ArtifactLiveBook, read with the chat's 500 ms tick
  (`ChatHost.liveArtifacts`). Something new within 3 minutes opens beside the chat when the window is wide (unless
  another artifact is showing there); narrow, a strip above the composer says "Claude is editing x · v4" / "x · v4 is
  ready" with `[ view ]`, and never covers the chat. Unfolding while the strip shows opens that artifact.
- Panel: version badge, `versions · N` (every version with its note; a tap shows that one with "Viewing vN, an
  earlier version [ back to latest ]"), red "Claude is editing · vN" strip over a dimmed preview, "vN ready" toast, a
  new version loaded into the same WebView. Discuss puts "About NAME vN: " in the draft.
- The WebView sandbox is unchanged: no JS bridge, no file/content access, network blocked for generated HTML.
