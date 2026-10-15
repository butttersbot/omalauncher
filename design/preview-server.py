#!/usr/bin/env python3
"""Serves the design mock-ups (like `python3 -m http.server`) and keeps the keyboard practice page's tap log.

POST /kb-log with a JSON list of taps appends them, one per line, to ~/.local/share/oma-kb/taps.jsonl, outside Git.
Run from the checkout: oma keep run mockup-preview -- python3 design/preview-server.py
"""
import functools
import http.server
import json
import os
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
LOG = pathlib.Path.home() / '.local/share/oma-kb/taps.jsonl'


class Handler(http.server.SimpleHTTPRequestHandler):
    def do_POST(self):
        if self.path != '/kb-log':
            self.send_error(404)
            return
        n = int(self.headers.get('Content-Length') or 0)
        if n <= 0 or n > 2_000_000:
            self.send_error(413)
            return
        try:
            taps = json.loads(self.rfile.read(n))
            assert isinstance(taps, list)
        except Exception:
            self.send_error(400)
            return
        LOG.parent.mkdir(parents=True, exist_ok=True)
        with LOG.open('a') as f:
            for t in taps:
                f.write(json.dumps(t, ensure_ascii=False) + '\n')
        self.send_response(204)
        self.end_headers()

    def end_headers(self):
        # The page changes often while we tune it; never let Chrome serve a stale copy.
        self.send_header('Cache-Control', 'no-store')
        super().end_headers()


if __name__ == '__main__':
    os.chdir(ROOT)
    http.server.ThreadingHTTPServer(('127.0.0.1', 8765), functools.partial(Handler, directory=str(ROOT))).serve_forever()
