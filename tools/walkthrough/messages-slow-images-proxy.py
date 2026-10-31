#!/usr/bin/env python3
"""A slow network for the Messages image walk, in front of the isolated DEMO service.

Attachments arrive LAG seconds late, so a walk can see the chat before and after they load. The demo's sent files
carry no dimensions; the Mac's do, so with DIMS=1 each picture's real size is added to the history the phone reads.
Never logs Authorization. Usage: LISTEN_HOST LISTEN_PORT UP_PORT LAG DIMS
"""
import http.client, io, json, sys, time, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from PIL import Image

HOST, PORT, UP, LAG, DIMS = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), float(sys.argv[4]), sys.argv[5] == "1"
SIZES = {}


def size_of(attachment, auth):
    i = attachment["id"]
    if i not in SIZES:
        c = http.client.HTTPConnection("127.0.0.1", UP, timeout=10)
        c.request("GET", "/v1/attachments/" + urllib.parse.quote(i, safe=""), headers={"Authorization": auth})
        SIZES[i] = Image.open(io.BytesIO(c.getresponse().read())).size
        c.close()
    return SIZES[i]


class Proxy(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def forward(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n) if n else None
        if self.path.startswith("/v1/attachments/"):
            time.sleep(LAG)
        headers = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "connection")}
        c = http.client.HTTPConnection("127.0.0.1", UP, timeout=120)
        c.request(self.command, self.path, body=body, headers=headers)
        r = c.getresponse()
        path = urllib.parse.urlparse(self.path).path
        if DIMS and self.command == "GET" and path.endswith("/messages") and r.status == 200:
            o = json.loads(r.read())
            for m in o.get("messages", []):
                for a in m.get("attachments", []):
                    if a.get("mime", "").startswith("image/") and "width" not in a:
                        a["width"], a["height"] = size_of(a, self.headers.get("Authorization", ""))
            data = json.dumps(o).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
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
            d = r.read1(65536)
            if not d:
                break
            if streamed:
                self.wfile.write(b"%x\r\n%s\r\n" % (len(d), d))
            else:
                self.wfile.write(d)
            self.wfile.flush()
        if streamed:
            self.wfile.write(b"0\r\n\r\n")
        c.close()

    do_GET = do_POST = forward


ThreadingHTTPServer((HOST, PORT), Proxy).serve_forever()
