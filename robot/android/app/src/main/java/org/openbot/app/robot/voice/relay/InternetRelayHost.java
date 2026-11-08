package org.openbot.app.robot.voice.relay;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import timber.log.Timber;

/**
 * Robot phone side of the Internet Mic: listens on a private, encrypted room on free public MQTT
 * brokers, so a Remote Mic phone anywhere — on another WiFi or on 4G/5G — can speak into this
 * phone's Voice Control page.
 *
 * <p>Each internet phone becomes a remote of {@link VoiceRelayServer} ({@link
 * VoiceRelayServer#attachRemote}), so it gets exactly the WiFi rules: hello, mic floor, mic
 * acks, STOP. Messages are {@code {"f":"<phone id>","l":"<relay line>"}} up and {@code
 * {"t":"<phone id>","l":"<relay line>"}} down, AES-256-GCM sealed ({@link NetCrypto}) on
 * {@code friendbot/v1/<room>/up|down}. A phone silent for 15 s is dropped.
 *
 * <p>The room and key persist (one Internet QR per robot phone); {@link #newCode} replaces them,
 * which locks out every phone that had the old QR. Main thread API.
 */
public final class InternetRelayHost {

  /** Status for the Voice Control page. */
  public interface Listener {
    void onInternetStatus(boolean online, int phones);
  }

  private static final String PREFS = "internet_relay_host";
  private static final long SILENT_DROP_MS = 15_000;
  private static final long RETRY_MS = 5_000;

  private static final InternetRelayHost INSTANCE = new InternetRelayHost();

  public static InternetRelayHost get() {
    return INSTANCE;
  }

  /** One internet phone. */
  private static final class Phone {
    final String id;
    String broker;
    long lastSeenMs;
    VoiceRelayServer.RemoteHandle handle;
    boolean closed;

    Phone(String id) {
      this.id = id;
    }
  }

  private final Handler main = new Handler(Looper.getMainLooper());
  private final Map<String, MqttWs> brokers = new HashMap<>(); // name → connection
  private final Map<String, Phone> phones = new HashMap<>(); // main thread
  private final Runnable sweep = this::sweep;

  private Context ctx;
  private String room;
  private byte[] key;
  private String keyText;
  private boolean running;
  private Listener listener;

  private InternetRelayHost() {}

  public void setListener(Listener l) {
    listener = l;
    notifyStatus();
  }

  /** Starts listening (idempotent). */
  public void start(Context context) {
    if (running) return;
    running = true;
    ctx = context.getApplicationContext();
    loadOrCreateRoom();
    for (String[] b : RelayQr.BROKERS) {
      connect(b[0], b[1]);
    }
    main.postDelayed(sweep, 5_000);
  }

  /** Stops listening and drops every internet phone. */
  public void stop() {
    if (!running) return;
    running = false;
    main.removeCallbacks(sweep);
    for (MqttWs m : new ArrayList<>(brokers.values())) m.close();
    brokers.clear();
    for (Phone p : new ArrayList<>(phones.values())) dropPhone(p);
    phones.clear();
    notifyStatus();
  }

  /** The Internet QR text for this robot phone. */
  public String qrText() {
    if (room == null && ctx != null) loadOrCreateRoom();
    return room == null ? null : RelayQr.buildNet(room, keyText, Build.MODEL);
  }

  /** Makes a new room and key: phones with the old Internet QR can no longer connect. */
  public void newCode() {
    if (ctx == null) return;
    boolean was = running;
    stop();
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    room = null;
    loadOrCreateRoom();
    if (was) start(ctx);
  }

  public boolean isOnline() {
    for (MqttWs m : brokers.values()) if (m.isReady()) return true;
    return false;
  }

  public int phoneCount() {
    return phones.size();
  }

  // ------------------------------------------------------------------ internals

  private void loadOrCreateRoom() {
    SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    String r = prefs.getString("room", "");
    String k = prefs.getString("key", "");
    byte[] raw = k.isEmpty() ? null : NetCrypto.unb64(k);
    if (r.length() < 8 || raw == null || raw.length != 32) {
      r = NetCrypto.randomId(16);
      raw = NetCrypto.randomKey();
      k = NetCrypto.b64(raw);
      prefs.edit().putString("room", r).putString("key", k).apply();
    }
    room = r;
    key = raw;
    keyText = k;
  }

