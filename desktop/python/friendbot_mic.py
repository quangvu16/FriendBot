#!/usr/bin/env python3
"""FriendBot Mic for desktop (macOS, Windows, Linux).

Your computer's microphone speaks to the FriendBot robot phone's Voice Control page,
exactly like the phone apps' two pages:

* Remote Mic   — same WiFi. Finds the robot phone by WiFi broadcast (or its QR / IP),
                 connects over TCP (port 47821) and streams the microphone.
* Internet Mic — any WiFi or 4G/5G. Meets the robot phone in a private room on a free
                 public MQTT broker (HiveMQ, EMQX as backup), end-to-end encrypted with
                 AES-256-GCM using the key in the robot phone's Internet QR.

Audio: 16 kHz mono, 60 ms frames, raw Opus (the same as the phones), so Xiaozhi on the robot
phone hears it directly. Xiaozhi's replies play on the robot phone.

Run:  python3 friendbot_mic.py                 (window)
      python3 friendbot_mic.py --lan [IP]      (terminal: Enter = talk / stop)
      python3 friendbot_mic.py --net "friendbot://net?room=…&key=…&name=…"
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import queue
import random
import socket
import ssl
import string
import sys
import threading
import time
import urllib.parse
from dataclasses import dataclass
from typing import Callable, List, Optional

APP_NAME = "FriendBot Mic"
DISCOVERY_PORT = 47820
RELAY_PORT = 47821
PROBE = b"FRIENDBOT?"
# (name, host, port, TLS) — secure WebSocket, path /mqtt; the same brokers as the phones.
BROKERS = [
    ("hivemq", "broker.hivemq.com", 8884, True),
    ("emqx", "broker.emqx.io", 8084, True),
]
SAMPLE_RATE = 16000
FRAME = 960  # 60 ms

def _dumps(obj) -> str:
    """Compact JSON, exactly like the phones send."""
    return json.dumps(obj, separators=(",", ":"))


# ----------------------------------------------------------------------------- QR codes


@dataclass
class LanInfo:
    ip: str
    port: int
    name: str


@dataclass
class NetInfo:
    room: str
    key: str
    name: str


def _query(text: str, prefix: str) -> Optional[dict]:
    if not text or not text.startswith(prefix):
        return None
    out = {}
    for part in text[len(prefix):].split("&"):
        if "=" in part:
            k, v = part.split("=", 1)
            out[k] = urllib.parse.unquote_plus(v)
    return out


def parse_lan_qr(text: str) -> Optional[LanInfo]:
    """friendbot://mic?ip=192.168.1.23&port=47821&name=Joy%204 (the Remote Mic QR)."""
    q = _query(text.strip(), "friendbot://mic?")
    if not q:
        return None
    ip = q.get("ip", "")
    parts = ip.split(".")
    if len(parts) != 4 or not all(p.isdigit() and 0 <= int(p) <= 255 for p in parts):
        return None
    try:
        port = int(q.get("port", RELAY_PORT))
    except ValueError:
        port = RELAY_PORT
    return LanInfo(ip, port, q.get("name") or ip)


def parse_net_qr(text: str) -> Optional[NetInfo]:
    """friendbot://net?room=…&key=…&name=… (the Internet QR)."""
    q = _query(text.strip(), "friendbot://net?")
    if not q:
        return None
    room, key = q.get("room", ""), q.get("key", "")
    if not (8 <= len(room) <= 32 and all(c in string.ascii_lowercase + string.digits for c in room)):
        return None
    raw = unb64(key)
    if raw is None or len(raw) != 32:
        return None
    return NetInfo(room, key, q.get("name") or "Robot phone")


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def unb64(text: str) -> Optional[bytes]:
    try:
        return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))
    except Exception:
        return None


def random_id(n: int) -> str:
    alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
    return "".join(random.SystemRandom().choice(alphabet) for _ in range(n))


# ----------------------------------------------------------------------------- encryption


def seal(key: bytes, text: str) -> bytes:
    """AES-256-GCM: nonce(12) + ciphertext + tag(16) — the phones' layout."""
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    nonce = os.urandom(12)
    return nonce + AESGCM(key).encrypt(nonce, text.encode(), None)


