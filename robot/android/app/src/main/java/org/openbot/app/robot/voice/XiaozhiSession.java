package org.openbot.app.robot.voice;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.openbot.app.robot.BuildConfig;
import timber.log.Timber;

/**
 * The live XiaoZhi companion session of the Voice Control screen.
 *
 * <p>The phone behaves like a real XiaoZhi client (per PiSugar/whisplay-xiaozhi,
 * huangjunsen0406/py-xiaozhi, 78/xiaozhi-esp32): it keeps one WebSocket session open to the
 * endpoint the device was bound to, pre-recognized text goes in as {@code listen} frames, spoken
 * replies come back as {@code tts} sentences, and the robot itself is exposed to the assistant as
 * MCP tools ({@code self.robot.move}/{@code self.robot.stop}).
 *
 * <p>Wire protocol per 78/xiaozhi-esp32 docs/websocket.md + mcp-protocol.md and the
 * PiSugar/whisplay-xiaozhi client:
 *
 * <ul>
 *   <li>Connect with headers {@code Authorization: Bearer <token>}, {@code Protocol-Version: 1},
 *       {@code Device-Id}, {@code Client-Id}; send a {@code hello} frame announcing the MCP
 *       feature; store the {@code session_id} from the server's {@code hello} reply and include it
 *       in every outgoing message.
 *   <li>Outgoing text-in: {@code {"session_id":...,"type":"listen","state":"detect","text":...}}.
 *   <li>Outgoing voice-in (push-to-talk): {@code listen start/stop} control frames around raw
 *       opus microphone frames sent as binary WebSocket messages; {@code abort} barges in while
 *       the assistant is speaking. The hello says {@code version: 1}, therefore binary frames are
 *       RAW OPUS in both directions (no framing header).
 *   <li>Incoming: {@code stt} (transcript of what the server heard), {@code tts sentence_start}
 *       (assistant reply sentence), {@code tts start/stop} (speaking state), binary frames (raw
 *       opus TTS audio, decoded at the server hello's {@code audio_params.sample_rate}),
 *       {@code llm} (emotion hints), {@code mcp} (JSON-RPC envelope), {@code goodbye}
 *       (disconnect).
 *   <li>Outgoing MCP envelope: {@code {"session_id":...,"type":"mcp","payload":{...}}} with the
 *       JSON-RPC 2.0 reply for {@code initialize}, {@code tools/list} and {@code tools/call}.
 * </ul>
 *
 * <p>Reconnects with a 1 s / 2 s / 5 s backoff after unexpected closes only; an intentional
 * {@link #disconnect()} (screen closed) never reconnects. All listener callbacks arrive on the
 * main thread.
 */
public final class XiaozhiSession {

  /** Fallback endpoint when the OTA reply carried no {@code websocket.url}. */
  public static final String DEFAULT_WS_URL = "wss://api.tenclass.net/xiaozhi/v1/";

  /** {@code listen} mode: hands-free — keep streaming, the server VAD ends each utterance. */
  public static final String MODE_AUTO = "auto";

  /** {@code listen} mode: push-to-talk — the tap starts and stops each utterance. */
  public static final String MODE_MANUAL = "manual";

  /** Backoff between reconnect attempts; the last entry repeats forever. */
  private static final long[] BACKOFF_MS = {1000, 2000, 5000};

  private static final String MCP_PROTOCOL_VERSION = "2024-11-05";

  /** Camera tool, same name the KS5028 S3CAM firmware registers (take a photo and explain it). */
  private static final String TAKE_PHOTO_TOOL = "self.camera.take_photo";

  /** Capture + vision explain can take a while; the tools/call is failed after this. */
  private static final long PHOTO_TIMEOUT_MS = 60000;

  /** Main-thread callbacks for everything that happens on the session. */
  public interface Listener {
    /** The server answered our {@code hello}; the session is ready for traffic. */
    void onConnected();

    /**
     * The socket closed unexpectedly. {@code reconnecting} is true while another attempt is
     * scheduled (the UI can show "connecting" instead of "offline").
     */
    void onDisconnected(boolean reconnecting);

    /** The assistant echoed what it heard ({@code stt}); update the "You said" row. */
    void onStt(String text);

    /** One spoken reply sentence ({@code tts sentence_start}); append and speak in order. */
    void onAssistantText(String text);

