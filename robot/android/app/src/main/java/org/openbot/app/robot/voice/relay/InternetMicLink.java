package org.openbot.app.robot.voice.relay;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Collections;
import timber.log.Timber;

/**
 * The Internet Mic page's link: this phone speaks into a robot phone's Voice Control page over
 * the internet — any WiFi or 4G/5G — through a free public MQTT broker, end-to-end encrypted with
 * the key from the robot phone's Internet QR ({@link RelayQr#parseNet}).
 *
 * <p>It tries the brokers in {@link RelayQr#BROKERS} order and moves to the next one when a
 * broker does not answer. Once connected it says hello (the robot phone answers {@code auth ok}),
 * pings every 3 s, and reports the robot phone offline after 12 s without an answer. After any
 * reconnect it re-opens the microphone stream by itself. Callbacks arrive on the main thread.
 */
public final class InternetMicLink {

  public enum State {
    /** No Internet QR scanned yet. */
    NO_CODE,
    /** Reaching the internet relay. */
    CONNECTING,
    /** On the relay; waiting for the robot phone to answer. */
    WAITING_ROBOT,
    /** The robot phone answered: the microphone can stream. */
    CONNECTED,
    /** The robot phone stopped answering (its internet or Voice Control page is off). */
    OFFLINE
  }

  public interface Listener {
    void onLinkChanged();

    void onMicAck(boolean live);

    void onMicFloor(String speaker, boolean you, int phones);

    /**
     * Any other line from the robot phone (pong, heard, said, status, cam, safety) for the
     * Pro pages. Main thread.
     */
    default void onExtra(JsonObject msg) {}
  }

  private static final String PREFS = "internet_mic_link";
  private static final long TICK_MS = 3_000;
  private static final long ROBOT_SILENT_MS = 12_000;
  private static final long READY_TIMEOUT_MS = 12_000;

  private static final InternetMicLink INSTANCE = new InternetMicLink();

  public static InternetMicLink get() {
    return INSTANCE;
  }

  private final Handler main = new Handler(Looper.getMainLooper());
  private final String myId = NetCrypto.randomId(12);

  private volatile Listener listener;
  private SharedPreferences prefs;
  private HandlerThread thread;
  private volatile Handler worker;
  private volatile boolean running;

  // Worker thread state.
  private volatile RelayQr.NetInfo info;
  private volatile byte[] key;
  private volatile MqttWs mqtt;
  private int brokerIndex;
  /** The current connection got as far as the broker (else the next broker is tried). */
  private boolean reachedRelay;
  private long connectStartedMs;
  private int generation;

  private volatile State state = State.NO_CODE;
  private volatile boolean authed;
  private volatile boolean micOn;
  private volatile long lastFromRobotMs;

  private InternetMicLink() {}

  public void start(Context context, Listener l) {
    listener = l;
    if (running) {
      notifyChanged();
      return;
    }
    running = true;
    prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    thread = new HandlerThread("internet-mic-link");
    thread.start();
    worker = new Handler(thread.getLooper());
    worker.post(
        () -> {
          RelayQr.NetInfo saved = RelayQr.parseNet(prefs.getString("qr", ""));
          if (saved != null) use(saved);
          else setState(State.NO_CODE);
          tick();
        });
  }

  public void stop() {
    if (!running) return;
    running = false;
    listener = null;
    Handler w = worker;
    HandlerThread t = thread;
    worker = null;
    thread = null;
    micOn = false;
    authed = false;
    if (w != null) {
      w.removeCallbacksAndMessages(null);
      w.post(
          () -> {
            generation++;
            if (mqtt != null) mqtt.close();
            mqtt = null;
            if (t != null) t.quitSafely();
          });
    }
  }

  /** The user scanned a robot phone's Internet QR: remember it and connect. */
  public void setCode(RelayQr.NetInfo qr, String rawText) {
    Handler w = worker;
    if (w == null || qr == null) return;
    w.post(
        () -> {
          prefs.edit().putString("qr", rawText).apply();
          brokerIndex = 0;
          use(qr);
        });
  }

  /** Forget the robot phone (stop connecting until a new QR is scanned). */
  public void forget() {
    Handler w = worker;
    if (w == null) return;
    w.post(
        () -> {
          prefs.edit().remove("qr").apply();
          generation++;
          if (mqtt != null) mqtt.close();
          mqtt = null;
          info = null;
          authed = false;
          setState(State.NO_CODE);
        });
  }

  public State state() {
    return state;
  }

  public boolean isConnected() {
    return authed && state == State.CONNECTED;
  }

  /** The robot phone's name from its QR, or "". */
  public String robotName() {
    RelayQr.NetInfo i = info;
    return i == null ? "" : i.name;
  }

  public void sendMicState(boolean on) {
    micOn = on;
    if (authed) sendLine("{\"t\":\"mic\",\"on\":" + on + "}");
  }

  /** Sends one JSON line to the robot phone once linked (Pro pages: pro, move, stop). */
  public void sendRaw(String line) {
    if (authed) sendLine(line);
  }

  public void sendMicFrame(String base64Opus, float rms) {
    if (!authed) return;
    JsonObject m = new JsonObject();
    m.addProperty("t", "mic");
    m.addProperty("b", base64Opus);
    m.addProperty("r", rms);
    sendLine(m.toString());
  }

  // ------------------------------------------------------------------ internals

  private void use(RelayQr.NetInfo qr) {
    info = qr;
    key = NetCrypto.unb64(qr.key);
    authed = false;
    connectBroker();
  }

