#!/usr/bin/env python3
"""Soma's photo search on the Mac Pro: EmbeddingGemma 2 (LiteRT-LM) turns photos and searches into vectors in one
space, and macOS's Vision reads any text in a photo. The phone sends small copies of its photos to be read once, and
each photo search's words; it keeps what comes back. See docs/SEARCH-PHOTOS-MAC.md.

  GET  /health                -> {"ok": true, "model": ..., "dim": 256, "reads": READS}
  POST /text    {"texts": [...]}                         -> {"vectors": [[...256 floats]]}
  POST /images  {"images": [{"id": "...", "jpeg": "<base64>"}]} -> {"items": [{"id", "vector", "text", "tags"}]}
  POST /rerank  {"query": "...", "docs": ["...", ...]}  -> {"scores": [...]}: how near each doc is to the query
  GET  /spotlight?q=WORDS&n=12  -> {"items": [{"path", "name", "kind", "modified", "size"}]}: Spotlight, in his folders
  GET  /file?path=PATH          -> the file itself, for the phone to keep (only his own files: see offered(); 500 MB at most)

/spotlight and /file answer only devices signed in as this Mac's own Tailscale user (`tailscale whois`, as
tailshare-serve does); the rest give out nothing but numbers.

Vectors are the model's first DIM numbers, scaled to length 1 (its Matryoshka cut), so the phone's own copy of the
text model gives the same ones when the Mac is away. Listens on the Mac Pro's Tailscale address only.
"""
import base64
import json
import os
import re
import select
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse
from collections import OrderedDict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = os.environ.get("EMBED_HOST", "100.120.219.46")
PORT = int(os.environ.get("EMBED_PORT", "8179"))
MODEL = os.path.expanduser(os.environ.get("EMBED_MODEL", "~/.cache/embeddinggemma-2/embeddinggemma-2-text-vision-440m.litertlm"))
DIM = 256
QUERY = "task: search query | text: "
DOC = "title: none | text: "
MAX_BODY = 64 * 1024 * 1024
MAX_FILE = 500 * 1024 * 1024
HOME = os.path.realpath(os.path.expanduser("~"))
# What the phone has of a photo when it was read by a server of this many reads; it asks again for photos read by an
# older one. 1: vector and text. 2: and Vision's tags (what is in it, as words).
READS = 2

_engine = None
_lock = threading.Lock()


def engine():
    """The model, loaded once on first use; one request reads it at a time."""
    global _engine
    if _engine is None:
        from litert_lm import Backend, EmbeddingEngine  # noqa: PLC0415

        # GPU (Metal) for both parts: on the M2 Ultra a photo takes ~70 ms against ~970 ms on the CPU.
        _engine = EmbeddingEngine(MODEL, backend=Backend.GPU(), vision_backend=Backend.GPU())
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
    # The encoded file's bytes (JPEG, PNG...); LiteRT-LM scales it to the vision encoder. No prefix on images.
    from litert_lm import Content  # noqa: PLC0415

    with _lock:
        return cut(engine().compute_embedding(Content.ImageBytes(jpeg)).embedding)


def doc_vector(text):
    """A result's words (a file name, a title) as a document, cached: the same names come back letter after letter."""
    with _lock:
        hit = _docs.get(text)
        if hit is not None:
            _docs.move_to_end(text)
            return hit
        v = cut(engine().compute_embedding(DOC + text.strip()).embedding)
        _docs[text] = v
        if len(_docs) > 4000:
            _docs.popitem(last=False)
        return v


_docs = OrderedDict()


def rerank(query, docs):
    q = text_vector(query)
    return [round(sum(a * b for a, b in zip(q, doc_vector(d))), 5) for d in docs]


def cg_image(jpeg):
    import Quartz  # noqa: PLC0415
    from Foundation import NSData  # noqa: PLC0415

    data = NSData.dataWithBytes_length_(jpeg, len(jpeg))
    source = Quartz.CGImageSourceCreateWithData(data, None)
    return Quartz.CGImageSourceCreateImageAtIndex(source, 0, None)


def read_tags(jpeg):
    """What is in the photo as words ("dog", "beach", "birthday cake"), by Vision's on-device classifier: the phone
    keeps them so a photo is found by words too, even with the Mac away. Empty if Vision isn't there."""
    try:
        import Vision  # noqa: PLC0415

        request = Vision.VNClassifyImageRequest.alloc().init()
        handler = Vision.VNImageRequestHandler.alloc().initWithCGImage_options_(cg_image(jpeg), None)
        handler.performRequests_error_([request], None)
        found = sorted(((o.confidence(), str(o.identifier())) for o in (request.results() or []) if o.confidence() >= TAG_FLOOR), reverse=True)
        return [name.replace("_", " ") for _, name in found[:TAGS]]
    except Exception:
        return []


