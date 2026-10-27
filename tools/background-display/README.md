# Soma background display

Shell-owned, independent Android display for the Soma phone-control bridge.
Built on the Mac with `bash build.sh`; this uses the local Android SDK and does
not require installing android.jar on the phone. Output: `out/classes.dex`.

The helper takes `WIDTH HEIGHT DPI SOMA_UID`, for example `1248 1972 420 10373`.
Flags: PUBLIC, OWN_CONTENT_ONLY, SUPPORTS_TOUCH, DESTROY_CONTENT_ON_REMOVAL,
TRUSTED, OWN_DISPLAY_GROUP, ALWAYS_UNLOCKED, OWN_FOCUS and STEAL_TOP_FOCUS_DISABLED. The physical screen
is never mirrored or unlocked. Dimensions and flags were verified on the Fold.

Provision the same random 64-byte hex key, mode 600, in
`/data/local/tmp/soma-vd/key` (shell) and Soma's private
`files/background-display.key` (app). Never print it or pass it on a command line.
Push the DEX and mark it read-only (0444), then launch with Android shell UID:

```
CLASSPATH=/data/local/tmp/soma-display.dex app_process / SomaDisplay 1248 1972 420 10373
```

It prints `DISPLAY N`. Touch `/data/local/tmp/soma-vd/shot` to save `shot.png`;
touch `stop` to release the display and end the process. Releasing the display
destroys its tasks instead of moving them onto the owner's screen. Frames are
drained continuously; bitmap conversion occurs only for requested captures.

The control port is loopback-only 18746, with HMAC-SHA256 mutual authentication.
It accepts only `status` and `takeover`; takeover moves only this display's tasks
to display 0. Soma exposes takeover only through its owner notification activity,
after unlocking and pausing phone control. Keep the shell command under `oma keep`
on the phone for ordinary operation. A reboot still requires a helper restart.

Keep the ADB server itself alive too when the agent's turn cleanup stops ordinary
child processes. Use an isolated server port so existing ADB work is unaffected:

```
oma keep run background-adb -- adb -P 5038 nodaemon server
oma keep run background-display -- env ADB_SERVER_SOCKET=tcp:localhost:5038 bash /root/wt/builder-b/tools/background-display/start.sh 127.0.0.1:38937
```

Use the current owner-provided debugging port, and the actual checkout path.
Check both `oma keep list` and `oma-phone --display bg status` after startup and
again in the next turn. This arrangement was tested through the awake UI pass;
survival across a turn boundary has not yet been verified.

`Probe` and `LoopbackProbe` are developer diagnostics run under adb/run-as; no
debug-exec switch is enabled. They never print the authentication key.

References: [scrcpy display implementation](https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/video/NewDisplayCapture.java)
and [shell context](https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/FakeContext.java).

## Main-screen keyboard focus

The agent display has independent window focus but must not become the system's
 top-focused display. `OWN_FOCUS` alone does not guarantee that: the helper also
sets `STEAL_TOP_FOCUS_DISABLED` (1 << 16), requiring the existing TRUSTED and
OWN_FOCUS flags. This prevents launch/touch steps from taking the main display's
IME target, rather than briefly stealing focus and trying to restore it afterwards.
See [Android DisplayManager flags](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/hardware/display/DisplayManager.java).

A changed flag applies only to newly created displays. Deploy the rebuilt helper
DEX and let the old display close before testing; updating the launcher APK alone
does not replace a running shell helper. Keep the on-demand lifetime and Take over
behavior. Real-Fold keyboard verification lives in background-on-demand.walk.
