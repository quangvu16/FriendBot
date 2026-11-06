package org.openbot.app.robot.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.openbot.app.robot.BuildConfig;
import timber.log.Timber;

/**
 * The "Xiaozhi brain" of the Voice Control screen.
 *
 * <p>The phone does its own listening (OS speech-to-text) and talking (TextToSpeech); this client
 * only does the understanding: it sends what you said to a Xiaozhi LLM endpoint and gets back a
 * robot command as JSON. It speaks the OpenAI-compatible chat-completions shape exposed by the
 * Xiaozhi API gateway (https://api.tenclass.net/v1, the service behind xiaozhi.me) and by any
 * OpenAI-compatible endpoint the user points it at - their own OpenAI/DeepSeek/... key or a proxy
 * in front of a self-hosted xiaozhi-esp32-server. The model is told about the robot and must answer
 * with one JSON object:
 *
 * <pre>
 *   {"action":"forward|backward|left|right|stop|none",
 *    "speed":0.15-1.0, "duration_ms":300-8000, "say":"short spoken reply"}
 * </pre>
 *
 * <p>The action {@code "none"} means pure conversation (no movement) and maps to {@link
 * VoiceCommand.Action#CHAT}. The call is multi-turn: the previous exchanges go along so follow-ups
 * ("faster" after "go forward") make sense.
 *
 * <p>When no server is configured (or the call fails) the screen falls back to {@link
 * VoiceInterpreter}, so the robot is always drivable by voice, online or not.
 */
public class XiaozhiClient {

  public interface Callback {
    void onCommand(VoiceCommand command, String say);

    void onError(String message);
  }

  /** One prior conversation turn kept for multi-turn context (plain text). */
  public static class Turn {
    /** {@code "user"} or {@code "assistant"}. */
    public final String role;
    public final String text;

    public Turn(String role, String text) {
      this.role = role;
      this.text = text == null ? "" : text;
    }
  }

  public static final String PREFS_NAME = "voice_control";
  public static final String KEY_ENDPOINT = "xiaozhi_endpoint";
  public static final String KEY_TOKEN = "xiaozhi_token";
  public static final String KEY_MODEL = "xiaozhi_model";

  /** Chat-completions endpoint of the Xiaozhi API gateway (any OpenAI-compatible endpoint works). */
  public static final String DEFAULT_ENDPOINT =
      "https://api.tenclass.net/v1/chat/completions";
  public static final String DEFAULT_MODEL = "xz-lite";

  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  private static final long TIMEOUT_S = 15;

  /** Previous turns sent for context: the last 3 exchanges (user + assistant) = 6 turns. */
  private static final int MAX_HISTORY_TURNS = 6;

  private static final String SYSTEM_PROMPT =
      "You are the drive controller of FriendBot, a two-wheel differential-drive robot. "
          + "The user speaks to it in their own language; you decide what the robot should do. "
          + "Understand driving commands even when phrased loosely or in any language "
          + "(for example Vietnamese or Chinese). "
          + "Turns spin the robot in place. When in doubt choose the safest action (stop). "
          + "Answer with ONLY a JSON object, no other text, with these fields: "
          + "\"action\": one of \"forward\", \"backward\", \"left\", \"right\", \"stop\", "
          + "\"none\" (\"none\" = pure conversation, no movement); "
          + "\"speed\": number 0.15 to 1.0 (default 0.75); "
          + "\"duration_ms\": how long to keep moving, 300 to 8000 (default 1200; use 300 for stop); "
          + "\"say\": a short spoken confirmation in the user's language, or \"\" for none.";

  private final OkHttpClient http;
  private final Gson gson = new Gson();
  private final Handler main = new Handler(Looper.getMainLooper());

  private final String endpoint;
  private final String token;
  private final String model;

  public XiaozhiClient(String endpoint, String token, String model) {
    this.endpoint = endpoint == null || endpoint.isEmpty() ? DEFAULT_ENDPOINT : endpoint.trim();
    this.token = token == null ? "" : token.trim();
    this.model = model == null || model.isEmpty() ? DEFAULT_MODEL : model.trim();
    this.http =
        new OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build();
  }

