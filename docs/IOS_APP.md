# 4 · FriendBot iOS app (iPhone)

![iOS Voice Control screen, annotated](images/ios-app.svg)

*Source: [`robot/ios/OpenBot/`](../robot/ios/OpenBot/) — mainly `OpenBot/Controllers/VoiceControl/` (`VoiceControlController.swift`, `XiaozhiSession.swift`, `RemoteMicController.swift`). The app is the [OpenBot](https://www.openbot.org) iOS robot app with FriendBot's features added; it shows as **FriendBot** on the home screen. The Xcode project folder is still called `OpenBot` so the project file and CocoaPods paths keep working.*

## Why there is no download link

Apple only lets you install iPhone apps from the App Store, TestFlight, or **your own Xcode build**. So for iPhone you build it once on a Mac (about 15 minutes the first time).

## Build and install

**You need:** a Mac with **Xcode**, **CocoaPods** (`sudo gem install cocoapods` or `brew install cocoapods`), an iPhone with **iOS 15 or newer**, and a USB cable.

```bash
git clone https://github.com/quangvu16/FriendBot.git
cd FriendBot/robot/ios/OpenBot
pod install                     # downloads libopus, Starscream, TensorFlowLiteSwift, …
open OpenBot.xcworkspace        # the .xcworkspace, not the .xcodeproj
```

1. In Xcode select the **OpenBot** target → **Signing & Capabilities** → **Team** = your Apple ID (Xcode → Settings → Accounts to add it).
   If Xcode says the bundle id `com.vunguyen.openbot.ackermann` is taken, change it to something unique, e.g. `com.<yourname>.friendbot`.
2. On the iPhone: **Settings → Privacy & Security → Developer Mode → On** (restart).
3. Choose your iPhone at the top of Xcode → ▶ **Run**.
4. First launch: **Settings → General → VPN & Device Management →** trust your developer certificate.

*Why the app stops opening after 7 days:* builds signed with a **free** Apple ID expire after 7 days — just press ▶ Run again. A paid developer account (99 USD/year) gives 1-year builds and TestFlight.

## Connect the robot — Bluetooth only

iOS does not let apps talk to USB serial devices such as the ESP32, so the iPhone always uses **Bluetooth LE**:

1. Switch the robot on — LCD shows `FriendBot MTV`.
2. App home screen → **Bluetooth** → tap **`FriendBot: MTV`**.
3. The LCD's first line ends with ` BLE`, and Voice Control shows **"Connected (BLE)"**.

*Why the firmware accepts both "write" and "write without response":* iOS sends the **Stop** button *with* response and drive commands *without*. The old OpenBot MTV sketch only accepted one kind and could miss a Stop. (*Source:* `ble_begin()` in the firmware.)

If Bluetooth drops, the firmware stops the motors at once.

## Use Voice Control

**Home grid → General → Voice Control.** Allow the **microphone** (and later the **camera**). The permission texts come from `Info.plist`.

### Pair once with Xiaozhi

> **Full step-by-step guide with the website screens: [Connect to xiaozhi.me](XIAOZHI_PAIRING.md).**

![Pairing screen](images/xiaozhi-pairing.svg)

1. The app shows a **6-digit code**.
2. Open **[xiaozhi.me](https://xiaozhi.me)** → sign in → **Add device** → enter the code.
3. The app saves the credentials in the iPhone and connects by itself next time.

### What you can do

Everything works the same as on Android:

| You do | What happens |
|---|---|
| Just speak (hands-free) | **Listening… → Thinking… → Speaking…**, reply in Xiaozhi's voice |
| **Push to speak** | tap mic to start / tap to send |
| Tap mic while it talks | barge-in: stops the reply and listens |
| Type a command | same AI, or built-in keywords when offline |
| Quick buttons, **Stop** | always work, even with no internet |
| Camera corner | tap = photo + "what do you see?"; ⟲ = back/front camera |
| Gear | sign in again, voice language (default Tiếng Việt), custom server |

*Example:* you say **"rẽ trái"** → Xiaozhi: "Rẽ trái nhé!" → chip **» Turn left** → the app sends `c-144,144` over Bluetooth every 250 ms for 1.2 s, then `c0,0`. The robot spins left on the spot and the left LED blinks.

The keyword list (English / Tiếng Việt / 中文) is the same as on Android — see [Android guide](ANDROID_APP.md#built-in-keywords-offline-and-safety-net). *Source:* `VoiceInterpreter.swift`.

### iPhone as helper or Remote Mic

An iPhone and an Android phone can work together on the same Wi-Fi: whichever phone holds the Bluetooth link shares the robot; the other opens **Voice Control** (to drive) or **Remote Mic** (to be a wireless microphone). iOS asks for **Local Network** permission the first time — allow it, or the phones cannot find each other.

## Troubleshooting

| Problem | Fix |
|---|---|
| `pod install` fails | `pod repo update`, then try again; Apple-silicon Macs: install CocoaPods with Homebrew |
| "No such module …" in Xcode | you opened the `.xcodeproj` — open `OpenBot.xcworkspace` |
| Signing error / "Failed to register bundle identifier" | pick your Team and change the bundle id |
| `FriendBot: MTV` not in the Bluetooth list | robot on? Not already connected to another phone? Turn iPhone Bluetooth off/on |
| App will not open after a week | free-account build expired — Run again from Xcode |
| Helper phone cannot find the robot | allow Local Network in iPhone Settings → FriendBot |

## Questions to go deeper

1. Apple blocks USB serial for normal apps. What would it take to use a cable anyway (hint: MFi accessories / External Accessory framework), and is it worth it?
2. Why does the firmware need a "Bluetooth disconnected → stop" rule more on iPhone than on Android over USB?
3. The same Xiaozhi protocol is written twice (Java and Swift). What are the pros and cons compared with one shared library (e.g. Kotlin Multiplatform or Flutter)?
4. Where are the Xiaozhi credentials stored on the iPhone, and would the Keychain be a safer place than app settings?
5. How could you ship the app to friends without them needing Xcode (TestFlight), and what does Apple review check for microphone and camera use?
