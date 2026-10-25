# Hunch 0.5.1 copy cleanup

Boss reports the real wallet test works on0.5.0. Requested removal of unrelated
treasury branding. Product commitf92f8659, versionCode12/versionName0.5.1.
Four UI strings changed: live wallet intro plus Setup/Money/Flow HTML references.
The three reference hashes were intentionally updated; layout/styles unchanged.
No wallet, signing, funding or trading implementation changed.

Local manifest/renderer checks PASS. Mac build16/16 unit tests PASS. APK hash,
all23 source asset bytes, absence of old branding throughout bundled assets,
and phone signing certificate independently verified.
Unsigned SHA256138b65e712a1babc7b0b0dda8154020dc16d8f72d3af1fd55ea90b5ec365fa4c.
Signed SHA2564f57afceab0603290c4b1c97264ec6aeedb3aaa636a1edfb8e64f3bb8763c56c.

Initial reference-copy walk PASS; its3LOOK screenshots and4wallet LOOKs reviewed
by Mac lane and Phone B. Wallet's new intro/absence assertions PASS, but broader
wallet walk FAIL35-39 after landscape->portrait, the same recurring failure seen
on0.5.0. Screenshot shows controls; cause is unresolved, not waived as PASS.

Focused hunch-copy.walk6b204739 now covers all4changed screens. It and the
rotation diagnostic are queued in laneB tee3543e8 behind laneA's shared emulator
work. Final focused walk result is pending. Candidate NOT installed or called
ready. Current installed release remains0.5.0.

Artifacts in /root/Inbox/hunch-f92f86590-12*; staging signedAPK at
/root/.cache/hunch-install-0.5.1/Hunch-0.5.1.apk. No install server running.
Pocket APK20261025160532-aede6c, metadata20261025160532-023efd,
initial walk evidence20261025161039-2d6ee1. Preserve failure evidence.

When laneB answers: inspect focused walk results and4LOOKs; resolve any failed
copy assertion. If passing, deliver normal Brave/Android update, verify Samsung
App info Version0.5.1. Do not tap protected installer confirmations by coordinates
or change security permissions. Update this record and stable Soma Note after
promotion. Real wallet creation/copy/reopen was owner-confirmed; no agent funds
or orders. Rotation diagnostic remains a separate open follow-up if unresolved.
