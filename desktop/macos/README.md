# FriendBot Mic for macOS

The **Remote Mic** (same Wi‑Fi) and **Internet Mic** (any Wi‑Fi or 4G/5G) pages of the
FriendBot phone apps, as a native Mac app with the same robotic design.
Your Mac's microphone talks to the robot phone's **Voice Control** page.

## Build (one time)

1. Install Xcode (App Store) or just the tools: `xcode-select --install`
2. Install Homebrew: https://brew.sh
3. In Terminal:

   ```bash
   cd "desktop/macos"
   ./build.sh
   ```

The app appears in `build/FriendBot Mic.app` and opens. Drag it to Applications if you like.
The first time, macOS asks for the **microphone**, **camera** (QR) and **local network** — allow all three.

## Use

| Tab | When | How |
|-----|------|-----|
| REMOTE MIC | Mac and robot phone on the **same Wi‑Fi** | Leave the box empty and press CONNECT (it finds the phone), or type the phone's IP, or SCAN QR the Wi‑Fi QR. |
| INTERNET MIC | Any network (home Wi‑Fi, office, phone hotspot 4G/5G) | Press SCAN QR and show the robot phone's **Internet QR** (bottom‑right button on Voice Control) to the camera, or choose a screenshot (PICTURE) or paste the `friendbot://net?…` text. |

Then click the big mic (or press **Space**). Green rings = your voice is reaching the robot.

## Notes

- Only one person talks at a time: if a phone has the mic, the Mac shows "… HAS THE MIC".
- Internet Mic is end‑to‑end encrypted (AES‑256‑GCM) through a free public MQTT relay
  (HiveMQ, then EMQX as a backup). "New code" on the robot phone stops old QR codes.
- The app saves the last IP / Internet QR and reconnects when it opens.

## FriendBot Mic Pro

`./build.sh` now also makes **`build/FriendBot Mic Pro.app`** (the same program; the simple app above is unchanged).
It has the Wi‑Fi Mic Pro and Internet Mic Pro pages:

- **STOP** (red bar or **Esc**). The robot also stops by itself if the Mac goes silent while it moves (1.5 s on Wi‑Fi, 3 s over the internet).
- **Heard / answered**: what Xiaozhi understood and said back.
- **Camera**: click the camera window to see the robot phone's camera.
- **Push‑to‑talk**: hold the orb or hold **Space**.
- **Drive pad**: hold an arrow (on screen or the arrow keys); let go to stop.
- **Delay and battery** chips at the top.
