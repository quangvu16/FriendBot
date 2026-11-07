package org.openbot.app.robot.voice.relay;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import timber.log.Timber;

/**
 * WiFi voice-relay remote: finds the phones that share their robot link ({@link
 * VoiceRelayServer}, advertised as {@code _friendbot-ctrl._tcp.} over NSD) and forwards the
 * voice control page's drive commands to one of them. The remote phone keeps its own Xiaozhi
 * session — mic in, assistant voice out — and only the resulting motion travels over the LAN,
 * so any phone on the network can drive a shared robot from the voice control page.
 *
 * <p>The user chooses the drive target from the voice page's connection pill ({@link
 * #hostNames()} for the list): {@link #chooseLocal()} keeps this phone's own robot link, {@link
 * #chooseHost(String)} picks one shared robot, {@link #chooseAuto()} restores the default
 * (auto-connect to the first shared robot). The choice is remembered across launches.
 *
 * <p>Each host asks for the 4-digit PIN shown on its voice control page. The PIN is remembered
 * per host; when none is saved (or the host says it is wrong) {@link Listener#onPinRequired}
 * asks the page to prompt the user ({@link RelayPinPrompt}), and {@link #setPin} connects.
 *
 * <p>Reconnects with 1 s / 2 s / 5 s backoff when the chosen host disappears. All callbacks
 * arrive on the main thread.
 */
public final class VoiceRelayClient {

  private static final String PREFS_NAME = "voice_relay";
  private static final String PREF_CHOICE = "choice";
  /** The user picked this phone's own robot link; never auto-connect to a host. */
  private static final String CHOICE_LOCAL = "local";
  /** No pick yet: auto-connect to the first shared robot (only used without a local link). */
  private static final String CHOICE_AUTO = "";
  /** Saved PIN per host: {@code pin_<host name>}. */
  private static final String PREF_PIN_PREFIX = "pin_";

  private static final long MAX_LINE_CHARS = 4096;
  private static final long[] RECONNECT_DELAY_MS = {1000, 2000, 5000};

  private static final VoiceRelayClient INSTANCE = new VoiceRelayClient();

  public static VoiceRelayClient get() {
    return INSTANCE;
  }

  /** Host-side status, on the main thread. */
  public interface Listener {
    void onHostConnected(String hostName);

    void onHostLost();

    /** The set of shared robots on the LAN changed (feeds the picker). */
    void onHostsChanged();

    /**
     * The host answered a {@link #sendMicState(boolean)}: {@code live} says whether its voice
     * control page is open and hearing the microphone.
     */
    default void onMicAck(boolean live) {}

    /**
     * {@code hostName} needs its PIN before this phone may drive through it ({@code wrong}: the
     * saved/typed one was refused). Show {@link RelayPinPrompt}; it calls {@link #setPin}.
     */
    default void onPinRequired(String hostName, boolean wrong) {}
  }

  private final Handler main = new Handler(Looper.getMainLooper());
  private final List<NsdServiceInfo> found = new ArrayList<>();
  private final Runnable connectAttempt = this::maybeConnect;

  private NsdManager nsdManager;
  private NsdManager.DiscoveryListener discoveryListener;
  private SharedPreferences prefs;
  private Listener listener;
  private volatile Socket socket;
  private BufferedWriter out;
  private volatile boolean hostConnected;
  private volatile boolean connecting;
  private String hostName = "";
  private String choice = CHOICE_AUTO;
  private int reconnectAttempts;
  /** Hosts the user was already asked about; not asked again until they pick the host. */
  private final Set<String> pinAsked = Collections.synchronizedSet(new HashSet<>());

  private VoiceRelayClient() {}

