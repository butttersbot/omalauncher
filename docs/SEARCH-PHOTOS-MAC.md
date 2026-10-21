# Photo search on the Mac Pro

Soma's search finds photos, videos and GIFs by what is in them (Boss, 2026-10-20). The model that reads them,
EmbeddingGemma 2, runs on the Mac Pro, not on the phone, as Whisper does; the phone keeps only what it says of each
photo. When the Mac can't be reached, the phone reads a search's words itself with the same model's text part,
loaded for that search and freed after.

## The Mac side (`tools/embed-mac/`)
- `embed-server.py`: EmbeddingGemma 2 (LiteRT-LM, `litert-community/embeddinggemma-2-740m-litert-lm`, the
  text + vision bundle, in `~/.cache/embeddinggemma-2/embeddinggemma-2-text-vision.litertlm`) and macOS Vision for
  text in photos. Python venv in `~/.local/share/embed-server/venv` (`litert-lm`, `pyobjc-framework-Vision`,
  `pyobjc-framework-Quartz`).
- Listens on the Mac Pro's Tailscale address only, `100.120.219.46:8179`, never 0.0.0.0.
- A login service, `dev.omalauncher.embed-server`, restarted whenever it ends: `tools/embed-mac/install`.
  Log: `~/Library/Logs/embed-server.log`.
- `GET /health`; `POST /text {"texts": [...]}`; `POST /images {"images": [{"id", "jpeg": base64}]}`. Vectors are
  the model's first 256 numbers scaled to length 1; queries carry the model's `task: search query | text:` prefix.

## The phone side (`search/PhotoIndex.kt`)
- Photos, videos and GIFs from the phone's media store, as 384-pixel copies, sent in batches while the phone is on
  unmetered Wi-Fi; what comes back (a vector, and any text in the photo) is kept in `files/search/photos.db`.
- A search asks the Mac for its words' vector (under a second); if the Mac doesn't answer, the phone's own copy of
  the text model is used. Either way the photos are compared on the phone, in a few milliseconds.
- Another server can be named in Linux's `~/.config/omalauncher/embed-server.txt` (one line, `http://host:port`).
