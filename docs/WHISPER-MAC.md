# Dictation on the Mac Pro

Oma's voice input is read by Whisper on the Mac Pro, not on the phone (Boss, 2026-10-17: the phone's two models
took some 500 MB of the launcher's memory for good). The phone only records.

## The Mac side
- whisper.cpp's `whisper-server` (Homebrew, `/opt/homebrew/bin`) with `ggml-large-v3-turbo.bin` from
  `~/.cache/whisper-cpp/`: more accurate than the phone's small.en, and it reads 3 s of speech in under a second
  on the M2 Ultra.
- Listens on the Mac Pro's Tailscale address only, `100.120.219.46:8178`, never 0.0.0.0.
- A login service, `dev.omalauncher.whisper-server`, restarted whenever it ends:
  `tools/whisper-mac/install` copies `tools/whisper-mac/dev.omalauncher.whisper-server.plist` to
  `~/Library/LaunchAgents/` and starts it. Log: `~/Library/Logs/whisper-server.log`.
- Check it from anything on the tailnet:
  `curl http://100.120.219.46:8178/inference -F file=@a.wav -F response_format=json`

## The phone side (`voice/WhisperMac.kt`, `voice/Whisper.kt`)
- Each dictation first tries to connect to the server (1 s). If it answers, the live readings and the final one
  are all made on the Mac.
- If it doesn't, or a reading fails or times out (6 s live; 15 s plus the audio's length for the final one), the
  rest of that dictation is read by the phone's own models, loaded for it and freed as soon as it ends, however
  it ends (finished, cancelled, or replaced by a new one).
- Another server can be named in Linux's `~/.config/omalauncher/whisper-server.txt` (one line, `http://host:port`);
  the emulator uses `http://10.0.2.2:8178` for a server on the Mac's own 127.0.0.1.
