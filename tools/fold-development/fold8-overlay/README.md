# Fold8 emulator: Boss's Fold's system bars

Measured on Boss's Galaxy Z Fold (SM-F971U1, Android 17) on 2026-10-23, cover screen: status bar 110 px (~42 dp at
420 dpi), navigation 39 px (gesture handle only). The Fold8 AVD (Pixel 9 Pro Fold profile) had a 152 px cover status bar,
because its camera cutout reached 152 px and Android uses the larger of the cutout and the status-bar height.

- `cutout/`: a static overlay with smaller cover cutout (the inner one unchanged). Static, because cutouts are read at boot.
- `bars/`: 42 dp status bar. Mutable, so it can be put above the emulator's own `pixel_9_pro_fold` overlay (52 dp).
  Android turns it off at each boot: `tools/oma-emu fold-bars` (run by `oma-emu start` and by `soma-macbuild` before the
  walks) turns it on again.
- Install once with `install.sh` (the emulator must run with `-writable-system`; `oma-emu start` passes it).

Result: cover status bar 110 px. Not matched: the inner screen keeps its 136 px top inset (its cutout; no phone measurement
of the inner screen yet), and the bottom inset stays 63 px (cover) / 84 px (inner). On this Android the bottom inset comes
from the Pixel launcher's taskbar code, not the framework `navigation_bar_height` (overridden here to 15 dp with no effect).
The inner screen already shows only the gesture handle, no persistent taskbar. One UI's own look is not reproduced.
