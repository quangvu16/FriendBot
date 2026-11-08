package org.openbot.app.robot.voice.relay;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import timber.log.Timber;

/**
 * The Remote Mic page's own WiFi link to a robot phone (the phone whose Voice Control page talks
 * to Xiaozhi). Separate from {@link VoiceRelayClient} on purpose: no shared "drive target"
 * state with the Voice Control page, so nothing on that page can block this one.
 *
 * <p>How a robot phone is found — two ways at once, whichever answers first:
 *
 * <ol>
 *   <li>WiFi broadcast: {@link WifiNet#PROBE} to UDP {@link WifiNet#DISCOVERY_PORT} every 1.5 s;
 *       robot phones answer with their name, relay port and WiFi name ({@link VoiceRelayServer}).
 *   <li>NSD/Bonjour ({@code _friendbot-ctrl._tcp.}) — also finds iPhones sharing their robot.
 * </ol>
 *
 * <p>Then it connects by IP (IPv4) over TCP, sends the PIN in its hello and, once the robot phone
 * says {@code auth ok}, streams the microphone ({@code mic} lines). Only phones on the same WiFi
 * name are picked automatically; the user can pick any listed phone or type an IP address. A
 * ping every 3 s detects a dead link within 10 s and reconnects.
 *
 * <p>All network work runs on a background thread; callbacks arrive on the main thread.
 */
public final class RemoteMicLink {

  /** What the link is doing, for the page's status line. */
  public enum State {
    /** This phone has no WiFi (or hotspot) address. */
    NO_WIFI,
    /** Looking for a robot phone on this WiFi. */
    SEARCHING,
    /** Robot phones were found, but only on another WiFi name. */
    OTHER_WIFI,
    /** {@link #stateHost()} runs an older FriendBot that still asks for a PIN. */
    NEED_PIN,
    /** Opening the connection to {@link #stateHost()}. */
    CONNECTING,
    /** {@link #stateHost()} was found but the connection failed; retrying. */
    UNREACHABLE,
    /** Connected and accepted (PIN ok): the microphone can stream. */
    CONNECTED
  }

  public interface Listener {
    /** State, robot phone list or WiFi name changed: refresh the page. */
    void onLinkChanged();

    /** Ask the user for {@code hostName}'s PIN, then call {@link #setPin}. */
    void onPinNeeded(String hostName, boolean wrong);

    /** The robot phone says whether its Voice Control page is hearing the microphone. */
    void onMicAck(boolean live);

    /** Several phones stream to the same robot phone: who holds the microphone. */
    void onMicFloor(String speaker, boolean you, int phones);

    /**
     * Any other line from the robot phone (pong, heard, said, status, cam, safety) for the
     * Pro pages. Main thread.
     */
    default void onExtra(JsonObject msg) {}
  }

  /** One robot phone found on the network (or typed by IP). */
  public static final class Host {
    public final String name;
    public final String address;
    public final int port;
    /** Its WiFi name, null when unknown (iPhones and phones without Location permission). */
    public volatile String wifi;

    final boolean manual;
    volatile boolean fromNsd;
    volatile long seenAtMs;

    Host(String name, String address, int port, String wifi, boolean manual, boolean fromNsd) {
      this.name = name;
      this.address = address;
      this.port = port;
      this.wifi = wifi;
      this.manual = manual;
      this.fromNsd = fromNsd;
    }

    /** Stable identity: the name without NSD's " (2)" suffix, or the typed IP. */
    public String key() {
      return manual ? "ip:" + address : VoiceRelayClient.canonName(name);
    }
  }

  private static final String PREFS = "remote_mic_link";
  private static final String PREF_PIN = "pin_";
  /** The user's pick: a {@link Host#key()} or "" for automatic. */
  private static final String PREF_PICK = "pick";
  private static final String PREF_MANUAL_IP = "manual_ip";
  private static final String PREF_MANUAL_PORT = "manual_port";
  private static final String PREF_MANUAL_NAME = "manual_name";

  private static final long TICK_MS = 1500;
  private static final long HOST_STALE_MS = 9000;
  private static final long PING_EVERY_MS = 3000;
  private static final long LINK_DEAD_MS = 10000;
  private static final long AUTH_TIMEOUT_MS = 6000;
  private static final long RETRY_MS = 2000;
  private static final long RESOLVE_TIMEOUT_MS = 6000;
  private static final int CONNECT_TIMEOUT_MS = 3000;
  /** Longest line read from the robot phone (Pro pages' camera pictures are ~20 KB). */
  private static final int MAX_LINE_CHARS = 262_144;