  /** Starts browsing for shared robots (idempotent); the saved choice is applied on discovery. */
  public synchronized void start(Context appContext, Listener listener) {
    this.listener = listener;
    if (prefs == null) {
      prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
      choice = prefs.getString(PREF_CHOICE, CHOICE_AUTO);
    }
    if (discoveryListener == null) {
      nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
      if (nsdManager == null) return;
      discoveryListener = newDiscoveryListener();
      try {
        nsdManager.discoverServices(
            VoiceRelayServer.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
      } catch (IllegalArgumentException e) {
        Timber.e(e, "Voice relay discovery failed to start");
        discoveryListener = null;
        return;
      }
      Timber.i("Voice relay: searching for shared robots");
    }
    // Discovery is a singleton kept across pages: re-evaluate now instead of waiting for
    // the next onServiceFound, or a page re-entry would never reconnect or ask for the PIN.
    main.post(this::maybeConnect);
  }

  /**
   * Page entry: give the PIN prompt another chance and re-evaluate the connection. Automatic
   * asks fire once per host per session; an explicit entry may ask again. Main thread.
   */
  public void retryPending() {
    pinAsked.clear();
    main.post(this::maybeConnect);
  }

  /** True when the pick is this phone's own robot link (the voice page's mode). The Remote Mic
   * page always speaks over the relay and resets this on entry. */
  public boolean isLocalChoice() {
    return CHOICE_LOCAL.equals(choice);
  }

  public synchronized void stop() {
    if (nsdManager != null && discoveryListener != null) {
      try {
        nsdManager.stopServiceDiscovery(discoveryListener);
      } catch (IllegalArgumentException ignored) {
        // Already stopped.
      }
    }
    discoveryListener = null;
    closeSocket();
  }

  public boolean isSearching() {
    return discoveryListener != null && !hostConnected && !CHOICE_LOCAL.equals(choice);
  }

  public boolean isConnected() {
    return hostConnected;
  }

  public String hostName() {
    return hostName;
  }

  /** The current pick: a host name, {@code "local"} or {@code ""} (automatic). */
  public String currentChoice() {
    return choice;
  }

  /** Names of the shared robots discovered on the LAN (connected one first). */
  public List<String> hostNames() {
    List<String> names = new ArrayList<>();
    synchronized (found) {
      for (NsdServiceInfo info : found) {
        String name = info.getServiceName();
        if (!names.contains(name)) {
          names.add(name);
        }
      }
    }
    if (hostConnected && names.contains(hostName)) {
      names.remove(hostName);
      names.add(0, hostName);
    }
    return names;
  }

  /** This phone's own robot link is the target: never auto-connect to a shared robot. */
  public void chooseLocal() {
    setChoice(CHOICE_LOCAL);
    disconnect();
    maybeConnect();
  }

  /** Drive the robot shared by {@code name} (from {@link #hostNames()}). */
  public void chooseHost(String name) {
    pinAsked.remove(name); // an explicit pick may ask for the PIN again
    setChoice(name);
    if (hostConnected && !name.equals(hostName)) {
      disconnect();
    }
    maybeConnect();
  }

  /** Back to the default: auto-connect to the first shared robot. */
  public void chooseAuto() {
    setChoice(CHOICE_AUTO);
    maybeConnect();
  }

  /** This phone's own robot link just came up: auto mode defers to it. */
  public void onLocalLinkUp() {
    if (CHOICE_AUTO.equals(choice)) {
      chooseLocal();
    }
  }

  /** The user typed {@code pin} for {@code hostName}: remember it and connect. */
  public void setPin(String hostName, String pin) {
    if (prefs != null) {
      prefs.edit().putString(PREF_PIN_PREFIX + hostName, pin.trim()).apply();
    }
    pinAsked.remove(hostName);
    reconnectAttempts = 0;
    maybeConnect();
  }

  private String savedPin(String hostName) {
    return prefs == null ? "" : prefs.getString(PREF_PIN_PREFIX + hostName, "");
  }

  private void setChoice(String newChoice) {
    choice = newChoice;
    if (prefs != null) {
      prefs.edit().putString(PREF_CHOICE, newChoice).apply();
    }
  }

  /** Forwards a duration-bounded move to the host (it auto-stops when the duration ends). */
  public void sendMove(float left, float right, int durationMs) {
    JsonObject message = new JsonObject();
    message.addProperty("t", "move");
    message.addProperty("l", left);
    message.addProperty("r", right);
    message.addProperty("ms", durationMs);
    send(message);
  }

  public void sendStop() {
    JsonObject message = new JsonObject();
    message.addProperty("t", "stop");
    send(message);
  }

  /**
   * Streams one 60 ms opus frame of this phone's microphone to the host voice control page
   * (Remote Mic page); {@code rms} is the frame's level (0..1) for its waveform display.
   */
  public void sendMicFrame(String base64Opus, float rms) {
    JsonObject message = new JsonObject();
    message.addProperty("t", "mic");
    message.addProperty("b", base64Opus);
    message.addProperty("r", rms);
    send(message);
  }

  /** Opens/closes the microphone stream; the host answers with {@code onMicAck}. */
  public void sendMicState(boolean streaming) {
    JsonObject message = new JsonObject();
    message.addProperty("t", "mic");
    message.addProperty("on", streaming);
    send(message);
  }

  private void send(JsonObject message) {
    if (!hostConnected) return;
    write(message);
  }

  /** Writes one line on the open socket, connected (PIN accepted) or not. */
  private void write(JsonObject message) {
    BufferedWriter writer = out;
    if (writer == null) return;
    synchronized (this) {
      try {
        writer.write(message.toString());
        writer.write('\n');
        writer.flush();
      } catch (IOException e) {
        Timber.w("Voice relay send failed: %s", e.getMessage());
        main.post(this::connectionLost);
      }
    }
  }

  // ----------------------------------------------------------------- discovery

  private NsdManager.DiscoveryListener newDiscoveryListener() {
    return new NsdManager.DiscoveryListener() {
      @Override
      public void onDiscoveryStarted(String serviceType) {}

      @Override
      public void onServiceFound(NsdServiceInfo serviceInfo) {
        synchronized (found) {
          found.add(serviceInfo);
        }
        notifyHostsChanged();
        maybeConnect();
      }

      @Override
      public void onServiceLost(NsdServiceInfo serviceInfo) {
        synchronized (found) {
          found.removeIf(
              s ->
                  s.getServiceName().equals(serviceInfo.getServiceName())
                      && s.getServiceType().equals(serviceInfo.getServiceType()));
        }
        notifyHostsChanged();
      }

      @Override
      public void onDiscoveryStopped(String serviceType) {}

      @Override
      public void onStartDiscoveryFailed(String serviceType, int errorCode) {
        Timber.w("Voice relay discovery failed to start: %d", errorCode);
        synchronized (VoiceRelayClient.this) {
          discoveryListener = null;
        }
      }

      @Override
      public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
    };
  }

  /**
   * Connects to the chosen host, or — in auto mode — to the first shared robot found. Does
   * nothing while already connected or connecting, while the user picked this phone's own
   * link, or while nothing matches the pick yet.
   */
  private void maybeConnect() {
    if (hostConnected || connecting || CHOICE_LOCAL.equals(choice)) return;
    NsdServiceInfo target;
    synchronized (found) {
      target = null;
      for (NsdServiceInfo info : found) {
        if (choice.isEmpty() || info.getServiceName().equals(choice)) {
          target = info;
          break;
        }
      }
    }
    if (target == null) return;
    String targetName = target.getServiceName();
    if (savedPin(targetName).isEmpty()) {
      // No PIN for this host yet: ask the user once instead of connecting.
      if (pinAsked.add(targetName)) {
        main.post(
            () -> {
              if (listener != null) {
                listener.onPinRequired(targetName, false);
              }
            });
      }
      return;
    }
    connecting = true;
    nsdManager.resolveService(
        target,
        new NsdManager.ResolveListener() {
          @Override
          public void onServiceResolved(NsdServiceInfo serviceInfo) {
            if (isLocalAddress(serviceInfo.getHost())) {
              // Our own advertisement (the server on this phone): hide it and never
              // self-connect. (Two phones sharing the same model name would hide both.)
              synchronized (found) {
                found.removeIf(s -> s.getServiceName().equals(serviceInfo.getServiceName()));
              }
              connecting = false;
              notifyHostsChanged();
              main.post(connectAttempt);
              return;
            }
            connectTo(
                serviceInfo.getServiceName(),
                serviceInfo.getHost(),
                serviceInfo.getPort());
          }

          @Override
          public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
            connecting = false;
            main.post(VoiceRelayClient.this::scheduleRetry);
          }
        });
  }

