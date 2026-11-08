// swift-tools-version:5.7
// FriendBot Mic for macOS — the Remote Mic (same WiFi) and Internet Mic (any WiFi or 4G/5G)
// pages of the FriendBot phone apps, as a native SwiftUI app. Build with ./build.sh.
import PackageDescription

let package = Package(
    name: "FriendBotMic",
    platforms: [.macOS(.v13)],
    targets: [
        // libopus from Homebrew (`brew install opus`): the phones' voice codec.
        .systemLibrary(
            name: "COpus",
            path: "Sources/COpus",
            pkgConfig: "opus",
            providers: [.brew(["opus"])]
        ),
        .executableTarget(
            name: "FriendBotMic",
            dependencies: ["COpus"],
            path: "Sources/FriendBotMic"
        ),
    ]
)