TAG_FLOOR = 0.3
TAGS = 10


def read_text(jpeg):
    """What the photo says (signs, screenshots), by macOS's Vision; empty if there is none or Vision isn't there."""
    try:
        import Vision  # noqa: PLC0415

        image = cg_image(jpeg)
        request = Vision.VNRecognizeTextRequest.alloc().init()
        request.setRecognitionLevel_(Vision.VNRequestTextRecognitionLevelAccurate)
        handler = Vision.VNImageRequestHandler.alloc().initWithCGImage_options_(image, None)
        handler.performRequests_error_([request], None)
        return " ".join(o.topCandidates_(1)[0].string() for o in (request.results() or []))[:4000]
    except Exception:
        return ""


def tailscale(*args, timeout=5):
    # The app's own binary is the CLI that works on a Mac; a symlink to it crashes. It needs TERM set to act as one.
    for cli in ("/Applications/Tailscale.app/Contents/MacOS/Tailscale", shutil.which("tailscale"), "/opt/homebrew/bin/tailscale"):
        if cli and os.path.exists(cli):
            break
    else:
        raise RuntimeError("Tailscale is not installed here")
    env = dict(os.environ, TERM=os.environ.get("TERM") or "dumb")
    out = subprocess.run([cli, *args], capture_output=True, text=True, timeout=timeout, env=env)
    if out.returncode:
        raise RuntimeError((out.stderr or out.stdout).strip() or "tailscale failed")
    return out.stdout


_owners = {}
_owner_lock = threading.Lock()


def owner(address):
    """Whether [address] is a device signed in as this Mac's own Tailscale user; remembered ten minutes."""
    with _owner_lock:
        hit = _owners.get(address)
        if hit and time.time() - hit[1] < 600:
            return hit[0]
    try:
        status = json.loads(tailscale("status", "--json"))
        me = status["User"][str(status["Self"]["UserID"])]["LoginName"]
        who = json.loads(tailscale("whois", "--json", address))
        ok = (who.get("UserProfile") or {}).get("LoginName") == me
    except Exception as e:
        sys.stderr.write("embed-server: could not check %s: %s\n" % (address, e))
        ok = False
    with _owner_lock:
        _owners[address] = (ok, time.time())
    return ok


# Where his own files are: Spotlight is asked only here, so a search finds his papers, not a library's source.
ROOTS = ("Desktop", "Documents", "Downloads", "Pictures", "Movies", "Music", "Inbox", "Pocket", "ClaudeSync",
         "Library/Mobile Documents/com~apple~CloudDocs")
# Never offered: caches, and package, environment or build trees, wherever they are.
SKIPPED = ("/.Trash/", "/node_modules/", "/.git/", "/build/", "/DerivedData/", "/Caches/", "/.cache/", "/venv/", "/.venv/",
           "/__pycache__/", "/site-packages/", "/uv-cache/", "/env/", "/Pods/", "/target/", "/dist/")
# What a person opens, ahead of code and data when both match as well.
DOCUMENTS = {"pdf", "doc", "docx", "pages", "key", "numbers", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf", "csv",
             "jpg", "jpeg", "png", "heic", "gif", "mov", "mp4", "m4a", "mp3", "wav", "zip", "epub", "html"}


def offered(path):
    """Whether [path] (a real path) is one of his own files, for /spotlight and /file alike: inside one of [ROOTS],
    with nothing hidden anywhere on the way (.ssh, .env, secrets folders), and not in a cache, package or build tree,
    an app or a photo library. The Mac Pro's photo thread found /file reading any path in ~ (2026-10-21)."""
    roots = [os.path.join(HOME, r) + "/" for r in ROOTS]
    inside = next((r for r in roots if path.startswith(r)), None)
    if inside is None:
        return False
    rel = "/" + path[len(inside):] + "/"
    return not (any(x in rel for x in SKIPPED) or "/." in rel or ".app/" in rel or ".photoslibrary/" in rel)