  private void connectTo(String name, InetAddress host, int port) {
    if (host == null) {
      connecting = false;
      scheduleRetry();
      return;
    }
    Thread connector =
        new Thread(
            () -> {
              Socket s = new Socket();
              try {
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(host.getHostAddress(), port), 4000);
                BufferedReader in =
                    new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                synchronized (VoiceRelayClient.this) {
                  socket = s;
                  out =
                      new BufferedWriter(
                          new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                }
                JsonObject hello = new JsonObject();
                hello.addProperty("t", "hello");
                hello.addProperty("name", Build.MODEL);
                hello.addProperty("pin", savedPin(name));
                // Not connected yet: the host answers {"t":"auth","ok":...} (handleServerLine).
                write(hello);
                String line;
                while ((line = in.readLine()) != null && line.length() <= MAX_LINE_CHARS) {
                  handleServerLine(line, name);
                }
              } catch (IOException e) {
                // Host went away; fall through to the retry path.
              } finally {
                closeSocket();
                main.post(VoiceRelayClient.this::scheduleRetry);
              }
            },
            "voice-relay-connect");
    connector.setDaemon(true);
    connector.start();
  }

  private synchronized void scheduleRetry() {
    if (hostConnected || discoveryListener == null || CHOICE_LOCAL.equals(choice)) return;
    long delay = RECONNECT_DELAY_MS[Math.min(reconnectAttempts, RECONNECT_DELAY_MS.length - 1)];
    reconnectAttempts++;
    main.removeCallbacks(connectAttempt);
    main.postDelayed(connectAttempt, delay);
  }

