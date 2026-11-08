package org.openbot.app.robot.voice.relay;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Small WiFi helpers shared by the relay host ({@link VoiceRelayServer}) and the Remote Mic link
 * ({@link RemoteMicLink}): the WiFi name, this phone's LAN address and the broadcast addresses
 * used to find robot phones without NSD.
 *
 * <p>Discovery: a Remote Mic phone broadcasts {@link #PROBE} to UDP port {@link #DISCOVERY_PORT};
 * every robot phone answers {@code {"t":"here","name":"Joy 4","port":47821,"wifi":"Home"}} to
 * the sender. The relay itself listens on TCP {@link #RELAY_PORT} when that port is free, so a
 * user can also type the robot phone's IP address.
 */
public final class WifiNet {

  public static final int DISCOVERY_PORT = 47820;
  public static final int RELAY_PORT = 47821;
  static final String PROBE = "FRIENDBOT?";

  private WifiNet() {}

  /**
   * The connected WiFi name, or null when unknown (no WiFi, or Android hides it because the
   * Location permission is off).
   */
  public static String wifiName(Context context) {
    try {
      WifiManager wm =
          (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
      if (wm == null) return null;
      WifiInfo info = wm.getConnectionInfo();
      if (info == null) return null;
      String ssid = info.getSSID();
      if (ssid == null) return null;
      if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
        ssid = ssid.substring(1, ssid.length() - 1);
      }
      if (ssid.isEmpty() || "<unknown ssid>".equalsIgnoreCase(ssid) || "0x".equals(ssid)) {
        return null;
      }
      return ssid;
    } catch (SecurityException e) {
      return null;
    }
  }

  /** This phone's IPv4 address on WiFi (or on its own hotspot), or null when there is none. */
  public static String localIpv4() {
    String fallback = null;
    for (LanAddress la : ipv4Interfaces()) {
      InetAddress a = la.address.getAddress();
      if (!a.isSiteLocalAddress()) continue;
      if (la.iface.startsWith("wlan") || la.iface.startsWith("ap") || la.iface.startsWith("swlan")) {
        return a.getHostAddress();
      }
      if (fallback == null) fallback = a.getHostAddress();
    }
    return fallback;
  }

  /** Where to send discovery probes: the global broadcast plus each LAN's own broadcast. */
  static List<InetAddress> broadcastAddresses() {
    List<InetAddress> list = new ArrayList<>();
    try {
      list.add(InetAddress.getByName("255.255.255.255"));
    } catch (Exception ignored) {
      // Always resolvable.
    }
    for (LanAddress la : ipv4Interfaces()) {
      InetAddress b = la.address.getBroadcast();
      if (b != null && !list.contains(b)) list.add(b);
    }
    return list;
  }

  /** True when the address belongs to this phone (its own server answering). */
  static boolean isLocalAddress(InetAddress address) {
    if (address == null) return false;
    if (address.isLoopbackAddress()) return true;
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces != null && interfaces.hasMoreElements()) {
        Enumeration<InetAddress> addresses = interfaces.nextElement().getInetAddresses();
        while (addresses.hasMoreElements()) {
          if (addresses.nextElement().equals(address)) return true;
        }
      }
    } catch (SocketException e) {
      // Cannot enumerate: treat as remote rather than hide a real robot phone.
    }
    return false;
  }

  // ---------------------------------------------------------------------- internals

  /** One IPv4 address and the name of the interface that carries it. */
  private static final class LanAddress {
    final InterfaceAddress address;
    final String iface;

    LanAddress(InterfaceAddress address, String iface) {
      this.address = address;
      this.iface = iface;
    }
  }

  /** IPv4 addresses of the interfaces that are up, not loopback (WiFi, hotspot, ethernet). */
  private static List<LanAddress> ipv4Interfaces() {
    List<LanAddress> list = new ArrayList<>();
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces != null && interfaces.hasMoreElements()) {
        NetworkInterface ni = interfaces.nextElement();
        if (!ni.isUp() || ni.isLoopback()) continue;
        String name = ni.getName() == null ? "" : ni.getName();
        // Mobile data (rmnet/ccmni) and VPN tunnels never reach the robot phone.
        if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun")) {
          continue;
        }
        for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
          if (ia != null && ia.getAddress() instanceof Inet4Address) {
            list.add(new LanAddress(ia, name));
          }
        }
      }
    } catch (SocketException e) {
      // No interfaces readable: nothing to probe.
    }
    return list;
  }
}