def spotlight(q, n):
    """His files (in [ROOTS]) whose name or text has every word of [q], by Spotlight's own index (mdfind): names
    first, documents before code, then the newest. A second at most: past that what has come is what there is."""
    # Spotlight's query language: quotes, stars and backslashes in a word would change what it asks.
    words = [w for w in q.translate(str.maketrans('"*\\', "   ")).split() if w][:6]
    if not words:
        return []
    # Each word begins a word of the name or the text ("lease" finds Lease_2026.pdf, never "release").
    each = ['(kMDItemDisplayName == "%s*"cdw || kMDItemTextContent == "%s*"cdw)' % (w, w) for w in words]
    try:
        # Asked over all of ~ and kept to [ROOTS] by offered(): scoped to ~/Downloads or iCloud Drive itself, mdfind
        # waits on macOS's privacy check and never answers (found by the Mac Pro's photo thread, 2026-10-21).
        proc = subprocess.Popen(["mdfind", "-onlyin", HOME, " && ".join(each)], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    except OSError:
        return []
    paths = []
    deadline = time.time() + 1.0
    # Ends mdfind even if a read is somehow still waiting.
    timer = threading.Timer(1.2, proc.kill)
    timer.start()
    try:
        while True:
            # The deadline holds even while Spotlight says nothing: a read only waits as long as is left.
            left = deadline - time.time()
            if left <= 0 or not select.select([proc.stdout], [], [], left)[0]:
                break
            line = proc.stdout.readline()
            if not line:
                break
            p = line.rstrip("\n")
            if offered(os.path.realpath(p)):
                paths.append(p)
                if len(paths) >= 400:
                    break
    finally:
        timer.cancel()
        proc.kill()
    lower = [w.lower() for w in words]
    items = []
    for p in paths:
        try:
            st = os.stat(p)
        except OSError:
            continue
        name = os.path.basename(p)
        folder = os.path.isdir(p)
        if folder and name.endswith((".app", ".photoslibrary", ".bundle")):
            continue
        items.append({
            "path": p, "name": name, "kind": "folder" if folder else (os.path.splitext(name)[1][1:].lower() or "file"),
            "modified": int(st.st_mtime * 1000), "size": 0 if folder else st.st_size,
            "byName": all(re.search(r"(?:^|[^a-z0-9])" + re.escape(w), name.lower()) for w in lower),
        })
    items.sort(key=lambda it: (not it["byName"], it["kind"] not in DOCUMENTS, -it["modified"]))
    return items[:n]


class Handler(BaseHTTPRequestHandler):
    def reply(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        args = urllib.parse.parse_qs(url.query)
        if url.path == "/health":
            return self.reply(200, {"ok": os.path.exists(MODEL), "model": os.path.basename(MODEL), "dim": DIM, "reads": READS})
        if url.path in ("/spotlight", "/file") and not owner(self.client_address[0]):
            return self.reply(403, {"error": "not one of this Mac's owner's devices"})
        if url.path == "/spotlight":
            q = (args.get("q") or [""])[0]
            n = max(1, min(40, int((args.get("n") or ["12"])[0] or 12)))
            return self.reply(200, {"items": spotlight(q, n)})
        if url.path == "/file":
            path = os.path.realpath((args.get("path") or [""])[0])
            if not offered(path) or not os.path.isfile(path):
                return self.reply(404, {"error": "no such file among his own"})
            size = os.path.getsize(path)
            if size > MAX_FILE:
                return self.reply(413, {"error": "too large"})
            # Opened before answering: if macOS hasn't let this service into the folder, say so instead of a 200 with
            # nothing after it.
            try:
                f = open(path, "rb")
            except PermissionError:
                return self.reply(403, {"error": "macOS hasn't let the embed-server into this folder"})
            with f:
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Length", str(size))
                self.send_header("Content-Disposition", "attachment; filename*=UTF-8''" + urllib.parse.quote(os.path.basename(path)))
                self.end_headers()
                shutil.copyfileobj(f, self.wfile, 1024 * 1024)
            return None
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
                    items.append({"id": it["id"], "vector": image_vector(jpeg), "text": read_text(jpeg), "tags": read_tags(jpeg)})
                return self.reply(200, {"items": items})
            if self.path == "/rerank":
                return self.reply(200, {"scores": rerank(body["query"], [str(d) for d in body["docs"][:40]])})
            self.reply(404, {"error": "not found"})
        except Exception as e:  # one bad photo or request must not take the server down
            self.reply(500, {"error": str(e)})

    def log_message(self, fmt, *args):
        sys.stderr.write("embed-server: " + fmt % args + "\n")


if __name__ == "__main__":
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