  /** Reads the saved configuration; the screen only calls interpret() when isConfigured(). */
  public static XiaozhiClient fromPreferences(Context context) {
    SharedPreferences prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    return new XiaozhiClient(
        prefs.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT),
        prefs.getString(KEY_TOKEN, ""),
        prefs.getString(KEY_MODEL, DEFAULT_MODEL));
  }

  /** A token is required; the endpoint has a default. */
  public boolean isConfigured() {
    return !token.isEmpty();
  }

  /**
   * Asks the LLM what to do with {@code text}. Calls back on the main thread with either a command
   * or an error message; the screen falls back to {@link VoiceInterpreter} on error.
   */
  public void interpret(String text, String languageTag, @NonNull Callback callback) {
    interpret(text, languageTag, Collections.emptyList(), callback);
  }

  /**
   * Multi-turn variant: {@code history} holds the previous exchanges (user/assistant turns, plain
   * text) so follow-ups like "faster" after "go forward" work. The last 3 exchanges are sent. The
   * JSON-only answer rule is unchanged.
   */
  public void interpret(
      String text, String languageTag, List<Turn> history, @NonNull Callback callback) {
    JsonObject body = new JsonObject();
    body.addProperty("model", model);
    body.addProperty("temperature", 0.2);
    body.addProperty("max_tokens", 200);

    com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
    JsonObject system = new JsonObject();
    system.addProperty("role", "system");
    system.addProperty("content", SYSTEM_PROMPT);
    messages.add(system);
    if (history != null && !history.isEmpty()) {
      int from = Math.max(0, history.size() - MAX_HISTORY_TURNS);
      for (Turn turn : history.subList(from, history.size())) {
        JsonObject past = new JsonObject();
        past.addProperty("role", turn.role);
        past.addProperty("content", turn.text);
        messages.add(past);
      }
    }
    JsonObject user = new JsonObject();
    user.addProperty("role", "user");
    String lang = languageTag == null ? "" : languageTag;
    user.addProperty("content", (lang.isEmpty() ? "" : "[user language: " + lang + "] ") + text);
    messages.add(user);
    body.add("messages", messages);

    Request.Builder request =
        new Request.Builder().url(endpoint).post(RequestBody.create(JSON, body.toString()));
    if (!token.isEmpty()) {
      request.header("Authorization", "Bearer " + token);
    }
    request.header("User-Agent", "FriendBot-Android/" + BuildConfig.VERSION_NAME);

    http.newCall(request.build())
        .enqueue(
            new okhttp3.Callback() {
              @Override
              public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Timber.e(e, "Xiaozhi request failed");
                main.post(() -> callback.onError(e.getMessage()));
              }

              @Override
              public void onResponse(@NonNull Call call, @NonNull Response response)
                  throws IOException {
                try (ResponseBody responseBody = response.body()) {
                  String payload = responseBody == null ? "" : responseBody.string();
                  if (!response.isSuccessful()) {
                    main.post(() -> callback.onError("HTTP " + response.code()));
                    return;
                  }
                  try {
                    ParsedReply reply = parseReply(payload);
                    main.post(() -> callback.onCommand(reply.command, reply.say));
                  } catch (Exception parseError) {
                    Timber.e(parseError, "Xiaozhi reply not understood: %s", payload);
                    main.post(() -> callback.onError("bad reply"));
                  }
                }
              }
            });
  }

  private static class ParsedReply {
    VoiceCommand command;
    String say;
  }

  /**
   * Pulls the command JSON out of a chat-completions response. Tolerates code fences and prose
   * around the object, since small models sometimes add both despite instructions.
   */
  private ParsedReply parseReply(String payload) {
    JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
    String content =
        root.getAsJsonArray("choices")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("message")
            .get("content")
            .getAsString();

    String json = extractJsonObject(content);
    JsonObject cmd = JsonParser.parseString(json).getAsJsonObject();

    ParsedReply reply = new ParsedReply();
    String action = cmd.has("action") ? cmd.get("action").getAsString() : "stop";
    float speed = cmd.has("speed") ? cmd.get("speed").getAsFloat() : VoiceCommand.DEFAULT_SPEED;
    int duration =
        cmd.has("duration_ms")
            ? cmd.get("duration_ms").getAsInt()
            : VoiceCommand.DEFAULT_DURATION_MS;
    reply.say = cmd.has("say") ? cmd.get("say").getAsString() : "";
    reply.command =
        VoiceCommand.of(parseAction(action), speed, duration, action, "xiaozhi");
    return reply;
  }

  private static VoiceCommand.Action parseAction(String action) {
    String a = action == null ? "" : action.toLowerCase(Locale.ROOT).trim();
    switch (a) {
      case "forward":
      case "go":
        return VoiceCommand.Action.FORWARD;
      case "backward":
      case "back":
      case "reverse":
        return VoiceCommand.Action.BACKWARD;
      case "left":
      case "turn_left":
        return VoiceCommand.Action.LEFT;
      case "right":
      case "turn_right":
        return VoiceCommand.Action.RIGHT;
      case "none":
      case "chat":
        // Spec voice action "none": pure conversation, no movement (named CHAT to avoid
        // clashing with Optional.none in the iOS counterpart).
        return VoiceCommand.Action.CHAT;
      case "stop":
      default:
        return VoiceCommand.Action.STOP;
    }
  }

  private static String extractJsonObject(String content) {
    String s = content.trim();
    int start = s.indexOf('{');
    int end = s.lastIndexOf('}');
    if (start < 0 || end <= start) {
      throw new IllegalStateException("no JSON object in reply");
    }
    return s.substring(start, end + 1);
  }
}
