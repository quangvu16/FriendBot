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
import java.net.Inet6Address;
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
import java.util.concurrent.atomic.AtomicInteger;
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
  /** Android's NSD resolve sometimes never calls back: give up on it after this long. */
  private static final long RESOLVE_TIMEOUT_MS = 6000;
  /** Per-address TCP connect timeout (IPv4 is tried first, then the other addresses). */
  private static final int CONNECT_TIMEOUT_MS = 3000;

  /** What the link is doing while not connected (shown on the Remote Mic page). */
  public enum Status {
    /** No shared robot (or not the picked one) found on the WiFi yet. */
    SEARCHING,
    /** Found {@link #statusHost()}: resolving its address / opening the socket. */
    CONNECTING,
    /** {@link #statusHost()} needs its PIN: pick it again to type it. */
    NEED_PIN,
    /** {@link #statusHost()} was found but could not be reached; retrying. */
    UNREACHABLE
  }

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
     * Several phones are streaming to the same host: {@code speaker} holds the microphone
     * floor ({@code you}: it is this phone), {@code phones} are streaming in all.
     */
    default void onMicFloor(String speaker, boolean you, int phones) {}

    /**
     * {@code hostName} needs its PIN before this phone may drive through it ({@code wrong}: the
     * saved/typed one was refused). Show {@link RelayPinPrompt}; it calls {@link #setPin}.
     */
    default void onPinRequired(String hostName, boolean wrong) {}
  }

  /** Sends lines in order, off the main thread. */
  private static final java.util.concurrent.ExecutorService SENDER =
      java.util.concurrent.Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "voice-relay-client-send");
            t.setDaemon(true);
            return t;
          });

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
  private volatile Status status = Status.SEARCHING;
  private volatile String statusHost = "";
  /**
   * Generation of the current connection attempt. A resolve answer or socket that belongs to an
   * older attempt (the user picked another phone, or the resolve timed out) is ignored.
   */
  private final AtomicInteger attempt = new AtomicInteger();
  /** Unsticks a resolve that never answered, so the page does not stay "searching" forever. */
  private final Runnable resolveWatchdog =
      () -> {
        if (!connecting || socket != null) return;
        Timber.w("Voice relay: resolving '%s' timed out", statusHost);
        attempt.incrementAndGet();
        connecting = false;
        setStatus(Status.UNREACHABLE, statusHost);
        scheduleRetry();
      };
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

  /** What the link is doing while not connected; see {@link Status}. */
  public Status status() {
    return status;
  }

  /** The phone {@link #status()} is about (empty while searching). */
  public String statusHost() {
    return statusHost;
  }

  private void setStatus(Status newStatus, String host) {
    String h = host == null ? "" : host;
    if (status == newStatus && statusHost.equals(h)) return;
    status = newStatus;
    statusHost = h;
    notifyHostsChanged();
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
    pinAsked.remove(canonName(name)); // an explicit pick may ask for the PIN again
    setChoice(name);
    reconnectAttempts = 0;
    if ((hostConnected && !canonName(name).equals(canonName(hostName)))
        || (!hostConnected && connecting)) {
      // Drop the old link, or an attempt that may be stuck (a resolve that never answered):
      // the pick must start a fresh connection now.
      attempt.incrementAndGet();
      main.removeCallbacks(resolveWatchdog);
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
      prefs.edit().putString(PREF_PIN_PREFIX + canonName(hostName), pin.trim()).apply();
    }
    pinAsked.remove(canonName(hostName));
    reconnectAttempts = 0;
    maybeConnect();
  }

  private String savedPin(String hostName) {
    return prefs == null ? "" : prefs.getString(PREF_PIN_PREFIX + canonName(hostName), "");
  }

  /**
   * NSD may re-register a phone as "Joy 4 (2)" after a restart. The same phone must keep
   * matching the user's pick and its saved PIN, so compare and key without the suffix.
   */
  static String canonName(String name) {
    return name == null ? "" : name.replaceAll(" \\(\\d+\\)$", "");
  }

  /** The host the user picked (null in auto mode or when this phone's own link is chosen). */
  public String chosenHost() {
    return choice.isEmpty() || CHOICE_LOCAL.equals(choice) ? null : choice;
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
    if (Looper.myLooper() == Looper.getMainLooper()) {
      // Android forbids network I/O on the main thread: hand the line to the sender thread.
      SENDER.execute(() -> write(message));
      return;
    }
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
          // One entry per phone: NSD reports a service again per network / address family,
          // and a restarted phone may come back as "Name (2)".
          String canon = canonName(serviceInfo.getServiceName());
          found.removeIf(s -> canonName(s.getServiceName()).equals(canon));
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
        if (choice.isEmpty()
            || canonName(info.getServiceName()).equals(canonName(choice))) {
          target = info;
          break;
        }
      }
    }
    if (target == null) {
      if (!connecting) setStatus(Status.SEARCHING, chosenHost());
      return;
    }
    String targetName = target.getServiceName();
    // No PIN needed: connect straight away. (A robot phone running an older FriendBot that
    // still wants one answers "auth false", and onPinRequired asks for it then.)
    connecting = true;
    setStatus(Status.CONNECTING, targetName);
    final int token = attempt.incrementAndGet();
    main.removeCallbacks(resolveWatchdog);
    main.postDelayed(resolveWatchdog, RESOLVE_TIMEOUT_MS);
    try {
      nsdManager.resolveService(
          target,
          new NsdManager.ResolveListener() {
            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
              if (attempt.get() != token) return; // an older attempt answered late
              main.removeCallbacks(resolveWatchdog);
              List<InetAddress> addresses = addressesOf(serviceInfo);
              if (isOwnService(addresses)) {
                // Our own advertisement (the server on this phone): hide it and never
                // self-connect.
                synchronized (found) {
                  found.removeIf(s -> s.getServiceName().equals(serviceInfo.getServiceName()));
                }
                connecting = false;
                notifyHostsChanged();
                main.post(connectAttempt);
                return;
              }
              connectTo(serviceInfo.getServiceName(), addresses, serviceInfo.getPort(), token);
            }

            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
              if (attempt.get() != token) return;
              main.removeCallbacks(resolveWatchdog);
              Timber.w("Voice relay: resolving '%s' failed (%d)", targetName, errorCode);
              connecting = false;
              setStatus(Status.UNREACHABLE, targetName);
              main.post(VoiceRelayClient.this::scheduleRetry);
            }
          });
    } catch (IllegalArgumentException e) {
      // NSD refused the request (e.g. a resolve still in flight): retry instead of sticking.
      main.removeCallbacks(resolveWatchdog);
      connecting = false;
      setStatus(Status.UNREACHABLE, targetName);
      main.post(this::scheduleRetry);
    }
  }

  /**
   * Every address the host advertised, IPv4 first. Newer Android versions may list an IPv6
   * link-local address first, and a connect to it can fail where IPv4 works.
   */
  private static List<InetAddress> addressesOf(NsdServiceInfo info) {
    List<InetAddress> list = new ArrayList<>();
    if (Build.VERSION.SDK_INT >= 34) {
      for (InetAddress a : info.getHostAddresses()) {
        if (a != null && !list.contains(a)) list.add(a);
      }
    }
    InetAddress host = info.getHost();
    if (host != null && !list.contains(host)) list.add(host);
    list.sort((a, b) -> Boolean.compare(a instanceof Inet6Address, b instanceof Inet6Address));
    return list;
  }

  /** True when the resolved service is this phone's own server. */
  private static boolean isOwnService(List<InetAddress> addresses) {
    for (InetAddress a : addresses) {
      if (isLocalAddress(a)) return true;
    }
    return false;
  }

  private void connectTo(String name, List<InetAddress> addresses, int port, int token) {
    if (addresses.isEmpty()) {
      connecting = false;
      setStatus(Status.UNREACHABLE, name);
      main.post(this::scheduleRetry);
      return;
    }
    Thread connector =
        new Thread(
            () -> {
              Socket s = null;
              for (InetAddress address : addresses) {
                if (attempt.get() != token) return; // the user picked another phone
                Socket trial = new Socket();
                try {
                  trial.setTcpNoDelay(true);
                  trial.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
                  s = trial;
                  break;
                } catch (IOException e) {
                  Timber.w(
                      "Voice relay: cannot reach '%s' at %s:%d (%s)",
                      name, address.getHostAddress(), port, e.getMessage());
                  try {
                    trial.close();
                  } catch (IOException ignored) {
                    // Never opened.
                  }
                }
              }
              if (s == null) {
                if (attempt.get() == token) {
                  connecting = false;
                  setStatus(Status.UNREACHABLE, name);
                  main.post(VoiceRelayClient.this::scheduleRetry);
                }
                return;
              }
              final Socket open = s;
              boolean ours = false;
              try {
                BufferedReader in =
                    new BufferedReader(
                        new InputStreamReader(open.getInputStream(), StandardCharsets.UTF_8));
                synchronized (VoiceRelayClient.this) {
                  if (attempt.get() != token) {
                    open.close(); // superseded while connecting
                    return;
                  }
                  socket = open;
                  out =
                      new BufferedWriter(
                          new OutputStreamWriter(open.getOutputStream(), StandardCharsets.UTF_8));
                  ours = true;
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
                boolean stillOurs;
                synchronized (VoiceRelayClient.this) {
                  stillOurs = ours && socket == open;
                }
                if (stillOurs) {
                  closeSocket();
                  main.post(VoiceRelayClient.this::scheduleRetry);
                } else {
                  try {
                    open.close();
                  } catch (IOException ignored) {
                    // Already gone.
                  }
                }
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
      } else if ("floor".equals(type)) {
        String speaker = root.has("name") ? root.get("name").getAsString() : "";
        boolean you = root.has("you") && root.get("you").getAsBoolean();
        int phones = root.has("phones") ? root.get("phones").getAsInt() : 1;
        main.post(
            () -> {
              if (listener != null) {
                listener.onMicFloor(speaker, you, phones);
              }
            });
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
    if (address == null) return false; // unknown is not "ours": never hide a real host
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
      // Cannot enumerate interfaces: allow the connection rather than block all hosts.
    }
    return false;
  }
}