def open_sealed(key: bytes, data: bytes) -> Optional[str]:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    if len(data) < 28:
        return None
    try:
        return AESGCM(key).decrypt(data[:12], data[12:], None).decode()
    except Exception:
        return None


# ----------------------------------------------------------------------------- links


class Link:
    """What the window needs from a link: a state line, connected or not, mic in/out."""

    def __init__(self, on_change: Callable[[], None]):
        self.on_change = on_change
        self.state = "idle"  # idle | searching | connecting | connected | offline | error
        self.detail = ""
        self.host_name = ""
        self.connected = False
        self.mic_on = False
        self.mic_ack_live = False
        self.floor_other: Optional[str] = None
        self.floor_phones = 1

    def _set(self, state: str, detail: str = "") -> None:
        self.state, self.detail = state, detail
        self.on_change()

    def _robot_line(self, line: str) -> None:
        """One relay line from the robot phone (same protocol for both links)."""
        try:
            m = json.loads(line)
        except ValueError:
            return
        t = m.get("t")
        if t == "auth":
            if m.get("ok"):
                self.connected = True
                self._set("connected")
                if self.mic_on:
                    self.send_line(_dumps({"t": "mic", "on": True}))
            else:
                self._set("error", "the robot phone runs an older FriendBot that wants a PIN — update it")
        elif t == "micack":
            self.mic_ack_live = bool(m.get("live"))
            self.on_change()
        elif t == "floor":
            self.floor_other = None if m.get("you") else m.get("name", "")
            self.floor_phones = int(m.get("phones", 1))
            self.on_change()
        elif t == "hello" and m.get("name"):
            self.host_name = m["name"]

    def hello(self) -> None:
        self.send_line(_dumps({"t": "hello", "name": socket.gethostname() or APP_NAME, "pin": ""}))

    def set_mic(self, on: bool) -> None:
        self.mic_on = on
        self.mic_ack_live = False
        if self.connected:
            self.send_line(_dumps({"t": "mic", "on": on}))
        if not on:
            self.floor_other, self.floor_phones = None, 1
        self.on_change()

    def send_frame(self, opus: bytes, rms: float) -> None:
        if self.connected:
            self.send_line(_dumps({"t": "mic", "b": base64.b64encode(opus).decode(), "r": round(rms, 4)}))

    # Subclasses
    def send_line(self, line: str) -> None:  # pragma: no cover
        raise NotImplementedError

    def stop(self) -> None:  # pragma: no cover
        raise NotImplementedError


def local_ips() -> List[str]:
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    ips.add("127.0.0.1")
    return list(ips)


def discover_lan(timeout: float = 2.0) -> List[LanInfo]:
    """WiFi broadcast probe (Android robot phones answer), plus Bonjour if zeroconf is installed
    (iPhone robot phones)."""
    found = {}
    mine = set(local_ips())
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.settimeout(0.3)
        end = time.time() + timeout
        next_probe = 0.0
        while time.time() < end:
            if time.time() >= next_probe:
                for target in ("255.255.255.255", "<broadcast>"):
                    try:
                        s.sendto(PROBE, (target, DISCOVERY_PORT))
                    except OSError:
                        pass
                next_probe = time.time() + 0.7
            try:
                data, (ip, _) = s.recvfrom(1024)
            except socket.timeout:
                continue
            if ip in mine:
                continue
            try:
                m = json.loads(data.decode())
            except ValueError:
                continue
            if m.get("t") == "here":
                found[ip] = LanInfo(ip, int(m.get("port", RELAY_PORT)), m.get("name") or ip)
        s.close()
    except OSError:
        pass
    try:  # Optional: Bonjour (_friendbot-ctrl._tcp) for iPhone robot phones.
        from zeroconf import ServiceBrowser, Zeroconf  # type: ignore

        zc = Zeroconf()
        results = []

        class _L:
            def add_service(self, zc_, type_, name):
                info = zc_.get_service_info(type_, name, 1500)
                if info:
                    for addr in info.parsed_addresses():
                        if "." in addr and addr not in mine:
                            results.append(LanInfo(addr, info.port, name.split(".")[0]))

            def update_service(self, *a):
                pass

            def remove_service(self, *a):
                pass

        ServiceBrowser(zc, "_friendbot-ctrl._tcp.local.", _L())
        time.sleep(min(timeout, 1.5))
        zc.close()
        for r in results:
            found.setdefault(r.ip, r)
    except Exception:
        pass
    return list(found.values())