  private static final RemoteMicLink INSTANCE = new RemoteMicLink();

  public static RemoteMicLink get() {
    return INSTANCE;
  }

  private final Handler main = new Handler(Looper.getMainLooper());
  private final Map<String, Host> hosts = new LinkedHashMap<>(); // guarded by itself
  private final Set<String> pinAsked = new HashSet<>(); // worker thread
  private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>(); // worker thread
  private final Object writeLock = new Object();

  private volatile Listener listener;
  private volatile boolean running;
  private Context ctx;
  private SharedPreferences prefs;
  private HandlerThread workerThread;
  private volatile Handler worker;
  private WifiManager.MulticastLock multicastLock;
  private volatile DatagramSocket probeSocket;
  private NsdManager nsd;
  private NsdManager.DiscoveryListener nsdListener;
  private boolean resolving; // worker thread
  private int resolveGen; // worker thread

  private volatile State state = State.SEARCHING;
  private volatile String stateHost = "";
  private volatile String myWifi;

  // The link; socket/out under writeLock, the rest on the worker thread unless volatile.
  private Socket socket;
  private BufferedWriter out;
  private volatile Host linkHost;
  private volatile boolean authed;
  private volatile long lastLineAtMs;
  private volatile int wrongPinGen = -1;
  private volatile boolean connecting;
  private volatile int linkGen;
  private long linkStartedAtMs;
  private long lastPingAtMs;
  private long nextTryAtMs;

  private RemoteMicLink() {}

  // ------------------------------------------------------------------ public API

  /** Starts finding and connecting (idempotent). Main thread. */
  public void start(Context context, Listener l) {
    listener = l;
    if (running) {
      Handler w = worker;
      if (w != null) {
        w.post(
            () -> {
              pinAsked.clear(); // page re-entry may ask for the PIN again
              nextTryAtMs = 0;
              evaluate();
            });
      }
      return;
    }
    running = true;
    ctx = context.getApplicationContext();
    prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    workerThread = new HandlerThread("remote-mic-link");
    workerThread.start();
    worker = new Handler(workerThread.getLooper());
    try {
      WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
      if (wm != null) {
        multicastLock = wm.createMulticastLock("friendbot-remote-mic");
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();
      }
    } catch (RuntimeException e) {
      multicastLock = null;
    }
    worker.post(
        () -> {
          toResolve.clear();
          resolving = false;
          String manualIp = prefs.getString(PREF_MANUAL_IP, "");
          if (prefs.getString(PREF_PICK, "").startsWith("ip:") && !manualIp.isEmpty()) {
            int port = WifiNet.RELAY_PORT;
            try {
              port = Integer.parseInt(prefs.getString(PREF_MANUAL_PORT, "" + port));
            } catch (NumberFormatException ignored) {
              // Default port.
            }
            String name = prefs.getString(PREF_MANUAL_NAME, manualIp);
            upsert(new Host(name, manualIp, port, null, true, false));
          }
          openProbeSocket();
          tick();
        });
    startNsd();
  }

  /** Stops everything and closes the link (idempotent). Main thread. */
  public void stop() {
    if (!running) return;
    running = false;
    listener = null;
    stopNsd();
    if (multicastLock != null) {
      try {
        multicastLock.release();
      } catch (RuntimeException ignored) {
        // Already released.
      }
      multicastLock = null;
    }
    Handler w = worker;
    HandlerThread t = workerThread;
    worker = null;
    workerThread = null;
    if (w != null) w.removeCallbacksAndMessages(null);
    // Clean up right here (not on the old worker): a quick stop/start (page rotation) must
    // never let the old cleanup close the new link or probe socket.
    linkGen++;
    closeSocket();
    connecting = false;
    authed = false;
    linkHost = null;
    DatagramSocket ds = probeSocket;
    probeSocket = null;
    if (ds != null) ds.close();
    synchronized (hosts) {
      hosts.clear();
    }
    state = State.SEARCHING;
    stateHost = "";
    if (t != null) t.quitSafely();
  }

  public State state() {
    return state;
  }

  /** The robot phone the state is about (empty while searching). */
  public String stateHost() {
    return stateHost;
  }

  /** This phone's WiFi name, or null when unknown. */
  public String myWifi() {
    return myWifi;
  }