  private String upTopic() {
    return "friendbot/v1/" + room + "/up";
  }

  private String downTopic() {
    return "friendbot/v1/" + room + "/down";
  }

  private void connect(String name, String url) {
    if (!running) return;
    MqttWs m =
        new MqttWs(
            url,
            "fbh-" + NetCrypto.randomId(10),
            Collections.singletonList(upTopic()),
            new MqttWs.Callback() {
              @Override
              public void onReady(MqttWs mqtt) {
                Timber.i("Internet relay: listening on %s", name);
                main.post(InternetRelayHost.this::notifyStatus);
              }

              @Override
              public void onMessage(MqttWs mqtt, String topic, byte[] payload) {
                String text = NetCrypto.open(key, payload);
                if (text == null) return; // not ours
                main.post(() -> onLine(name, text));
              }

              @Override
              public void onLost(MqttWs mqtt, String why) {
                main.post(
                    () -> {
                      if (brokers.get(name) == mqtt) brokers.remove(name);
                      notifyStatus();
                      if (running) main.postDelayed(() -> connect(name, url), RETRY_MS);
                    });
              }
            });
    brokers.put(name, m);
    m.connect();
  }

  /** Main thread: one decrypted message from an internet phone. */
  private void onLine(String broker, String text) {
    if (!running) return;
    String from;
    String line;
    try {
      JsonObject o = JsonParser.parseString(text).getAsJsonObject();
      from = o.get("f").getAsString();
      line = o.get("l").getAsString();
    } catch (Exception e) {
      return;
    }
    if (!from.matches("[a-z0-9]{6,32}")) return;
    Phone phone = phones.get(from);
    boolean fresh = phone == null;
    if (fresh) {
      Phone p = new Phone(from);
      phone = p;
      phones.put(from, p);
      p.handle =
          VoiceRelayServer.get()
              .attachRemote(
                  new VoiceRelayServer.RemoteSender() {
                    @Override
                    public void send(String out) {
                      sendDown(p, out);
                    }

                    @Override
                    public long backlog() {
                      MqttWs m = brokers.get(p.broker);
                      return m == null ? 0 : m.queued();
                    }

                    @Override
                    public void close() {
                      main.post(
                          () -> {
                            p.closed = true;
                            if (phones.get(p.id) == p) phones.remove(p.id);
                            notifyStatus();
                          });
                    }
                  });
      notifyStatus();
    }
    phone.broker = broker;
    phone.lastSeenMs = SystemClock.uptimeMillis();
    if (fresh && !line.contains("\"hello\"")) {
      // A phone that was already talking before this robot phone restarted: let it back in.
      phone.handle.deliver("{\"t\":\"hello\",\"name\":\"Internet Mic\",\"pin\":\"\"}");
    }
    phone.handle.deliver(line);
  }

  private void sendDown(Phone phone, String line) {
    if (phone.closed) return;
    JsonObject o = new JsonObject();
    o.addProperty("t", phone.id);
    o.addProperty("l", line);
    byte[] sealed = NetCrypto.seal(key, o.toString());
    if (sealed == null) return;
    MqttWs m = brokers.get(phone.broker);
    if (m != null) m.publish(downTopic(), sealed);
  }

  private void sweep() {
    if (!running) return;
    long now = SystemClock.uptimeMillis();
    for (Phone p : new ArrayList<>(phones.values())) {
      if (now - p.lastSeenMs > SILENT_DROP_MS) {
        Timber.i("Internet relay: phone %s went silent", p.id);
        dropPhone(p);
      }
    }
    main.postDelayed(sweep, 5_000);
  }

  private void dropPhone(Phone p) {
    phones.remove(p.id);
    if (!p.closed && p.handle != null) {
      p.closed = true;
      p.handle.drop();
    }
    notifyStatus();
  }

  private void notifyStatus() {
    Listener l = listener;
    if (l != null) l.onInternetStatus(isOnline(), phones.size());
  }
}
