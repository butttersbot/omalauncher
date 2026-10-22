# Background phone control

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
