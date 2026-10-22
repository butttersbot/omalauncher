# Soma 121 verification: blocked on wireless debugging

2026-10-21. Version 121 is owner-reported; package version could not be checked
because no usable Android shell connection was available.

1. **FAIL: helper restoration.** Phone control reports connected=true,
   active=true, locked=false and awake=true. Background status reports
   backgroundAvailable=false, backgroundControlsAvailable=false, and
   ECONNREFUSED on the helper's loopback port 18746. The cached ADB port 33015
   was closed. A loopback scan found ports 33475, 33913, 35761 and 46900;
   connection attempts produced offline transports, timeouts or refusal, not
   a usable shell. No helper was started under oma keep.
2. **PARTIAL: source investigation; no new sleep verification.**
   PhoneService.root explicitly refuses background roots when PowerManager
   reports noninteractive. PhoneBridge also derives backgroundControlsAvailable
   from that global interactive state. Removing these checks alone is not a
   demonstrated fix: the previous hardware evidence in
   /root/Projects/soma-bgdisplay/evidence/locked-before-action.json records an
   actual null display root, before this explicit refusal was added. The earlier
   handoff also records a shell UiAutomation probe returning null roots.
   Those are prior observations, not a fresh proof on version 121.
   The helper's numeric flags match AOSP Android 16 constants. The earlier
   group-0 observation needs fresh dumpsys display/power/window/activity output
   and awake/asleep root probes before it can be called the cause. Impossibility
   on this Fold has **not** been proved.
3. **FAIL: side-by-side news UI pass not started.** No background display was
   available. No main-display fallback, screenshots of the news reader or
   reproduced news UI issues are claimed. NewsActivity belongs to Soma itself;
   verify the owner's current activity before moving any Soma task to background.
   Background global back/page/stay are currently refused by the command allowlist;
   reader back should be tested through its own visible control where available.

Raw current status and ADB evidence is in
/root/handoff/background-121-evidence/ on the phone. No APK or helper was built
or installed, and no lock, security or accessibility settings were changed.

## Resume

Owner: Settings → Developer options → Wireless debugging. Enable it if off,
then provide the port under “IP address & port”, not the pairing-code port.
If already enabled, leave it enabled and provide its current port.

After verifying the paired shell and device identity, start the existing helper:

```
oma keep run background-display -- bash /root/Projects/omalauncher/tools/background-display/start.sh 127.0.0.1:PORT
oma keep log background-display
oma-phone --display bg status
```

Require backgroundAvailable=true before any background UI action. Ask the owner
to lock and let the screen sleep for the sleep test; never weaken keyguard,
create an insecure virtual device, keep the panel awake, or install a candidate.
Build changes on the Mac through oma-macbuild and leave promotion to the owner.
