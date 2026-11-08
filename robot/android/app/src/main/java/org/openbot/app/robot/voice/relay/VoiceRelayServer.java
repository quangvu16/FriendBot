package org.openbot.app.robot.voice.relay;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.BatteryManager;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.openbot.app.robot.vehicle.Vehicle;
import timber.log.Timber;

/**
 * WiFi voice-relay host: the phone that holds the robot link (USB or Bluetooth) shares it with
 * other phones on the same network. Remote phones run their own voice control page (mic to
 * Xiaozhi) and forward only drive commands here as newline-delimited JSON, so anyone nearby can
 * drive the robot by voice without pairing Bluetooth:
 *
 * <pre>
 *   {"t":"hello","name":"Pixel 8","pin":"1234"}  remote greeting + the host's PIN (required)
 *   {"t":"move","l":0.75,"r":0.75,"ms":1200}
 *   {"t":"stop"}
 *   {"t":"ping"}
 *   {"t":"mic","on":true}                 remote microphone opens (Remote Mic page)
 *   {"t":"mic","b":"<base64 opus>","r":0.4}  one 60 ms mic frame + its level
 *   {"t":"mic","on":false}                remote microphone closes
 * </pre>
 *
 * <p>Several phones may stream at once (two people, two Remote Mic pages). The host's Xiaozhi
 * session hears one voice at a time: the phone that is speaking holds the "floor" and its
 * frames are forwarded; another phone takes the floor when it speaks after the holder has been
 * quiet for {@link #FLOOR_HOLD_MS}. Every streaming phone is told who holds it with {@code
 * {"t":"floor","name":"<phone>","you":true|false,"phones":2}}.
 *
 * <p>Pro remote pages (Wi-Fi Mic Pro / Internet Mic Pro) also send {@code
 * {"t":"pro","on":true,"watch":1500,"cam":true,"fps":4}} and pings with an {@code id} (echoed in
 * the pong, so they can show the link delay). Pro remotes then get {@code {"t":"heard"|"said",
 * "text":..}} (what Xiaozhi heard and answered), {@code {"t":"status",..}} every 5 s (phone and
 * robot battery), {@code {"t":"cam","b":<jpeg>,..}} while the camera is on, and auto-stop: a
 * Pro remote that is silent longer than its {@code watch} ms while the robot moves stops it
 * (told with {@code {"t":"safety","why":"link"}} when it comes back).
 *
 * <p>Answered by {@code {"t":"hello","name":"<host>","proto":2}} on connect, {@code
 * {"t":"auth","ok":true|false}} to the remote's hello and {@code {"t":"pong"}} to pings.
 *
 * <p>PIN: the host shows a 4-digit PIN on its voice control page. A remote must send it in its
 * hello before any move, stop or microphone message is accepted; a wrong PIN closes the
 * connection, and after {@link #MAX_PIN_FAILURES} wrong PINs every attempt is refused for
 * {@link #PIN_LOCKOUT_MS}. So a stranger on the same WiFi cannot drive the robot or speak into
 * the host's Xiaozhi session. Discovered as {@code _friendbot-ctrl._tcp.} over NSD/Bonjour on a
 * dynamic port ({@link VoiceRelayClient} finds it).
 *
 * <p>Safety rules — the robot always fails safe:
 *
 * <ul>
 *   <li>every move is duration-bounded and resent every 250 ms, then auto-stops;
 *   <li>"stop" from anyone stops everyone and latches until the next move;
 *   <li>a remote that disconnects mid-move is cancelled at once;
 *   <li>while a host-side (voice page) move runs, it owns the wire — remote ticks wait;
 *   <li>all output goes through {@link Vehicle#setControl}, so the big STOP latch applies.
 * </ul>
 *
 * <p>Start it from the voice control page once a vehicle link exists ({@link #start}) and stop it
 * when the page goes away ({@link #stop}): the robot is shared only while that page is open.
 */
public final class VoiceRelayServer {

  public static final String SERVICE_TYPE = "_friendbot-ctrl._tcp.";

  /** Remote-microphone feed for the host voice page (registered while the page is alive). */
  public interface MicSink {
    /** A remote phone's microphone started/stopped feeding the page's session. */
    void onRemoteMic(boolean active, String phoneName);

    /** One 60 ms opus frame from that microphone (base64 already decoded). */
    void onRemoteMicFrame(byte[] opus, float rms);

