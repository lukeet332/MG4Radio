"""Fake DiAuto radio bridge (same endpoints/JSON as RadioBridgeServer) for testing MG4 Radio in the DHU."""
import json, struct, zlib, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

DAB = [dict(key="dab:3:49761:12289:0", name="Absolute Radio", type=4, frequencyKhz=225648, favourite=True, nowPlaying="Oasis - Wonderwall"),
       dict(key="dab:3:50466:12289:0", name="Heart UK", type=4, frequencyKhz=225648, favourite=False, nowPlaying="Dua Lipa - Levitating"),
       dict(key="dab:5:50178:12290:0", name="BBC Radio 2", type=4, frequencyKhz=227360, favourite=True, nowPlaying="")]
FM = [dict(key="2:88100", name="", type=2, frequencyKhz=88100, favourite=False, nowPlaying=""),
      dict(key="2:97600", name="Radio X", type=2, frequencyKhz=97600, favourite=True, nowPlaying="")]
state = dict(list=DAB, idx=0, playing=True)

def png(w, h, rgb):
    raw = b"".join(b"\x00" + bytes(rgb) * w for _ in range(h))
    chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")

LOGOS = {DAB[0]["key"]: png(320, 240, (220, 30, 40)), DAB[1]["key"]: png(320, 240, (230, 0, 120))}

def current():
    s = dict(state["list"][state["idx"]]); s["playing"] = state["playing"]; return s

class H(BaseHTTPRequestHandler):
    def send(self, code, ctype, body):
        self.send_response(code); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def ok(self, obj): self.send(200, "application/json", json.dumps(obj).encode())
    def do_GET(self):
        u = urlparse(self.path); q = parse_qs(u.query); p = u.path
        if p == "/radio/state": return self.ok(current())
        if p == "/radio/logo.png":
            logo = LOGOS.get(current()["key"])
            return self.send(200, "image/png", logo) if logo else self.send(404, "text/plain", b"")
        if p == "/radio/favourites": return self.ok(dict(stations=[s for s in DAB + FM if s["favourite"]]))
        if p == "/radio/stations": return self.ok(dict(stations=FM if q.get("type") == ["2"] else DAB))
        if p == "/radio/play": state["playing"] = True
        elif p == "/radio/pause": state["playing"] = False
        elif p == "/radio/next": state["idx"] = (state["idx"] + 1) % len(state["list"]); state["playing"] = True
        elif p == "/radio/previous": state["idx"] = (state["idx"] - 1) % len(state["list"]); state["playing"] = True
        elif p == "/radio/tune":
            key = q["key"][0]
            for lst in (DAB, FM):
                for i, s in enumerate(lst):
                    if s["key"] == key: state.update(list=lst, idx=i, playing=True)
        else: return self.send(404, "text/plain", b"")
        print("CMD", p, q, "->", current()["key"], current()["playing"], flush=True)
        self.ok(dict(ok=True))
    def log_message(self, *a): pass

ThreadingHTTPServer(("0.0.0.0", 30519), H).serve_forever()
