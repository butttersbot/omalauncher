# Photo search on the Mac Pro

Soma's search finds photos, videos and GIFs by what is in them (Boss, 2026-10-20). The model that reads them,
EmbeddingGemma 2, runs on the Mac Pro, not on the phone, as Whisper does; the phone keeps only what it says of each
photo. When the Mac can't be reached, the phone reads a search's words itself with the same model's text part,
loaded for that search and freed after.

## The Mac side (`tools/embed-mac/`)
- `embed-server.py`: EmbeddingGemma 2 on LiteRT-LM 0.18.0, the text + vision bundle
  (`litert-community/embeddinggemma-2-text-vision-440m-litert-lm`, `embeddinggemma-2-text-vision-440m.litertlm`,
  in `~/.cache/embeddinggemma-2/`), on the GPU (69 ms a photo, 15 ms a query on the M2 Ultra), and macOS Vision
  for text in photos. Python venv in `~/.local/share/embed-server/venv` (`litert-lm==0.18.0`,
  `pyobjc-framework-Vision`, `pyobjc-framework-Quartz`).
- The phone's fallback uses the text-only bundle (`litert-community/embeddinggemma-2-text-270m-litert-lm`,
  `embeddinggemma-2-text-270m.litertlm`, 164,626,432 bytes): its query vectors are bit-identical to the text +
  vision bundle's (checked 2026-10-20, five queries, GPU and CPU).
- Closeness sits in a narrow band (about 0.55 to 0.85; unrelated photos near 0.6): rank, never cut at a fixed value.
- Listens on the Mac Pro's Tailscale address only, `100.120.219.46:8179`, never 0.0.0.0.
- A login service, `dev.omalauncher.embed-server`, restarted whenever it ends: `tools/embed-mac/install`.
  Log: `~/Library/Logs/embed-server.log`.
- `GET /health`; `POST /text {"texts": [...]}`; `POST /images {"images": [{"id", "jpeg": base64}]}`. Vectors are
  the model's first 256 numbers scaled to length 1; queries carry the model's `task: search query | text:` prefix.
- Each photo also gets Vision's tags (`VNClassifyImageRequest`, confidence 0.3 or more, ten at most: "dog",
  "beach", "birthday cake"), which the phone keeps as words, so a photo is found by them with the Mac away too.
  `/health` says `reads`: the phone sends again the photos a server with fewer reads saw (2 = tags).
- `POST /rerank {"query", "docs"}`: how near each result's words are to the query (documents carry
  `title: none | text:`), for ordering files when typing pauses. Document vectors are cached.
- `GET /spotlight?q=&n=` (Spotlight over `~`, names first) and `GET /file?path=` (a file under `~`, 500 MB at
  most) answer only devices of this Mac's own Tailscale user, checked with `tailscale whois`.

## The phone side (`search/PhotoIndex.kt`)
- Photos, videos and GIFs from the phone's media store, as 384-pixel copies, sent in batches while the phone is on
  unmetered Wi-Fi; what comes back (a vector, and any text in the photo) is kept in `files/search/photos.db`.
- A search asks the Mac for its words' vector (under a second); if the Mac doesn't answer, the phone's own copy of
  the text model is used. Either way the photos are compared on the phone, in a few milliseconds.
- A video is read from three frames (at 15, 50 and 85% of it); it is found by whichever is nearest.
- Another server can be named in Linux's `~/.config/omalauncher/embed-server.txt` (one line, `http://host:port`).