    /** {@code tts start} / {@code tts stop}: the assistant began/finished speaking. */
    void onSpeaking(boolean speaking);

    /**
     * One raw opus TTS frame from the server (binary WebSocket message; the hello says {@code
     * version: 1}, so there is no framing header). {@code sampleRate} is the rate the packet must
     * be decoded at (the server hello's {@code audio_params.sample_rate}).
     */
    void onAudio(byte[] opus, int sampleRate);

    /**
     * The assistant's emotion hint ({@code llm} frame, e.g. {@code "😀"}); show it transiently and
     * never crash on it. Either field may be empty.
     */
    void onEmotion(String emotion, String text);

    /**
     * The assistant asked for a robot tool ({@code tools/call}); the command must run through the
     * same execute path as the on-screen quick buttons. The session answers the JSON-RPC call.
     */
    void onToolCall(VoiceCommand command);

    /**
     * The assistant asked to see the environment ({@code self.camera.take_photo}): capture a photo
     * with the phone camera, explain it, and answer through {@link PhotoCallback}. The session
     * replies to the JSON-RPC call exactly once (a timeout guard covers a stuck camera).
     */
    void onTakePhoto(String question, PhotoCallback callback);
  }

  /** One-shot answer to a {@code self.camera.take_photo} tools/call. */
  public interface PhotoCallback {
    /**
     * @param photoJson the photo information JSON on success, {@code null} on failure
     * @param errorMessage short reason on failure (e.g. {@code "Camera permission denied"})
     */
    void onPhotoResult(@Nullable String photoJson, @NonNull String errorMessage);
  }

  private final OkHttpClient http;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final String wsUrl;
  private final String token;
  private final String deviceId;
  private final String clientId;
  private final Listener listener;

  private volatile WebSocket webSocket;
  private volatile String sessionId;
  /** Explain endpoint for camera photos; the server pushes it in the MCP initialize. */
  private volatile String visionExplainUrl = "";
  private volatile String visionExplainToken = "";
  /** TTS playback rate from the server hello's {@code audio_params.sample_rate} (16 kHz default). */
  private volatile int playbackSampleRate = 16000;
  /** Written on the main thread (disconnect), read on the OkHttp reader thread (handleClosed). */
  private volatile boolean intentionalClose;
  private boolean closeHandled;
  private int attempts;

  /**
   * @param wsUrl endpoint from {@code websocket.url}; empty falls back to {@link #DEFAULT_WS_URL}
   * @param token bearer token from {@code websocket.token}
   */
  public XiaozhiSession(
      @Nullable String wsUrl,
      @NonNull String token,
      @NonNull String deviceId,
      @NonNull String clientId,
      @NonNull Listener listener) {
    this.wsUrl = wsUrl == null || wsUrl.trim().isEmpty() ? DEFAULT_WS_URL : wsUrl.trim();
    this.token = token;
    this.deviceId = deviceId;
    this.clientId = clientId;
    this.listener = listener;
    // Read timeout disabled: WebSocket frames can be minutes apart; pingInterval keeps NATs happy.
    this.http =
        new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build();
  }

  /** Opens the session. Callbacks arrive on the main thread. */
  public void connect() {
    intentionalClose = false;
    attempts = 0;
    connectInternal();
  }

  /** Intentional disconnect (screen closed / session replaced); never reconnects. */
  public void disconnect() {
    intentionalClose = true;
    main.removeCallbacksAndMessages(null);
    WebSocket ws = webSocket;
    webSocket = null;
    if (ws != null) {
      ws.close(1000, "client disconnect");
    }
  }

  /** Sends one pre-recognized utterance as text-in. */
  public void sendListenText(String text) {
    JsonObject message = new JsonObject();
    message.addProperty("session_id", sessionId == null ? "" : sessionId);
    message.addProperty("type", "listen");
    message.addProperty("state", "detect");
    message.addProperty("text", text);
    send(message);
  }

  /**
   * Listening start: the microphone is streaming now. The frames that follow go out as binary
   * WebSocket messages via {@link #sendAudio(byte[])} until {@link #sendListenStop()}.
   *
   * @param mode {@link #MODE_AUTO} hands-free (keep streaming, the server VAD ends the utterance) or
   *     {@link #MODE_MANUAL} push-to-talk (tap to start/stop)
   */
  public void sendListenStart(String mode) {
    JsonObject message = new JsonObject();
    message.addProperty("session_id", sessionId == null ? "" : sessionId);
    message.addProperty("type", "listen");
    message.addProperty("state", "start");
    message.addProperty(
        "mode", MODE_AUTO.equals(mode) ? MODE_AUTO : MODE_MANUAL);
    send(message);
  }

