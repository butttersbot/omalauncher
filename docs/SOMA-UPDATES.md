# Soma desktop updates (the update pill)

Boss approved the design on Soma Canvas (soma-desktop/Updates: soma-desktop-update-pill, -sheet, -states).

- **Publishing.** On the Mac Pro, `tools/soma-release/build-release.sh [CHECKOUT]` runs in his GUI session (a one-shot
  gui/501 launchd job: the signing key is in his login Keychain). It runs every suite (`:chat:desktopTest`, `:desktop:test`,
  each `tools/mac-runner/test-*.py`, `tools/soma-release/test-soma-release.py`), then builds the signed Soma.app and the
  Linux jar, then `soma-release publish` writes `~/.local/share/soma-release/<commit>/` and `latest.json`. A failing suite,
  a changed checkout or an ad hoc signature publishes nothing.
- **Serving.** The Mac Pro's runner serves it to paired devices: `GET /release`, `GET /release/files/NAME`.
- **Offering.** Each Soma asks every runner it has a key for, after its keys are read and then every half hour, and shows the
  pill when the published commit is newer than its own (`soma-build.properties`, stamped at build).
- **Updating, on his tap only.** Fetch, check each sha256, unpack (and on the Mac check the signature and its team), hold
  this computer's runner (`POST /update/hold`: no new turns, agent mail waits) until nothing on it is busy, install the
  runner (`oma-runner install` from the build's tarball, which carries `oma-runner.build`), then quit; a helper swaps the
  app (`Soma.app.prev` kept on the Mac, the old jar kept beside the new on Linux), starts the new one, and puts the old one
  back if it doesn't start and stay up.
- **When it fails.** The card says why and keeps the old version; `[ copy details for an agent ]` copies the details, which are
  also in `~/.config/soma/update/last-error.txt`. The helper's log is `~/.config/soma/update/swap.log`.