    /** True while the page can pass the microphone on (its Xiaozhi session is connected). */
    default boolean isMicReady() {
      return true;
    }
  }

  private static final long DRIVE_TICK_MS = 250;

  /** A frame at or above this level (0..1) counts as speech for the floor hand-over. */
  static final float FLOOR_SPEECH_RMS = 0.03f;
  /** The floor holder keeps it until it has been quiet this long. */
  static final long FLOOR_HOLD_MS = 1500;
  private static final long MAX_LINE_CHARS = 4096;

  private static final String PREFS_NAME = "voice_relay_host";
  private static final String PREF_PIN = "pin";
  static final int MAX_PIN_FAILURES = 5;
  static final long PIN_LOCKOUT_MS = 5 * 60 * 1000;

  private static final VoiceRelayServer INSTANCE = new VoiceRelayServer();

  public static VoiceRelayServer get() {
    return INSTANCE;
  }

  private final Handler main = new Handler(Looper.getMainLooper());
  private final List<Client> clients = new ArrayList<>();
  private final Runnable driveTick = this::tick;

  private volatile ServerSocket serverSocket;
  /** Answers Remote Mic phones' WiFi broadcast probes (see {@link WifiNet}). */
  private volatile DatagramSocket discoverySocket;
  private WifiManager.MulticastLock multicastLock;
  private Context appContext;
  private NsdManager nsdManager;
  private NsdManager.RegistrationListener registrationListener;
  private volatile Vehicle vehicle;

  // PIN check; touched on the main thread only.
  private String pin = "";
  private int pinFailures;
  private long pinLockedUntilMs;

  // Remote move state; touched on the main thread only.
  private float remoteLeft;
  private float remoteRight;
  private long remoteEndsAtMs;
  private Client remoteOwner;
  private long localEndsAtMs;
  private boolean stopLatched;

  // Remote microphone state; touched on the main thread only.
  private MicSink micSink;
  /** Every phone whose Remote Mic page is streaming, in the order they opened. */
  private final List<Client> micClients = new ArrayList<>();
  /** The streaming phone whose frames reach the session (null when nobody streams). */
  private Client floor;
  /** When the floor holder last sent a speech-level frame. */
  private long floorVoiceAtMs;

