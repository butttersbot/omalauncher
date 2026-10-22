# Walkthroughs: use the feature like a person before Boss does

Boss, 2026-10-22: "minimize these simple ui bugs which would be caught with a simple test run using the feature like a
human would", without adding much time. Every UI change ships with a walkthrough in `scripts/<feature>.walk`; a bug
he reports gets a step that would have caught it.

## The split: [emu] or [phone]

- **[emu]**, the default: our own app on standard Android: layouts, spacing, wrapping, taps, drags, scrolling,
  handles, back, the keyboard's panels, news, chat, widgets, home, tiles, cover vs unfolded sizes, rotation, network
  features. Run on the Mac Pro's Fold emulator with each build; they must PASS, and their screenshots be looked at,
  before a build is called ready.
- **[phone]**: what only the real Fold has: Samsung's own software (One UI, its Quick Settings, status strip, Samsung
  apps, lock screen), hardware and sensors (step counter, the physical fold, mic, camera, haptics, sleep), locked or
  asleep states and Samsung's permission prompts, Boss's own state (his Linux, logins, phone control, agent mail,
  Tailscale, his home and data), and real speed and feel. Run on the phone after he promotes, before "done"
  (`oma-phone`, on the hidden display when it is up); if they cannot run, say so.

## Script steps

One per line; `#` comments; a line without a tag is `[emu]`. Coordinates are fractions of the screen (`0.5 0.9`) or
pixels (`540 1800`).

| step | does |
|---|---|
| `launch PKG/ACTIVITY` | starts an activity and waits for it |
| `url URL` | opens a link |
| `ime PKG/SERVICE` | makes that keyboard the current one |
| `tap TEXT` or `tap X Y` | taps what says TEXT (text or content description), or a point |
| `swipe X1 Y1 X2 Y2 [MS]` | a drag |
| `type WORDS` / `key ENTER\|BACK\|DEL…` | typing, through whatever keyboard is up |
| `wait MS` | pauses |
| `shell CMD …` | an adb shell command (e.g. `cmd statusbar click-tile PKG/TILE`) |
| `rotate portrait\|landscape`, `fold folded\|open` | the Fold profile's orientation and screens |
| `expect TEXT` / `absent TEXT` | fails unless TEXT is (is not) on screen; takes a screenshot |
| `shot NAME` | a screenshot to look at (canvas-drawn UI, like the keyboard, has no text to expect) |
| `[phone] …` | a step in words for the real Fold, after promotion |

## Running

    tools/walkthrough/walk.py tools/walkthrough/scripts/gif-keyboard.walk --serial emulator-5554 --out /tmp/walk/gif
    tools/walkthrough/walk.py tools/walkthrough/scripts/gif-keyboard.walk --phone     # the [phone] checklist

On the Mac, `soma-macbuild` installs the build on the running emulator (AVD `soma-fold`, the Pixel Fold profile)
and walks the scripts named in the commit's `Walk:` trailer, or every script whose feature the commit touched; the
results and screenshots come back with the build. Keyboard steps tap by position (the keys are drawn, not labelled):
when a layout change moves them, fix the coordinates in the script in the same commit.