class LanLink(Link):
    """Remote Mic: same WiFi, TCP JSON lines to the robot phone's relay (port 47821)."""

    def __init__(self, on_change, target: Optional[LanInfo] = None):
        super().__init__(on_change)
        self.target = target
        self._sock: Optional[socket.socket] = None
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def _run(self) -> None:
        while not self._stop.is_set():
            target = self.target
            if target is None:
                self._set("searching")
                hosts = discover_lan()
                if not hosts:
                    continue
                target = hosts[0]
            self.host_name = target.name
            self._set("connecting", target.name)
            try:
                sock = socket.create_connection((target.ip, target.port), timeout=4)
            except OSError:
                self._set("offline", f"can't reach {target.name} ({target.ip}) — same WiFi?")
                self._stop.wait(2)
                continue
            sock.settimeout(None)
            with self._lock:
                self._sock = sock
            self.hello()
            pinger = threading.Thread(target=self._ping, args=(sock,), daemon=True)
            pinger.start()
            buf = b""
            try:
                while not self._stop.is_set():
                    chunk = sock.recv(4096)
                    if not chunk:
                        break
                    buf += chunk
                    while b"\n" in buf:
                        line, buf = buf.split(b"\n", 1)
                        self._robot_line(line.decode("utf-8", "replace"))
            except OSError:
                pass
            with self._lock:
                self._sock = None
            try:
                sock.close()
            except OSError:
                pass
            self.connected = False
            self.mic_ack_live = False
            if not self._stop.is_set():
                self._set("offline", f"{target.name} left — reconnecting")
                self._stop.wait(1)

    def _ping(self, sock) -> None:
        while not self._stop.is_set() and self._sock is sock:
            self.send_line('{"t":"ping"}')
            self._stop.wait(3)

    def send_line(self, line: str) -> None:
        with self._lock:
            sock = self._sock
            if sock is None:
                return
            try:
                sock.sendall((line + "\n").encode())
            except OSError:
                pass

    def stop(self) -> None:
        self._stop.set()
        with self._lock:
            if self._sock:
                try:
                    self._sock.close()
                except OSError:
                    pass


