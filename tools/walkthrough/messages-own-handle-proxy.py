#!/usr/bin/env python3
"""Plays a Mac that names the owner's reactions by another of his handles, in front of the isolated DEMO service.

Live, 2026-10-30: the Direct service sends from his number, but chat.db's rows carry his email, so his own reactions
came back as fromMe false with sender his email, which health lists among his handles. Usage: HOST PORT UP_PORT
"""
import http.client, json, sys, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST, PORT, UP = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
OTHER = "owner@example.test"


def renamed(m):
    for t in m.get("tapbacks") or []:
        if t.get("fromMe"):
            t["fromMe"] = False
            t["sender"] = OTHER


def rewrite(o, path):
    if path.endswith("/health"):
        o.setdefault("account", {}).setdefault("handles", []).append(OTHER)
    for m in o.get("messages") or []:
        renamed(m)
    for c in o.get("chats") or []:
        if isinstance(c.get("lastMessage"), dict):
            renamed(c["lastMessage"])
    renamed(o)
    if isinstance(o.get("message"), dict):
        renamed(o["message"])
    return o


class Proxy(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def forward(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n) if n else None
        headers = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "connection")}
        c = http.client.HTTPConnection("127.0.0.1", UP, timeout=120)
        c.request(self.command, self.path, body=body, headers=headers)
        r = c.getresponse()
        path = urllib.parse.urlparse(self.path).path
        kind = r.getheader("Content-Type") or ""
        if "json" in kind and r.getheader("Content-Length") is not None or path.endswith(("/health", "/messages", "/chats")):
            raw = r.read()
            try:
                raw = json.dumps(rewrite(json.loads(raw), path)).encode()
            except Exception:
                pass
            self.send_response(r.status)
            self.send_header("Content-Type", kind or "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
            self.wfile.flush()
            c.close()
            return
        self.send_response(r.status)
        streamed = r.getheader("Content-Length") is None
        for k, v in r.getheaders():
            if k.lower() not in ("connection", "transfer-encoding", "date", "server"):
                self.send_header(k, v)
        if streamed:
            self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        while True:
            line = r.readline() if streamed else r.read1(65536)
            if not line:
                break
            if streamed and line.startswith(b"data:"):
                try:
                    o = json.loads(line[5:])
                    if isinstance(o, dict):
                        line = b"data: " + json.dumps(rewrite(o, path)).encode() + b"\n"
                except Exception:
                    pass
            if streamed:
                self.wfile.write(b"%x\r\n%s\r\n" % (len(line), line))
            else:
                self.wfile.write(line)
            self.wfile.flush()
        if streamed:
            self.wfile.write(b"0\r\n\r\n")
        c.close()

    do_GET = do_POST = forward


class Server(ThreadingHTTPServer):
    # The phone opens several connections at once; the default queue of 5 drops some, and its requests then time out.
    request_queue_size = 128


Server((HOST, PORT), Proxy).serve_forever()
