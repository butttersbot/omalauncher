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
OWN_FOCUS flags. These kept top focus on display 0 in the AOSP emulator; they do
not establish Samsung IME isolation. The real Fold test below failed.
See [Android DisplayManager flags](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/hardware/display/DisplayManager.java).

A changed flag applies only to newly created displays. Deploy the rebuilt helper
DEX and let the old display close before testing; updating the launcher APK alone
does not replace a running shell helper. Keep the on-demand lifetime and Take over
behavior. Real-Fold keyboard verification lives in background-on-demand.walk.

### Focus patch checkpoint (56b75ca1, 2026-10-26)

MacBook lane B compiled the helper successfully with android-35 / d8 36.0.0.
Artifact: `soma-display-56b75ca1.dex`, 17560 bytes, SHA256
`098cb9ddd50e2929defd43263bd458b6607d63d2139aebf96605b46dadde1ffe`.
The received phone copy was checksum-verified in `/root/Inbox`; it was not deployed.

Fold8-B AOSP API37 comparison used authenticated helper open/close, launched Hunch
on the virtual display, then injected a tap on that display. Old helper: top focus
0 after open, virtual display10 after launch and tap. New helper: top focus0
through open, launch and tap (virtual display11). Both created/released normally.
This is an emulator top-focus PASS, not a Samsung keyboard PASS.

Not checked: main IME visible/served client, composer draft retention, oma-phone
background press/screenshot, idle release/recreate, Samsung One UI. The emulator's
IME was hidden throughout. Physical phone was asleep/locked when reproduction was
attempted; no unlock or phone steps taken. These remain in the phone walkthrough.

### Real Fold failure and launch guard (2026-10-26)

Boss tested launcher 171 with the helper deployed at 15:02 (reported source
56b75ca1). At 22:35:11 `url about:blank` was rejected by the HTTP(S)-only guard
and launched nothing. At 22:35:42 `url https://example.com` opened Brave on
display 44 (`inFront:true`); Boss reported that Soma's main keyboard closed.
This is a Samsung IME FAIL, superseding any inference from the emulator result.

Source trace: `/usr/local/bin/oma-bg-heal` and `tools/background-display/start.sh`
both load `/data/local/tmp/soma-display.dex`. `SomaDisplay` has one on-demand
`createVirtualDisplay` call using FLAGS, including TRUSTED, OWN_FOCUS and
STEAL_TOP_FOCUS_DISABLED. PhoneService's open/url share `launchOnDisplay`, which
sets launchDisplayId and NEW_TASK; there is no explicit main-display launch or
IME-hide call on this path. The bridge may create a display before this launch.
These source findings do not prove the running dex bytes or Samsung's actual
top-focused display/IME client. Wi-Fi/ADB was unavailable during the failure.

The launcher now checks display 0's accessibility TYPE_INPUT_METHOD windows
with nonempty bounds before direct bg open/url can ensure a helper display,
then rechecks immediately before startActivity (also for plan launch steps).
It returns ok:false, waiting:true, reason:boss-typing, actionMayHaveRun:false
with “Waiting: Boss is typing”; it neither queues a later launch nor hides the
keyboard. The caller retries after Boss closes the keyboard. Background
screen/read/press/screenshot retain their existing admission rules. Main-display
launches remain explicit. The guard does not claim to stop an app from launching
its own activity as a consequence of a press.

Before the next real test, with Boss's Wi-Fi/debugging available, capture the
running helper PID and CLASSPATH, hash the deployed dex against the expected
SHA above, and dump display flags, window top-focus and input_method clients.
Verify again after idle recreation and a healer restart. Do not replace a
running helper or disturb Boss's composer just to obtain this evidence.
The new guard's real-Fold/IME result is NOT RUN until the bundled launcher is
promoted and Boss repeats the walkthrough.