  /** Host → remote line: the PIN answer and the microphone ack matter here. */
  private void handleServerLine(String line, String name) {
    try {
      JsonObject root = JsonParser.parseString(line).getAsJsonObject();
      String type = root.has("t") ? root.get("t").getAsString() : "";
      if ("auth".equals(type)) {
        boolean ok = root.has("ok") && root.get("ok").getAsBoolean();
        if (ok) {
          reconnectAttempts = 0;
          setHostState(true, name);
        } else {
          main.post(
              () -> {
                // Wrong PIN: forget it and ask; the host closes the socket.
                if (prefs != null) {
                  prefs.edit().remove(PREF_PIN_PREFIX + name).apply();
                }
                pinAsked.add(name);
                if (listener != null) {
                  listener.onPinRequired(name, true);
                }
              });
        }
      } else if ("micack".equals(type)) {
        boolean live = root.has("live") && root.get("live").getAsBoolean();
        main.post(
            () -> {
              if (listener != null) {
                listener.onMicAck(live);
              }
            });
      }
    } catch (Exception e) {
      // Pong/echo replies and anything malformed are dropped.
    }
  }

  private void connectionLost() {
    disconnect();
    scheduleRetry();
  }

  private synchronized void disconnect() {
    try {
      if (socket != null) socket.close();
    } catch (IOException ignored) {
      // Already gone.
    }
    socket = null;
    out = null;
    connecting = false;
    setHostState(false, "");
  }

  private void setHostState(boolean connected, String name) {
    main.post(
        () -> {
          // A disconnect may have raced this update: trust the live socket, not the request.
          boolean nowConnected = connected && socket != null;
          connecting = false;
          boolean wasConnected = hostConnected;
          hostConnected = nowConnected;
          hostName = nowConnected ? name : "";
          if (listener == null || wasConnected == nowConnected) return;
          if (nowConnected) {
            listener.onHostConnected(name);
          } else {
            listener.onHostLost();
          }
        });
  }

  private void notifyHostsChanged() {
    main.post(
        () -> {
          if (listener != null) {
            listener.onHostsChanged();
          }
        });
  }

  private void closeSocket() {
    disconnect();
  }

  /** True when the address belongs to this phone — i.e. the service is our own server. */
  private static boolean isLocalAddress(InetAddress address) {
    if (address == null || address.isLoopbackAddress()) return true;
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces != null && interfaces.hasMoreElements()) {
        Enumeration<InetAddress> addresses = interfaces.nextElement().getInetAddresses();
        while (addresses.hasMoreElements()) {
          if (addresses.nextElement().equals(address)) return true;
        }
      }
    } catch (SocketException e) {
      // Cannot enumerate interfaces: allow the connection rather than block all hosts.
    }
    return false;
  }
}
