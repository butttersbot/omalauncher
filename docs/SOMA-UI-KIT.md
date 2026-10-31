# Soma UI kit: how a screen feels

Boss, 2026-10-30, after Soma Messages 0.8.7: "The interactions feel much better... really smooth... make sure that all
of our apps... and the widgets too get this baseline of smoothness, the animations, the snappiness and the
responsiveness."

The kit (`:kit`, Compose on Android and the desktop) is feel only: motion, gestures, press response, overlays, scroll.
It knows no pack. Colours, type, layout, headers and pills stay as shipped and come from the active theme; a piece
that needs a colour is handed it. Never redraw a screen to adopt this.

## What makes Messages feel right

| What | The number | Why it matters |
|---|---|---|
| The one spring (`Motion.spatial`) | damping 1, stiffness 1000 | All but there in a fifth of a second, never overshoots. A finger's speed carries in, and a new target turns it mid-way. |
| The one fade (`Motion.fade`) | 150 ms on (0.2, 0, 0, 1) | Out fast, in gently. |
| Screen in by tap / out by button (`Feel.open`, `close`) | 270 / 200 ms on that curve | Leaving is quicker than arriving. |
| A finger's slide ending | the spring, not a timing | It lands from where the finger left it. |
| Just-sent item (`Feel.entrance`) | 370 ms; scale 0.92, 24 dp low, alpha 0.6 → rest, from the sender's foot corner | Asked once per item, so history, a reconnect or an echo never replays it. |
| Menu or sheet (`KitOverlay`) | 180 ms; scale 0.96, scrim 18% of the look's ink | A real modal window: Back and accessibility stay on it, and the composer's focus is cleared first, so closing never reopens the keyboard. |
| Sideways pull in a list (`sidewaysPull`) | the system's touch slop; the direction is fixed at slop | Vertical scrolling wins until then. A touch in the first 24 dp is left to the system's Back. |
| Pull that does something (`pulled`) | arms at 64 dp with one haptic tick; past 1.3 marks it moves 30% | It says when it's armed, and stiffens instead of stopping dead. |
| Slide away (`SlideOver`) | past 35% of the width, or a flick over 500 dp/s; else it comes back | Predictive Back and a drag run the same `Animatable`, so a gesture taken back returns smoothly. |
| While following a finger (`settling`) | no animation at all, then the spring on release | The thing is under the finger, not chasing it. |
| Every moving value | read in `graphicsLayer {}` or `offset {}`, never in composition | A moving frame doesn't recompose or re-measure anything. |
| First frame | the list state is created at its place (`rememberLazyListState(index)`), under `key(conversation.id)` | A conversation opens where it should be, not somewhere and then jumping. |
| List items | stable keys everywhere; the inbox moves on the spring with no fades (`movesInList`) | Nothing flashes or replays when a list changes. |
| Off the main thread (`offMain`) | images, posters, voice and thumbnails: `produceState` + `Dispatchers.IO` | No frame waits for disk or a decoder. |
| Remove animations | `Motion.reduced`: everything lands at once | The system's setting is honoured everywhere. |

Timings are starting points from the MacBook's iPhone reference (`docs/messages-imessage-parity-build.md` on the
Messages branch), not measured latency. Android's IME motion stays native, with nothing animated on top.

**What Messages doesn't have:** a press motion (its rows take Compose's instant pressed shade), `contentType` on its
lists, or a frame-time check. `Feel.PRESS_SCALE` is therefore new with the kit, not from the reference.

## Using it on a screen

The kit is `:kit`. An app with `:chat` already has it; any other Compose app adds `implementation(project(":kit"))`.
A page in a WebView links `kit/web/feel.css` (the same numbers, checked by a test) and uses `.feel-press`,
`.feel-arrive`, `.feel-overlay` and the `--feel-*` values.

Start from the screen's Shipped card, keep everything as shipped, and swap only how it moves:

| Instead of | Use |
|---|---|
| `Modifier.clickable { }` on something you can see move | `Modifier.pressable { }`, placed **before** `framed`/`background`, so the frame gives too |
| `animateFloatAsState(x, spring(...))` under a finger | `settling(x, following = dragging)` |
| `Animatable.animateTo(...)` | `moveTo(target)`, or `moveTo(target, Feel.open)` (honours remove-animations) |
| A screen `if`-ed over another | `SlideOver(widthPx, onGone, background) { back, modalChanged -> ... }`; call `back` from its own close |
| A `Dialog` for a menu | `KitOverlay(ink = Pack.foreground, onDismiss, lightBars, enterMillis = Feel.OVERLAY_MS) { ... }` |
| `if (open) Bar()` in a column | `Reveal(open) { Bar() }` |
| A sideways gesture in a list | `Modifier.sidewaysPull(...)` with `pulled(raw, mark)` and `Feel.PULL_MARK` |
| A new item popping in | `Modifier.arriving(key, end = fromMe) { isNew }`, with `NewerThan(newestAtOpen)` if nothing marks new items |
| Reordering rows | `Modifier.movesInList(this)` in the `items` block |
| Disk, decoding, thumbnails in composition | `val x by offMain(key) { load() }` |

Four rules that aren't code:

1. **A moving value is read in `graphicsLayer {}` or `offset {}`,** never in composition. Pass `{ value }`, not `value`.
2. **Don't animate at rest.** Whatever every row of a list pays, it pays while scrolling: an entrance run on each row
   doubled Soma chat's scroll jank until only arriving rows paid.
3. **A list state is made where it opens,** under `key(what)`, so the first frame is right.
4. **New numbers go in `Feel`,** never on a screen. A number that differs from a shipped one is a decision.

## Checking it

Feel can't be proven by a test, so someone's thumb decides. To find problems, a focused walk has `frames` steps
(`tools/walkthrough/README.md`): `scripts/feel-messages.walk` is the reference, `scripts/feel-chat.walk` reads Soma chat
against it. They only report; no number can hold a build, a take or an install.

- Compare only on one run of one emulator, alternating builds. Its 60 Hz, shared GPU and load say nothing of the phone.
- A screen with a pulsing dot draws all the time, so its first-frame number is only the next frame.

## Widgets, later

`.owl` widgets run in the launcher, so what the runtime takes, every widget has with no work of its own. Boss, 2026-10-30:
first prove the kit works, then everything else. So nothing here is in yet; the work is on branch `soma/ui-kit-widgets`.

**It can take:** a node with `onTap` or `onLongPress` giving under the finger; plain `animate x: spring()` as Soma's
spring (`spring(damping, stiffness)` stays as named, and the three bundled widgets that say `spring()` lose their bounce);
a new target for an animated value keeping the speed it had.

**It can't take:**
- `if` and `for` swap their nodes at once. A fade would have to wrap them, which changes the layout widgets were
  written for and would soften a widget that uses `if` for sprites or a blink.
- Scrolling, drags and `onDrag` stay the widget's own: the runtime doesn't know what a drag means.
- Its expressions run on the main thread, by design (they are read while drawing), so the frame budget is theirs.
- A widget's own `Canvas`, `Web` and `Terminal` nodes draw for themselves.

## Follow-ups, one job each

| App | What it needs |
|---|---|
| Widgets | Land `soma/ui-kit-widgets` (above). |
| Launcher home | Measure against Messages first: page swipes, opening and closing a widget, the overview, the drawer, tile press and drag, returning from an app, cover and unfolded. Already on `Motion`. Then `pressable` for rows, menus and tiles. `MotionSourceCheckTest` stays. |
| Messages | `offMain` for its `produceState` + IO loads, once the Messages bugs thread's work has landed. Its rows keep their plain press unless Boss wants more. |
| Soma Notes (`notes/`) | No `:chat`: add `:kit`. Its Compose screens (Stream, Page, Reader) take `pressable`, `SlideOver` for Reader, `KitOverlay` for its dialogs. |
| Desktop | Has what's in `commonMain` from this job, proven by its tests only. Nobody has felt it. Mouse hover, wheel and window resize aren't in the kit. |
| News reader | A WebView in the launcher: link `kit/web/feel.css`, `.feel-press` on its rows and controls. |
| Hunch | Views and WebViews, no Compose: CSS tokens, `kit/web/feel.css` for `paper/`, `.feel-press` on markets and tickets. Its native screens would need Compose first, which isn't worth it for this. |
| The build itself | Soma on the phone is debug-type and debuggable; Messages is a release build. See below. |

## What the emulator measured

The same launcher code on the same emulator, opening Soma chat cold: debuggable (as on his phone) about 1200 ms, not
debuggable about 300 ms, with R8 about 270 ms. Home's page swipes: 14-24% janky frames, against 3%. So much of a good feel
is the build type, which no kit can fix. Whether to change it is Boss's decision, and it has costs (`run-as`, the debug door).