  private String upTopic() {
    return "friendbot/v1/" + info.room + "/up";
  }

  private String downTopic() {
    return "friendbot/v1/" + info.room + "/down";
  }

  /** Worker: (re)connect to the current broker. */
  private void connectBroker() {
    if (!running || info == null) return;
    final int gen = ++generation;
    if (mqtt != null) mqtt.close();
    String url = RelayQr.BROKERS[brokerIndex % RelayQr.BROKERS.length][1];
    setState(State.CONNECTING);
    reachedRelay = false;
    connectStartedMs = SystemClock.uptimeMillis();
    mqtt =
        new MqttWs(
            url,
            "fbr-" + myId,
            Collections.singletonList(downTopic()),
            new MqttWs.Callback() {
              @Override
              public void onReady(MqttWs m) {
                post(
                    () -> {
                      if (gen != generation) return;
                      Timber.i("Internet mic: on relay %s", m.url);
                      reachedRelay = true;
                      setState(State.WAITING_ROBOT);
                      sayHello();
                    });
              }

              @Override
              public void onMessage(MqttWs m, String topic, byte[] payload) {
                byte[] k = key;
                if (k == null) return;
                String text = NetCrypto.open(k, payload);
                if (text != null) post(() -> { if (gen == generation) onDown(text); });
              }

              @Override
              public void onLost(MqttWs m, String why) {
                post(
                    () -> {
                      if (gen != generation) return;
                      boolean wasAuthed = authed;
                      authed = false;
                      if (wasAuthed) postMicAck(false);
                      // Never reached it: try the next broker; dropped later: the same one.
                      if (!reachedRelay) brokerIndex++;
                      setState(State.CONNECTING);
                      Handler w = worker;
                      if (w != null) w.postDelayed(this::retry, 2_000);
                    });
              }

              private void retry() {
                if (gen == generation) connectBroker();
              }
            });
    mqtt.connect();
  }

  private void sayHello() {
    JsonObject hello = new JsonObject();
    hello.addProperty("t", "hello");
    hello.addProperty("name", Build.MODEL);
    hello.addProperty("pin", "");
    sendLine(hello.toString());
  }

  /** Worker: one decrypted message from the robot phone. */
  private void onDown(String text) {
    String to;
    String line;
    try {
      JsonObject o = JsonParser.parseString(text).getAsJsonObject();
      to = o.get("t").getAsString();
      line = o.get("l").getAsString();
    } catch (Exception e) {
      return;
    }
    if (!myId.equals(to)) return; // for another phone in the same room
    lastFromRobotMs = SystemClock.uptimeMillis();
    JsonObject o;
    try {
      o = JsonParser.parseString(line).getAsJsonObject();
    } catch (Exception e) {
      return;
    }
    String t = o.has("t") ? o.get("t").getAsString() : "";
    switch (t) {
      case "auth":
        if (o.has("ok") && o.get("ok").getAsBoolean()) {
          authed = true;
          setState(State.CONNECTED);
          if (micOn) sendLine("{\"t\":\"mic\",\"on\":true}"); // re-open after a reconnect
        }
        break;
      case "micack":
        postMicAck(o.has("live") && o.get("live").getAsBoolean());
        break;
      case "floor":
        String speaker = o.has("name") ? o.get("name").getAsString() : "";
        boolean you = o.has("you") && o.get("you").getAsBoolean();
        int phones = o.has("phones") ? o.get("phones").getAsInt() : 1;
        main.post(
            () -> {
              Listener l = listener;
              if (l != null) l.onMicFloor(speaker, you, phones);
            });
        break;
      default:
        if (state == State.OFFLINE && authed) setState(State.CONNECTED); // pong: back again
        main.post(
            () -> {
              Listener l = listener;
              if (l != null) l.onExtra(o);
            });
        break;
    }
  }

  /** Every 3 s: ping, notice a silent robot phone or a broker that never answers. */
  private void tick() {
    if (!running) return;
    long now = SystemClock.uptimeMillis();
    if (info != null) {
      if (state == State.CONNECTING && now - connectStartedMs > READY_TIMEOUT_MS) {
        brokerIndex++; // this broker does not answer: next one
        connectBroker();
      } else if (mqtt != null && mqtt.isReady()) {
        if (!authed) {
          sayHello();
        } else {
          sendLine("{\"t\":\"ping\"}");
          if (now - lastFromRobotMs > ROBOT_SILENT_MS) {
            authed = false;
            postMicAck(false);
            setState(State.OFFLINE);
            sayHello();
          }
        }
      }
    }
    Handler w = worker;
    if (w != null) w.postDelayed(this::tick, TICK_MS);
  }

  private void sendLine(String line) {
    MqttWs m = mqtt;
    byte[] k = key;
    if (m == null || k == null || !m.isReady()) return;
    JsonObject o = new JsonObject();
    o.addProperty("f", myId);
    o.addProperty("l", line);
    byte[] sealed = NetCrypto.seal(k, o.toString());
    if (sealed != null) m.publish(upTopic(), sealed);
  }

  private void post(Runnable r) {
    Handler w = worker;
    if (w != null) w.post(r);
  }

  private void setState(State s) {
    if (state == s) return;
    state = s;
    notifyChanged();
  }

  private void notifyChanged() {
    main.post(
        () -> {
          Listener l = listener;
          if (l != null) l.onLinkChanged();
        });
  }

  private void postMicAck(boolean live) {
    main.post(
        () -> {
          Listener l = listener;
          if (l != null) l.onMicAck(live);
        });
  }
}