class NetLink(Link):
    """Internet Mic: any WiFi or 4G/5G, encrypted, through a free public MQTT broker."""

    def __init__(self, on_change, info: NetInfo, brokers=None):
        super().__init__(on_change)
        import paho.mqtt.client as mqtt

        self._mqtt_mod = mqtt
        self.info = info
        self.host_name = info.name
        self.key = unb64(info.key) or b""
        self.my_id = random_id(12)
        self.brokers = brokers or BROKERS
        self._index = 0
        self._client = None
        self._ready = False
        self._last_from_robot = 0.0
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    @property
    def up(self) -> str:
        return f"friendbot/v1/{self.info.room}/up"

    @property
    def down(self) -> str:
        return f"friendbot/v1/{self.info.room}/down"

    def _connect(self):
        mqtt = self._mqtt_mod
        name, host, port, use_tls = self.brokers[self._index % len(self.brokers)]
        try:
            client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="fbd-" + self.my_id,
                                 transport="websockets")
        except AttributeError:  # paho-mqtt 1.x
            client = mqtt.Client(client_id="fbd-" + self.my_id, transport="websockets")
        client.ws_set_options(path="/mqtt")
        if use_tls:
            client.tls_set(cert_reqs=ssl.CERT_REQUIRED)

        def on_connect(c, userdata, flags, rc, *args):
            ok = (rc == 0) if isinstance(rc, int) else not getattr(rc, "is_failure", False)
            if ok:
                c.subscribe(self.down, qos=0)
                self._ready = True
                self._set("connecting", "waiting for " + self.info.name)
                self.hello()

        def on_message(c, userdata, msg):
            text = open_sealed(self.key, msg.payload)
            if not text:
                return
            try:
                env = json.loads(text)
            except ValueError:
                return
            if env.get("t") != self.my_id:
                return
            self._last_from_robot = time.time()
            if self.state == "offline" and self.connected:
                self._set("connected")
            self._robot_line(env.get("l", ""))

        def on_disconnect(c, *args):
            self._ready = False

        client.on_connect = on_connect
        client.on_message = on_message
        client.on_disconnect = on_disconnect
        self._set("connecting", f"reaching the {name} relay")
        client.connect_async(host, port, keepalive=60)
        client.loop_start()
        return client

    def _run(self) -> None:
        self._client = self._connect()
        started = time.time()
        while not self._stop.wait(3):
            if not self._ready:
                if time.time() - started > 12:  # this broker does not answer: next one
                    self._drop_client()
                    self._index += 1
                    self._client = self._connect()
                    started = time.time()
                continue
            started = time.time()
            if not self.connected:
                self.hello()
            else:
                self.send_line('{"t":"ping"}')
                if time.time() - self._last_from_robot > 12:
                    self.connected = False
                    self.mic_ack_live = False
                    self._set("offline", f"{self.info.name} is offline — retrying")
                    self.hello()

    def _drop_client(self) -> None:
        c, self._client, self._ready = self._client, None, False
        if c is not None:
            try:
                c.loop_stop()
                c.disconnect()
            except Exception:
                pass

    def send_line(self, line: str) -> None:
        c = self._client
        if c is None or not self._ready:
            return
        env = _dumps({"f": self.my_id, "l": line})
        c.publish(self.up, seal(self.key, env), qos=0)

    def stop(self) -> None:
        self._stop.set()
        self._drop_client()


# ----------------------------------------------------------------------------- microphone


class Mic:
    """Microphone → 16 kHz mono → 60 ms raw Opus frames (sounddevice + opuslib)."""

    def __init__(self, on_frame: Callable[[bytes, float], None]):
        self.on_frame = on_frame
        self._stream = None
        self._encoder = None
        self.level = 0.0

    def start(self) -> None:
        import numpy as np
        import opuslib  # needs the Opus library: macOS `brew install opus`
        import sounddevice as sd

        self._encoder = opuslib.Encoder(SAMPLE_RATE, 1, opuslib.APPLICATION_VOIP)

        def callback(indata, frames, t, status):
            pcm = indata[:, 0].astype(np.int16)
            rms = float(np.sqrt(np.mean((pcm.astype(np.float32) / 32768.0) ** 2))) if len(pcm) else 0.0
            self.level = rms
            try:
                packet = self._encoder.encode(pcm.tobytes(), FRAME)
            except Exception:
                return
            self.on_frame(packet, rms)

        self._stream = sd.InputStream(samplerate=SAMPLE_RATE, channels=1, dtype="int16",
                                      blocksize=FRAME, callback=callback)
        self._stream.start()

    def stop(self) -> None:
        if self._stream is not None:
            try:
                self._stream.stop()
                self._stream.close()
            except Exception:
                pass
        self._stream = None
        self.level = 0.0


# ----------------------------------------------------------------------------- QR input


def qr_from_image(path: str) -> Optional[str]:
    try:
        import cv2  # type: ignore
    except ImportError:
        return None
    img = cv2.imread(path)
    if img is None:
        return None
    text, _, _ = cv2.QRCodeDetector().detectAndDecode(img)
    return text or None


def qr_from_camera(timeout: float = 25.0) -> Optional[str]:
    """Shows the webcam; returns the first QR text it reads (Esc / Q to cancel)."""
    try:
        import cv2  # type: ignore
    except ImportError:
        return None
    cam = cv2.VideoCapture(0)
    detector = cv2.QRCodeDetector()
    end = time.time() + timeout
    found = None
    while time.time() < end:
        ok, frame = cam.read()
        if not ok:
            break
        text, _, _ = detector.detectAndDecode(frame)
        cv2.imshow("Scan the robot phone's QR — Esc to cancel", frame)
        if text:
            found = text
            break
        if cv2.waitKey(30) & 0xFF in (27, ord("q")):
            break
    cam.release()
    cv2.destroyAllWindows()
    return found


