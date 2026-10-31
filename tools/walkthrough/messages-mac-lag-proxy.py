#!/usr/bin/env python3
"""Plays a Mac whose history lags its sends, in front of the isolated DEMO service, for the tapback walk.

The Direct service sends through its transport, but reads history from chat.db, which only has a tapback once
Messages.app has received Apple's copy. Here a tapback is answered at once and reaches the demo LAG seconds later.
Logs requests (never Authorization) to the log path. Usage: LISTEN_HOST LISTEN_PORT UP_PORT LAG LOG
"""
import asyncio, re, sys, time

HOST, PORT, UP, LAG, LOG = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), float(sys.argv[4]), sys.argv[5]
OK = b'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 11\r\n\r\n{"ok":true}'


def log(*a):
    with open(LOG, "a") as f:
        f.write(f"{time.time():.3f} " + " ".join(map(str, a)) + "\n")


async def later(raw):
    await asyncio.sleep(LAG)
    r, w = await asyncio.open_connection("127.0.0.1", UP)
    w.write(raw)
    await w.drain()
    await r.read(65536)
    w.close()
    log("APPLIED tapback")


async def client_to_up(r, w, client):
    buf = b""
    while True:
        d = await r.read(65536)
        if not d:
            break
        buf += d
        while True:
            i = buf.find(b"\r\n\r\n")
            if i < 0:
                break
            head = buf[:i].decode("latin1")
            m = re.search(r"content-length: *(\d+)", head, re.I)
            n = int(m.group(1)) if m else 0
            if "chunked" in head.lower():
                w.write(buf)
                await w.drain()
                buf = b""
                break
            if len(buf) < i + 4 + n:
                break
            raw, buf = buf[: i + 4 + n], buf[i + 4 + n:]
            line = head.split("\r\n")[0]
            log(line.split(" HTTP")[0][-70:])
            if line.startswith("POST") and "/tapback" in line:
                log("TAPBACK", raw[i + 4 :].decode("utf8", "replace")[:160])
                asyncio.create_task(later(raw.replace(b"keep-alive", b"close")))
                client.write(OK)
                await client.drain()
            else:
                w.write(raw)
                await w.drain()
    w.close()


async def up_to_client(r, w):
    while True:
        d = await r.read(65536)
        if not d:
            break
        w.write(d)
        await w.drain()
    w.close()


async def conn(cr, cw):
    sr, sw = await asyncio.open_connection("127.0.0.1", UP)
    try:
        await asyncio.gather(client_to_up(cr, sw, cw), up_to_client(sr, cw))
    except Exception as e:
        log("ERR", type(e).__name__)
    finally:
        for w in (cw, sw):
            try:
                w.close()
            except Exception:
                pass


async def main():
    async with await asyncio.start_server(conn, HOST, PORT) as s:
        await s.serve_forever()


asyncio.run(main())
