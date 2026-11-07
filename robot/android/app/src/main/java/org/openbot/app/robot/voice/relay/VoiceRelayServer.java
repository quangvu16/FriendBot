package org.openbot.app.robot.voice.relay;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
  }

  private static final long DRIVE_TICK_MS = 250;
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

  private ServerSocket serverSocket;
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
  private Client micOwner;

  private VoiceRelayServer() {}

  /** Starts serving (idempotent). The vehicle reference may be swapped in on later calls. */
  public synchronized void start(Context appContext, Vehicle vehicle) {
    this.vehicle = vehicle;
    if (serverSocket != null) return;
    pin = loadOrCreatePin(appContext);
    try {
      serverSocket = new ServerSocket(0);
    } catch (IOException e) {
      Timber.e(e, "Voice relay server could not open a socket");
      return;
    }
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
    main.post(
        () -> {
          main.removeCallbacks(driveTick);
          boolean remoteWasDriving = remoteOwner != null;
          remoteOwner = null;
          remoteEndsAtMs = 0;
          micOwner = null;
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

  /** The 4-digit PIN remotes must send; shown on the host voice control page. */
  public String pin() {
    return pin;
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
    if (micOwner != null) {
      if (sink != null) {
        sink.onRemoteMic(true, micOwner.name);
      }
      // Keep the streaming remote's liveness in step with the page.
      micOwner.send("{\"t\":\"micack\",\"live\":" + (sink != null) + "}");
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
          client.send("{\"t\":\"pong\"}");
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

  /** Main thread: accept or refuse a remote's PIN, with a lockout against guessing. */
  private void checkPin(Client client, String given) {
    if (client.authorized) return;
    long now = SystemClock.uptimeMillis();
    boolean locked = now < pinLockedUntilMs;
    if (!locked && !pin.isEmpty() && pin.equals(given)) {
      client.authorized = true;
      pinFailures = 0;
      client.send("{\"t\":\"auth\",\"ok\":true}");
      Timber.i("Voice relay: '%s' joined with the PIN", client.name);
      return;
    }
    if (!locked && ++pinFailures >= MAX_PIN_FAILURES) {
      pinFailures = 0;
      pinLockedUntilMs = now + PIN_LOCKOUT_MS;
      Timber.w("Voice relay: too many wrong PINs, refusing remotes for 5 minutes");
    }
    client.send("{\"t\":\"auth\",\"ok\":false}");
    client.close();
  }

  /**
   * A remote phone's microphone (Remote Mic page): {@code on:true/false} opens and closes the
   * stream, {@code b} carries one base64 60 ms opus frame with its level {@code r}. The host
   * voice page's {@link MicSink} receives it; when no page is open the stream is refused
   * ({@code {"t":"micack","live":false}}) so the remote can say so.
   */
  private void handleMic(Client client, JsonObject root) {
    if (root.has("on")) {
      boolean on = root.get("on").getAsBoolean();
      MicSink sink = micSink;
      if (on) {
        if (micOwner != null && micOwner != client && sink != null) {
          sink.onRemoteMic(false, micOwner.name);
        }
        micOwner = client;
        if (sink != null) {
          sink.onRemoteMic(true, client.name);
        }
        client.send("{\"t\":\"micack\",\"live\":" + (sink != null) + "}");
      } else {
        if (micOwner == client) {
          micOwner = null;
          if (sink != null) {
            sink.onRemoteMic(false, client.name);
          }
        }
      }
      return;
    }
    if (root.has("b") && micOwner == client && micSink != null) {
      byte[] opus;
      try {
        opus = Base64.decode(root.get("b").getAsString(), Base64.NO_WRAP);
      } catch (IllegalArgumentException e) {
        return;
      }
      float rms = root.has("r") ? (float) root.get("r").getAsDouble() : 0f;
      micSink.onRemoteMicFrame(opus, rms);
    }
  }

  private void handleMove(Client client, float l, float r, int ms) {
    stopLatched = false;
    remoteLeft = l;
    remoteRight = r;
    remoteEndsAtMs = SystemClock.uptimeMillis() + ms;
    remoteOwner = client;
    main.removeCallbacks(driveTick);
    main.postDelayed(driveTick, DRIVE_TICK_MS);
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
    if (remoteOwner == client) {
      remoteEndsAtMs = 0;
      remoteOwner = null;
      Vehicle v = vehicle;
      if (v != null) {
        v.setControl(0f, 0f);
      }
    }
    if (micOwner == client) {
      micOwner = null;
      if (micSink != null) {
        micSink.onRemoteMic(false, client.name);
      }
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
    private BufferedWriter out;
    /** The remote phone's name (its hello); empty until it greets. */
    volatile String name = "";
    /** Sent the right PIN in its hello (main thread only). */
    boolean authorized;

    Client(Socket socket) {
      this.socket = socket;
      try {
        out =
            new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
      } catch (IOException e) {
        out = null;
      }
    }

    synchronized void send(String json) {
      if (out == null) return;
      try {
        out.write(json);
        out.write('\n');
        out.flush();
      } catch (IOException e) {
        Timber.w("Voice relay send failed: %s", e.getMessage());
      }
    }

    void close() {
      try {
        socket.close();
      } catch (IOException ignored) {
        // Already gone.
      }
    }
  }
}
