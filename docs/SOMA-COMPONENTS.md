# Soma components

The shared building blocks every Soma app draws with: the launcher, Soma chat on the phone and the Mac, and
Soma Messages. They live in `chat/src/commonMain/kotlin/dev/omalauncher/ui`, take every color, typeface and line
from the active pack (`Pack`, `ActivePack`), and are tested on the desktop (`./gradlew :chat:desktopTest`).

Boss's rule (2026-10-22): standardize from here. A new screen uses these pieces before it makes its own. A
piece that two apps need moves here.

## In the kit

| Piece | File | What it is |
|---|---|---|
| `SomaComposer`, `ComposerState`, `VoiceRecorder` | `Composer.kt` | The one composer, after Apple's (below). |
| `Waveform` | `Composer.kt` | Loudness bars for a recording, live or played back. |
| `OverlayPanel`, `SectionLabel`, `MenuItems` | `Components.kt` | A framed full-screen panel, a group heading, popup menu rows. |
| `Menu`, `MenuItem`, `MenuTitle` | `Menu.kt` | Popup menus. |
| `Modifier.framed`, `Modifier.panel`, `Modifier.scrap` | `PackAdapter.kt`, `ScrapFrame.kt` | Pack surfaces: framed, panel, scrap. |
| `ThreadDot` | `ThreadDot.kt` | An agent thread's state dot. |
| `MicIcon` | `MicIcon.kt` | The microphone mark. |
| `Motion` | `Motion.kt` | Home's springs and fades; new motion takes its specs from here. |
| `initials` | `Initials.kt` | A name's initials for avatars. |

## SomaComposer

Boss asked to copy Apple's composer. The reference is the Soma Messages prototype
(`~/ClaudeSync/projects/omalauncher/features/soma-messages/prototype.html`, section 6 of PLAN.md there).

- **Layout:** a round + for attachments (`onAttach`). Next to it, a round pill that grows to five lines.
- **The pill's right end:** the microphone while the field is empty, Send once there's text or an attachment.
- **Voice messages** (`voice`, `onSendVoice`) are audio to send, not dictation.
  - Tap the microphone to record and Stop to end, or hold it and let go.
  - Stopping opens a review: Play and the waveform, Send, and × to delete.
  - A recording under one second is dropped.
- **Recording itself** is the platform's job: an app passes a `VoiceRecorder`, which is asked to record only while
  a recording is on screen.
- **Without voice:** an app can put its own button where the microphone would be (`emptyAction`). Soma chat's
  Dictate goes there.
- **Look:** the pill stays round in every look. Its fill, ink and line weight come from the pack, and Send takes
  `sendColor` (a conversation's iMessage or text-message color).
- **Keys:** a hardware Enter sends and Shift+Enter breaks the line. The on-screen keyboard's Return breaks the line.
- **Tests:** `ComposerRulesTest` covers the rules and state, `ComposerTapTest` covers tap, hold, review, send,
  delete and too-short recordings, and `ComposerRenderTest` draws every stage in Manga and City Pop to
  `chat/build/render/composer-*.png`.

Not adopted anywhere yet. Soma chat moves to it in its own slice; Soma Messages starts with it.

## Next candidates

Pieces both Soma Messages plans need, in the order they'd be built. None exist yet.

- **Avatar:** initials or a photo, with the pack's ink ring and an unread badge.
- **Conversation row:** avatar, name, preview, time and unread dot, with swipe actions (Pin, Unread).
- **Message bubble:** incoming and outgoing, with the conversation's color. Manga draws it as a speech balloon
  with a tail. Includes reactions and the voice-message bubble.
- **Back that follows the finger:** predictive back plus a full-surface swipe, on `Motion`'s springs.
- **Split view with a hinge snap:** the unfolded list-and-conversation divider.
