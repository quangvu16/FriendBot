# FriendBot Mic — Python desktop app

Your computer's microphone speaks to the FriendBot robot phone's **Voice Control** page — the
same two pages as the phone apps, in the same robotic look:

| Tab | Network | How it connects |
|---|---|---|
| **REMOTE MIC** | same WiFi | finds the robot phone by WiFi broadcast (or type its IP / paste its QR text), TCP port 47821 |
| **INTERNET MIC** | any WiFi or 4G/5G | private room on a free public MQTT broker (HiveMQ, EMQX backup), AES-256-GCM with the key in the robot phone's **Internet QR** |

Voice: 16 kHz mono, 60 ms Opus frames — exactly what the phones send. Xiaozhi answers on the
robot phone. No PIN.

## Install (macOS)

```bash
brew install opus python-tk        # Opus library + Tk for the window
cd desktop/python
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python3 friendbot_mic.py
```

macOS asks for **Microphone** (and **Camera** for scanning) the first time — allow them for
Terminal (or your Python app).

## Use

1. On the robot phone open **Voice Control**. Tap the QR button (bottom-right of the face):
   *Internet QR* for Internet Mic; for Remote Mic its IP is on the Voice Control card.
2. In the app pick a tab. **SCAN QR** uses the webcam (or opens a photo/screenshot of the QR);
   or paste the QR text / type the IP and press **CONNECT**. Remote Mic with an empty box finds
   the robot phone on the WiFi by itself.
3. Click the big round mic and speak. Click again to stop.

The status pill's light: green linked · amber busy · red trouble · grey nothing yet.

## Terminal mode

```bash
python3 friendbot_mic.py --lan                    # Remote Mic, find the robot phone
python3 friendbot_mic.py --lan 192.168.1.23       # Remote Mic, by IP
python3 friendbot_mic.py --net "friendbot://net?room=…&key=…&name=…"
```
Press **Enter** to talk / stop, **Ctrl-C** to quit.

## FriendBot Mic Pro (Wi‑Fi Mic Pro / Internet Mic Pro)

`python3 friendbot_mic_pro.py` opens the Pro pages. The simple app above is unchanged.

| Feature | How |
|---|---|
| **STOP** | Red bar or **Esc**. The robot also stops by itself if this app goes silent while it moves (1.5 s on Wi‑Fi, 3 s over the internet). |
| **Heard / answered** | Shows what Xiaozhi understood and what it said back. |
| **Camera** | Click the camera window (needs `pillow`): 5 pictures a second on Wi‑Fi, 2 over the internet. |
| **Push‑to‑talk** | Hold the orb or hold **Space**. The mic is open only while held. |
| **Drive pad** | Hold an arrow (on screen or the arrow keys); let go to stop. |
| **Delay and battery** | Ping time in ms (green < 150, amber < 400, red above) and the robot battery. |

The robot phone needs the updated FriendBot app with its Voice Control page open.
