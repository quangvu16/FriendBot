#!/usr/bin/env python3
"""FriendBot Mic Pro for desktop (macOS, Windows, Linux).

The Wi‑Fi Mic Pro and Internet Mic Pro pages of the FriendBot phone apps, on your computer:

* STOP        — the red bar (or Esc) stops the robot at once. Auto-stop: the robot phone stops
                by itself when this app goes silent (1.5 s on Wi‑Fi, 3 s over the internet)
                while the robot moves.
* Heard / said — what Xiaozhi understood and what it answered.
* Camera      — click the camera window to see the robot phone's camera (needs Pillow).
* Push-to-talk — hold the orb (or hold Space): the microphone is open only while held.
* Drive pad   — hold an arrow (or the arrow keys) to drive, let go to stop.
* Link delay and battery — ping time in ms, robot phone battery.

Same links, microphone and QR reading as friendbot_mic.py (which keeps the simple pages).

Run:  python3 friendbot_mic_pro.py
"""

from __future__ import annotations

import base64
import io
import json
import math
import os
import queue
import threading
import time
from typing import Optional

from friendbot_mic import (
    AMBER, CYAN, GREEN, INK, MONO, MUTED, PANEL, RED, RELAY_PORT, TEXT,
    LanInfo, LanLink, Mic, NetInfo, NetLink, _dumps, parse_lan_qr, parse_net_qr,
    qr_from_camera, qr_from_image, status_line,
)

APP_NAME = "FriendBot Mic Pro"
DRIVE_MS = 600          # one pad move lasts this long…
DRIVE_REPEAT_MS = 250   # …and is re-sent this often while an arrow is held
DRIVE_SPEED = 0.6
TURN_SPEED = 0.45
PAD = {"up": (DRIVE_SPEED, DRIVE_SPEED), "down": (-DRIVE_SPEED, -DRIVE_SPEED),
       "left": (-TURN_SPEED, TURN_SPEED), "right": (TURN_SPEED, -TURN_SPEED)}


# ----------------------------------------------------------------------------- Pro links


class ProMixin:
    """Adds the Pro protocol to a link: pro wishes, fast pings (auto-stop heartbeat + delay),
    heard / said / status / camera / safety lines, drive and STOP."""

    PING_EVERY = 0.5
    WATCH_MS = 1500
    CAM_FPS = 5

    def _pro_setup(self) -> None:
        self.cam_on = False
        self.rtt_ms: Optional[int] = None
        self.last_pong = 0.0
        self.ping_id = 0
        self.ping_sent = 0.0
        self.heard = ""
        self.said = ""
        self.battery: dict = {}
        self.cam_jpeg: Optional[bytes] = None
        self.cam_at = 0.0
        self.safety_at = 0.0
        self._pro_stop = threading.Event()

    def _pro_start(self) -> None:
        threading.Thread(target=self._heartbeat, daemon=True).start()

    def send_pro(self) -> None:
        self.send_line(_dumps({"t": "pro", "on": True, "watch": self.WATCH_MS,
                               "cam": self.cam_on, "fps": self.CAM_FPS}))

    def _robot_line(self, line: str) -> None:
        super()._robot_line(line)  # auth, micack, floor, hello
        try:
            m = json.loads(line)
        except ValueError:
            return
        t = m.get("t")
        now = time.time()
        if t == "auth" and m.get("ok"):
            self.last_pong = now
            self.send_pro()  # fresh link or reconnect: tell the robot phone what we want
        elif t == "pong":
            self.last_pong = now
            if m.get("id") == self.ping_id:
                self.rtt_ms = int((now - self.ping_sent) * 1000)
        elif t == "heard":
            self.heard = m.get("text", "")
        elif t == "said":
            self.said = m.get("text", "")
        elif t == "status":
            self.battery = m
        elif t == "cam":
            try:
                self.cam_jpeg = base64.b64decode(m.get("b", ""))
                self.cam_at = now
            except ValueError:
                pass
        elif t == "safety":
            self.safety_at = now
        else:
            return
        self.on_change()

    def _heartbeat(self) -> None:
        while not self._pro_stop.wait(self.PING_EVERY):
            if self.connected:
                self.ping_id += 1
                self.ping_sent = time.time()
                self.send_line(_dumps({"t": "ping", "id": self.ping_id}))

    def link_ok(self) -> bool:
        return self.connected and time.time() - self.last_pong < 3

    def set_cam(self, on: bool) -> None:
        self.cam_on = on
        if not on:
            self.cam_jpeg = None
        if self.connected:
            self.send_pro()

    def move(self, left: float, right: float) -> None:
        if self.connected:
            self.send_line(_dumps({"t": "move", "l": round(left, 2), "r": round(right, 2), "ms": DRIVE_MS}))

    def stop_robot(self) -> None:
        self.send_line('{"t":"stop"}')

    def stop(self) -> None:
        self._pro_stop.set()
        if self.connected:
            self.send_line('{"t":"pro","on":false}')
        super().stop()


