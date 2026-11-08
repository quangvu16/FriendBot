package org.openbot.app.robot.voice.relay;

import android.graphics.Bitmap;
import android.graphics.Color;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Remote Mic pairing by QR code. The robot phone shows a QR holding its IP address, relay port,
 * name and WiFi name — no PIN:
 *
 * <pre>friendbot://mic?ip=192.168.1.23&amp;port=47821&amp;name=Joy%204&amp;wifi=Home</pre>
 *
 * The Remote Mic page scans it and connects straight away, with no searching and no typing.
 */
public final class RelayQr {

  private static final String PREFIX = "friendbot://mic?";

  /** What a scanned QR says. */
  public static final class Info {
    public final String ip;
    public final int port;
    /** Only in QR codes from older FriendBot versions; null otherwise. */
    public final String pin;
    public final String name;
    /** Null when the robot phone did not know its WiFi name. */
    public final String wifi;

    Info(String ip, int port, String pin, String name, String wifi) {
      this.ip = ip;
      this.port = port;
      this.pin = pin;
      this.name = name;
      this.wifi = wifi;
    }
  }

  private RelayQr() {}

  /** The QR text for this robot phone. {@code wifi} may be null. */
  public static String build(String ip, int port, String name, String wifi) {
    StringBuilder b = new StringBuilder(PREFIX);
    b.append("ip=").append(enc(ip));
    b.append("&port=").append(port);
    b.append("&name=").append(enc(name));
    if (wifi != null && !wifi.isEmpty()) {
      b.append("&wifi=").append(enc(wifi));
    }
    return b.toString();
  }

  /** Reads a scanned QR; null when it is not a FriendBot Remote Mic code. */
  public static Info parse(String text) {
    if (text == null || !text.startsWith(PREFIX)) return null;
    Map<String, String> q = new HashMap<>();
    for (String part : text.substring(PREFIX.length()).split("&")) {
      int eq = part.indexOf('=');
      if (eq <= 0) continue;
      q.put(part.substring(0, eq), dec(part.substring(eq + 1)));
    }
    String ip = q.get("ip");
    String pin = q.get("pin");
    if (ip == null || !ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return null;
    if (pin != null && !pin.matches("\\d{4}")) pin = null; // optional, older codes only
    int port = WifiNet.RELAY_PORT;
    try {
      if (q.containsKey("port")) port = Integer.parseInt(q.get("port"));
    } catch (NumberFormatException ignored) {
      // Keep the default port.
    }
    if (port <= 0 || port > 65535) return null;
    String name = q.get("name");
    if (name == null || name.isEmpty()) name = ip;
    String wifi = q.get("wifi");
    return new Info(ip, port, pin, name, wifi == null || wifi.isEmpty() ? null : wifi);
  }

  /** Draws {@code text} as a black-on-white QR of {@code sizePx} square; null on failure. */
  public static Bitmap render(String text, int sizePx) {
    try {
      Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
      hints.put(EncodeHintType.MARGIN, 1);
      hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
      BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
      int w = m.getWidth();
      int h = m.getHeight();
      int[] pixels = new int[w * h];
      for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
          pixels[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
        }
      }
      Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
      bmp.setPixels(pixels, 0, w, 0, 0, w, h);
      return bmp;
    } catch (WriterException | IllegalArgumentException e) {
      return null;
    }
  }

  // ------------------------------------------------------------------ Internet Mic

  private static final String NET_PREFIX = "friendbot://net?";

  /**
   * The free public MQTT brokers both apps know, by short name (the QR lists names, not URLs).
   * The robot phone listens on all of them; the Remote Mic phone uses the first that answers.
   */
  public static final String[][] BROKERS = {
    {"hivemq", "wss://broker.hivemq.com:8884/mqtt"},
    {"emqx", "wss://broker.emqx.io:8084/mqtt"},
  };

  /** What an Internet QR says: a private room, its secret key, the robot phone's name. */
  public static final class NetInfo {
    public final String room;
    public final String key;
    public final String name;

    NetInfo(String room, String key, String name) {
      this.room = room;
      this.key = key;
      this.name = name;
    }
  }

  /** {@code friendbot://net?room=…&key=…&name=…} — works over WiFi internet or 4G/5G. */
  public static String buildNet(String room, String key, String name) {
    return NET_PREFIX + "room=" + enc(room) + "&key=" + enc(key) + "&name=" + enc(name);
  }

  /** Reads an Internet QR; null when it is not one. */
  public static NetInfo parseNet(String text) {
    if (text == null || !text.startsWith(NET_PREFIX)) return null;
    Map<String, String> q = new HashMap<>();
    for (String part : text.substring(NET_PREFIX.length()).split("&")) {
      int eq = part.indexOf('=');
      if (eq <= 0) continue;
      q.put(part.substring(0, eq), dec(part.substring(eq + 1)));
    }
    String room = q.get("room");
    String key = q.get("key");
    if (room == null || !room.matches("[a-z0-9]{8,32}")) return null;
    byte[] raw = key == null ? null : NetCrypto.unb64(key);
    if (raw == null || raw.length != 32) return null;
    String name = q.get("name");
    return new NetInfo(room, key, name == null || name.isEmpty() ? "Robot phone" : name);
  }

  private static String enc(String s) {
    try {
      return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      return "";
    }
  }

  private static String dec(String s) {
    try {
      return URLDecoder.decode(s, "UTF-8");
    } catch (UnsupportedEncodingException | IllegalArgumentException e) {
      return s;
    }
  }
}