  public boolean isConnected() {
    return authed && state == State.CONNECTED;
  }

  /** The connected robot phone's name, or "". */
  public String hostName() {
    Host h = linkHost;
    return h == null ? "" : h.name;
  }

  /** Robot phones found so far, same-WiFi first. */
  public List<Host> hosts() {
    List<Host> list;
    synchronized (hosts) {
      list = new ArrayList<>(hosts.values());
    }
    List<Host> sorted = new ArrayList<>();
    for (Host h : list) if (sameWifi(h)) sorted.add(h);
    for (Host h : list) if (!sameWifi(h)) sorted.add(h);
    return sorted;
  }

  /** True when {@code h} is on this phone's WiFi name (or either name is unknown). */
  public boolean sameWifi(Host h) {
    String mine = myWifi;
    String theirs = h.wifi;
    return mine == null || theirs == null || mine.equals(theirs);
  }

  /** The user picked a robot phone from the list. */
  public void choose(Host h) {
    Handler w = worker;
    if (w == null) return;
    w.post(
        () -> {
          prefs.edit().putString(PREF_PICK, h.key()).apply();
          pinAsked.remove(h.key());
          restartLink();
        });
  }

  /** Back to automatic: connect to the first robot phone on this WiFi. */
  public void chooseAuto() {
    Handler w = worker;
    if (w == null) return;
    w.post(
        () -> {
          prefs.edit().putString(PREF_PICK, "").apply();
          restartLink();
        });
  }

  /** The user typed the robot phone's IP address (shown under its PIN). */
  public void connectToAddress(String ip) {
    String clean = ip == null ? "" : ip.trim();
    if (clean.isEmpty()) return;
    useManualHost(clean, WifiNet.RELAY_PORT, clean, null, null);
  }

  /**
   * The user scanned the robot phone's QR code: connect to that IP and port with that PIN at
   * once — no searching, no typing.
   */
  public void connectFromQr(RelayQr.Info qr) {
    if (qr == null) return;
    useManualHost(qr.ip, qr.port, qr.name, qr.wifi, qr.pin);
  }

  private void useManualHost(String ip, int port, String name, String wifi, String pin) {
    Handler w = worker;
    if (w == null) return;
    w.post(
        () -> {
          Host h = new Host(name, ip, port, wifi, true, false);
          SharedPreferences.Editor e =
              prefs
                  .edit()
                  .putString(PREF_MANUAL_IP, ip)
                  .putString(PREF_MANUAL_PORT, Integer.toString(port))
                  .putString(PREF_MANUAL_NAME, name)
                  .putString(PREF_PICK, h.key());
          if (pin != null) {
            // Remember it for this address and for the phone's name, so the same robot
            // phone found later by WiFi discovery needs no PIN either.
            e.putString(PREF_PIN + h.key(), pin);
            if (!name.equals(ip)) {
              e.putString(PREF_PIN + VoiceRelayClient.canonName(name), pin);
            }
          }
          e.apply();
          h.seenAtMs = SystemClock.uptimeMillis();
          synchronized (hosts) {
            hosts.entrySet().removeIf(en -> en.getValue().manual);
            hosts.put(h.key(), h); // no evaluate here: restartLink connects (or asks) once
          }
          pinAsked.remove(h.key());
          notifyChanged();
          restartLink();
        });
  }

  /** The user typed {@code hostName}'s PIN. */
  public void setPin(String hostName, String pin) {
    Handler w = worker;
    if (w == null) return;
    w.post(
        () -> {
          Host target = findByName(hostName);
          String key = target != null ? target.key() : VoiceRelayClient.canonName(hostName);
          prefs.edit().putString(PREF_PIN + key, pin.trim()).apply();
          pinAsked.remove(key);
          nextTryAtMs = 0;
          evaluate();
        });
  }

  /** Sends one JSON line to the robot phone once linked (Pro pages: pro, move, stop). */
  public void sendRaw(String line) {
    if (!authed) return;
    if (Looper.myLooper() == Looper.getMainLooper()) {
      postWrite(line);
    } else {
      writeLine(line);
    }
  }

  /** Opens/closes the microphone stream on the robot phone. Any thread. */
  public void sendMicState(boolean on) {
    if (!authed) return;
    postWrite("{\"t\":\"mic\",\"on\":" + on + "}");
  }

