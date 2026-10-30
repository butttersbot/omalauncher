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

Desktop: the same workspace, Markdown, code and images, and HTML/web in the side panel through the desktop HTML engine (below) once it is installed. Until then, or if it can't start, the panel shows the source with one line saying why (`HTML engine not installed · [ install ]`).

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

## Desktop HTML engine (2026-10-29)

Decision (Boss, 2026-10-29: "yeah we need the linux engine"; the artifact panel's open "desktop HTML engine" question): HTML artifacts run **in the side panel** on every desktop, the same engine on Linux and the Macs (no Mac-only WKWebView window), with the Fold's sandbox. Source is the fallback only when the engine isn't installed or fails to start, and the panel says which.

- **Engine.** jcefmaven 152.0.6 (`me.friwi:jcefmaven`, Apache-2.0; Chromium 152 through JCEF), windowed in a `SwingPanel` (`chat/src/desktopMain/.../ArtifactView.desktop.kt`, `WebEngine.desktop.kt`). KCEF, named in the plan, was archived in October 2025 and its README advises against it.
- **A component of its own.** The natives (~145–170 MB per platform, ~360 MB unpacked) are not in the app. `tools/soma-release/engine.json` pins the version, the jcef release tag and each platform's sha256 and size (taken from Maven Central and checked against its sha1). `soma-release publish` fetches each natives file once into `RELEASE/engine/`, refuses any that doesn't match its pin, keeps them across builds, and names them in the record's `engine`. The runner serves only the engine files the record names (`GET /release/engine/NAME`). The update pill shows **install html engine** as its own line the first time, and again only when the pinned version moves (with an app built against it); app updates never fetch it again. Installed into `~/.config/soma/engine/jcef-VERSION`, unpacked beside it and moved into place, `install.lock` last. jcefmaven is given no mirrors, so it never downloads at run time.
- **Sandbox (the Fold's rules).** Generated HTML is served by a resource handler at `https://soma-artifact.invalid/` with the same CSP as the Fold (`isolatedArtifactHtml`, now shared in `ArtifactSandbox.kt`), in an in-memory request context of its own (no storage, cookies or cache). Every request except its own address and `data:`/`blob:` is cancelled; navigation anywhere else is cancelled; popups, JS dialogs and the context menu are off. The only way out is the same declared-recipient `soma-answer:` reply as the Fold, which fills the native Send bar and never sends. Web pages stay on http(s). Decisions are in `ArtifactPolicy`, tested in `WebEngineTest`.
- **Chromium switches, in one place** (`WebEngineFlags`, tested): no first run or default-browser check (on Linux the first-run window held Chromium's start on the AWT thread forever), no component updates, extensions, sync, background networking or translation; no remote debugging port; `--ozone-platform=x11` on Linux (Soma's window is X11/XWayland; on Wayland Chromium opens its own window and never attaches). Scale: JCEF already sizes Chromium's window by Soma's uiScale, so `--force-device-scale-factor` doubled it (MG1 walk: a 941-wide panel became a 1882-wide page); on Linux Soma's scale is the page zoom instead (`WebEngineFlags.zoomLevel`, 2x = ln 2 / ln 1.2), which also reports devicePixelRatio 2. The Mac's Retina scale reaches Chromium natively.
- **GPU first, then software.** A start with the GPU leaves `gpu-unconfirmed` until a page draws; still there at the next start, it becomes `gpu-failed` and that engine starts with `--disable-gpu --disable-gpu-compositing` from then on (`SOMA_WEB_SOFTWARE=1` forces it). A start that doesn't finish in 30 s is reported in the panel.
- **Building :desktop without the Android SDK** (MG1): `./gradlew --configure-on-demand --no-configuration-cache :desktop:… -PsomaOs=linux-x64` with `sdk.dir` pointing at an empty folder in `local.properties`; `:app` (which wants the NDK) is not configured. A JDK 17 with javac is needed (Temurin via mise on MG1).
- **Tests.** `:chat:desktopTest --tests WebEngineTest` (flags, sandbox decisions, install), `:desktop:test --tests EngineTest` (record, offer, install steps, sha refusal, pill), `tools/soma-release/test-soma-release.py` (pin, publish, refusal, runner serving, one version across engine.json, the catalog and the app).
