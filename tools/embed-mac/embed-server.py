#!/usr/bin/env python3
"""Soma's photo search on the Mac Pro: EmbeddingGemma 2 (LiteRT-LM) turns photos and searches into vectors in one
space, and macOS's Vision reads any text in a photo. The phone sends small copies of its photos to be read once, and
each photo search's words; it keeps what comes back. See docs/SEARCH-PHOTOS-MAC.md.

  GET  /health                -> {"ok": true, "model": ..., "dim": 256}
  POST /text    {"texts": [...]}                         -> {"vectors": [[...256 floats]]}
  POST /images  {"images": [{"id": "...", "jpeg": "<base64>"}]} -> {"items": [{"id", "vector", "text"}]}

Vectors are the model's first DIM numbers, scaled to length 1 (its Matryoshka cut), so the phone's own copy of the
text model gives the same ones when the Mac is away. Listens on the Mac Pro's Tailscale address only.
"""
import base64
import io
import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = os.environ.get("EMBED_HOST", "100.120.219.46")
PORT = int(os.environ.get("EMBED_PORT", "8179"))
MODEL = os.path.expanduser(os.environ.get("EMBED_MODEL", "~/.cache/embeddinggemma-2/embeddinggemma-2-text-vision.litertlm"))
DIM = 256
QUERY = "task: search query | text: "
MAX_BODY = 64 * 1024 * 1024

_engine = None
_lock = threading.Lock()


def engine():
    """The model, loaded once on first use; one request reads it at a time."""
    global _engine
    if _engine is None:
        from litert_lm import Backend, EmbeddingEngine  # noqa: PLC0415

        _engine = EmbeddingEngine(MODEL, backend=Backend.GPU())
        _engine.__enter__()
    return _engine


def cut(vector):
    v = list(vector)[:DIM]
    n = sum(x * x for x in v) ** 0.5 or 1.0
    return [round(x / n, 6) for x in v]


def text_vector(text):
    with _lock:
        return cut(engine().compute_embedding(QUERY + text.strip()).embedding)


def image_vector(jpeg):
    # LiteRT-LM takes an image as input data next to text; see the embedding models guide.
    from litert_lm import InputData  # noqa: PLC0415

    with _lock:
        return cut(engine().compute_embedding([InputData.Image(jpeg)]).embedding)


def read_text(jpeg):
    """What the photo says (signs, screenshots), by macOS's Vision; empty if there is none or Vision isn't there."""
    try:
        import Quartz  # noqa: PLC0415
        import Vision  # noqa: PLC0415
        from Foundation import NSData  # noqa: PLC0415

        data = NSData.dataWithBytes_length_(jpeg, len(jpeg))
        source = Quartz.CGImageSourceCreateWithData(data, None)
        image = Quartz.CGImageSourceCreateImageAtIndex(source, 0, None)
        request = Vision.VNRecognizeTextRequest.alloc().init()
        request.setRecognitionLevel_(Vision.VNRequestTextRecognitionLevelAccurate)
        handler = Vision.VNImageRequestHandler.alloc().initWithCGImage_options_(image, None)
        handler.performRequests_error_([request], None)
        return " ".join(o.topCandidates_(1)[0].string() for o in (request.results() or []))[:4000]
    except Exception:
        return ""


class Handler(BaseHTTPRequestHandler):
    def reply(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            return self.reply(200, {"ok": os.path.exists(MODEL), "model": os.path.basename(MODEL), "dim": DIM})
        self.reply(404, {"error": "not found"})

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0 or n > MAX_BODY:
            return self.reply(413, {"error": "too large"})
        try:
            body = json.loads(self.rfile.read(n))
            if self.path == "/text":
                return self.reply(200, {"vectors": [text_vector(t) for t in body["texts"][:16]]})
            if self.path == "/images":
                items = []
                for it in body["images"][:32]:
                    jpeg = base64.b64decode(it["jpeg"])
                    items.append({"id": it["id"], "vector": image_vector(jpeg), "text": read_text(jpeg)})
                return self.reply(200, {"items": items})
            self.reply(404, {"error": "not found"})
        except Exception as e:  # one bad photo or request must not take the server down
            self.reply(500, {"error": str(e)})

    def log_message(self, fmt, *args):
        sys.stderr.write("embed-server: " + fmt % args + "\n")


if __name__ == "__main__":
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
