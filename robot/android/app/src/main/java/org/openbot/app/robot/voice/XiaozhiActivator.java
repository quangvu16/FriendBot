package org.openbot.app.robot.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.openbot.app.robot.BuildConfig;
import timber.log.Timber;

/**
 * Device login for the Xiaozhi platform, following the pairing flow of
 * github.com/PiSugar/whisplay-xiaozhi (protocol/ota_client.py) instead of asking the user to
 * copy an API key by hand.
 *
 * <p>The app registers itself as a Xiaozhi device at the OTA endpoint ({@code
 * https://api.tenclass.net/xiaozhi/ota/}), receives a verification code, and waits while the
 * user signs in at xiaozhi.me and binds the device with that code. Once bound, the platform
 * hands the credentials (WebSocket token, MQTT login) back over the same OTA channel and the
 * token becomes the saved API key. The WebSocket endpoint ({@code websocket.url} + {@code
 * websocket.token}) is saved too, so {@link XiaozhiSession} can keep a live companion session.
 *
 * <p>Wire protocol (mirrors the reference client):
 *
 * <ul>
 *   <li>{@code POST <ota>/} with headers {@code Device-Id}, {@code Client-Id}, {@code
 *       Activation-Version} and body {@code {"application":{"version","elf_sha256"},
 *       "board":{"type","name","mac"}}}. The response carries {@code activation
 *       {code,challenge}} when unbound, or {@code websocket {url,token}} / {@code mqtt {...}}
 *       credentials when bound.
 *   <li>{@code POST <ota>/activate} with {@code {"Payload":{"algorithm":"hmac-sha256",
 *       "serial_number","challenge","hmac"}}} where {@code hmac} is HMAC-SHA256 of the
 *       challenge under the device fingerprint. HTTP 200 = bound, 202 = still pending, poll
 *       again.
 * </ul>
 *
 * <p>The identity (device id, client id, HMAC fingerprint) is generated once per install and
 * kept in the same preferences as the rest of the voice settings. The serial number follows
 * the reference format {@code SN-<8 hex>-<mac>} but hashes with SHA-256 rather than MD5 - it
 * is an opaque identifier and the server never verifies the digest.
 */
public final class XiaozhiActivator {

  public interface Listener {
    /** Verification code to show the user; empty string when the server did not send one. */
    void onCode(String code);

    /**
     * Binding done; {@code apiToken} is the issued token and is already saved as the key. The
     * WebSocket endpoint ({@code websocket.url}/{@code websocket.token} from the OTA reply) is
     * saved as well and passed along for the companion session.
     */
    void onBound(String apiToken, String wsUrl, String wsToken);

    void onError(String message);
  }

  public static final String DEFAULT_OTA_URL = "https://api.tenclass.net/xiaozhi/ota/";

  public static final String KEY_DEVICE_ID = "act_device_id";
  public static final String KEY_CLIENT_ID = "act_client_id";
  /** Saved WebSocket endpoint from {@code websocket.url}; empty until the device is bound. */
  public static final String KEY_WS_URL = "act_ws_url";
  /** Saved WebSocket bearer token from {@code websocket.token}; empty until the device is bound. */
  public static final String KEY_WS_TOKEN = "act_ws_token";

  private static final String KEY_HMAC_KEY = "act_hmac_key";

  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  private static final long REQUEST_TIMEOUT_S = 15;
  private static final long POLL_INTERVAL_MS = 2500;
  private static final long TOTAL_TIMEOUT_MS = 5 * 60_000;

  private final OkHttpClient http;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final SharedPreferences prefs;

  private final String deviceId;
  private final String clientId;
  private final String hmacKey;
  private final String serialNumber;

  private volatile boolean cancelled;

