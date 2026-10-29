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
| `tap TEXT` or `tap X Y` | taps what says TEXT (text or content description; `A \| B` for whichever shows), or a point (`48dp` works too) |
| `swipe X1 Y1 X2 Y2 [MS]` | a drag |
| `hold TEXT [MS]` | a long press on what says TEXT, without moving (800 ms unless given) |
| `drag TEXT [offset DX DY] to X Y` | a long press on what says TEXT (or DX DY from it, `60dp` works), then carried to the point (lifting a note, a sticker or a tile) |
| `type WORDS` / `key ENTER\|BACK\|DEL…` | typing, through whatever keyboard is up |
| `wait MS` | pauses |
| `shell CMD …` | an adb shell command (e.g. `cmd statusbar click-tile PKG/TILE`) |
| `rotate portrait\|landscape`, `fold folded\|open` | the Fold profile's orientation and screens |
| `corner right\|left TEXT` | fails unless what says TEXT ends near that side of the screen (corner controls that must not drift inward) |
| `expect TEXT` / `absent TEXT` | fails unless TEXT is (is not) on screen; takes a screenshot |
| `clearlog`, `log TEXT`, `nolog TEXT` | the app's own account of drawn UI (Log tag `SomaWalk`): clear it, wait for a line with TEXT, or fail if one has come |
| `swipefrom TEXT DX DY [MS]` | a swipe that starts on what says TEXT and moves by DX DY (fractions of the screen; negative DY scrolls a list up): scrolls a panel whose place differs between the cover and unfolded |
| `within TOP BOTTOM TEXT` | asserts the label’s entire reported bounds are inside a vertical screen band (fractions 0..1); review the screenshot too, since Android may report clipped bounds |
| `scrollto TEXT` | swipes up until TEXT shows (a WebView reports only what is on screen) |
| `shot NAME` | a screenshot to look at (canvas-drawn UI, like the keyboard, has no text to expect) |
| `shot NAME card=CARD-ID` | the same, and this screenshot is that Soma Canvas card: see the design gate below |
| `[phone] …` | a step in words for the real Fold, after promotion |

A `shot` is reported **LOOK**, never PASS: a walk counts only once its screenshots have been looked at. (The first
walk, of 128, "passed" the GIF keyboard while its swipes had missed the handle and pulled down Android's shade.)

## Running

    tools/walkthrough/walk.py tools/walkthrough/scripts/gif-keyboard.walk --serial emulator-5554 --out /tmp/walk/gif
    tools/walkthrough/walk.py tools/walkthrough/scripts/gif-keyboard.walk --phone     # the [phone] checklist

On the Mac, `soma-macbuild` installs the build on the Mac's Fold emulator (AVD `Fold8`, shaped like his phone; docs/EMULATOR.md; started headless when it isn't running)
and walks the scripts named in the commit's `Walk:` trailer, or every script whose feature the commit touched; the
results and screenshots come back with the build. Keyboard steps tap by position (the keys are drawn, not labelled):
when a layout change moves them, fix the coordinates in the script in the same commit.

## The design gate: done = PASS + as approved

Boss, 2026-10-29: "What is the point of having an approved design if the agents keep handing back work that doesn't
follow the approved design." (design/soma-canvas/DESIGN-GATE.md.) Build a screen only from its **Approved** card on
Soma Canvas, and name the card in the commit. In the walk, every screenshot of an approved screen names its card:

    # canvas-app: soma              (optional; hunch* walks default to hunch, the rest to soma)
    shot connections-tile card=connections-tile

walk.py writes `canvas.json` (screenshot → card, the map `soma-canvas publish` reads) beside `results.json`. On the
Mac lane, after the walks, `design_gate.py run` takes each walk that **PASSed**: its carded screenshots become
Shipped on Canvas (`soma-canvas publish APP DIR --version CODE --build SHORT`), and each is checked against Approved
(`soma-canvas check CARD SHOT`, a vision diff on the Canvas side). The answers go to `design-report.json`
(`design-<short>-<code>.json` beside the walk zip, Taildropped). Each result is its own verdict beside PASS/FAIL,
in report.txt, the walk mail's title and body, and the take/install output:

- **DESIGN FAIL** (drift): the screen differs from Approved; each card is listed with its numbered differences.
  `oma-macbuild --take` and `oma-install` refuse the build until it is built as approved or Boss waives the
  difference (in chat or on Canvas; they ask Canvas again, so what he does after the walk counts).
- **DESIGN UNAPPROVED**: the card has nothing Approved. It does **not** block (amendment 77d43019: "I don't want
  things to be blocked if I forgot to click a few approve buttons"). Ask Boss once, with one native question card
  for the whole batch ("Approve these N designs?"), linking the Canvas page; his answer, or an approval he types in
  chat, is recorded on Canvas with Soma's signed receipt (`soma-canvas approve-from-chat`). Never call such a build
  done without saying so.
- **DESIGN UNCHECKED**: a check that could not run (Canvas down, `check` missing), or no design report at all. It
  does not block for now; say so too.

A failed walk publishes nothing. Tests: `python3 -m unittest discover -s tools/walkthrough -p 'test_*.py'`.

### Fresh accessibility evidence after WebView transitions

Launch, URL open, `shell am start`, rotation and fold steps wait for a readable app tree. Every lookup makes a new dump: the old `/sdcard/walk.xml` is removed first, dump/read failures and malformed or empty trees retry (at most three attempts, with bounded adb timeouts). A named WebView shell with no readable descendants is not ready, even when its pixels have already rendered. `expect`, `absent` and `scrollto` all use this same fresh lookup. If the tree stays unavailable the step FAILS explicitly; it cannot turn into an `absent` PASS or an ungrounded swipe. These retries do not relax the expected text or turn failures into passes.

Regression: launcher164's Hunch wallet screenshot showed Copy wallet address and Back to markets after rotation while UiAutomation exposed no matching controls. LaneB's b18de027 wallet walk passed35/0, with a similar one-off cold-MainActivity empty-tree delay. The runner correction targets that evidence failure, not wallet behavior. Unit coverage: `python3 -m unittest discover -s tools/walkthrough -p test_walk.py -v` (10 tests passed at implementation). Emulator wallet rerun and broader165 report coverage remain pending the lane checkpoint.