  /** One 60 ms opus frame (base64) and its level. Any thread. */
  public void sendMicFrame(String base64Opus, float rms) {
    if (!authed) return;
    JsonObject m = new JsonObject();
    m.addProperty("t", "mic");
    m.addProperty("b", base64Opus);
    m.addProperty("r", rms);
    String line = m.toString();
    if (Looper.myLooper() == Looper.getMainLooper()) {
      postWrite(line);
    } else {
      writeLine(line); // the mic thread: write in order, right away
    }
  }

  // ------------------------------------------------------------------ the loop

  /** Every 1.5 s on the worker: probe, expire, keep the link alive, reconnect. */
  private void tick() {
    if (!running) return;
    String wifi = WifiNet.wifiName(ctx);
    if (!eq(wifi, myWifi)) {
      myWifi = wifi;
      notifyChanged();
    }
    sendProbes();
    expireHosts();
    keepAlive();
    evaluate();
    Handler w = worker;
    if (w != null) w.postDelayed(this::tick, TICK_MS);
  }

  /** Decides what to connect to (worker thread). */
  private void evaluate() {
    if (!running) return;
    if (connecting || socket != null) return; // a link is opening or open
    if (SystemClock.uptimeMillis() < nextTryAtMs) return;
    Host target = pickTarget();
    if (target == null) return; // pickTarget set SEARCHING / OTHER_WIFI
    // No PIN needed: the robot phone accepts any phone on its WiFi. A saved PIN is still sent
    // for robot phones running an older FriendBot; one that refuses it asks the user once
    // (linkEnded) and waits here until setPin().
    if (pinAsked.contains(target.key())) {
      setState(State.NEED_PIN, target.name);
      return;
    }
    connect(target, prefs.getString(PREF_PIN + target.key(), ""));
  }

  private Host pickTarget() {
    String pick = prefs.getString(PREF_PICK, "");
    List<Host> list = hosts();
    if (!pick.isEmpty()) {
      for (Host h : list) {
        if (h.key().equals(pick)) return h; // the user's pick, even on another WiFi
      }
    }
    Host other = null;
    for (Host h : list) {
      if (sameWifi(h)) return h;
      if (other == null) other = h;
    }
    if (other != null) {
      setState(State.OTHER_WIFI, other.name + (other.wifi != null ? " (" + other.wifi + ")" : ""));
    } else {
      // Only a hint: some phones name their WiFi/hotspot interface differently, so a missing
      // address never blocks a robot phone that was found or typed.
      setState(WifiNet.localIpv4() == null ? State.NO_WIFI : State.SEARCHING, "");
    }
    return null;
  }

  private void restartLink() {
    linkGen++;
    closeSocket();
    connecting = false;
    if (authed) {
      authed = false;
      postMicAck(false);
    }
    linkHost = null;
    nextTryAtMs = 0;
    setState(State.SEARCHING, "");
    evaluate();
  }

  // ------------------------------------------------------------------ discovery

  private void openProbeSocket() {
    if (probeSocket != null) return;
    try {
      DatagramSocket ds = new DatagramSocket();
      ds.setBroadcast(true);
      probeSocket = ds;
      Thread reader = new Thread(() -> readProbeReplies(ds), "remote-mic-probe");
      reader.setDaemon(true);
      reader.start();
    } catch (SocketException e) {
      Timber.w("Remote mic: cannot open the probe socket: %s", e.getMessage());
    }
  }

  private void sendProbes() {
    DatagramSocket ds = probeSocket;
    if (ds == null) {
      openProbeSocket();
      ds = probeSocket;
      if (ds == null) return;
    }
    byte[] msg = WifiNet.PROBE.getBytes(StandardCharsets.UTF_8);
    for (InetAddress b : WifiNet.broadcastAddresses()) {
      try {
        ds.send(new DatagramPacket(msg, msg.length, b, WifiNet.DISCOVERY_PORT));
      } catch (IOException ignored) {
        // That network is gone; the others may still answer.
      }
    }
  }