  public XiaozhiActivator(Context context) {
    this.prefs = prefsOf(context);
    this.deviceId = getOrCreate(prefs, KEY_DEVICE_ID, XiaozhiActivator::newDeviceId);
    this.clientId = getOrCreate(prefs, KEY_CLIENT_ID, () -> UUID.randomUUID().toString());
    this.hmacKey = getOrCreate(prefs, KEY_HMAC_KEY, () -> newHmacKey(deviceId, clientId));
    this.serialNumber = "SN-" + sha256Hex(deviceId).substring(0, 8) + "-" + deviceId;
    this.http =
        new OkHttpClient.Builder()
            .connectTimeout(REQUEST_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(REQUEST_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(REQUEST_TIMEOUT_S, TimeUnit.SECONDS)
            .build();
  }

  /**
   * Device identity for the {@code Device-Id} header; created on first use so the WebSocket session
   * sees the same value the activator registered with the platform.
   */
  public static String deviceId(Context context) {
    return getOrCreate(prefsOf(context), KEY_DEVICE_ID, XiaozhiActivator::newDeviceId);
  }

  /** Client identity for the {@code Client-Id} header; created on first use. */
  public static String clientId(Context context) {
    return getOrCreate(prefsOf(context), KEY_CLIENT_ID, () -> UUID.randomUUID().toString());
  }

  private static SharedPreferences prefsOf(Context context) {
    return context.getApplicationContext().getSharedPreferences(
        XiaozhiClient.PREFS_NAME, Context.MODE_PRIVATE);
  }

  /** Starts the pairing flow on a background thread. All callbacks arrive on the main thread. */
  public void start(@NonNull Listener listener) {
    new Thread(() -> run(listener), "xiaozhi-activator").start();
  }

  public void cancel() {
    cancelled = true;
  }

  // --------------------------------------------------------------------- flow

  private void run(Listener listener) {
    long deadline = System.currentTimeMillis() + TOTAL_TIMEOUT_MS;
    try {
      State state = parseState(checkVersion());
      if (state.bound) {
        finish(listener, state);
        return;
      }
      post(() -> listener.onCode(state.code));

      if (state.challenge != null && !state.challenge.isEmpty()) {
        pollActivate(listener, state.challenge, deadline);
      } else {
        pollCheckVersion(listener, state.code, deadline);
      }
    } catch (IOException e) {
      Timber.e(e, "Xiaozhi activation failed");
      post(() -> listener.onError(String.valueOf(e.getMessage())));
    }
  }

  /** v2 flow: sign the challenge and post it back until the server answers 200. */
  private void pollActivate(Listener listener, String challenge, long deadline)
      throws IOException {
    while (!cancelled && System.currentTimeMillis() < deadline) {
      String status = activate(challenge);
      if ("ok".equals(status)) {
        State state = parseState(checkVersion());
        if (state.bound) {
          finish(listener, state);
          return;
        }
      }
      sleep(POLL_INTERVAL_MS);
    }
    timeout(listener);
  }

  /** v1 flow (code but no challenge): just re-check the version until credentials appear. */
  private void pollCheckVersion(Listener listener, String lastCode, long deadline)
      throws IOException {
    while (!cancelled && System.currentTimeMillis() < deadline) {
      sleep(POLL_INTERVAL_MS);
      State state = parseState(checkVersion());
      if (state.bound) {
        finish(listener, state);
        return;
      }
      if (!state.code.equals(lastCode)) {
        lastCode = state.code;
        final String code = lastCode;
        post(() -> listener.onCode(code));
      }
    }
    timeout(listener);
  }

  private void finish(Listener listener, State state) {
    String token = state.token;
    if (token == null || token.isEmpty()) {
      post(() -> listener.onError("no token in server reply"));
      return;
    }
    // Keep the token as the REST API key (existing behavior) and also save the WebSocket
    // endpoint so XiaozhiSession can connect without asking the user for anything.
    prefs.edit()
        .putString(XiaozhiClient.KEY_TOKEN, token)
        .putString(KEY_WS_URL, state.wsUrl)
        .putString(KEY_WS_TOKEN, token)
        .apply();
    String wsUrl = state.wsUrl;
    post(() -> listener.onBound(token, wsUrl, token));
  }

  private void timeout(Listener listener) {
    if (cancelled) {
      return;
    }
    post(() -> listener.onError("timed out waiting for binding"));
  }

  // ------------------------------------------------------------------ network

  private static final class State {
    boolean bound;
    String token = "";
    String wsUrl = "";
    String code = "";
    String challenge = "";
  }

  private State parseState(String payload) {
    State state = new State();
    JsonObject root = JsonParser.parseString(payload).getAsJsonObject();

    JsonObject ws = root.getAsJsonObject("websocket");
    if (ws != null) {
      state.token = text(ws, "token");
      state.wsUrl = text(ws, "url");
    }
    String mqttUser = "";
    JsonObject mqtt = root.getAsJsonObject("mqtt");
    if (mqtt != null) {
      mqttUser = text(mqtt, "username");
    }
    state.bound = !state.token.isEmpty() || !mqttUser.isEmpty();

    JsonObject activation = root.getAsJsonObject("activation");
    if (activation != null) {
      state.code = text(activation, "code");
      state.challenge = text(activation, "challenge");
      // A code/challenge means the device is not bound yet, even if other keys are present.
      if (!state.code.isEmpty() || !state.challenge.isEmpty()) {
        state.bound = false;
      }
    }
    return state;
  }

  private String checkVersion() throws IOException {
    JsonObject application = new JsonObject();
    application.addProperty("version", BuildConfig.VERSION_NAME);
    application.addProperty("elf_sha256", hmacKey);
    JsonObject board = new JsonObject();
    board.addProperty("type", "openbot-phone");
    board.addProperty("name", "FriendBot Android");
    board.addProperty("mac", deviceId);
    JsonObject body = new JsonObject();
    body.add("application", application);
    body.add("board", board);
    return postOta(DEFAULT_OTA_URL, body.toString());
  }

  private String activate(String challenge) throws IOException {
    JsonObject payload = new JsonObject();
    payload.addProperty("algorithm", "hmac-sha256");
    payload.addProperty("serial_number", serialNumber);
    payload.addProperty("challenge", challenge);
    payload.addProperty("hmac", hmacSha256Hex(hmacKey, challenge));
    JsonObject body = new JsonObject();
    body.add("Payload", payload);
    okhttp3.Response response = execute(DEFAULT_OTA_URL + "activate", body.toString());
    try {
      int code = response.code();
      if (code == 200) return "ok";
      if (code == 202) return "pending";
      Timber.w("Xiaozhi activate returned HTTP %d", code);
      return "error";
    } finally {
      response.close();
    }
  }

  private String postOta(String url, String body) throws IOException {
    Response response = execute(url, body);
    try (ResponseBody responseBody = response.body()) {
      if (!response.isSuccessful()) {
        throw new IOException("HTTP " + response.code());
      }
      return responseBody == null ? "{}" : responseBody.string();
    }
  }

  private Response execute(String url, String body) throws IOException {
    Request.Builder request =
        new Request.Builder().url(url).post(RequestBody.create(JSON, body));
    request.header("Device-Id", deviceId);
    request.header("Client-Id", clientId);
    request.header("Content-Type", "application/json");
    request.header("User-Agent", "android/openbot-voice-" + BuildConfig.VERSION_NAME);
    request.header("Accept-Language", Locale.getDefault().toLanguageTag());
    request.header("Activation-Version", BuildConfig.VERSION_NAME);
    return http.newCall(request.build()).execute();
  }

  // ------------------------------------------------------------------ identity

  private interface Factory {
    String create();
  }

  private static String getOrCreate(SharedPreferences prefs, String key, Factory factory) {
    String value = prefs.getString(key, "");
    if (!value.isEmpty()) {
      return value;
    }
    value = factory.create();
    prefs.edit().putString(key, value).apply();
    return value;
  }

  /** Random locally-administered MAC-shaped id; the platform treats Device-Id as opaque. */
  private static String newDeviceId() {
    byte[] bytes = new byte[6];
    new java.util.Random().nextBytes(bytes);
    bytes[0] = (byte) ((bytes[0] & 0xFC) | 0x02);
    StringBuilder id = new StringBuilder();
    for (byte b : bytes) {
      if (id.length() > 0) id.append(':');
      id.append(String.format(Locale.US, "%02x", b));
    }
    return id.toString();
  }

  private static String newHmacKey(String deviceId, String clientId) {
    return sha256Hex(deviceId + "|" + clientId + "|" + System.currentTimeMillis());
  }

  private static String hmacSha256Hex(String key, String message) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return toHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String sha256Hex(String text) {
    try {
      return toHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String toHex(byte[] bytes) {
    StringBuilder hex = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      hex.append(String.format(Locale.US, "%02x", b));
    }
    return hex.toString();
  }

  private static String text(JsonObject object, String key) {
    return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void post(Runnable runnable) {
    main.post(runnable);
  }
}