# ----------------------------------------------------------------------------- window

INK, PANEL, CYAN, MUTED = "#05070B", "#0B141A", "#7FE7FF", "#8FA9B3"
GREEN, AMBER, RED, TEXT = "#3DDC97", "#FFB547", "#FF6B6B", "#E6F9FF"
MONO = ("Menlo", "Consolas", "DejaVu Sans Mono")


def status_line(link: Optional[Link], internet: bool) -> tuple:
    """(text, light colour) for the link pill — the phones' wording."""
    if link is None:
        return ("SCAN THE INTERNET QR TO START" if internet else "SEARCHING FOR A ROBOT ON WIFI…", MUTED)
    name = (link.host_name or "robot phone").upper()
    if link.connected:
        if link.mic_on and not link.mic_ack_live:
            return (f"{name} IS NOT HEARING YOU YET — OPEN ITS VOICE CONTROL", AMBER)
        if link.mic_on and link.floor_other:
            return (f"{link.floor_other.upper()} IS TALKING — WAIT", AMBER)
        tag = "INTERNET" if internet else "WIFI"
        return (f"LINKED · {name} · {tag}", GREEN)
    if link.state == "searching":
        return ("SEARCHING FOR A ROBOT ON WIFI…", AMBER)
    if link.state == "connecting":
        return ((link.detail or f"CONNECTING TO {name}").upper() + "…", AMBER)
    if link.state == "offline":
        return ((link.detail or f"{name} OFFLINE").upper(), RED)
    if link.state == "error":
        return (link.detail.upper(), RED)
    return ("IDLE", MUTED)