  /** Push-to-talk end: no more microphone audio follows this message. */
  public void sendListenStop() {
    JsonObject message = new JsonObject();
    message.addProperty("session_id", sessionId == null ? "" : sessionId);
    message.addProperty("type", "listen");
    message.addProperty("state", "stop");
    send(message);
  }

  /** Barge-in: drop what the assistant is saying and start listening again. */
  public void sendAbort() {
    JsonObject message = new JsonObject();
    message.addProperty("session_id", sessionId == null ? "" : sessionId);
    message.addProperty("type", "abort");
    message.addProperty("reason", "wake_word_detected");
    send(message);
  }

  /** Sends one encoded microphone frame as a binary WebSocket message (raw opus, hello v1). */
  public void sendAudio(byte[] opus) {
    WebSocket ws = webSocket;
    if (ws == null) {
      Timber.w("Xiaozhi WebSocket not connected, dropping audio frame");
      return;
    }
    ws.send(ByteString.of(opus));
  }

  /** The rate the server's TTS opus must be decoded at (from the server hello; 16 kHz default). */
  public int getPlaybackSampleRate() {
    return playbackSampleRate;
  }

  // ---------------------------------------------------------------- connection

  private void connectInternal() {
    if (intentionalClose) {
      return;
    }
    Request request;
    try {
      request =
          new Request.Builder()
              .url(wsUrl)
              .header("Authorization", "Bearer " + token)
              .header("Protocol-Version", "1")
              .header("Device-Id", deviceId)
              .header("Client-Id", clientId)
              .build();
    } catch (IllegalArgumentException e) {
      Timber.e(e, "Xiaozhi WebSocket url invalid: %s", wsUrl);
      post(() -> listener.onDisconnected(false));
      return;
    }
    closeHandled = false;
    webSocket = http.newWebSocket(request, wsListener);
  }

  private final WebSocketListener wsListener =
      new WebSocketListener() {
        @Override
        public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
          send(helloMessage());
        }

