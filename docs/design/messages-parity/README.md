# Group 1 Canvas review

Generate the self-contained offline artifact and four Canvas variants:

`python3 docs/design/messages-parity/generate.py --out /tmp/messages-parity-review`

Uses the existing Manga font assets; no network dependencies. Current review version: `parity-g1-v2`.

Canvas app `soma-messages`, Chats page, flow `iMessage parity · group 1`:
- `messages-parity-status`
- `messages-parity-links`
- `messages-parity-typing`
- `messages-parity-fallback`

All are proposals; no owner approval recorded as of 2026-10-29 20:53. Offline Soma artifact `messages-parity-group-1.html` version 1 shows the four states together. UI behavior uses the active theme; exact iPhone motion tuning waits for the restored iOS Simulator. No phone installation or live backend deployment.
