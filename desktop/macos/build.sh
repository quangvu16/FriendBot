#!/bin/bash
# Builds "FriendBot Mic.app" into ./build and opens it.
# Needs: Xcode (or its Command Line Tools) and Homebrew.
set -euo pipefail
cd "$(dirname "$0")"

command -v brew >/dev/null || { echo "Install Homebrew first: https://brew.sh"; exit 1; }
brew list opus >/dev/null 2>&1 || brew install opus
brew list pkg-config >/dev/null 2>&1 || brew install pkg-config
OPUS="$(brew --prefix opus)"

echo "▸ Compiling…"
swift build -c release \
    -Xcc -I"$OPUS/include/opus" -Xcc -I"$OPUS/include" \
    -Xlinker -L"$OPUS/lib"

BIN="$(swift build -c release --show-bin-path)/FriendBotMic"

# One program, two apps: the simple pages and the Pro pages (bundle id ending in ".pro").
pack() {
    local APP="build/$1.app"
    echo "▸ Packing $APP"
    rm -rf "$APP"
    mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
    cp "$BIN" "$APP/Contents/MacOS/FriendBotMic"
    sed -e "s/com.vunguyen.friendbot.mic</$2</" \
        -e "s/<string>FriendBot Mic<\/string>/<string>$1<\/string>/g" \
        Info.plist > "$APP/Contents/Info.plist"
    codesign --force --deep --sign - "$APP"
}
pack "FriendBot Mic" "com.vunguyen.friendbot.mic"
pack "FriendBot Mic Pro" "com.vunguyen.friendbot.mic.pro"

echo "✓ Done: build/FriendBot Mic.app and build/FriendBot Mic Pro.app"
open "build/FriendBot Mic Pro.app"