        @Override
        public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
          dispatch(text);
        }

        @Override
        public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
          // Binary frames are raw opus TTS audio (hello version 1: no framing header).
          byte[] opus = bytes.toByteArray();
          int rate = playbackSampleRate;
          post(() -> listener.onAudio(opus, rate));
        }

        @Override
        public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
          // Complete the close handshake; onClosed then runs the reconnect bookkeeping.
          webSocket.close(code, reason);
        }

        @Override
        public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
          handleClosed();
        }

        @Override
        public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t,
            @Nullable Response response) {
          Timber.w(t, "Xiaozhi WebSocket failed");
          handleClosed();
        }
      };

  private void handleClosed() {
    if (closeHandled || intentionalClose) {
      return;
    }
    closeHandled = true;
    webSocket = null;
    long delay = BACKOFF_MS[Math.min(attempts, BACKOFF_MS.length - 1)];
    attempts++;
    post(() -> listener.onDisconnected(true));
    main.postDelayed(this::connectInternal, delay);
  }

  private static JsonObject helloMessage() {
    JsonObject hello = new JsonObject();
    hello.addProperty("type", "hello");
    hello.addProperty("version", 1);
    hello.addProperty("transport", "websocket");
    JsonObject features = new JsonObject();
    features.addProperty("mcp", true);
    hello.add("features", features);
    JsonObject audio = new JsonObject();
    audio.addProperty("format", "opus");
    audio.addProperty("sample_rate", 16000);
    audio.addProperty("channels", 1);
    audio.addProperty("frame_duration", 60);
    hello.add("audio_params", audio);
    return hello;
  }

  /** The server hello's TTS playback rate ({@code audio_params.sample_rate}); 16000 when absent. */
  private static int helloPlaybackRate(JsonObject hello) {
    if (hello.has("audio_params") && hello.get("audio_params").isJsonObject()) {
      JsonObject audio = hello.getAsJsonObject("audio_params");
      if (audio.has("sample_rate") && audio.get("sample_rate").isJsonPrimitive()) {
        try {
          int rate = audio.get("sample_rate").getAsInt();
          if (rate > 0) {
            return rate;
          }
        } catch (Exception ignored) {
          // Fall through to the default.
        }
      }
    }
    return 16000;
  }

  // ---------------------------------------------------------------- dispatch

  private void dispatch(String text) {
    JsonObject message;
    try {
      message = JsonParser.parseString(text).getAsJsonObject();
    } catch (Exception e) {
      Timber.w("Xiaozhi message not understood: %s", text);
      return;
    }
    String type = text(message, "type");
    switch (type) {
      case "hello":
        sessionId = text(message, "session_id");
        playbackSampleRate = helloPlaybackRate(message);
        attempts = 0;
        post(listener::onConnected);
        break;
      case "stt":
        {
          String heard = text(message, "text");
          post(() -> listener.onStt(heard));
          break;
        }
      case "tts":
        dispatchTts(message);
        break;
      case "mcp":
        if (message.has("payload")) {
          dispatchMcp(message.get("payload"));
        }
        break;
      case "goodbye":
        // Server sign-off: treat as a disconnect and let the backoff bring us back.
        WebSocket ws = webSocket;
        if (ws != null) {
          ws.close(1000, "goodbye");
        }
        break;
      case "llm":
        {
          // Emotion hints ("happy" / "😀"); shown transiently by the UI, never crash on them.
          String emotion = text(message, "emotion");
          String hint = text(message, "text");
          if (!emotion.isEmpty() || !hint.isEmpty()) {
            post(() -> listener.onEmotion(emotion, hint));
          }
          break;
        }
      default:
        break;
    }
  }

  private void dispatchTts(JsonObject message) {
    String state = text(message, "state");
    switch (state) {
      case "sentence_start":
        {
          String sentence = text(message, "text");
          if (!sentence.isEmpty()) {
            post(() -> listener.onAssistantText(sentence));
          }
          break;
        }
      case "start":
        post(() -> listener.onSpeaking(true));
        break;
      case "stop":
        post(() -> listener.onSpeaking(false));
        break;
      default:
        break;
    }
  }

  // ---------------------------------------------------------------- mcp

  private void dispatchMcp(JsonElement payload) {
    if (payload == null || !payload.isJsonObject()) {
      return;
    }
    JsonObject rpc = payload.getAsJsonObject();
    JsonElement id = rpc.get("id");
    if (id == null || id.isJsonNull()) {
      return; // notification
    }
    String method = text(rpc, "method");
    switch (method) {
      case "initialize":
        parseVisionCapability(rpc);
        sendRpcResult(id, initializeResult());
        break;
      case "tools/list":
        sendRpcResult(id, toolsListResult());
        break;
      case "tools/call":
        {
          JsonObject params =
              rpc.has("params") && rpc.get("params").isJsonObject()
                  ? rpc.getAsJsonObject("params")
                  : new JsonObject();
          String name = text(params, "name");
          JsonObject args =
              params.has("arguments") && params.get("arguments").isJsonObject()
                  ? params.getAsJsonObject("arguments")
                  : new JsonObject();
          if (TAKE_PHOTO_TOOL.equals(name)) {
            dispatchTakePhoto(id, args);
            break;
          }
          VoiceCommand command = toolCommand(name, args);
          if (command == null) {
            sendRpcError(id, -32601, "Unknown tool: " + name);
          } else {
            JsonElement rpcId = id;
            post(
                () -> {
                  listener.onToolCall(command);
                  sendRpcResult(rpcId, toolCallResult());
                });
          }
          break;
        }
      default:
        sendRpcError(id, -32601, "Unknown method: " + method);
        break;
    }
  }

  /** The explain URL for photo questions ("" until the server sends one). */
  public String visionExplainUrl() {
    return visionExplainUrl;
  }

  /** Bearer token for the explain URL (may be empty). */
  public String visionExplainToken() {
    return visionExplainToken;
  }

  /**
   * The server's MCP {@code initialize} may carry {@code params.capabilities.vision = {url,
   * token}} — the image explain service to upload camera photos to (the S3CAM firmware's
   * {@code Esp32Camera::SetExplainUrl}). Without it the photo tool answers like the
   * firmware: "Image explain URL or token is not set".
   */
  private void parseVisionCapability(JsonObject rpc) {
    JsonObject params =
        rpc.has("params") && rpc.get("params").isJsonObject() ? rpc.getAsJsonObject("params") : null;
    if (params == null) {
      return;
    }
    JsonObject capabilities =
        params.has("capabilities") && params.get("capabilities").isJsonObject()
            ? params.getAsJsonObject("capabilities")
            : null;
    if (capabilities == null) {
      return;
    }
    JsonObject vision =
        capabilities.has("vision") && capabilities.get("vision").isJsonObject()
            ? capabilities.getAsJsonObject("vision")
            : null;
    if (vision == null) {
      return;
    }
    String url = text(vision, "url");
    if (!url.isEmpty()) {
      visionExplainUrl = url;
      visionExplainToken = text(vision, "token");
    }
  }

  private static JsonObject initializeResult() {
    JsonObject result = new JsonObject();
    result.addProperty("protocolVersion", MCP_PROTOCOL_VERSION);
    JsonObject capabilities = new JsonObject();
    capabilities.add("tools", new JsonObject());
    result.add("capabilities", capabilities);
    JsonObject serverInfo = new JsonObject();
    serverInfo.addProperty("name", "FriendBot");
    serverInfo.addProperty("version", BuildConfig.VERSION_NAME);
    result.add("serverInfo", serverInfo);
    return result;
  }

  private static JsonObject toolsListResult() {
    JsonArray tools = new JsonArray();
    tools.add(moveTool());
    tools.add(stopTool());
    tools.add(photoTool());
    JsonObject result = new JsonObject();
    result.add("tools", tools);
    result.addProperty("nextCursor", "");
    return result;
  }

  /**
   * The camera tool of the KS5028 S3CAM firmware: "Take a photo and explain it. Use this tool after
   * the user asks you to see something." The answer travels back as a JSON photo-info text result.
   */
  private static JsonObject photoTool() {
    JsonObject tool = new JsonObject();
    tool.addProperty("name", TAKE_PHOTO_TOOL);
    tool.addProperty(
        "description",
        "Take a photo with the phone camera and explain it. Use this tool after the user asks you"
            + " to see something or asks about the environment. `question`: The question that you"
            + " want to ask about the photo. Return: A JSON object that provides the photo"
            + " information.");
    JsonObject schema = new JsonObject();
    schema.addProperty("type", "object");
    JsonObject properties = new JsonObject();
    JsonObject question = new JsonObject();
    question.addProperty("type", "string");
    question.addProperty("description", "The question that you want to ask about the photo.");
    properties.add("question", question);
    schema.add("properties", properties);
    tool.add("inputSchema", schema);
    return tool;
  }

  /** Answers a photo tools/call later, through the fragment's camera + vision explain pipeline. */
  private void dispatchTakePhoto(JsonElement id, JsonObject args) {
    String question = text(args, "question");
    AtomicBoolean answered = new AtomicBoolean(false);
    PhotoCallback callback =
        (photoJson, errorMessage) -> {
          if (!answered.compareAndSet(false, true)) {
            return; // timeout guard already answered
          }
          if (photoJson == null || photoJson.isEmpty()) {
            sendRpcError(
                id,
                -32000,
                errorMessage == null || errorMessage.isEmpty()
                    ? "Failed to capture photo"
                    : errorMessage);
          } else {
            sendRpcResult(id, photoToolResult(photoJson));
          }
        };
    post(() -> listener.onTakePhoto(question, callback));
    main.postDelayed(
        () -> {
          if (answered.compareAndSet(false, true)) {
            sendRpcError(id, -32000, "Failed to capture photo");
          }
        },
        PHOTO_TIMEOUT_MS);
  }

  private static JsonObject photoToolResult(String photoJson) {
    JsonObject item = new JsonObject();
    item.addProperty("type", "text");
    item.addProperty("text", photoJson);
    JsonArray content = new JsonArray();
    content.add(item);
    JsonObject result = new JsonObject();
    result.add("content", content);
    result.addProperty("isError", false);
    return result;
  }

  private static JsonObject moveTool() {
    JsonObject tool = new JsonObject();
    tool.addProperty("name", "self.robot.move");
    tool.addProperty(
        "description",
        "Move the two-wheel differential-drive robot. Turns spin the robot in place."
            + " Automatically stops after duration_ms.");
    JsonObject schema = new JsonObject();
    schema.addProperty("type", "object");
    JsonObject properties = new JsonObject();
    JsonObject direction = new JsonObject();
    direction.addProperty("type", "string");
    JsonArray enumValues = new JsonArray();
    enumValues.add("forward");
    enumValues.add("backward");
    enumValues.add("left");
    enumValues.add("right");
    direction.add("enum", enumValues);
    properties.add("direction", direction);
    JsonObject speed = new JsonObject();
    speed.addProperty("type", "number");
    speed.addProperty("minimum", 0.15);
    speed.addProperty("maximum", 1.0);
    speed.addProperty("default", 0.75);
    properties.add("speed", speed);
    JsonObject duration = new JsonObject();
    duration.addProperty("type", "integer");
    duration.addProperty("minimum", 300);
    duration.addProperty("maximum", 8000);
    duration.addProperty("default", 1200);
    properties.add("duration_ms", duration);
    schema.add("properties", properties);
    JsonArray required = new JsonArray();
    required.add("direction");
    schema.add("required", required);
    tool.add("inputSchema", schema);
    return tool;
  }

  private static JsonObject stopTool() {
    JsonObject tool = new JsonObject();
    tool.addProperty("name", "self.robot.stop");
    tool.addProperty("description", "Stop the robot immediately.");
    JsonObject schema = new JsonObject();
    schema.addProperty("type", "object");
    schema.add("properties", new JsonObject());
    tool.add("inputSchema", schema);
    return tool;
  }

  private static JsonObject toolCallResult() {
    JsonObject item = new JsonObject();
    item.addProperty("type", "text");
    item.addProperty("text", "true");
    JsonArray content = new JsonArray();
    content.add(item);
    JsonObject result = new JsonObject();
    result.add("content", content);
    result.addProperty("isError", false);
    return result;
  }

  /**
   * Maps a {@code tools/call} to the same {@link VoiceCommand} model the quick buttons use, so the
   * fragment can run it through its normal execute path. Returns null for unknown tools.
   */
  @Nullable
  private static VoiceCommand toolCommand(String name, JsonObject args) {
    float speed =
        args.has("speed") && args.get("speed").isJsonPrimitive()
            ? args.get("speed").getAsFloat()
            : VoiceCommand.DEFAULT_SPEED;
    int duration =
        args.has("duration_ms") && args.get("duration_ms").isJsonPrimitive()
            ? args.get("duration_ms").getAsInt()
            : VoiceCommand.DEFAULT_DURATION_MS;
    switch (name) {
      case "self.robot.move":
        {
          String direction = text(args, "direction");
          VoiceCommand.Action action;
          switch (direction) {
            case "forward":
              action = VoiceCommand.Action.FORWARD;
              break;
            case "backward":
              action = VoiceCommand.Action.BACKWARD;
              break;
            case "left":
              action = VoiceCommand.Action.LEFT;
              break;
            case "right":
              action = VoiceCommand.Action.RIGHT;
              break;
            default:
              action = VoiceCommand.Action.STOP;
              break;
          }
          return VoiceCommand.of(action, speed, duration, name, "mcp");
        }
      case "self.robot.stop":
        return VoiceCommand.of(VoiceCommand.Action.STOP, name, "mcp");
      default:
        return null;
    }
  }

  private void sendRpcResult(JsonElement id, JsonObject result) {
    JsonObject rpc = new JsonObject();
    rpc.addProperty("jsonrpc", "2.0");
    rpc.add("id", id);
    rpc.add("result", result);
    sendMcp(rpc);
  }

  private void sendRpcError(JsonElement id, int code, String message) {
    JsonObject error = new JsonObject();
    error.addProperty("code", code);
    error.addProperty("message", message);
    JsonObject rpc = new JsonObject();
    rpc.addProperty("jsonrpc", "2.0");
    rpc.add("id", id);
    rpc.add("error", error);
    sendMcp(rpc);
  }

  private void sendMcp(JsonObject rpc) {
    JsonObject envelope = new JsonObject();
    envelope.addProperty("session_id", sessionId == null ? "" : sessionId);
    envelope.addProperty("type", "mcp");
    envelope.add("payload", rpc);
    send(envelope);
  }

  private void send(JsonObject message) {
    WebSocket ws = webSocket;
    if (ws == null) {
      Timber.w("Xiaozhi WebSocket not connected, dropping %s", text(message, "type"));
      return;
    }
    ws.send(message.toString());
  }

  // ---------------------------------------------------------------- helpers

  private static String text(JsonObject object, String key) {
    return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
  }

  private void post(Runnable runnable) {
    main.post(runnable);
  }
}
