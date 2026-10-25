# Background phone control

Hardware result, 2026-10-21: this is a prototype. Background app launch, read,
batch commands and screenshots work on the Fold while its main screen is awake.
When the main screen sleeps, background app controls become unreadable in both
Soma Accessibility and a shell UiAutomation session. Captures still work, and
main-display operations remain refused while locked. The original plan's promise
of continuing full interaction while locked and asleep has **not** been achieved.
The complete current scrcpy flag combination was also tested without resolving it.
The phone's `cmd virtualdevice create-device` help requires insecure keyguard;
that route was not used and no lock settings were changed.

The shell helper in `soma-bgdisplay` creates a separate display named `soma-agent`.
The main screen retains its lock. A live, authenticated helper is required before
only the background display is permitted to work while the phone is locked or asleep.

Use `oma-phone --display bg status`, then put `--display bg` before every command,
including `run`. Reads, app launches, text, gestures and screenshots target that display.
An unavailable helper fails closed. Global home, back, recents, notifications and
home-page editing are unavailable there; use the app's own navigation controls.

The phone-control notification has **Stop** and **Take over**. Stop pauses all phone
control. Take over requires unlocking, pauses control first, then moves the helper's
tasks to the main screen. Resume is the owner's action in Phone control.
Pending approvals continue to block mutations. Password fields and protected
screens retain their existing restrictions. Messages, purchases and deletions still
need the owner's OK. Apps already on the main screen must wait rather than be stolen.

The helper needs the phone's paired wireless debugging. It listens only on
127.0.0.1:18746. A 64-byte private key is provisioned in shell-only
`/data/local/tmp/soma-vd/key` and app-private `files/background-display.key`.
HMAC-SHA256 challenges authenticate both sides without transmitting the key.
Only status and moving the helper's own tasks to main are accepted. No shell-command
endpoint exists. Android denied cross-UID Unix sockets in either direction on the Fold.

Restart the helper after a phone reboot. Neither Bluetooth nor Wi-Fi debugging
settings are enabled automatically. The normal main-display lock policy is unchanged.

## Default and self-heal (2026-10-25)

Boss chose: without `--display`, `oma-phone` uses the background display while its helper is up and the main
screen when it is not (with a note on stderr). Home's pages, notes, stickers and looks stay on main. `--display bg`
still fails closed. Verified on the Fold: a helper started detached (`setsid nohup … app_process …`) keeps working
after Wireless debugging is switched off. `tools/fold-development/oma-bg-heal` (started by Soma with Linux, one at a
time) checks every minute and restarts the helper when it is down and Wireless debugging answers. It never turns
Wireless debugging on. After a reboot the phone needs Wireless debugging on for about a minute; Android also drops a
debugging key unused for 7 days, which then needs pairing again.