  // Pro remote pages; touched on the main thread only (see the class comment).
  private ProSource proSource;
  private final Runnable proTick = this::proTick;
  private boolean proTicking;
  private long lastStatusAtMs;
  private long lastCamAtMs;
  /** JPEG encoding off the main thread; one frame at a time. */
  private final AtomicBoolean camBusy = new AtomicBoolean();
  private final ExecutorService camEncoder =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "voice-relay-cam");
            t.setDaemon(true);
            return t;
          });

  static final long PRO_TICK_MS = 100;
  static final long STATUS_EVERY_MS = 5000;
  static final int CAM_WIDTH = 320;
  static final int CAM_QUALITY = 50;
  /** Skip camera frames for a remote whose unsent data is above this (slow 4G uplink). */
  static final long CAM_BACKLOG_LIMIT = 96_000;

  private VoiceRelayServer() {}

  /** Starts serving (idempotent). The vehicle reference may be swapped in on later calls. */
  public synchronized void start(Context appContext, Vehicle vehicle) {
    this.vehicle = vehicle;
    if (serverSocket != null) return;
    this.appContext = appContext.getApplicationContext();
    pin = loadOrCreatePin(appContext);
    // A fixed port when it is free, so a Remote Mic phone can also connect by typed IP.
    try {
      ServerSocket fixed = new ServerSocket();
      fixed.setReuseAddress(true);
      fixed.bind(new InetSocketAddress(WifiNet.RELAY_PORT));
      serverSocket = fixed;
    } catch (IOException busy) {
      try {
        serverSocket = new ServerSocket(0);
      } catch (IOException e) {
        Timber.e(e, "Voice relay server could not open a socket");
        return;
      }
    }
    startDiscoveryResponder();
    Thread acceptThread = new Thread(this::acceptLoop, "voice-relay-accept");
    acceptThread.setDaemon(true);
    acceptThread.start();

    NsdServiceInfo info = new NsdServiceInfo();
    info.setServiceName(Build.MODEL);
    info.setServiceType(SERVICE_TYPE);
    info.setPort(serverSocket.getLocalPort());
    nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
    if (nsdManager != null) {
      try {
        // A fresh listener each time: NSD refuses to reuse one that is still unregistering.
        registrationListener = newRegistrationListener();
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener);
      } catch (IllegalArgumentException e) {
        Timber.e(e, "Voice relay NSD registration failed");
        registrationListener = null;
      }
    }
    Timber.i("Voice relay serving on port %d", serverSocket.getLocalPort());
  }

  /**
   * Stops sharing (idempotent): un-advertises, closes every remote and stops any remote move.
   * Called when the host voice control page goes away.
   */
  public synchronized void stop() {
    if (serverSocket == null) return;
    if (nsdManager != null && registrationListener != null) {
      try {
        nsdManager.unregisterService(registrationListener);
      } catch (IllegalArgumentException ignored) {
        // Never registered / already gone.
      }
    }
    registrationListener = null;
    try {
      serverSocket.close();
    } catch (IOException ignored) {
      // Already closed.
    }
    serverSocket = null;
    stopDiscoveryResponder();
    main.post(
        () -> {
          main.removeCallbacks(driveTick);
          boolean remoteWasDriving = remoteOwner != null;
          remoteOwner = null;
          remoteEndsAtMs = 0;
          micClients.clear();
          floor = null;
          for (Client client : new ArrayList<>(clients)) {
            client.close(); // its reader thread then reports clientDropped
          }
          Vehicle v = vehicle;
          if (remoteWasDriving && v != null) {
            v.setControl(0f, 0f);
          }
        });
    Timber.i("Voice relay stopped");
  }

  /** The TCP port remotes connect to (47821 unless it was busy), or 0 when not serving. */
  public int port() {
    ServerSocket ss = serverSocket;
    return ss == null ? 0 : ss.getLocalPort();
  }

  // --------------------------------------------------------------- WiFi discovery

  /**
   * Listens on UDP {@link WifiNet#DISCOVERY_PORT} for Remote Mic probes and answers each with
   * this phone's name, relay port and WiFi name. Works on networks where NSD/Bonjour is
   * unreliable, as long as both phones are on the same WiFi.
   */
  private void startDiscoveryResponder() {
    DatagramSocket ds;
    try {
      ds = new DatagramSocket(null);
      ds.setReuseAddress(true);
      ds.setBroadcast(true);
      ds.bind(new InetSocketAddress(WifiNet.DISCOVERY_PORT));
    } catch (IOException e) {
      Timber.w("Voice relay discovery port busy: %s", e.getMessage());
      return;
    }
    discoverySocket = ds;
    try {
      WifiManager wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
      if (wm != null) {
        // Some phones drop broadcast packets to apps without this lock.
        multicastLock = wm.createMulticastLock("friendbot-relay");
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();
      }
    } catch (RuntimeException e) {
      multicastLock = null;
    }
    Thread t = new Thread(() -> discoveryLoop(ds), "voice-relay-discovery");
    t.setDaemon(true);
    t.start();
  }

  private void stopDiscoveryResponder() {
    DatagramSocket ds = discoverySocket;
    discoverySocket = null;
    if (ds != null) ds.close();
    if (multicastLock != null) {
      try {
        multicastLock.release();
      } catch (RuntimeException ignored) {
        // Already released.
      }
      multicastLock = null;
    }
  }

  private void discoveryLoop(DatagramSocket ds) {
    byte[] buf = new byte[256];
    while (!ds.isClosed()) {
      DatagramPacket packet = new DatagramPacket(buf, buf.length);
      try {
        ds.receive(packet);
      } catch (IOException e) {
        return; // closed by stop()
      }
      String text =
          new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
      if (!text.startsWith(WifiNet.PROBE)) continue;
      int port = port();
      if (port == 0) return;
      JsonObject reply = new JsonObject();
      reply.addProperty("t", "here");
      reply.addProperty("name", Build.MODEL);
      reply.addProperty("port", port);
      String wifi = WifiNet.wifiName(appContext);
      reply.addProperty("wifi", wifi == null ? "" : wifi);
      byte[] out = reply.toString().getBytes(StandardCharsets.UTF_8);
      try {
        ds.send(new DatagramPacket(out, out.length, packet.getAddress(), packet.getPort()));
      } catch (IOException e) {
        Timber.w("Voice relay discovery reply failed: %s", e.getMessage());
      }
    }
  }

  /** The 4-digit PIN remotes must send; shown on the host voice control page. */
  public String pin() {
    return pin;
  }

  /**
   * The same PIN, readable before the server has ever started (the connection screens show
   * it so the user always knows what remotes must type).
   */
  public static String pinFor(Context appContext) {
    return loadOrCreatePin(appContext);
  }

  /** One PIN per phone, kept across launches so remotes do not have to retype it. */
  private static String loadOrCreatePin(Context context) {
    SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    String saved = prefs.getString(PREF_PIN, "");
    if (saved != null && saved.matches("\\d{4}")) return saved;
    String fresh = String.format(Locale.US, "%04d", new SecureRandom().nextInt(10000));
    prefs.edit().putString(PREF_PIN, fresh).apply();
    return fresh;
  }

  public boolean isRunning() {
    return serverSocket != null;
  }

  /** True while a remote (or the host page) has halted the robot and no move has resumed it. */
  public boolean isStopLatched() {
    return stopLatched;
  }

  /** The host voice page started a duration-bounded move: it owns the wire until it ends. */
  public void noteLocalMove(long endsAtMs) {
    stopLatched = false;
    localEndsAtMs = endsAtMs;
  }

  /** The host voice page stopped: cancel every remote move too (STOP is global). */
  public void noteLocalStop() {
    stopLatched = true;
    remoteEndsAtMs = 0;
    remoteOwner = null;
  }

  /**
   * The host voice page registers here to receive a remote phone's microphone (Remote Mic
   * page on the other end). Pass null when the page goes away. A remote that is already
   * streaming is announced to the fresh sink at once.
   */
  public void setMicSink(MicSink sink) {
    micSink = sink;
    if (!micClients.isEmpty()) {
      if (sink != null && floor != null) {
        sink.onRemoteMic(true, floor.name);
      }
    }
    refreshMicAcks();
  }

  /** True when a streaming remote is actually heard: the page is open and Xiaozhi is up. */
  private boolean micLive() {
    MicSink sink = micSink;
    return sink != null && sink.isMicReady();
  }

  /**
   * Re-tells every streaming remote whether it is heard. The host page calls this when its
   * Xiaozhi session connects or drops, so the Remote Mic page never shows "Live" while its
   * voice goes nowhere.
   */
  public void refreshMicAcks() {
    String ack = "{\"t\":\"micack\",\"live\":" + micLive() + "}";
    for (Client c : micClients) {
      c.send(ack);
    }
  }

  // ------------------------------------------------------------------ accept

  private static NsdManager.RegistrationListener newRegistrationListener() {
    return new NsdManager.RegistrationListener() {
        @Override
        public void onServiceRegistered(NsdServiceInfo serviceInfo) {
          Timber.i("Voice relay advertised as '%s'", serviceInfo.getServiceName());
        }

        @Override
        public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
          Timber.w("Voice relay NSD registration failed: %d", errorCode);
        }

        @Override
        public void onServiceUnregistered(NsdServiceInfo serviceInfo) {}

        @Override
        public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
      };
  }

  private void acceptLoop() {
    ServerSocket listening = serverSocket;
    while (listening != null && !listening.isClosed()) {
      try {
        Socket socket = listening.accept();
        socket.setTcpNoDelay(true);
        Thread reader = new Thread(() -> handleClient(socket), "voice-relay-client");
        reader.setDaemon(true);
        reader.start();
      } catch (IOException e) {
        if (!listening.isClosed()) {
          Timber.w("Voice relay accept failed: %s", e.getMessage());
        }
        return;
      }
    }
  }

  // ------------------------------------------------------------------ Pro remotes

  /** What the voice page offers Pro remote pages. Main thread. */
  public interface ProSource {
    /** What the robot camera sees right now, or null (no camera / no permission). */
    Bitmap cameraFrame();
  }

  /** The voice page registers its camera here while it is open; null when it goes away. */
  public void setProSource(ProSource source) {
    main.post(() -> proSource = source);
  }

  /**
   * Tells Pro remotes what Xiaozhi heard ({@code "heard"}) or answered ({@code "said"}). Any
   * thread; old remote pages never get these lines.
   */
  public void sendProText(String type, String text) {
    if (text == null || text.isEmpty()) return;
    JsonObject m = new JsonObject();
    m.addProperty("t", type);
    m.addProperty("text", text.length() > 400 ? text.substring(0, 400) : text);
    String line = m.toString();
    main.post(
        () -> {
          for (Client c : clients) {
            if (c.authorized && c.pro) c.send(line);
          }
        });
  }

  /** {@code {"t":"pro","on":..,"watch":ms,"cam":..,"fps":..}}: a Pro page's wishes. */
  private void handlePro(Client client, JsonObject root) {
    if (root.has("on")) client.pro = root.get("on").getAsBoolean();
    if (root.has("watch")) {
      long ms = root.get("watch").getAsLong();
      client.watchMs = ms <= 0 ? 0 : Math.max(800, Math.min(6000, ms));
    }
    if (root.has("cam")) client.camOn = root.get("cam").getAsBoolean();
    if (root.has("fps")) client.camFps = Math.max(1, Math.min(8, root.get("fps").getAsInt()));
    if (!client.pro) {
      client.camOn = false;
      client.watchMs = 0;
      return;
    }
    client.send(statusLine(SystemClock.uptimeMillis()));
    if (!proTicking) {
      proTicking = true;
      main.post(proTick);
    }
  }

  /** Every 100 ms while a Pro remote is here: watchdog, status, camera. */
  private void proTick() {
    proTicking = false;
    long now = SystemClock.uptimeMillis();
    boolean anyPro = false;
    boolean anyCam = false;
    for (Client c : new ArrayList<>(clients)) {
      if (!c.authorized || !c.pro) continue;
      anyPro = true;
      if (c.camOn) anyCam = true;
      if (c.watchMs > 0 && !c.watchTripped && now - c.lastSeenMs > c.watchMs) {
        c.watchTripped = true;
        if (robotMoving(now)) {
          c.watchStopped = true;
          Timber.w("Voice relay: '%s' went silent while driving: STOP", c.name);
          handleStop();
        }
      }
    }
    if (!anyPro) return;
    if (now - lastStatusAtMs >= STATUS_EVERY_MS) {
      lastStatusAtMs = now;
      String status = statusLine(now);
      for (Client c : clients) {
        if (c.authorized && c.pro) c.send(status);
      }
    }
    if (anyCam) sendCamera(now);
    proTicking = true;
    main.postDelayed(proTick, PRO_TICK_MS);
  }

  private boolean robotMoving(long now) {
    return (remoteOwner != null && now < remoteEndsAtMs) || now < localEndsAtMs;
  }

  /** {@code {"t":"status","bat":72,"chg":false,"rv":11.8,"rp":80,"moving":false}}. */
  private String statusLine(long now) {
    JsonObject s = new JsonObject();
    s.addProperty("t", "status");
    Context ctx = appContext;
    if (ctx != null) {
      try {
        Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
          int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
          int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
          int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
          if (level >= 0 && scale > 0) s.addProperty("bat", Math.round(level * 100f / scale));
          s.addProperty(
              "chg",
              st == BatteryManager.BATTERY_STATUS_CHARGING
                  || st == BatteryManager.BATTERY_STATUS_FULL);
        }
      } catch (RuntimeException ignored) {
        // No battery info on this device.
      }
    }
    Vehicle v = vehicle;
    if (v != null && v.getBatteryVoltage() > 0.5f) {
      s.addProperty("rv", Math.round(v.getBatteryVoltage() * 10f) / 10f);
      s.addProperty("rp", Math.max(0, Math.min(100, v.getBatteryPercentage())));
    }
    s.addProperty("moving", robotMoving(now));
    return s.toString();
  }

  /** One camera picture to every Pro remote that wants one now (main thread). */
  private void sendCamera(long now) {
    ProSource src = proSource;
    if (src == null || camBusy.get()) return;
    List<Client> targets = new ArrayList<>();
    for (Client c : clients) {
      if (!c.authorized || !c.pro || !c.camOn) continue;
      if (now - c.lastCamSentMs < 1000 / c.camFps) continue;
      if (c.backlog() > CAM_BACKLOG_LIMIT) continue; // its link is behind: skip a frame
      targets.add(c);
    }
    if (targets.isEmpty()) return;
    Bitmap frame;
    try {
      frame = src.cameraFrame();
    } catch (RuntimeException e) {
      frame = null;
    }
    if (frame == null || frame.getWidth() <= 0) return;
    for (Client c : targets) c.lastCamSentMs = now;
    lastCamAtMs = now;
    camBusy.set(true);
    final Bitmap picture = frame;
    try {
      camEncoder.execute(
          () -> {
            try {
              int w = Math.min(CAM_WIDTH, picture.getWidth());
              int h = Math.max(1, Math.round(picture.getHeight() * (float) w / picture.getWidth()));
              Bitmap small =
                  w == picture.getWidth() ? picture : Bitmap.createScaledBitmap(picture, w, h, true);
              ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
              small.compress(Bitmap.CompressFormat.JPEG, CAM_QUALITY, jpeg);
              String line =
                  "{\"t\":\"cam\",\"w\":"
                      + w
                      + ",\"h\":"
                      + h
                      + ",\"b\":\""
                      + Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP)
                      + "\"}";
              for (Client c : targets) c.send(line);
            } catch (RuntimeException e) {
              Timber.w("Voice relay camera frame failed: %s", e.getMessage());
            } finally {
              camBusy.set(false);
            }
          });
    } catch (java.util.concurrent.RejectedExecutionException e) {
      camBusy.set(false);
    }
  }

  // ------------------------------------------------------------ internet remotes

  /** How lines reach a remote that is not a socket (the internet relay). Any thread. */
  public interface RemoteSender {
    void send(String line);

    /** Bytes still waiting to go out (camera frames are skipped while this is large). */
    default long backlog() {
      return 0;
    }

    /** The server dropped this remote (left, stopped, replaced). */
    void close();
  }

  /** A remote attached through the internet relay: feed it lines, drop it when it goes. */
  public final class RemoteHandle {
    private final Client client;

    private RemoteHandle(Client client) {
      this.client = client;
    }

    /** One line from the remote, exactly like a socket line. Any thread. */
    public void deliver(String json) {
      if (json == null || json.length() > MAX_LINE_CHARS) return;
      main.post(() -> handleLine(client, json));
    }

    /** The remote went away (silence, new code): stop its move and mic like a socket drop. */
    public void drop() {
      main.post(() -> clientDropped(client));
    }
  }

  /**
   * Attaches a remote phone reached over the internet (Internet Mic) as if it had connected by
   * WiFi: same hello, same PIN-free join, same mic floor and STOP rules.
   */
  public RemoteHandle attachRemote(RemoteSender sender) {
    Client client = new Client(sender);
    main.post(() -> clients.add(client));
    JsonObject hello = new JsonObject();
    hello.addProperty("t", "hello");
    hello.addProperty("name", Build.MODEL);
    hello.addProperty("proto", 2);
    client.send(hello.toString());
    return new RemoteHandle(client);
  }

  /** One remote connection: read JSON lines until the socket dies, then cancel its move. */
  private void handleClient(Socket socket) {
    Client client = new Client(socket);
    main.post(() -> clients.add(client));
    try (BufferedReader in =
        new BufferedReader(
            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
      JsonObject hello = new JsonObject();
      hello.addProperty("t", "hello");
      hello.addProperty("name", Build.MODEL);
      hello.addProperty("proto", 2);
      client.send(hello.toString());
      String line;
      while ((line = in.readLine()) != null && line.length() <= MAX_LINE_CHARS) {
        final String json = line;
        main.post(() -> handleLine(client, json));
      }
    } catch (IOException e) {
      // Disconnects are normal (remote closed the app / left the network).
    } finally {
      main.post(() -> clientDropped(client));
    }
  }

  private void handleLine(Client client, String json) {
    try {
      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      String type = root.has("t") ? root.get("t").getAsString() : "";
      if (!client.authorized && !"hello".equals(type) && !"ping".equals(type)) {
        return; // nothing but hello/ping until the remote has sent the right PIN
      }
      client.lastSeenMs = SystemClock.uptimeMillis();
      if (client.watchTripped) {
        client.watchTripped = false;
        if (client.watchStopped) {
          client.watchStopped = false;
          client.send("{\"t\":\"safety\",\"why\":\"link\"}");
        }
      }
      switch (type) {
        case "move":
          float l = (float) root.get("l").getAsDouble();
          float r = (float) root.get("r").getAsDouble();
          int ms = root.get("ms").getAsInt();
          handleMove(client, clampUnit(l), clampUnit(r), clampUnitMs(ms));
          break;
        case "stop":
          handleStop();
          break;
        case "ping":
          if (root.has("id")) {
            client.send("{\"t\":\"pong\",\"id\":" + root.get("id").getAsLong() + "}");
          } else {
            client.send("{\"t\":\"pong\"}");
          }
          break;
        case "pro":
          handlePro(client, root);
          break;
        case "hello":
          client.name = root.has("name") ? root.get("name").getAsString() : "";
          checkPin(client, root.has("pin") ? root.get("pin").getAsString() : "");
          break;
        case "mic":
          handleMic(client, root);
          break;
        default:
          break;
      }
    } catch (Exception e) {
      Timber.w("Voice relay dropped a bad message: %s", e.getMessage());
    }
  }

  /**
   * Main thread: accept the remote. No PIN is needed any more — a phone joins by scanning the
   * QR (IP address) on the Voice Control page, or by WiFi discovery. The "pin" a remote may
   * still send is ignored, so older Remote Mic apps keep working.
   */
  private void checkPin(Client client, String given) {
    if (client.authorized) return;
    client.authorized = true;
    client.send("{\"t\":\"auth\",\"ok\":true}");
    Timber.i("Voice relay: '%s' joined", client.name);
  }

  /**
   * A remote phone's microphone (Remote Mic page): {@code on:true/false} opens and closes the
   * stream, {@code b} carries one base64 60 ms opus frame with its level {@code r}. The host
   * voice page's {@link MicSink} receives it; when no page is open the stream is refused
   * ({@code {"t":"micack","live":false}}) so the remote can say so.
   *
   * <p>Several phones can stream together; only the floor holder's frames are forwarded (see
   * the class comment). The sink sees one remote microphone: "active" when the first phone
   * opens, "stopped" when the last one closes.
   */
  private void handleMic(Client client, JsonObject root) {
    if (root.has("on")) {
      boolean on = root.get("on").getAsBoolean();
      MicSink sink = micSink;
      if (on) {
        boolean first = micClients.isEmpty();
        if (!micClients.contains(client)) {
          micClients.add(client);
        }
        if (floor == null) {
          floor = client;
          floorVoiceAtMs = SystemClock.uptimeMillis();
        }
        if (first && sink != null) {
          sink.onRemoteMic(true, client.name);
        }
        client.send("{\"t\":\"micack\",\"live\":" + micLive() + "}");
        announceFloor();
      } else if (micClients.remove(client)) {
        micReleased(client);
      }
      return;
    }
    if (!root.has("b") || micSink == null || !micClients.contains(client)) return;
    float rms = root.has("r") ? (float) root.get("r").getAsDouble() : 0f;
    long now = SystemClock.uptimeMillis();
    if (client != floor) {
      // Another phone may take the floor only by speaking while the holder is quiet.
      boolean holderQuiet = floor == null || now - floorVoiceAtMs >= FLOOR_HOLD_MS;
      if (rms < FLOOR_SPEECH_RMS || !holderQuiet) return;
      floor = client;
      announceFloor();
      Timber.i("Voice relay: '%s' took the microphone floor", client.name);
    }
    if (rms >= FLOOR_SPEECH_RMS) {
      floorVoiceAtMs = now;
    }
    byte[] opus;
    try {
      opus = Base64.decode(root.get("b").getAsString(), Base64.NO_WRAP);
    } catch (IllegalArgumentException e) {
      return;
    }
    micSink.onRemoteMicFrame(opus, rms);
  }

  /** A phone stopped streaming (or left): pass the floor on, or end the remote mic. */
  private void micReleased(Client client) {
    if (floor == client) {
      floor = micClients.isEmpty() ? null : micClients.get(0);
      floorVoiceAtMs = 0; // whoever speaks next may take it at once
    }
    if (micClients.isEmpty()) {
      if (micSink != null) {
        micSink.onRemoteMic(false, client.name);
      }
    } else {
      announceFloor();
    }
  }

  /** Tells every streaming phone who holds the floor and how many phones share the mic. */
  private void announceFloor() {
    if (floor == null) return;
    for (Client c : micClients) {
      JsonObject msg = new JsonObject();
      msg.addProperty("t", "floor");
      msg.addProperty("name", floor.name);
      msg.addProperty("you", c == floor);
      msg.addProperty("phones", micClients.size());
      c.send(msg.toString());
    }
  }

  private void handleMove(Client client, float l, float r, int ms) {
    stopLatched = false;
    remoteLeft = l;
    remoteRight = r;
    remoteEndsAtMs = SystemClock.uptimeMillis() + ms;
    remoteOwner = client;
    main.removeCallbacks(driveTick);
    // Drive now, then every 250 ms. (Only re-posting the tick would let a remote that resends
    // its move faster than 250 ms — a held drive-pad arrow — push the tick back forever.)
    tick();
  }

  private void handleStop() {
    stopLatched = true;
    remoteEndsAtMs = 0;
    remoteOwner = null;
    main.removeCallbacks(driveTick);
    Vehicle v = vehicle;
    if (v != null) {
      v.setControl(0f, 0f);
    }
  }

  private void clientDropped(Client client) {
    clients.remove(client);
    client.close(); // frees its writer thread
    if (remoteOwner == client) {
      remoteEndsAtMs = 0;
      remoteOwner = null;
      Vehicle v = vehicle;
      if (v != null) {
        v.setControl(0f, 0f);
      }
    }
    if (micClients.remove(client)) {
      micReleased(client);
    }
    Timber.i("Voice relay remote left (%d connected)", clients.size());
  }

  /** 250 ms resend tick for the active remote move (main thread). */
  private void tick() {
    if (remoteOwner == null || SystemClock.uptimeMillis() >= remoteEndsAtMs) {
      remoteOwner = null;
      Vehicle v = vehicle;
      // Stop the remote's move, but never cut into a move the host page is running.
      if (v != null && SystemClock.uptimeMillis() >= localEndsAtMs) {
        v.setControl(0f, 0f);
      }
      return;
    }
    if (SystemClock.uptimeMillis() >= localEndsAtMs) {
      // The host page is idle: the remote owns the wire.
      Vehicle v = vehicle;
      if (v != null) {
        v.setControl(remoteLeft, remoteRight);
      }
    }
    // Re-post the same Runnable: handleMove/handleStop cancel it by identity, so a fresh
    // method reference here would leave extra tick loops running.
    main.postDelayed(driveTick, DRIVE_TICK_MS);
  }

  private static float clampUnit(float v) {
    return Math.max(-1f, Math.min(1f, v));
  }

  private static int clampUnitMs(int ms) {
    return Math.max(300, Math.min(15000, ms));
  }

  /** One connected remote. Writes are serialized per socket. */
  private static final class Client {
    private final Socket socket;
    /** Set for a remote reached through the internet relay instead of a socket. */
    private final RemoteSender external;
    private BufferedWriter out;
    /** The remote phone's name (its hello); empty until it greets. */
    volatile String name = "";
    /** Sent the right PIN in its hello (main thread only). */
    boolean authorized;

    // Pro remote page wishes and watchdog (main thread only).
    boolean pro;
    boolean camOn;
    int camFps = 3;
    long watchMs;
    long lastSeenMs = SystemClock.uptimeMillis();
    boolean watchTripped;
    boolean watchStopped;
    long lastCamSentMs;
    /** Characters queued for the socket writer but not written yet. */
    private final AtomicLong queued = new AtomicLong();

    long backlog() {
      return external != null ? external.backlog() : queued.get();
    }
    /**
     * Writes run here, never on the caller's thread: most sends come from the main thread,
     * where Android forbids network I/O (NetworkOnMainThreadException) — the PIN answer and
     * mic acks would otherwise be lost and the remote would wait forever.
     */
    private final ExecutorService writer =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "voice-relay-send");
              t.setDaemon(true);
              return t;
            });

    Client(RemoteSender external) {
      this.socket = null;
      this.external = external;
      this.out = null;
    }

    Client(Socket socket) {
      this.socket = socket;
      this.external = null;
      try {
        out =
            new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
      } catch (IOException e) {
        out = null;
      }
    }

    void send(String json) {
      if (external != null) {
        external.send(json);
        return;
      }
      if (out == null) return;
      queued.addAndGet(json.length());
      try {
        writer.execute(
            () -> {
              sendNow(json);
              queued.addAndGet(-json.length());
            });
      } catch (java.util.concurrent.RejectedExecutionException closed) {
        queued.addAndGet(-json.length()); // Client already closed.
      }
    }

    private synchronized void sendNow(String json) {
      try {
        out.write(json);
        out.write('\n');
        out.flush();
      } catch (IOException e) {
        Timber.w("Voice relay send failed: %s", e.getMessage());
      }
    }

    void close() {
      if (external != null) {
        writer.shutdown();
        external.close();
        return;
      }
      // Let queued replies (e.g. a wrong-PIN answer) go out first, then close.
      try {
        writer.execute(
            () -> {
              try {
                socket.close();
              } catch (IOException ignored) {
                // Already gone.
              }
            });
        writer.shutdown();
      } catch (java.util.concurrent.RejectedExecutionException alreadyClosed) {
        try {
          socket.close();
        } catch (IOException ignored) {
          // Already gone.
        }
      }
    }
  }
}