class ProLanLink(ProMixin, LanLink):
    """Wi‑Fi Mic Pro: same Wi‑Fi."""

    def __init__(self, on_change, target: Optional[LanInfo] = None):
        self._pro_setup()
        LanLink.__init__(self, on_change, target)
        self._pro_start()


class ProNetLink(ProMixin, NetLink):
    """Internet Mic Pro: any Wi‑Fi or 4G/5G (pictures and pings are sent less often)."""

    PING_EVERY = 1.0
    WATCH_MS = 3000
    CAM_FPS = 2

    def __init__(self, on_change, info: NetInfo, brokers=None):
        self._pro_setup()
        NetLink.__init__(self, on_change, info, brokers)
        self._pro_start()


# ----------------------------------------------------------------------------- window


class ProApp:
    """Two tabs (WI‑FI PRO / NET PRO); link pill + delay + battery; camera window;
    heard / said; drive pad + hold-to-talk orb; STOP bar; connect / scan."""

    CAM_W, CAM_H = 372, 210

    def __init__(self):
        import tkinter as tk
        from tkinter import font as tkfont

        self.tk = tk
        self.root = tk.Tk()
        self.root.title(APP_NAME)
        self.root.configure(bg=INK)
        self.root.geometry("420x880")
        self.root.minsize(400, 820)
        families = set(tkfont.families())
        self.mono = next((f for f in MONO if f in families), "Courier")
        self.events: "queue.Queue[str]" = queue.Queue()
        self.link: Optional[ProMixin] = None
        self.internet = False
        self.mic: Optional[Mic] = None
        self.talking = False
        self.driving: Optional[str] = None
        self._drive_job = None
        self._key_release_jobs: dict = {}
        self._cam_photo = None
        self._cam_shown_at = 0.0
        self._safety_seen = 0.0
        self._note = ""
        self._note_until = 0.0
        self.config_path = os.path.join(os.path.expanduser("~"), ".friendbot_mic_pro.json")
        self.saved = self._load()
        try:
            from PIL import Image, ImageTk  # noqa: F401
            self.has_pil = True
        except ImportError:
            self.has_pil = False
        self._build()
        self._bind_keys()
        self._switch(self.saved.get("mode") == "internet")
        self.root.after(80, self._tick)
        self.root.protocol("WM_DELETE_WINDOW", self._quit)

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
    def _label(self, parent, size=10, bold=False, fg=TEXT, bg=INK, **kw):
        return self.tk.Label(parent, font=(self.mono, size, "bold" if bold else "normal"),
                             fg=fg, bg=bg, **kw)

    def _chip(self, parent, text):
        return self.tk.Label(parent, text=text, font=(self.mono, 9, "bold"), fg=MUTED, bg=PANEL,
                             highlightbackground="#1E3640", highlightthickness=1, padx=8, pady=8)

    def _button(self, parent, text, cmd):
        b = self.tk.Label(parent, text=text, font=(self.mono, 10, "bold"), fg=CYAN, bg=INK,
                          highlightbackground=CYAN, highlightthickness=1, pady=10, cursor="hand2")
        b.bind("<Button-1>", lambda e: cmd())
        return b

    def _build(self) -> None:
        tk = self.tk
        r = self.root
        pad = {"padx": 22}
        tabs = tk.Frame(r, bg=INK)
        tabs.pack(fill="x", pady=(14, 0), **pad)
        self.tab_lan = self._label(tabs, 11, True, cursor="hand2", text="WI‑FI PRO")
        self.tab_net = self._label(tabs, 11, True, cursor="hand2", text="NET PRO")
        self.tab_lan.pack(side="left")
        self.tab_net.pack(side="left", padx=(18, 0))
        self.tab_lan.bind("<Button-1>", lambda e: self._switch(False))
        self.tab_net.bind("<Button-1>", lambda e: self._switch(True))

        self.kicker = self._label(r, 9, fg="#6FA8B8", anchor="w")
        self.kicker.pack(fill="x", pady=(12, 0), **pad)
        self.title = tk.Label(r, font=("Helvetica", 26, "bold"), fg="white", bg=INK, anchor="w")
        self.title.pack(fill="x", **pad)

        row = tk.Frame(r, bg=INK)
        row.pack(fill="x", pady=(8, 0), **pad)
        pill = tk.Frame(row, bg=PANEL, highlightbackground="#1E3640", highlightthickness=1)
        self.dot = tk.Canvas(pill, width=10, height=10, bg=PANEL, highlightthickness=0)
        self.dot.pack(side="left", padx=(10, 6), pady=10)
        self.dot_id = self.dot.create_oval(1, 1, 9, 9, fill=AMBER, outline="")
        self.link_label = self._label(pill, 9, fg="#CFF6FF", bg=PANEL, anchor="w")
        self.link_label.pack(side="left", fill="x", expand=True, padx=(0, 8))
        self.battery_chip = self._chip(row, "BAT —")
        self.battery_chip.pack(side="right", padx=(6, 0))
        self.ping_chip = self._chip(row, "— ms")
        self.ping_chip.pack(side="right", padx=(6, 0))
        pill.pack(side="left", fill="x", expand=True)  # after the chips: they keep their room

        self.cam = tk.Canvas(r, width=self.CAM_W, height=self.CAM_H, bg=PANEL, cursor="hand2",
                             highlightbackground="#1E3640", highlightthickness=1)
        self.cam.pack(pady=(10, 0))
        self.cam.bind("<Button-1>", lambda e: self._toggle_cam())

        words = tk.Frame(r, bg=PANEL, highlightbackground="#1E3640", highlightthickness=1)
        words.pack(fill="x", pady=(8, 0), **pad)
        self.heard_label = self._label(words, 10, bg=PANEL, anchor="w", justify="left", wraplength=350)
        self.heard_label.pack(fill="x", padx=10, pady=(6, 0))
        self.said_label = self._label(words, 10, fg=CYAN, bg=PANEL, anchor="w", justify="left", wraplength=350)
        self.said_label.pack(fill="x", padx=10, pady=(2, 6))

        controls = tk.Frame(r, bg=INK)
        controls.pack(fill="x", pady=(8, 0), **pad)
        self.pad = tk.Canvas(controls, width=168, height=168, bg=INK, highlightthickness=0)
        self.pad.pack(side="left")
        self.pad.bind("<ButtonPress-1>", self._pad_press)
        self.pad.bind("<ButtonRelease-1>", lambda e: self._pad_release())
        right = tk.Frame(controls, bg=INK)
        right.pack(side="left", fill="both", expand=True)
        self.orb = tk.Canvas(right, width=170, height=150, bg=INK, highlightthickness=0, cursor="hand2")
        self.orb.pack()
        self.orb.bind("<ButtonPress-1>", lambda e: self._talk(True))
        self.orb.bind("<ButtonRelease-1>", lambda e: self._talk(False))
        self.state_label = self._label(right, 10, True)
        self.state_label.pack()

        self.stop_btn = tk.Label(r, text="■  STOP   (Esc)", font=(self.mono, 15, "bold"), fg="white",
                                 bg=RED, pady=12, cursor="hand2")
        self.stop_btn.pack(fill="x", pady=(10, 0), **pad)
        self.stop_btn.bind("<Button-1>", lambda e: self._stop_robot())

        bottom = tk.Frame(r, bg=INK)
        bottom.pack(side="bottom", fill="x", pady=(0, 16), **pad)
        self.entry_label = self._label(bottom, 8, fg=MUTED, anchor="w")
        self.entry_label.pack(fill="x")
        self.entry = tk.Entry(bottom, font=(self.mono, 10), bg=PANEL, fg=TEXT, insertbackground=CYAN,
                              relief="flat", highlightthickness=1, highlightbackground="#1E3640",
                              highlightcolor=CYAN)
        self.entry.pack(fill="x", pady=(4, 6), ipady=6)
        self.entry.bind("<Return>", lambda e: self._use_entry())
        btns = tk.Frame(bottom, bg=INK)
        btns.pack(fill="x")
        self._button(btns, "CONNECT", self._use_entry).pack(side="left", fill="x", expand=True, padx=(0, 6))
        self.scan_btn = self._button(btns, "SCAN QR", self._scan)
        self.scan_btn.pack(side="left", fill="x", expand=True, padx=(6, 0))
        self._label(r, 8, fg=MUTED, text="HOLD SPACE = TALK · ARROWS = DRIVE · ESC = STOP").pack(side="bottom", pady=(0, 6))

    # ---- keyboard (key repeat safe: a release followed at once by a press is ignored)
    def _bind_keys(self) -> None:
        keys = {"Up": "up", "Down": "down", "Left": "left", "Right": "right", "space": "talk"}
        for sym, what in keys.items():
            self.root.bind(f"<KeyPress-{sym}>", lambda e, w=what: self._key(w, True))
            self.root.bind(f"<KeyRelease-{sym}>", lambda e, w=what: self._key(w, False))
        self.root.bind("<Escape>", lambda e: self._stop_robot())

    def _key(self, what: str, down: bool) -> None:
        if self.root.focus_get() is self.entry:
            return
        job = self._key_release_jobs.pop(what, None)
        if job:
            self.root.after_cancel(job)
        if down:
            if what == "talk":
                if not self.talking:
                    self._talk(True)
            elif self.driving != what:
                self._drive(what)
        else:
            self._key_release_jobs[what] = self.root.after(70, lambda: self._key_up(what))

    def _key_up(self, what: str) -> None:
        self._key_release_jobs.pop(what, None)
        if what == "talk":
            self._talk(False)
        elif self.driving == what:
            self._drive(None)

    # ---- modes
    def _switch(self, internet: bool) -> None:
        self._talk(False)
        self._drive(None)
        if self.link:
            self.link.stop()
            self.link = None
        self.internet = internet
        self.saved["mode"] = "internet" if internet else "lan"
        self._save()
        self.tab_lan.configure(fg=MUTED if internet else CYAN)
        self.tab_net.configure(fg=CYAN if internet else MUTED)
        self.kicker.configure(text="FRIENDBOT // NET PRO" if internet else "FRIENDBOT // LAN PRO")
        self.title.configure(text="Internet Mic Pro" if internet else "Wi‑Fi Mic Pro")
        self.entry.delete(0, "end")
        self.entry_label.configure(text="INTERNET QR TEXT (friendbot://net?…)" if internet
                                   else "ROBOT PHONE IP OR QR TEXT — EMPTY = FIND IT ON THE WI‑FI")
        if internet:
            self.entry.insert(0, self.saved.get("net_qr", ""))
            self.scan_btn.configure(text="SCAN INTERNET QR")
            info = parse_net_qr(self.saved.get("net_qr", ""))
            if info:
                self._start(ProNetLink(self._changed, info))
        else:
            ip = self.saved.get("lan_ip", "")
            self.entry.insert(0, ip)
            self.scan_btn.configure(text="SCAN ROBOT QR")
            self._start(ProLanLink(self._changed, parse_lan_qr(ip) or (LanInfo(ip, RELAY_PORT, ip) if ip else None)))

    def _start(self, link) -> None:
        if self.link:
            self.link.stop()
        self.link = link
        self._cam_photo = None

    def _changed(self) -> None:
        self.events.put("change")

    def _use_entry(self) -> None:
        text = self.entry.get().strip()
        self.root.focus_set()
        if self.internet:
            info = parse_net_qr(text)
            if not info:
                self._flash("PASTE THE INTERNET QR TEXT (friendbot://net?…)")
                return
            self.saved["net_qr"] = text
            self._save()
            self._start(ProNetLink(self._changed, info))
        else:
            lan = parse_lan_qr(text)
            if lan is None and text:
                parts = text.split(":")
                port = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else RELAY_PORT
                lan = LanInfo(parts[0], port, parts[0])
            self.saved["lan_ip"] = text
            self._save()
            self._start(ProLanLink(self._changed, lan))

    def _scan(self) -> None:
        from tkinter import filedialog

        text = qr_from_camera()
        if text is None:
            path = filedialog.askopenfilename(title="Open a photo or screenshot of the robot phone's QR")
            text = qr_from_image(path) if path else None
        if not text:
            self._flash("NO QR READ — PASTE ITS TEXT OR TYPE THE IP")
            return
        if parse_net_qr(text) and not self.internet:
            self._switch(True)
        elif parse_lan_qr(text) and self.internet:
            self._switch(False)
        self.entry.delete(0, "end")
        self.entry.insert(0, text)
        self._use_entry()

    def _flash(self, msg: str, seconds: float = 3) -> None:
        self._note, self._note_until = msg, time.time() + seconds

    # ---- talking (push-to-talk)
    def _talk(self, on: bool) -> None:
        if on and not self.talking:
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
            self.talking = True
            link.set_mic(True)
        elif not on and self.talking:
            if self.mic:
                self.mic.stop()
                self.mic = None
            if self.link:
                self.link.set_mic(False)
            self.talking = False

    # ---- driving
    def _pad_hit(self, x: int, y: int) -> Optional[str]:
        for name, (cx, cy) in self._pad_centres().items():
            if abs(x - cx) <= 28 and abs(y - cy) <= 28:
                return name
        return None

    @staticmethod
    def _pad_centres() -> dict:
        return {"up": (84, 28), "down": (84, 140), "left": (28, 84), "right": (140, 84)}

    def _pad_press(self, event) -> None:
        hit = self._pad_hit(event.x, event.y)
        if hit:
            self._drive(hit)

    def _pad_release(self) -> None:
        if self.driving:
            self._drive(None)

    def _drive(self, direction: Optional[str]) -> None:
        if self._drive_job:
            self.root.after_cancel(self._drive_job)
            self._drive_job = None
        was = self.driving
        self.driving = direction
        if direction is None:
            if was and self.link:
                self.link.stop_robot()  # let go = stop
            return
        if not (self.link and self.link.connected):
            self.driving = None
            self._flash(status_line(self.link, self.internet)[0])
            return
        self._drive_tick()

    def _drive_tick(self) -> None:
        if not self.driving or not self.link:
            return
        self.link.move(*PAD[self.driving])
        self._drive_job = self.root.after(DRIVE_REPEAT_MS, self._drive_tick)

    def _stop_robot(self) -> None:
        self._drive(None)
        if self.link and self.link.connected:
            self.link.stop_robot()
            # Twice, a moment apart: one lost packet on 4G must not leave the robot driving.
            self.root.after(150, lambda: self.link and self.link.stop_robot())
            self._flash("STOP SENT", 1.5)
        else:
            self._flash(status_line(self.link, self.internet)[0])

    # ---- camera
    def _toggle_cam(self) -> None:
        if not self.link:
            return
        self.link.set_cam(not self.link.cam_on)
        self._cam_photo = None

    # ---- drawing
    def _tick(self) -> None:
        while not self.events.empty():
            self.events.get_nowait()
        link = self.link
        text, color = status_line(link, self.internet)
        if time.time() < self._note_until:
            text, color = self._note, RED
        self.link_label.configure(text=text if len(text) <= 26 else text[:25] + "…")
        self.dot.itemconfigure(self.dot_id, fill=color)
        # delay + battery chips
        if link and link.connected:
            if not link.link_ok():
                self.ping_chip.configure(text="LOST", fg=RED)
            elif link.rtt_ms is not None:
                ms = link.rtt_ms
                self.ping_chip.configure(text=f"{ms} ms", fg=GREEN if ms < 150 else AMBER if ms < 400 else RED)
        else:
            self.ping_chip.configure(text="— ms", fg=MUTED)
        bat = link.battery if link else {}
        if "bat" in bat:
            pct, chg = int(bat["bat"]), bool(bat.get("chg"))
            # Short: the robot's battery when it reports one, else the robot phone's.
            label = f"BOT {int(bat['rp'])}%" if "rp" in bat else f"PH {pct}%" + ("+" if chg else "")
            low = int(bat.get("rp", pct))
            self.battery_chip.configure(text=label, fg=RED if low < 20 and not chg else AMBER if low < 40 and not chg else GREEN)
        else:
            self.battery_chip.configure(text="BAT —", fg=MUTED)
        # heard / said
        self.heard_label.configure(text="HEARD › " + ((link.heard if link else "") or "—"))
        self.said_label.configure(text="ROBOT › " + ((link.said if link else "") or "—"))
        if link and link.safety_at > self._safety_seen:
            self._safety_seen = link.safety_at
            self._flash("ROBOT STOPPED BY ITSELF — THE LINK WAS LOST", 5)
        self._draw_cam()
        self._draw_pad()
        self._draw_orb()
        if self.talking and link:
            if link.floor_other:
                st = f"{link.floor_other.upper()} IS TALKING"
            else:
                st = "TALKING_" if link.mic_ack_live else "OPENING…"
            self.state_label.configure(text=st, fg=CYAN)
        else:
            self.state_label.configure(text="HOLD TO TALK", fg=TEXT)
        filled = link is None or not link.connected
        self.scan_btn.configure(bg=CYAN if filled else INK, fg=INK if filled else CYAN)
        self.root.after(60, self._tick)

    def _corner_marks(self, c, w, h) -> None:
        for x, y, dx, dy in ((8, 8, 1, 1), (w - 8, 8, -1, 1), (8, h - 8, 1, -1), (w - 8, h - 8, -1, -1)):
            c.create_line(x, y, x + 18 * dx, y, fill=CYAN, width=2)
            c.create_line(x, y, x, y + 18 * dy, fill=CYAN, width=2)

    def _draw_cam(self) -> None:
        c, w, h = self.cam, self.CAM_W, self.CAM_H
        link = self.link
        on = bool(link and link.cam_on)
        fresh = on and link.cam_jpeg is not None and time.time() - link.cam_at < 4
        if fresh and self.has_pil and link.cam_at != self._cam_shown_at:
            from PIL import Image, ImageTk

            try:
                img = Image.open(io.BytesIO(link.cam_jpeg)).convert("RGB")
                scale = max(w / img.width, h / img.height)
                img = img.resize((max(1, int(img.width * scale)), max(1, int(img.height * scale))))
                left, top = (img.width - w) // 2, (img.height - h) // 2
                self._cam_photo = ImageTk.PhotoImage(img.crop((left, top, left + w, top + h)))
                self._cam_shown_at = link.cam_at
            except Exception:
                self._cam_photo = None
        c.delete("all")
        if fresh and self._cam_photo is not None:
            c.create_image(0, 0, image=self._cam_photo, anchor="nw")
        else:
            if not on:
                msg = "CAMERA OFF · CLICK TO VIEW"
            elif not self.has_pil:
                msg = "INSTALL PILLOW TO SEE THE CAMERA\npip install pillow"
            else:
                msg = "WAITING FOR PICTURES…\nKeep Voice Control open on the robot phone"
            c.create_text(w / 2, h / 2, text=msg, fill="#6FA8B8", font=(self.mono, 10), justify="center")
        self._corner_marks(c, w, h)
        tag = f"● LIVE {link.CAM_FPS} FPS" if on and link else "CAM OFF"
        c.create_rectangle(12, 12, 14 + 8 * len(tag), 32, fill=PANEL, outline="#1E3640")
        c.create_text(18, 22, text=tag, anchor="w", fill=GREEN if on else MUTED, font=(self.mono, 8, "bold"))

    def _draw_pad(self) -> None:
        c = self.pad
        c.delete("all")
        arrows = {"up": (0, -1), "down": (0, 1), "left": (-1, 0), "right": (1, 0)}
        for name, (cx, cy) in self._pad_centres().items():
            held = self.driving == name
            c.create_rectangle(cx - 27, cy - 27, cx + 27, cy + 27, fill=CYAN if held else PANEL,
                               outline=CYAN, width=1.5)
            dx, dy = arrows[name]
            tip = (cx + 12 * dx, cy + 12 * dy)
            base = [(cx - 10 * dx + 9 * dy, cy - 10 * dy + 9 * dx), (cx - 10 * dx - 9 * dy, cy - 10 * dy - 9 * dx)]
            c.create_polygon(tip[0], tip[1], base[0][0], base[0][1], base[1][0], base[1][1],
                             fill=INK if held else CYAN, outline="")
        c.create_text(84, 84, text="DRIVE", fill="#6FA8B8", font=(self.mono, 8))

    def _draw_orb(self) -> None:
        c = self.orb
        c.delete("all")
        cx, cy, r = 85, 70, 52
        t = time.time()
        c.create_oval(cx - 66, cy - 66, cx + 66, cy + 66, outline="#1E3640", dash=(4, 4))
        if self.talking:
            for phase in (0.0, 0.5):
                k = ((t / 1.6) + phase) % 1.0
                rr = r * (1 + 0.25 * k)
                col = f"#{int(0x7F * (1 - k)):02x}{int(0xE7 * (1 - k)):02x}{int(0xFF * (1 - k)):02x}"
                c.create_oval(cx - rr, cy - rr, cx + rr, cy + rr, outline=col, width=2)
        fill = CYAN if self.talking else PANEL
        ink = INK if self.talking else CYAN
        c.create_oval(cx - r, cy - r, cx + r, cy + r, fill=fill, outline=CYAN, width=2)
        c.create_rectangle(cx - 8, cy - 22, cx + 8, cy + 3, outline=ink, width=3)
        c.create_arc(cx - 18, cy - 16, cx + 18, cy + 16, start=200, extent=140, style="arc", outline=ink, width=3)
        c.create_line(cx, cy + 16, cx, cy + 24, fill=ink, width=3)
        c.create_line(cx - 10, cy + 24, cx + 10, cy + 24, fill=ink, width=3)
        level = min(1.0, (self.mic.level if self.mic else 0) * 3.5)
        shape = [0.4, 0.7, 1, 0.7, 0.4]
        for i, s in enumerate(shape):
            k = max(0.25, min(1.0, (0.25 + level) * (0.75 + 0.25 * math.sin(t * 11 + i)))) if self.talking else 0.3
            h = 16 * s * k
            x = cx - 22 + i * 10
            c.create_rectangle(x, 148 - h, x + 5, 148, fill=CYAN if self.talking else "#1E3640", outline="")

    def _quit(self) -> None:
        self._talk(False)
        self._drive(None)
        if self.link:
            self.link.stop()
        self.root.destroy()

    def run(self) -> None:
        self.root.mainloop()


def main() -> None:
    ProApp().run()


if __name__ == "__main__":
    main()
