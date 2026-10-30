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