  private void readProbeReplies(DatagramSocket ds) {
    byte[] buf = new byte[1024];
    while (!ds.isClosed()) {
      DatagramPacket packet = new DatagramPacket(buf, buf.length);
      try {
        ds.receive(packet);
      } catch (IOException e) {
        return; // closed by stop()
      }
      InetAddress from = packet.getAddress();
      if (from == null || WifiNet.isLocalAddress(from)) continue; // our own server answering
      try {
        String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
        JsonObject o = JsonParser.parseString(text).getAsJsonObject();
        if (!"here".equals(str(o, "t"))) continue;
        String ip = from.getHostAddress();
        String name = str(o, "name");
        int port = o.has("port") ? o.get("port").getAsInt() : WifiNet.RELAY_PORT;
        String wifi = str(o, "wifi");
        Host h =
            new Host(
                name.isEmpty() ? ip : name, ip, port, wifi.isEmpty() ? null : wifi, false, false);
        Handler w = worker;
        if (w != null) w.post(() -> upsert(h));
      } catch (RuntimeException ignored) {
        // Not ours / malformed.
      }
    }
  }

  private void startNsd() {
    nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
    if (nsd == null) return;
    nsdListener =
        new NsdManager.DiscoveryListener() {
          @Override
          public void onServiceFound(NsdServiceInfo info) {
            Handler w = worker;
            if (w == null) return;
            w.post(
                () -> {
                  for (NsdServiceInfo q : toResolve) {
                    if (q.getServiceName().equals(info.getServiceName())) return;
                  }
                  toResolve.add(info);
                  resolveNext();
                });
          }

          @Override
          public void onServiceLost(NsdServiceInfo info) {
            Handler w = worker;
            if (w == null) return;
            w.post(
                () -> {
                  String key = VoiceRelayClient.canonName(info.getServiceName());
                  synchronized (hosts) {
                    Host h = hosts.get(key);
                    if (h != null) h.fromNsd = false; // now it expires unless broadcast sees it
                  }
                });
          }

          @Override
          public void onDiscoveryStarted(String serviceType) {}

          @Override
          public void onDiscoveryStopped(String serviceType) {}

          @Override
          public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            Timber.w("Remote mic: NSD discovery failed (%d); WiFi broadcast still works", errorCode);
          }

          @Override
          public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
        };
    try {
      nsd.discoverServices(VoiceRelayServer.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, nsdListener);
    } catch (IllegalArgumentException e) {
      nsdListener = null;
    }
  }

  private void stopNsd() {
    if (nsd != null && nsdListener != null) {
      try {
        nsd.stopServiceDiscovery(nsdListener);
      } catch (IllegalArgumentException ignored) {
        // Never started.
      }
    }
    nsdListener = null;
  }

  /** One NSD resolve at a time (Android allows only one), with a timeout. Worker thread. */
  private void resolveNext() {
    if (!running || resolving || toResolve.isEmpty() || nsd == null) return;
    NsdServiceInfo info = toResolve.poll();
    resolving = true;
    final int gen = ++resolveGen;
    Handler w = worker;
    if (w == null) return;
    w.postDelayed(
        () -> {
          if (resolving && resolveGen == gen) {
            resolving = false; // Android sometimes never answers: move on
            resolveNext();
          }
        },
        RESOLVE_TIMEOUT_MS);
    try {
      nsd.resolveService(
          info,
          new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo si, int errorCode) {
              Handler w2 = worker;
              if (w2 == null) return;
              w2.post(
                  () -> {
                    if (resolveGen != gen) return;
                    resolving = false;
                    resolveNext();
                  });
            }

            @Override
            public void onServiceResolved(NsdServiceInfo si) {
              Handler w2 = worker;
              if (w2 == null) return;
              w2.post(
                  () -> {
                    if (resolveGen != gen) return;
                    resolving = false;
                    InetAddress a = ipv4Of(si);
                    if (a != null && !isOwn(si)) {
                      upsert(
                          new Host(si.getServiceName(), a.getHostAddress(), si.getPort(), null,
                              false, true));
                    }
                    resolveNext();
                  });
            }
          });
    } catch (IllegalArgumentException e) {
      resolving = false;
    }
  }

  private static InetAddress ipv4Of(NsdServiceInfo si) {
    List<InetAddress> all = new ArrayList<>();
    if (Build.VERSION.SDK_INT >= 34) {
      all.addAll(si.getHostAddresses());
    }
    if (si.getHost() != null) all.add(si.getHost());
    for (InetAddress a : all) {
      if (a instanceof Inet4Address) return a;
    }
    return null; // IPv6 only: the WiFi broadcast path will find this phone instead
  }

  private static boolean isOwn(NsdServiceInfo si) {
    List<InetAddress> all = new ArrayList<>();
    if (Build.VERSION.SDK_INT >= 34) {
      all.addAll(si.getHostAddresses());
    }
    if (si.getHost() != null) all.add(si.getHost());
    for (InetAddress a : all) {
      if (WifiNet.isLocalAddress(a)) return true;
    }
    return false;
  }

  /** Adds or refreshes a robot phone (worker thread). */
  private void upsert(Host h) {
    h.seenAtMs = SystemClock.uptimeMillis();
    boolean changed;
    synchronized (hosts) {
      Host old = hosts.get(h.key());
      if (old != null) {
        if (h.wifi == null) h.wifi = old.wifi; // NSD knows no WiFi name; keep the broadcast's
        h.fromNsd = h.fromNsd || old.fromNsd;
      }
      changed =
          old == null
              || !old.address.equals(h.address)
              || old.port != h.port
              || !eq(old.wifi, h.wifi);
      if (old != null && !changed && linkHost == old) {
        old.seenAtMs = h.seenAtMs; // keep the linked object; just refresh it
      } else {
        hosts.put(h.key(), h);
      }
    }
    if (changed) {
      notifyChanged();
      evaluate();
    }
  }

  private void expireHosts() {
    long now = SystemClock.uptimeMillis();
    boolean removed;
    synchronized (hosts) {
      removed =
          hosts
              .entrySet()
              .removeIf(
                  e -> {
                    Host h = e.getValue();
                    return !h.manual
                        && !h.fromNsd
                        && h != linkHost
                        && now - h.seenAtMs > HOST_STALE_MS;
                  });
    }
    if (removed) notifyChanged();
  }

  private Host findByName(String name) {
    synchronized (hosts) {
      for (Host h : hosts.values()) {
        if (h.name.equals(name)) return h;
      }
    }
    return null;
  }

  // ------------------------------------------------------------------ the link

  private void connect(Host target, String pin) {
    connecting = true;
    authed = false;
    final int gen = ++linkGen;
    linkHost = target;
    linkStartedAtMs = SystemClock.uptimeMillis();
    setState(State.CONNECTING, target.name);
    Thread io = new Thread(() -> runLink(target, pin, gen), "remote-mic-link-io");
    io.setDaemon(true);
    io.start();
  }

  /** IO thread: connect, say hello with the PIN, then read lines until the link ends. */
  private void runLink(Host target, String pin, int gen) {
    Socket s = new Socket();
    boolean opened = false;
    try {
      s.setTcpNoDelay(true);
      s.connect(new InetSocketAddress(target.address, target.port), CONNECT_TIMEOUT_MS);
      BufferedReader in =
          new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
      synchronized (writeLock) {
        if (gen != linkGen || !running) {
          s.close();
          return;
        }
        socket = s;
        out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
      }
      opened = true;
      lastLineAtMs = SystemClock.uptimeMillis();
      JsonObject hello = new JsonObject();
      hello.addProperty("t", "hello");
      hello.addProperty("name", Build.MODEL);
      hello.addProperty("pin", pin);
      writeLine(hello.toString());
      String line;
      while ((line = in.readLine()) != null && line.length() <= MAX_LINE_CHARS) {
        lastLineAtMs = SystemClock.uptimeMillis();
        handleLine(line, target, gen);
      }
    } catch (IOException e) {
      Timber.w(
          "Remote mic: link to %s (%s:%d) ended: %s",
          target.name, target.address, target.port, e.getMessage());
    } finally {
      synchronized (writeLock) {
        if (socket == s) {
          socket = null;
          out = null;
        }
      }
      try {
        s.close();
      } catch (IOException ignored) {
        // Already closed.
      }
      final boolean wasOpened = opened;
      Handler w = worker;
      if (w != null) w.post(() -> linkEnded(target, gen, wasOpened));
    }
  }

  /** Robot phone → this phone lines (IO thread). */
  private void handleLine(String line, Host target, int gen) {
    JsonObject o;
    try {
      o = JsonParser.parseString(line).getAsJsonObject();
    } catch (RuntimeException e) {
      return;
    }
    String t = str(o, "t");
    switch (t) {
      case "auth":
        boolean ok = o.has("ok") && o.get("ok").getAsBoolean();
        Handler w = worker;
        if (ok) {
          if (w != null) {
            w.post(
                () -> {
                  if (gen != linkGen) return;
                  connecting = false;
                  authed = true;
                  lastPingAtMs = SystemClock.uptimeMillis();
                  setState(State.CONNECTED, target.name);
                  Timber.i("Remote mic: connected to %s", target.name);
                });
          }
        } else {
          wrongPinGen = gen; // the robot phone closes the socket next
        }
        break;
      case "micack":
        boolean live = o.has("live") && o.get("live").getAsBoolean();
        postMicAck(live);
        break;
      case "floor":
        String speaker = str(o, "name");
        boolean you = o.has("you") && o.get("you").getAsBoolean();
        int phones = o.has("phones") ? o.get("phones").getAsInt() : 1;
        main.post(
            () -> {
              Listener l = listener;
              if (l != null) l.onMicFloor(speaker, you, phones);
            });
        break;
      default:
        // hello / pong / Pro page lines (heard, said, status, cam, safety)
        main.post(
            () -> {
              Listener l = listener;
              if (l != null) l.onExtra(o);
            });
        break;
    }
  }

  /** Worker thread: the IO thread finished. */
  private void linkEnded(Host target, int gen, boolean opened) {
    if (gen != linkGen) return; // superseded by a newer attempt
    boolean wasAuthed = authed;
    connecting = false;
    authed = false;
    linkHost = null;
    if (wasAuthed) postMicAck(false);
    long now = SystemClock.uptimeMillis();
    if (wrongPinGen == gen) {
      prefs.edit().remove(PREF_PIN + target.key()).apply();
      pinAsked.add(target.key());
      setState(State.NEED_PIN, target.name);
      postPinNeeded(target.name, true);
    } else if (!wasAuthed) {
      // Never accepted: unreachable (WiFi blocks phone-to-phone) or the robot phone left.
      setState(State.UNREACHABLE, target.name);
      nextTryAtMs = now + RETRY_MS;
    } else {
      setState(State.CONNECTING, target.name);
      nextTryAtMs = now + 500; // a dropped link: try again soon
    }
    evaluate();
  }

  /** Pings while linked; closes a link that went silent (worker thread). */
  private void keepAlive() {
    if (socket == null) return;
    long now = SystemClock.uptimeMillis();
    if (!authed) {
      if (now - linkStartedAtMs > AUTH_TIMEOUT_MS) {
        Timber.w("Remote mic: no PIN answer from %s; reconnecting", hostName());
        closeSocket();
      }
      return;
    }
    if (now - lastLineAtMs > LINK_DEAD_MS) {
      Timber.w("Remote mic: %s went silent; reconnecting", hostName());
      closeSocket();
      return;
    }
    if (now - lastPingAtMs >= PING_EVERY_MS) {
      lastPingAtMs = now;
      writeLine("{\"t\":\"ping\"}");
    }
  }

  private void closeSocket() {
    Socket s;
    synchronized (writeLock) {
      s = socket;
    }
    if (s != null) {
      try {
        s.close(); // the IO thread then ends and reports linkEnded
      } catch (IOException ignored) {
        // Already closed.
      }
    }
  }

  private void postWrite(String line) {
    Handler w = worker;
    if (w != null) w.post(() -> writeLine(line));
  }

  /** Writes one line now. Never call on the main thread. */
  private void writeLine(String line) {
    synchronized (writeLock) {
      if (out == null) return;
      try {
        out.write(line);
        out.write('\n');
        out.flush();
      } catch (IOException e) {
        Timber.w("Remote mic: send failed: %s", e.getMessage());
        try {
          if (socket != null) socket.close();
        } catch (IOException ignored) {
          // Already closed.
        }
      }
    }
  }

  // ------------------------------------------------------------------ helpers

  private void setState(State s, String host) {
    String h = host == null ? "" : host;
    if (state == s && stateHost.equals(h)) return;
    state = s;
    stateHost = h;
    notifyChanged();
  }

  private void notifyChanged() {
    main.post(
        () -> {
          Listener l = listener;
          if (l != null) l.onLinkChanged();
        });
  }

  private void postPinNeeded(String host, boolean wrong) {
    main.post(
        () -> {
          Listener l = listener;
          if (l != null) l.onPinNeeded(host, wrong);
        });
  }

  private void postMicAck(boolean live) {
    main.post(
        () -> {
          Listener l = listener;
          if (l != null) l.onMicAck(live);
        });
  }

  private static String str(JsonObject o, String key) {
    return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
  }

  private static boolean eq(String a, String b) {
    return a == null ? b == null : a.equals(b);
  }
}