class App:
    """Robotic, simple: two tabs (Remote Mic / Internet Mic), the link pill with a status light,
    one big round mic with level bars, the scan / connect controls at the bottom."""

    def __init__(self):
        import tkinter as tk
        from tkinter import font as tkfont

        self.tk = tk
        self.root = tk.Tk()
        self.root.title(APP_NAME)
        self._set_window_icon()
        self.root.configure(bg=INK)
        self.root.geometry("390x760")
        self.root.minsize(360, 680)
        families = set(tkfont.families())
        self.mono = next((f for f in MONO if f in families), "Courier")
        self.events: "queue.Queue[str]" = queue.Queue()
        self.link: Optional[Link] = None
        self.internet = False
        self.mic: Optional[Mic] = None
        self.streaming = False
        self.config_path = os.path.join(os.path.expanduser("~"), ".friendbot_mic.json")
        self.saved = self._load()
        self._build()
        self._switch(self.saved.get("mode") == "internet")
        self.root.after(80, self._tick)
        self.root.protocol("WM_DELETE_WINDOW", self._quit)

    def _set_window_icon(self) -> None:
        """FriendBot_logo.png as the window/Dock icon; a missing file never breaks startup."""
        script_dir = os.path.dirname(os.path.abspath(__file__))
        for candidate in (
            os.path.join(script_dir, "FriendBot_logo.png"),
            os.path.join(os.path.dirname(script_dir), "FriendBot_logo.png"),  # repo root
        ):
            if not os.path.isfile(candidate):
                continue
            try:
                photo = self.tk.PhotoImage(file=candidate)
                self.root.iconphoto(True, photo)
                self._window_icon = photo  # keep a reference or Tk garbage-collects it
            except Exception:
                pass
            break

    # ---- persistence
    def _load(self) -> dict:
        try:
            with open(self.config_path) as f:
                return json.load(f)
        except (OSError, ValueError):
            return {}

    def _save(self) -> None:
        try:
            with open(self.config_path, "w") as f:
                json.dump(self.saved, f)
        except OSError:
            pass

    # ---- layout
    def _build(self) -> None:
        tk = self.tk
        r = self.root
        pad = {"padx": 24}
        tabs = tk.Frame(r, bg=INK)
        tabs.pack(fill="x", pady=(16, 0), **pad)
        self.tab_lan = tk.Label(tabs, text="REMOTE MIC", font=(self.mono, 11, "bold"), bg=INK, cursor="hand2")
        self.tab_net = tk.Label(tabs, text="INTERNET MIC", font=(self.mono, 11, "bold"), bg=INK, cursor="hand2")
        self.tab_lan.pack(side="left")
        self.tab_net.pack(side="left", padx=(18, 0))
        self.tab_lan.bind("<Button-1>", lambda e: self._switch(False))
        self.tab_net.bind("<Button-1>", lambda e: self._switch(True))

        self.kicker = tk.Label(r, font=(self.mono, 9), fg="#6FA8B8", bg=INK, anchor="w")
        self.kicker.pack(fill="x", pady=(18, 0), **pad)
        self.title = tk.Label(r, font=("Helvetica", 30, "bold"), fg="white", bg=INK, anchor="w")
        self.title.pack(fill="x", **pad)

        pill = tk.Frame(r, bg=PANEL, highlightbackground="#1E3640", highlightthickness=1)
        pill.pack(anchor="w", pady=(10, 0), **pad)
        self.dot = tk.Canvas(pill, width=10, height=10, bg=PANEL, highlightthickness=0)
        self.dot.pack(side="left", padx=(12, 8), pady=12)
        self.dot_id = self.dot.create_oval(1, 1, 9, 9, fill=AMBER, outline="")
        self.link_label = tk.Label(pill, font=(self.mono, 10), fg="#CFF6FF", bg=PANEL,
                                   wraplength=280, justify="left")
        self.link_label.pack(side="left", padx=(0, 12))

        self.canvas = tk.Canvas(r, width=340, height=330, bg=INK, highlightthickness=0, cursor="hand2")
        self.canvas.pack(pady=(18, 0))
        self.canvas.bind("<Button-1>", self._orb_click)
        self.state_label = tk.Label(r, font=(self.mono, 13, "bold"), fg=TEXT, bg=INK)
        self.state_label.pack()
        tk.Label(r, text="Xiaozhi answers on the robot phone", font=(self.mono, 9), fg=MUTED, bg=INK).pack(pady=(4, 0))

        bottom = tk.Frame(r, bg=INK)
        bottom.pack(side="bottom", fill="x", pady=(0, 22), **pad)
        row = tk.Frame(bottom, bg=INK)
        row.pack(fill="x")
        self.net_left = tk.Label(row, font=(self.mono, 9), fg=MUTED, bg=INK)
        self.net_left.pack(side="left")
        self.net_right = tk.Label(row, font=(self.mono, 9), fg=MUTED, bg=INK)
        self.net_right.pack(side="right")
        self.entry_label = tk.Label(bottom, font=(self.mono, 9), fg=MUTED, bg=INK, anchor="w")
        self.entry_label.pack(fill="x", pady=(10, 0))
        self.entry = tk.Entry(bottom, font=(self.mono, 10), bg=PANEL, fg=TEXT, insertbackground=CYAN,
                              relief="flat", highlightthickness=1, highlightbackground="#1E3640",
                              highlightcolor=CYAN)
        self.entry.pack(fill="x", pady=(4, 6), ipady=8)
        self.entry.bind("<Return>", lambda e: self._use_entry())
        btns = tk.Frame(bottom, bg=INK)
        btns.pack(fill="x")
        self.connect_btn = self._button(btns, "CONNECT", self._use_entry)
        self.connect_btn.pack(side="left", fill="x", expand=True, padx=(0, 6))
        self.scan_btn = self._button(btns, "SCAN QR", self._scan)
        self.scan_btn.pack(side="left", fill="x", expand=True, padx=(6, 0))

    def _button(self, parent, text, cmd):
        b = self.tk.Label(parent, text=text, font=(self.mono, 11, "bold"), fg=CYAN, bg=INK,
                          highlightbackground=CYAN, highlightthickness=1, pady=12, cursor="hand2")
        b.bind("<Button-1>", lambda e: cmd())
        return b

    # ---- modes
    def _switch(self, internet: bool) -> None:
        self._stop_mic()
        if self.link:
            self.link.stop()
            self.link = None
        self.internet = internet
        self.saved["mode"] = "internet" if internet else "lan"
        self._save()
        self.tab_lan.configure(fg=MUTED if internet else CYAN)
        self.tab_net.configure(fg=CYAN if internet else MUTED)
        self.kicker.configure(text="FRIENDBOT // MIC LINK 02 · NET" if internet else "FRIENDBOT // MIC LINK 01")
        self.title.configure(text="Internet Mic" if internet else "Remote Mic")
        self.net_left.configure(text="WIFI OR 4G/5G" if internet else "SAME WIFI")
        self.net_right.configure(text="ENCRYPTED" if internet else "LOCAL NETWORK")
        self.entry.delete(0, "end")
        self.entry_label.configure(text="INTERNET QR TEXT (friendbot://net?…)" if internet
                                   else "ROBOT PHONE IP OR QR TEXT — EMPTY = FIND IT ON THE WIFI")
        if internet:
            self.entry.insert(0, self.saved.get("net_qr", ""))
            self.scan_btn.configure(text="SCAN INTERNET QR")
            if parse_net_qr(self.saved.get("net_qr", "")):
                self._start_net(parse_net_qr(self.saved["net_qr"]))
        else:
            self.entry.insert(0, self.saved.get("lan_ip", ""))
            self.scan_btn.configure(text="SCAN ROBOT QR")
            ip = self.saved.get("lan_ip", "")
            self._start_lan(parse_lan_qr(ip) or (LanInfo(ip, RELAY_PORT, ip) if ip else None))

    def _changed(self) -> None:
        self.events.put("change")

    def _start_lan(self, target: Optional[LanInfo]) -> None:
        if self.link:
            self.link.stop()
        self.link = LanLink(self._changed, target)

    def _start_net(self, info: Optional[NetInfo]) -> None:
        if self.link:
            self.link.stop()
            self.link = None
        if info:
            self.link = NetLink(self._changed, info)

    def _use_entry(self) -> None:
        text = self.entry.get().strip()
        if self.internet:
            info = parse_net_qr(text)
            if not info:
                self._flash("PASTE THE INTERNET QR TEXT (friendbot://net?…)")
                return
            self.saved["net_qr"] = text
            self._save()
            self._start_net(info)
        else:
            lan = parse_lan_qr(text)
            if lan is None and text:
                parts = text.split(":")
                lan = LanInfo(parts[0], int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else RELAY_PORT, parts[0])
            self.saved["lan_ip"] = text
            self._save()
            self._start_lan(lan)

    def _scan(self) -> None:
        from tkinter import filedialog

        text = qr_from_camera()
        if text is None:
            path = filedialog.askopenfilename(title="Open a photo or screenshot of the robot phone's QR")
            text = qr_from_image(path) if path else None
        if text:
            self.entry.delete(0, "end")
            self.entry.insert(0, text)
            self._use_entry()
        else:
            self._flash("NO QR READ — PASTE ITS TEXT OR TYPE THE IP")

    def _flash(self, msg: str) -> None:
        self.link_label.configure(text=msg)
        self.dot.itemconfigure(self.dot_id, fill=RED)

    # ---- mic
    def _orb_click(self, event) -> None:
        if self.streaming:
            self._stop_mic()
            return
        if not (self.link and self.link.connected):
            self._flash(status_line(self.link, self.internet)[0])
            return
        link = self.link
        self.mic = Mic(lambda opus, rms: link.send_frame(opus, rms))
        try:
            self.mic.start()
        except Exception as e:  # missing audio / opus libraries
            self.mic = None
            self._flash(f"MICROPHONE UNAVAILABLE: {e}")
            return
        self.streaming = True
        link.set_mic(True)

    def _stop_mic(self) -> None:
        if self.mic:
            self.mic.stop()
            self.mic = None
        if self.streaming and self.link:
            self.link.set_mic(False)
        self.streaming = False

    # ---- drawing
    def _tick(self) -> None:
        while not self.events.empty():
            self.events.get_nowait()
        text, color = status_line(self.link, self.internet)
        self.link_label.configure(text=text)
        self.dot.itemconfigure(self.dot_id, fill=color)
        self.state_label.configure(
            text=("LISTENING_" if self.streaming else "TAP TO SPEAK") if (self.link and self.link.connected)
            else ("SCAN TO START" if self.internet and self.link is None else "WAITING"),
            fg=CYAN if self.streaming else TEXT)
        filled = self.link is None or not self.link.connected
        self.scan_btn.configure(bg=CYAN if filled else INK, fg=INK if filled else CYAN)
        self._draw_orb()
        self.root.after(60, self._tick)

    def _draw_orb(self) -> None:
        c = self.canvas
        c.delete("all")
        cx, cy, r = 170, 135, 80
        t = time.time()
        c.create_oval(cx - 105, cy - 105, cx + 105, cy + 105, outline="#1E3640", dash=(4, 4))
        if self.streaming:
            for phase in (0.0, 0.5):
                k = ((t / 1.6) + phase) % 1.0
                rr = r * (1 + 0.31 * k)
                shade = int(0x7F * (1 - k))
                col = f"#{shade:02x}{int(0xE7 * (1 - k)):02x}{int(0xFF * (1 - k)):02x}"
                c.create_oval(cx - rr, cy - rr, cx + rr, cy + rr, outline=col, width=2)
        fill = CYAN if self.streaming else PANEL
        ink = INK if self.streaming else CYAN
        c.create_oval(cx - r, cy - r, cx + r, cy + r, fill=fill, outline=CYAN, width=2)
        # Mic glyph.
        c.create_rectangle(cx - 11, cy - 32, cx + 11, cy + 4, outline=ink, width=3)
        c.create_arc(cx - 26, cy - 22, cx + 26, cy + 22, start=200, extent=140, style="arc", outline=ink, width=3)
        c.create_line(cx, cy + 22, cx, cy + 34, fill=ink, width=3)
        c.create_line(cx - 14, cy + 34, cx + 14, cy + 34, fill=ink, width=3)
        # Level bars.
        shape = [0.33, 0.55, 0.83, 0.6, 1, 0.66, 0.83, 0.5, 0.28]
        level = min(1.0, (self.mic.level if self.mic else 0) * 3.5)
        x0 = cx - 60
        for i, s in enumerate(shape):
            if self.streaming:
                wobble = 0.75 + 0.25 * __import__("math").sin(t * 11 + i * 1.3)
                k = max(0.25, min(1.0, (0.25 + level) * wobble))
                col = CYAN
            else:
                k, col = 0.35, "#1E3640"
            h = 36 * s * k
            x = x0 + i * 14
            c.create_rectangle(x, 300 - h, x + 6, 300, fill=col, outline="")

    def _quit(self) -> None:
        self._stop_mic()
        if self.link:
            self.link.stop()
        self.root.destroy()

    def run(self) -> None:
        self.root.mainloop()


# ----------------------------------------------------------------------------- terminal mode


def run_cli(link: Link) -> None:
    last = [""]

    def show():
        text, _ = status_line(link, isinstance(link, NetLink))
        if text != last[0]:
            last[0] = text
            print("·", text)

    link.on_change = show
    mic: Optional[Mic] = None
    print("Enter = talk / stop · Ctrl-C = quit")
    try:
        while True:
            input()
            if mic:
                mic.stop()
                mic = None
                link.set_mic(False)
                print("· mic off")
            elif link.connected:
                mic = Mic(link.send_frame)
                mic.start()
                link.set_mic(True)
                print("· LISTENING — speak now")
            else:
                show()
    except (KeyboardInterrupt, EOFError):
        pass
    finally:
        if mic:
            mic.stop()
        link.stop()


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--lan", nargs="?", const="", metavar="IP_OR_QR",
                    help="terminal Remote Mic (same WiFi); optional robot phone IP or its QR text")
    ap.add_argument("--net", metavar="INTERNET_QR", help="terminal Internet Mic with the Internet QR text")
    args = ap.parse_args()
    if args.net:
        info = parse_net_qr(args.net)
        if not info:
            sys.exit("That is not an Internet QR (friendbot://net?room=…&key=…).")
        run_cli(NetLink(lambda: None, info))
    elif args.lan is not None:
        target = parse_lan_qr(args.lan) or (LanInfo(args.lan, RELAY_PORT, args.lan) if args.lan else None)
        run_cli(LanLink(lambda: None, target))
    else:
        App().run()


if __name__ == "__main__":
    main()
