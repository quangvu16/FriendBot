package org.openbot.app.robot.voice;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.google.gson.JsonParser;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import org.openbot.app.robot.R;
import org.openbot.app.robot.common.ControlsFragment;
import org.openbot.app.robot.databinding.FragmentVoiceControlBinding;
import org.openbot.app.robot.vehicle.Vehicle;
import org.openbot.app.robot.voice.relay.RelayPinPrompt;
import org.openbot.app.robot.voice.relay.VoiceRelayClient;
import org.openbot.app.robot.voice.relay.VoiceRelayServer;
import timber.log.Timber;

/**
 * Voice Control screen: talk to the robot like a real XiaoZhi client (Lily+/py-xiaozhi style).
 *
 * <p>First-time pairing registers the phone as a Xiaozhi device and shows the verification code to
 * enter at xiaozhi.me; afterwards the screen opens straight into a persistent Xiaozhi WebSocket
 * session ({@link XiaozhiSession}). The XiaoZhi agent owns the whole voice channel: the mic streams
 * 16 kHz mono PCM16 → opus in 60 ms frames → binary WebSocket messages (the server runs ASR) and
 * the only voice output is the server's TTS opus binary frames, decoded and played on the speaker
 * ({@link MicStreamer} / {@link OpusPlayer} / {@link OpusCodec}). There is no local speech
 * recognition and no local TTS on this screen (the platform recognizers are Google voice and are
 * banned here). Typed input still works as text-in, and robot commands arrive as MCP {@code
 * tools/call} running through the same execute path as the on-screen quick buttons. When the
 * assistant is silent (or not connected) the REST brain ({@link XiaozhiClient}) and then the
 * offline keywords ({@link VoiceInterpreter}) take over, so the robot is always drivable, online
 * or not — their replies appear in the list but are never spoken.
 *
 * <p>Redesign (Lily-style): an emotional face hero shows the emotion of the current exchange — the
 * server's {@code llm} emotion/emoji first, content heuristics over the request/reply text when the
 * server sends none, state defaults otherwise (Ready 😊 / Listening 🙂 / Thinking 🤔 / Speaking 😄).
 * A live level waveform (mic RMS while uplink streams, playback level while TTS plays) sits between
 * the face and the bubble transcript. Hands-free listening is the default ({@code listen start
 * mode=auto}, keep streaming, server VAD ends utterances; the uplink pauses during TTS playback and
 * re-arms after playback drains); the toggle next to the state caption switches to push-to-talk
 * ({@code mode=manual}, tap to start/stop).
 *
 * <p>Drive commands always go out through the normal vehicle link (USB serial or Bluetooth) — or,
 * on a phone without one, over the WiFi relay to the phone that shares its link — as
 * {@code c<left>,<right>} messages - exactly what firmware/openbot-esp32s3 understands. Moving
 * commands run for their duration and then auto-stop; while they run the drive values are resent
 * every 250 ms, which also feeds the firmware's heartbeat failsafe. Leaving the screen stops the
 * robot (ControlsFragment does that).
 */
public class VoiceControlFragment extends ControlsFragment {

  private FragmentVoiceControlBinding binding;

  private static final long DRIVE_TICK_MS = 250;
  /** How long to wait for the first 's<cm>' report after asking for sonar readings. */
  private static final long SONAR_SETTLE_MS = 900;
  private static final int MAX_LOG_LINES = 8;
  /** How long the assistant gets to react to a text-in utterance before we fall back. */
  private static final long UTTERANCE_WAIT_MS = 3500;
  /** Safety net: THINKING falls back to LISTENING/Ready when no tts ever starts. */
  private static final long THINKING_TIMEOUT_MS = 5000;
  /** Suppress duplicate "You:" rows when the server echoes typed text back as {@code stt}. */
  private static final long USER_ECHO_WINDOW_MS = 2000;

  /** Voice language for listening, speaking and the assistant: "system" or a BCP-47 tag. */
  public static final String PREF_VOICE_LANG = "voice_lang";

  /** The default voice language: Vietnamese (Google voice). */
  public static final String DEFAULT_VOICE_LANG = "vi-VN";
  /** Conversation transcript cap (spec: 40 entries). */
  private static final int MAX_CONVERSATION_ENTRIES = 40;
  /** Previous exchanges sent to the REST brain for follow-ups ("faster" after "go forward"). */
  private static final int HISTORY_EXCHANGES = 3;

  /** Saved listen mode: "handsfree" (default) or "ptt". */
  private static final String PREF_LISTEN_MODE = "voice_listen_mode";
  private static final String MODE_KEY_HANDSFREE = "handsfree";
  private static final String MODE_KEY_PTT = "ptt";

  /** Accent (matches {@code voice_mic_orb} / iOS VoiceFaceView.accent): face ring,
   *  user bubbles, waveform bars, live state captions. */
  private static final int ACCENT_COLOR = 0xFF0A66D6;

  /** Tap-to-talk / hands-free state machine shown in the caption under the face. */
  private enum UiState {
    IDLE,
    CONNECTING,
    PAIRING,
    LISTENING,
    THINKING,
    SPEAKING
  }

  /** Transcript rows as chat bubbles: You: right, Xiaozhi: left, "»" action chips centered. */
  private enum RowKind {
    USER,
    BOT,
    ACTION
  }

  private static final class ConvRow {
    final RowKind kind;
    final String line;

    ConvRow(RowKind kind, String line) {
      this.kind = kind;
      this.line = line;
    }
  }

  private final Handler driveHandler = new Handler(Looper.getMainLooper());
  private final Handler utteranceHandler = new Handler(Looper.getMainLooper());
  private final Handler thinkingHandler = new Handler(Looper.getMainLooper());
  private final Handler levelHandler = new Handler(Looper.getMainLooper());
  private float driveLeft = 0f;
  private float driveRight = 0f;
  private long moveEndsAtMs = 0;
  /** Remaining legs of a choreographed move (triangle, circle, 360° spin…); see {@link #advanceScript()}. */
  private final ArrayDeque<float[]> scriptQueue = new ArrayDeque<>();
  /** Last lights state sent to the car, so the Lights tile can toggle. */
  private boolean lightsOn = false;
  private float speedScale = VoiceCommand.DEFAULT_SPEED;
  private final StringBuilder log = new StringBuilder();

  private XiaozhiSession session;
  private XiaozhiActivator pairingActivator;
  private boolean wsReady;
  private UiState uiState = UiState.IDLE;
  /** Hands-free (default) vs push-to-talk; toggled next to the state caption. */
  private boolean listenHandsFree = true;

  /** Opus codec + the two audio ends of the live session (one encoder, one decoder per session). */
  private OpusCodec opusCodec;
  private MicStreamer micStreamer;
  private OpusPlayer opusPlayer;
  /** True while microphone frames are being streamed to the server (uplink armed). */
  private boolean micStreaming;

  private VoiceRelayClient relayClient;
  /** Relay status (a robot shared over WiFi by another phone); feeds the connection text. */
  private final VoiceRelayClient.Listener relayListener =
      new VoiceRelayClient.Listener() {
        @Override
        public void onHostConnected(String hostName) {
          updateConnectionText();
        }

        @Override
        public void onHostLost() {
          updateConnectionText();
        }

        @Override
        public void onHostsChanged() {
          updateConnectionText();
        }

        @Override
        public void onPinRequired(String hostName, boolean wrong) {
          if (isAdded() && getView() != null) {
            RelayPinPrompt.show(requireContext(), hostName, wrong);
          }
        }
      };

  /** True while a remote phone's microphone (its Remote Mic page) feeds this page's session. */
  private boolean remoteMicActive;

  /** The remote microphone's frames and state, on the main thread (see VoiceRelayServer). */
  private final VoiceRelayServer.MicSink remoteMicSink =
      new VoiceRelayServer.MicSink() {
        @Override
        public void onRemoteMic(boolean active, String phoneName) {
          if (active) {
            onRemoteMicStarted();
          } else {
            onRemoteMicStopped();
          }
        }

        @Override
        public boolean isMicReady() {
          return session != null && wsReady;
        }

        @Override
        public void onRemoteMicFrame(byte[] opus, float rms) {
          if (!remoteMicActive || session == null || !wsReady) return;
          if (uiState == UiState.SPEAKING) return; // echo guard: the host is the one talking
          session.sendAudio(opus);
          onMicLevel(rms);
        }
      };

  /** Display rows of the conversation ("You: ..." / "Xiaozhi: ..." / "» ..."), newest last. */
  private final List<ConvRow> conversation = new ArrayList<>();
  /** The same exchange as plain turns, for the REST brain's multi-turn context. */
  private final List<XiaozhiClient.Turn> turns = new ArrayList<>();

  /** Invalidates stale utterance-window callbacks when a newer utterance starts. */
  private long utteranceSeq = 0;
  private boolean utteranceTraffic;
  /** Kept as the typed-input safety net's "toolCallSeen": did this utterance already move the robot? */
  private boolean utteranceToolCall;

  /** Duplicate-echo guard for the "You:" row (typed text echoed back as stt). */
  private String lastUserText;
  private long lastUserTextAtMs;

  // ---------------------------------------------------------------- emotional face

  /** Face emoji of the current exchange; null = fall back to the state default. */
  private String turnEmoji;
  /** The emoji the face currently shows (cross-fade + bounce only when it changes). */
  private String shownFaceEmoji;
  private boolean faceSwapRunning;
  private ValueAnimator faceBobAnimator;
  /** Lily-style level bars between the face and the transcript. */
  private WaveformView waveformView;

  private final Runnable driveTick =
      new Runnable() {
        @Override
        public void run() {
          if (VoiceRelayServer.get().isStopLatched()) {
            // A STOP from anywhere (this screen or a phone on the relay) halted the robot.
            moveEndsAtMs = 0;
            if (vehicle != null) {
              vehicle.setControl(0f, 0f);
            }
            if (binding != null) {
              appendLog(getString(R.string.voice_status_stopped));
            }
            return;
          }
          if (SystemClock.uptimeMillis() >= moveEndsAtMs) {
            if (!scriptQueue.isEmpty()) {
              // One leg of a choreographed move just ended: run the next one.
              advanceScript();
              return;
            }
            if (vehicle != null) {
              vehicle.setControl(0f, 0f);
            }
            if (binding != null) {
              appendLog(getString(R.string.voice_status_stopped));
            }
            return;
          }
          if (vehicle != null && vehicle.isConnected()) {
            vehicle.setControl(driveLeft, driveRight);
          }
          driveHandler.postDelayed(this, DRIVE_TICK_MS);
        }
      };

  /** Everything the WebSocket session reports, on the main thread. */
  private final XiaozhiSession.Listener sessionListener =
      new XiaozhiSession.Listener() {
        @Override
        public void onConnected() {
          wsReady = true;
          if (uiState == UiState.CONNECTING) {
            setUiState(UiState.IDLE);
          }
          updateConnectionText();
          // A remote phone's mic (Remote Mic page) that opened before Xiaozhi was up, or
          // across a reconnect, needs its listen start now or its voice is ignored.
          if (listenHandsFree || remoteMicActive) {
            armListening(); // hands-free default: the robot listens as soon as it is connected
          }
          VoiceRelayServer.get().refreshMicAcks();
        }

        @Override
        public void onDisconnected(boolean reconnecting) {
          wsReady = false;
          // No socket to stream to; the next connect sends a fresh listen start if needed.
          stopMicCapture();
          setUiState(reconnecting ? UiState.CONNECTING : UiState.IDLE);
          updateConnectionText();
          VoiceRelayServer.get().refreshMicAcks(); // remote mics: not heard until reconnect
        }

        @Override
        public void onStt(String text) {
          utteranceTraffic = true;
          VoiceRelayServer.get().sendProText("heard", text); // Pro remote pages show it
          long now = SystemClock.uptimeMillis();
          boolean echo = text.equals(lastUserText) && now - lastUserTextAtMs < USER_ECHO_WINDOW_MS;
          if (!echo) {
            beginExchange(text);
            appendUserTurn(text);
          }
          if (!listenHandsFree && micStreaming) {
            stopMicSession(true); // push-to-talk: the utterance is over
          }
          // Server VAD (or the tap) ended the utterance; wait for the reply.
          setUiState(UiState.THINKING);
          armThinkingTimeout();
        }

        @Override
        public void onAssistantText(String text) {
          utteranceTraffic = true;
          VoiceRelayServer.get().sendProText("said", text);
          // The list row is the only trace of the reply here: the server's own TTS opus
          // frames (onAudio) are this screen's voice output, there is no local TTS anymore.
          appendBotTurn(text);
        }

        @Override
        public void onSpeaking(boolean speaking) {
          if (speaking) {
            // The server started its tts: pause the microphone uplink (and tell it so).
            stopMicSession(true);
            setUiState(UiState.SPEAKING);
          } else if (uiState == UiState.SPEAKING) {
            // Let the queued audio drain; finishSpeaking() then re-arms hands-free listening.
            if (opusPlayer == null || !opusPlayer.hasPendingAudio()) {
              finishSpeaking();
            }
          }
        }

        @Override
        public void onAudio(byte[] opus, int sampleRate) {
          // Raw opus TTS frames (hello v1 = no framing header) are the only voice output.
          // While the uplink is streaming we are the ones talking: drop the downlink.
          if (micStreaming) return;
          ensureAudioPipeline(sampleRate);
          if (opusPlayer != null) opusPlayer.play(opus);
        }

        @Override
        public void onEmotion(String emotion, String text) {
          applyServerEmotion(emotion, text);
        }

        @Override
        public void onToolCall(VoiceCommand command) {
          utteranceTraffic = true;
          utteranceToolCall = true;
          // Same execute path as the quick buttons; the "»" action row shows what ran and the
          // assistant's own tts reply speaks for it (no local TTS for tool results).
          executeCommand(command, "", false);
        }

        @Override
        public void onTakePhoto(String question, XiaozhiSession.PhotoCallback callback) {
          if (!isAdded()) {
            callback.onPhotoResult(null, "screen closed");
            return;
          }
          // Camera vision (KS5028 S3CAM style): the "»" row marks the look-around, the answer
          // is spoken by the assistant's own tts like any other reply.
          utteranceTraffic = true;
          utteranceToolCall = true;
          appendConversation("» " + getString(R.string.tool_take_photo), RowKind.ACTION);
          if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
              != PackageManager.PERMISSION_GRANTED) {
            pendingPhotoQuestion = question;
            pendingPhotoCallback = callback;
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
            return;
          }
          runPhotoTool(question, callback);
        }
      };

  /** TTS playback feedback: level for the waveform, drain to re-arm hands-free listening. */
  private final OpusPlayer.Listener opusPlayerListener =
      new OpusPlayer.Listener() {
        @Override
        public void onPlaybackLevel(float rms) {
          levelHandler.post(
              () -> {
                // Mild bounce while speaking, driven by the playback level.
                if (waveformView != null && uiState == UiState.SPEAKING) {
                  waveformView.setLevel(rms * 0.6f);
                }
              });
        }

        @Override
        public void onPlaybackDrained() {
          levelHandler.post(
              () -> {
                if (uiState == UiState.SPEAKING) {
                  finishSpeaking();
                }
              });
        }
      };

  private final ActivityResultLauncher<String[]> micPermissionLauncher =
      registerForActivityResult(
          new ActivityResultContracts.RequestMultiplePermissions(),
          result -> {
            Boolean granted = result.get(Manifest.permission.RECORD_AUDIO);
            if (granted != null && granted) {
              armListening();
            } else {
              Toast.makeText(
                      requireContext(), R.string.voice_mic_permission_denied, Toast.LENGTH_LONG)
                  .show();
              setUiState(UiState.IDLE);
            }
          });

  /** Photo tool waiting on the camera permission dialog (answered when the user decides). */
  private String pendingPhotoQuestion = "";

  private XiaozhiSession.PhotoCallback pendingPhotoCallback;

  private final ActivityResultLauncher<String> cameraPermissionLauncher =
      registerForActivityResult(
          new ActivityResultContracts.RequestPermission(),
          granted -> {
            XiaozhiSession.PhotoCallback callback = pendingPhotoCallback;
            String question = pendingPhotoQuestion;
            pendingPhotoCallback = null;
            pendingPhotoQuestion = "";
            if (granted) {
              if (callback != null) {
                runPhotoTool(question, callback);
              } else {
                startCameraPreview();
              }
            } else if (callback != null) {
              callback.onPhotoResult(null, "Camera permission denied");
            }
          });

  /** The corner camera: live preview of the chosen lens plus capture for the vision tool. */
  private final PhotoExplainer photoExplainer = new PhotoExplainer();

  /** Persisted lens choice for the corner preview (back camera by default). */
  private boolean cameraFront;

  /** Live corner preview ("the eye"): tap = look around now, flip = the other lens. */
  private void setupCameraCorner() {
    cameraFront = prefs().getBoolean("voice_camera_front", false);
    binding.cameraFlipButton.setOnClickListener(v -> flipCamera());
    binding.cameraCorner.setOnClickListener(v -> manualLookAround());
    if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
        == PackageManager.PERMISSION_GRANTED) {
      startCameraPreview();
    } else if (!prefs().getBoolean("voice_camera_requested", false)) {
      // Ask once on first open; a denial hides the corner and the tool re-asks on demand.
      prefs().edit().putBoolean("voice_camera_requested", true).apply();
      cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
    }
  }

  private void startCameraPreview() {
    if (binding == null || getView() == null) {
      return;
    }
    photoExplainer.start(
        requireContext().getApplicationContext(),
        getViewLifecycleOwner(),
        binding.cameraPreview,
        cameraFront);
    binding.cameraCorner.setVisibility(View.VISIBLE);
  }

  private void flipCamera() {
    cameraFront = !cameraFront;
    prefs().edit().putBoolean("voice_camera_front", cameraFront).apply();
    if (binding == null || getView() == null) {
      return;
    }
    photoExplainer.switchCamera(
        requireContext().getApplicationContext(), getViewLifecycleOwner(), binding.cameraPreview);
  }

  /** Tap on the corner: take a photo and show the Xiaozhi vision model's answer at once. */
  private void manualLookAround() {
    if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
        != PackageManager.PERMISSION_GRANTED) {
      cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
      return;
    }
    appendConversation("» " + getString(R.string.tool_take_photo), RowKind.ACTION);
    runPhotoTool("Describe what the camera sees in detail.", null);
  }

  /**
   * The whole photo job: capture → corner still flash → explain → answer. With a tool callback
   * the answer is the assistant's JSON tool result (spoken by its tts); without one (the tap)
   * the answer is shown as a Xiaozhi bubble.
   */
  private void runPhotoTool(String question, @Nullable XiaozhiSession.PhotoCallback toolCallback) {
    if (getView() == null) {
      if (toolCallback != null) {
        toolCallback.onPhotoResult(null, "screen closed");
      }
      return;
    }
    XiaozhiSession active = session;
    photoExplainer.captureAndExplain(
        requireContext().getApplicationContext(),
        getViewLifecycleOwner(),
        question,
        active == null ? "" : active.visionExplainUrl(),
        active == null ? "" : active.visionExplainToken(),
        new PhotoExplainer.Callback() {
          @Override
          public void onCaptured(@Nullable byte[] jpeg) {
            showCameraStill(jpeg);
          }

          @Override
          public void onExplained(@Nullable String photoJson, @NonNull String error) {
            if (toolCallback != null) {
              toolCallback.onPhotoResult(photoJson, error);
            } else if (photoJson != null) {
              appendBotTurn(extractPhotoAnswer(photoJson));
            } else {
              appendBotTurn(error);
            }
          }
        });
  }

  /** Freeze-frame the shutter in the corner for a moment so the capture is visible. */
  private void showCameraStill(@Nullable byte[] jpeg) {
    if (binding == null || jpeg == null) {
      return;
    }
    Bitmap still = PhotoExplainer.decodeStill(jpeg);
    if (still == null) {
      return;
    }
    binding.cameraStill.setImageBitmap(still);
    binding.cameraStill.setVisibility(View.VISIBLE);
    // Face-only screen: the picture replaces the face for 3 s, then the face smiles.
    binding.robotFace.showPhoto(still, 3000);
    binding.cameraStill.postDelayed(
        () -> {
          if (binding != null) {
            binding.cameraStill.setVisibility(View.GONE);
          }
        },
        1600);
  }

  private static String extractPhotoAnswer(String photoJson) {
    try {
      return JsonParser.parseString(photoJson).getAsJsonObject().get("answer").getAsString();
    } catch (Exception e) {
      return photoJson;
    }
  }

  @Override
  public View onCreateView(
      @NotNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentVoiceControlBinding.inflate(inflater, container, false);
    return binding.getRoot();
  }

  @Override
  public void onViewCreated(@NotNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    if (vehicle == null) {
      mViewModel
          .getVehicle()
          .observe(
              getViewLifecycleOwner(),
              v -> {
                if (v != null && vehicle == null) {
                  vehicle = v;
                  // This phone owns the robot link: share it over WiFi for the other phones.
                  VoiceRelayServer.get().start(requireContext().getApplicationContext(), v);
                  // Automatic mode defers to the local link; an explicit host pick survives.
                  if (relayClient != null) {
                    relayClient.onLocalLinkUp();
                  }
                  updateConnectionText();
                }
              });
    } else {
      VoiceRelayServer.get().start(requireContext().getApplicationContext(), vehicle);
    }
    // Always browse the robots other phones share over WiFi: the connection pill's picker
    // lists them so the user chooses which one to drive — this phone's link or a shared one.
    relayClient = VoiceRelayClient.get();
    relayClient.start(requireContext().getApplicationContext(), relayListener);
    if (vehicle != null && vehicle.isConnected()) {
      relayClient.onLocalLinkUp();
    }
    binding.connectionText.setOnClickListener(v -> showRobotPicker());
    // A phone running the Remote Mic page speaks through this page's Xiaozhi session.
    VoiceRelayServer.get().setMicSink(remoteMicSink);
    // Pro remote pages may watch the corner camera (small frames, only while asked for).
    VoiceRelayServer.get()
        .setProSource(() -> binding == null ? null : binding.cameraPreview.getBitmap());

    binding.micButton.setOnClickListener(v -> onMicTapped());
    binding.sendTypedButton.setOnClickListener(v -> submitTypedCommand());
    binding.forwardButton.setOnClickListener(
        v -> executeCommand(VoiceCommand.of(VoiceCommand.Action.FORWARD, "tap", "tap"), ""));
    binding.backwardButton.setOnClickListener(
        v -> executeCommand(VoiceCommand.of(VoiceCommand.Action.BACKWARD, "tap", "tap"), ""));
    binding.leftButton.setOnClickListener(
        v -> executeCommand(VoiceCommand.of(VoiceCommand.Action.LEFT, "tap", "tap"), ""));
    binding.rightButton.setOnClickListener(
        v -> executeCommand(VoiceCommand.of(VoiceCommand.Action.RIGHT, "tap", "tap"), ""));
    binding.stopButton.setOnClickListener(
        v -> executeCommand(VoiceCommand.of(VoiceCommand.Action.STOP, "tap", "tap"), ""));
    // Internet Mic: phones on another WiFi or 4G/5G reach this page through the relay.
    org.openbot.app.robot.voice.relay.InternetRelayHost.get()
        .start(requireContext().getApplicationContext());
    org.openbot.app.robot.voice.relay.InternetRelayHost.get()
        .setListener(
            (online, phones) -> {
              if (binding != null) updateSharingPin();
            });
    binding.sharingInternetButton.setOnClickListener(v -> showInternetQr());
    // The face screen's own QR button: one tap, no need to reveal the controls first.
    binding.faceQrButton.setOnClickListener(v -> showInternetQr());
    // Face-only screen: long-press = STOP, double-tap = the controls for a while.
    binding.robotFace.setListener(
        new RobotFaceView.Listener() {
          @Override
          public void onFaceLongPress() {
            executeCommand(VoiceCommand.of(VoiceCommand.Action.STOP, "tap", "tap"), "");
          }

          @Override
          public void onFaceDoubleTap() {
            showControlsForAWhile();
          }
        });
    binding.lightsButton.setOnClickListener(
        v ->
            executeCommand(
                VoiceCommand.of(
                    lightsOn ? VoiceCommand.Action.LIGHTS_OFF : VoiceCommand.Action.LIGHTS_ON,
                    "tap",
                    "tap"),
                ""));
    binding.sonarButton.setOnClickListener(
        v -> executeCommand(VoiceCommand.of(VoiceCommand.Action.SONAR, "tap", "tap"), ""));
    binding.singButton.setOnClickListener(
        v -> executeCommand(VoiceCommand.of(VoiceCommand.Action.SING, "tap", "tap"), ""));
    binding.triangleButton.setOnClickListener(
        v -> executeCommand(VoiceCommand.of(VoiceCommand.Action.TRIANGLE, "tap", "tap"), ""));
    binding.circleButton.setOnClickListener(
        v -> executeCommand(VoiceCommand.of(VoiceCommand.Action.CIRCLE, "tap", "tap"), ""));
    binding.spinButton.setOnClickListener(
        v -> executeCommand(VoiceCommand.of(VoiceCommand.Action.SPIN360, "tap", "tap"), ""));
    binding.wiggleButton.setOnClickListener(
        v -> executeCommand(VoiceCommand.of(VoiceCommand.Action.WIGGLE, "tap", "tap"), ""));
    binding.turnButton.setOnClickListener(
        v -> executeCommand(VoiceCommand.of(VoiceCommand.Action.TURN_AROUND, "tap", "tap"), ""));
    binding.settingsButton.setOnClickListener(v -> showXiaozhiSettings());
    binding.pairRetryButton.setOnClickListener(v -> startPairing());
    binding.pairSkipButton.setOnClickListener(v -> skipPairing());

    binding.speedSlider.addOnChangeListener(
        (slider, value, fromUser) -> {
          speedScale = value;
          binding.speedValueText.setText(
              getString(R.string.voice_speed_value, Math.round(value * 100)));
        });
    binding.speedValueText.setText(
        getString(R.string.voice_speed_value, Math.round(speedScale * 100)));
    setupCameraCorner();

    // Emotional face hero: pulsing accent ring behind the emoji face.
    GradientDrawable ring = new GradientDrawable();
    ring.setShape(GradientDrawable.OVAL);
    ring.setColor(Color.TRANSPARENT);
    ring.setStroke((int) (3 * getResources().getDisplayMetrics().density), ACCENT_COLOR);
    binding.faceRing.setBackground(ring);

    // Lily-style level waveform between the face and the transcript.
    waveformView = new WaveformView(requireContext());
    binding.waveformContainer.addView(waveformView);

    // Listen-mode toggle: hands-free (default) vs push-to-talk.
    listenHandsFree =
        !MODE_KEY_PTT.equals(prefs().getString(PREF_LISTEN_MODE, MODE_KEY_HANDSFREE));
    binding.modeToggleGroup.check(
        listenHandsFree ? R.id.handsfreeButton : R.id.pttButton);
    binding.modeToggleGroup.addOnButtonCheckedListener(
        (group, checkedId, isChecked) -> {
          if (!isChecked) return;
          boolean handsFree = checkedId == R.id.handsfreeButton;
          if (handsFree == listenHandsFree) return;
          listenHandsFree = handsFree;
          prefs()
              .edit()
              .putString(PREF_LISTEN_MODE, handsFree ? MODE_KEY_HANDSFREE : MODE_KEY_PTT)
              .apply();
          onListenModeChanged();
        });

    renderConversation();
    appendLog(getString(R.string.voice_log_ready));
    setUiState(uiState); // refresh caption + face with the binding in place

    // Saved credentials: skip pairing entirely and auto-connect the session (subsequent boots).
    if (prefs().getString(XiaozhiActivator.KEY_WS_TOKEN, "").isEmpty()) {
      startPairing();
    } else {
      showVoiceUi();
      connectSession();
    }
    updateConnectionText();
  }

  @Override
  protected void processControllerKeyData(String command) {
    // No gamepad actions on this screen; gamepad input is handled by the shared joystick path.
  }

  @Override
  protected void processUSBData(String data) {
    // Telemetry is not shown here; the screen only drives.
  }

  // ---------------------------------------------------------------- pairing

  /** First-time pairing panel: device login, verification code, wait for the xiaozhi.me bind. */
  private void startPairing() {
    showPairingUi();
    setUiState(UiState.PAIRING);
    binding.pairCodeText.setText("");
    binding.pairStatusText.setText(R.string.voice_xiaozhi_login_waiting);
    binding.pairRetryButton.setVisibility(View.GONE);
    if (pairingActivator != null) {
      pairingActivator.cancel();
    }
    pairingActivator = new XiaozhiActivator(requireContext());
    pairingActivator.start(
        new XiaozhiActivator.Listener() {
          @Override
          public void onCode(String code) {
            if (binding == null) return;
            binding.pairCodeText.setText(getString(R.string.voice_pair_code, code));
            binding.pairStatusText.setText(R.string.voice_pair_waiting);
          }

          @Override
          public void onBound(String apiToken, String wsUrl, String wsToken) {
            if (binding == null) return;
            binding.pairStatusText.setText(R.string.voice_pair_bound);
            onActivated();
          }

          @Override
          public void onError(String message) {
            if (binding == null) return;
            binding.pairStatusText.setText(getString(R.string.voice_xiaozhi_login_error, message));
            binding.pairRetryButton.setVisibility(View.VISIBLE);
          }
        });
  }

  /** "Use offline mode": leave the app on REST/keywords; the gear button can retry sign-in. */
  private void skipPairing() {
    if (pairingActivator != null) {
      pairingActivator.cancel();
      pairingActivator = null;
    }
    showVoiceUi();
    setUiState(UiState.IDLE);
    updateConnectionText();
    appendLog(getString(R.string.voice_log_ready));
  }

  /**
   * The device is bound (pairing panel or the settings-dialog sign-in): the activator already
   * saved the token and WebSocket credentials, so switch to the live UI and connect.
   */
  private void onActivated() {
    if (binding == null) return;
    showVoiceUi();
    connectSession();
  }

  private void connectSession() {
    SharedPreferences prefs = prefs();
    String wsToken = prefs.getString(XiaozhiActivator.KEY_WS_TOKEN, "");
    if (wsToken.isEmpty()) {
      return; // offline mode: REST + keywords only
    }
    if (session != null) {
      session.disconnect();
    }
    setUiState(UiState.CONNECTING);
    session =
        new XiaozhiSession(
            prefs.getString(XiaozhiActivator.KEY_WS_URL, ""),
            wsToken,
            XiaozhiActivator.deviceId(requireContext()),
            XiaozhiActivator.clientId(requireContext()),
            sessionListener);
    session.connect();
  }

  private void showPairingUi() {
    binding.pairingPanel.setVisibility(View.VISIBLE);
    binding.voiceUiPanel.setVisibility(View.GONE);
    pairingShown = true;
    faceHandler.removeCallbacks(faceBack);
    binding.robotFace.setVisibility(View.GONE); // the pairing code must stay visible
    binding.faceQrButton.setVisibility(View.GONE);
  }

  private void showVoiceUi() {
    binding.pairingPanel.setVisibility(View.GONE);
    binding.voiceUiPanel.setVisibility(View.VISIBLE);
    pairingShown = false;
    binding.robotFace.setVisibility(View.VISIBLE);
    binding.faceQrButton.setVisibility(View.VISIBLE);
  }

  // ---------------------------------------------------------------- face-only screen

  /** How long a double-tap shows the controls (QR, settings, typing) before the face returns. */
  private static final long CONTROLS_SHOWN_MS = 30_000;

  private final android.os.Handler faceHandler =
      new android.os.Handler(android.os.Looper.getMainLooper());
  private boolean pairingShown;
  private final Runnable faceBack =
      () -> {
        if (binding != null && !pairingShown) {
          binding.robotFace.setVisibility(View.VISIBLE);
          binding.faceQrButton.setVisibility(View.VISIBLE);
        }
      };

  private void showControlsForAWhile() {
    if (binding == null) return;
    binding.robotFace.setVisibility(View.GONE);
    binding.faceQrButton.setVisibility(View.GONE);
    faceHandler.removeCallbacks(faceBack);
    faceHandler.postDelayed(faceBack, CONTROLS_SHOWN_MS);
    Toast.makeText(requireContext(), R.string.voice_face_controls_shown, Toast.LENGTH_SHORT).show();
  }

  /** The face to draw: the exchange's emotion when there is one, else the voice state. */
  private RobotFaceView.Emotion faceEmotion() {
    if (turnEmoji != null) return emojiEmotion(turnEmoji);
    switch (uiState) {
      case LISTENING:
        return RobotFaceView.Emotion.LISTENING;
      case THINKING:
        return RobotFaceView.Emotion.THINKING;
      default:
        return RobotFaceView.Emotion.NEUTRAL;
    }
  }

  private static RobotFaceView.Emotion emojiEmotion(String emoji) {
    switch (emoji) {
      case "😄":
      case "🤩":
      case "😍":
      case "😆":
      case "😂":
      case "🥰":
      case "😘":
      case "😉":
      case "😋":
        return RobotFaceView.Emotion.HAPPY;
      case "😢":
      case "😭":
      case "😳":
        return RobotFaceView.Emotion.SAD;
      case "😠":
      case "😡":
        return RobotFaceView.Emotion.ANGRY;
      case "😮":
      case "😨":
      case "😲":
        return RobotFaceView.Emotion.SURPRISED;
      case "🤔":
        return RobotFaceView.Emotion.THINKING;
      case "😕":
      case "🙃":
      case "🤪":
        return RobotFaceView.Emotion.CONFUSED;
      case "😴":
        return RobotFaceView.Emotion.SLEEPY;
      default:
        return RobotFaceView.Emotion.NEUTRAL; // 😊 🙂 😐 😎 and anything else
    }
  }

  // ---------------------------------------------------------------- mic uplink (hands-free / ptt)

  /**
   * Arms the microphone uplink in the selected listen mode and shows LISTENING. While the assistant
   * is speaking this barges in (abort + take the floor). Hands-free sends {@code listen start
   * mode=auto} once and keeps streaming (the server VAD ends utterances); push-to-talk sends {@code
   * mode=manual} (tap to start/stop, v3 behavior).
   */
  private void armListening() {
    if (session == null || !wsReady) {
      return; // permission callbacks can arrive after the session is gone
    }
    if (remoteMicActive) {
      // A remote phone's microphone owns the uplink (Remote Mic page): re-open it after
      // tts, never arm ours on top of it.
      session.sendListenStart(XiaozhiSession.MODE_AUTO);
      setUiState(UiState.LISTENING);
      return;
    }
    if (!hasMicPermission()) {
      micPermissionLauncher.launch(new String[] {Manifest.permission.RECORD_AUDIO});
      return;
    }
    if (micStreaming && uiState == UiState.LISTENING) {
      return; // already armed
    }
    if (uiState == UiState.SPEAKING) {
      // Barge-in: cut the assistant off at once, then take the floor.
      if (opusPlayer != null) opusPlayer.flushAndReset();
      session.sendAbort();
    }
    thinkingHandler.removeCallbacksAndMessages(null);
    ensureAudioPipeline(session.getPlaybackSampleRate());
    session.sendListenStart(
        listenHandsFree ? XiaozhiSession.MODE_AUTO : XiaozhiSession.MODE_MANUAL);
    startMicCapture();
    setUiState(UiState.LISTENING);
  }

  /**
   * Mic tap. While the uplink streams: end the utterance now (push-to-talk stop, or hands-free
   * "send") and wait for the reply. While the assistant speaks: barge in. Otherwise: start
   * listening. When the socket is not ready the tap only logs {@code voice_ws_offline} — the
   * platform speech recognizers are Google voice and are banned on this screen.
   */
  private void onMicTapped() {
    if (session == null || !wsReady) {
      appendLog(getString(R.string.voice_ws_offline));
      return;
    }
    if (micStreaming) {
      stopMicSession(true);
      setUiState(UiState.THINKING);
      armThinkingTimeout();
      return;
    }
    armListening();
  }

  /** Ends the uplink (mic tap again, server tts start, or screen exit). */
  private void stopMicSession(boolean notifyServer) {
    boolean wasStreaming = micStreaming;
    stopMicCapture();
    if (notifyServer && (wasStreaming || remoteMicActive) && session != null && wsReady) {
      // A remote mic pauses too while the assistant speaks (its echo would feed back).
      session.sendListenStop();
    }
  }

  /** The listen mode changed: close the old-mode session and re-arm (unless mid-exchange). */
  private void onListenModeChanged() {
    if (session == null || !wsReady) return;
    if (uiState == UiState.SPEAKING || uiState == UiState.THINKING) {
      return; // the new mode applies from the next arm / finishSpeaking
    }
    if (micStreaming) stopMicSession(true);
    if (listenHandsFree) {
      armListening();
    } else {
      setUiState(UiState.IDLE); // push-to-talk waits for the mic tap
    }
  }

  private boolean hasMicPermission() {
    return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED;
  }

  /** A remote phone's microphone (its Remote Mic page) just became this page's uplink. */
  private void onRemoteMicStarted() {
    remoteMicActive = true;
    appendLog(getString(R.string.voice_log_remote_mic));
    if (session == null || !wsReady) {
      appendLog(getString(R.string.voice_ws_offline)); // heard once Xiaozhi connects
      return;
    }
    if (uiState == UiState.SPEAKING) {
      // Barge-in from the remote mic: cut the assistant off at once, then take the floor.
      if (opusPlayer != null) opusPlayer.flushAndReset();
      session.sendAbort();
    }
    thinkingHandler.removeCallbacksAndMessages(null);
    ensureAudioPipeline(session.getPlaybackSampleRate());
    stopMicCapture(); // the remote phone's mic owns the uplink now
    session.sendListenStart(XiaozhiSession.MODE_AUTO);
    setUiState(UiState.LISTENING);
  }

  /** The remote microphone stopped: close the uplink and fall back like any ended utterance. */
  private void onRemoteMicStopped() {
    if (!remoteMicActive) return;
    remoteMicActive = false;
    if (session != null && wsReady) {
      session.sendListenStop();
    }
    setUiState(UiState.THINKING);
    armThinkingTimeout();
  }

  private void startMicCapture() {
    if (remoteMicActive) return; // the remote phone's mic is the uplink
    stopMicCapture();
    if (opusCodec == null) {
      Timber.e("No opus codec; microphone uplink not started");
      return;
    }
    final XiaozhiSession target = session;
    micStreamer =
        new MicStreamer(
            opusCodec,
            (frame, rms) -> {
              // Per-frame mic level for the waveform + face ring (main thread).
              levelHandler.post(() -> onMicLevel(rms));
              // Binary WebSocket message: raw opus, hello v1 (no framing header).
              if (frame != null && target != null) {
                target.sendAudio(frame);
              }
            });
    micStreamer.start();
    micStreaming = micStreamer.isRunning();
  }

  private void stopMicCapture() {
    micStreaming = false;
    if (micStreamer != null) {
      micStreamer.stop();
      micStreamer = null;
    }
    if (waveformView != null) {
      waveformView.setLevel(0f);
    }
    if (binding != null) {
      binding.faceRing.setAlpha(0f);
    }
  }

  /** Mic RMS sample: drives the level bars and the pulsing ring around the face. */
  private void onMicLevel(float rms) {
    if (binding == null) return;
    if (waveformView != null) {
      waveformView.setLevel(rms);
    }
    if (uiState == UiState.LISTENING) {
      float pulse = Math.min(1f, rms * 3.5f);
      binding.faceRing.setAlpha(0.25f + 0.75f * pulse);
      float scale = 1f + 0.05f * pulse;
      binding.faceRing.setScaleX(scale);
      binding.faceRing.setScaleY(scale);
    } else {
      binding.faceRing.setAlpha(0f);
    }
  }

  /** One codec + one player per session, rebuilt when the server's playback rate changes. */
  private void ensureAudioPipeline(int playbackSampleRate) {
    if (opusCodec != null && opusCodec.getPlaybackSampleRate() == playbackSampleRate) {
      return;
    }
    stopMicCapture(); // the encoder lives in the codec
    if (opusPlayer != null) {
      opusPlayer.release();
      opusPlayer = null;
    }
    opusCodec = new OpusCodec(playbackSampleRate);
    opusPlayer = new OpusPlayer(opusCodec, playbackSampleRate, opusPlayerListener);
  }

  private void submitTypedCommand() {
    String text = binding.typedCommand.getText() == null
        ? ""
        : binding.typedCommand.getText().toString().trim();
    if (text.isEmpty()) return;
    binding.typedCommand.setText("");
    onTranscript(text);
  }

  // ---------------------------------------------------------------- utterance flow

  /**
   * One typed sentence on its way to a command (spec section 3): the live assistant first (text-in
   * over the WebSocket, 3500 ms to react), then the REST brain, then the offline keywords. Spoken
   * utterances never come through here — their ASR is the server's ({@code stt} rows).
   */
  private void onTranscript(String text) {
    beginExchange(text);
    appendUserTurn(text);
    setUiState(UiState.THINKING);
    armThinkingTimeout();

    if (session != null && wsReady) {
      utteranceTraffic = false;
      utteranceToolCall = false;
      final long seq = ++utteranceSeq;
      session.sendListenText(text);
      utteranceHandler.postDelayed(() -> onUtteranceWindowClosed(seq, text), UTTERANCE_WAIT_MS);
    } else {
      restOrKeywords(text);
      resolveThinking();
    }
  }

  /** The 3500 ms assistant window closed: did the assistant handle the utterance at all? */
  private void onUtteranceWindowClosed(long seq, String text) {
    if (seq != utteranceSeq || binding == null) return;
    if (!utteranceTraffic) {
      Timber.w("Xiaozhi assistant silent, using built-in brain");
      appendLog(getString(R.string.voice_assistant_silent));
      restOrKeywords(text);
      resolveThinking();
      return;
    }
    if (!utteranceToolCall) {
      // The assistant answered conversationally, but the user clearly commanded the robot:
      // run the command as a safety fallback. (When a tool did run, its "»" row already
      // shows the action — nothing else to add, and nothing is spoken locally anymore.)
      VoiceCommand command = VoiceInterpreter.interpret(text);
      if (command.getAction() != VoiceCommand.Action.UNKNOWN) {
        executeCommand(command, "");
      }
      resolveThinking();
    }
    // When a tool did run, the assistant's tts reply is on its way: THINKING rides into SPEAKING.
  }

  /** REST brain fallback with the recent conversation as context; keywords when it errors out. */
  private void restOrKeywords(String text) {
    XiaozhiClient client = XiaozhiClient.fromPreferences(requireContext());
    if (client.isConfigured()) {
      client.interpret(
          text,
          voiceLanguageTag(),
          recentHistory(),
          new XiaozhiClient.Callback() {
            @Override
            public void onCommand(VoiceCommand command, String say) {
              executeCommand(command, say);
            }

            @Override
            public void onError(String message) {
              Timber.w("Xiaozhi failed (%s), falling back to keywords", message);
              appendLog(getString(R.string.voice_xiaozhi_fallback, message));
              executeCommand(VoiceInterpreter.interpret(text), "");
            }
          });
    } else {
      executeCommand(VoiceInterpreter.interpret(text), "");
    }
  }

  /** Previous exchanges before the current (last) utterance, capped at 3 exchanges. */
  private List<XiaozhiClient.Turn> recentHistory() {
    int end = Math.max(0, turns.size() - 1);
    int start = Math.max(0, end - HISTORY_EXCHANGES * 2);
    return new ArrayList<>(turns.subList(start, end));
  }

  // ---------------------------------------------------------------- driving

  private void executeCommand(VoiceCommand command, String say) {
    executeCommand(command, say, true);
  }

  /**
   * The one execute path for every command source (quick buttons, REST brain, keywords, MCP
   * tools/call): motion goes through {@code vehicle.setControl} with the 250 ms driveTick resend
   * and the moveEndsAtMs auto-stop.
   *
   * @param spoken when true the confirmation is spoken (and logged as a Xiaozhi turn); MCP tool
   *     calls pass false so the phone does not talk over the assistant's own tts reply
   */
  private void executeCommand(VoiceCommand command, String say, boolean spoken) {
    if (command.getAction() == VoiceCommand.Action.CHAT) {
      // Pure conversation (REST action "none"): halt the robot and just say the reply.
      stopMoving();
      sayToUser(say);
      return;
    }

    String summary = describe(command);
    if (binding != null) {
      appendLog(command.getRawText().isEmpty() ? summary : command.getRawText() + " -> " + summary);
    }
    if (!spoken || !say.isEmpty()) {
      // What the robot did also goes in the conversation list, next to the spoken reply.
      appendConversation("» " + summary, RowKind.ACTION);
    }

    switch (command.getAction()) {
      case FASTER:
        speedScale = Math.min(1f, speedScale + 0.15f);
        if (binding != null) binding.speedSlider.setValue(speedScale);
        if (spoken) sayToUser(say.isEmpty() ? getString(R.string.voice_faster) : say);
        return;
      case SLOWER:
        speedScale = Math.max(0.25f, speedScale - 0.15f);
        if (binding != null) binding.speedSlider.setValue(speedScale);
        if (spoken) sayToUser(say.isEmpty() ? getString(R.string.voice_slower) : say);
        return;
      case UNKNOWN:
        stopMoving();
        if (spoken) sayToUser(getString(R.string.voice_no_match));
        return;
      case STOP:
        stopMoving();
        if (spoken) sayToUser(say.isEmpty() ? getString(R.string.voice_stop) : say);
        return;
      case LIGHTS_ON:
        if (localLinkUp()) {
          vehicle.sendLight(255, 255); // stock OpenBot boards
          vehicle.sendIndicator(Vehicle.LED_ON, Vehicle.LED_ON); // FriendBot firmware LEDs
          lightsOn = true;
          if (spoken) sayToUser(say.isEmpty() ? summary : say);
        } else {
          Toast.makeText(requireContext(), R.string.voice_not_connected, Toast.LENGTH_SHORT).show();
        }
        return;
      case LIGHTS_OFF:
        if (localLinkUp()) {
          vehicle.sendLight(0, 0);
          vehicle.sendIndicator(Vehicle.LED_OFF, Vehicle.LED_OFF);
          lightsOn = false;
          if (spoken) sayToUser(say.isEmpty() ? summary : say);
        } else {
          Toast.makeText(requireContext(), R.string.voice_not_connected, Toast.LENGTH_SHORT).show();
        }
        return;
      case INDICATOR_LEFT:
      case INDICATOR_RIGHT:
      case INDICATOR_HAZARD:
      case INDICATOR_OFF:
        if (localLinkUp()) {
          switch (command.getAction()) {
            case INDICATOR_LEFT:
              vehicle.sendIndicator(Vehicle.LED_BLINK, Vehicle.LED_OFF);
              break;
            case INDICATOR_RIGHT:
              vehicle.sendIndicator(Vehicle.LED_OFF, Vehicle.LED_BLINK);
              break;
            case INDICATOR_HAZARD:
              vehicle.sendIndicator(Vehicle.LED_BLINK, Vehicle.LED_BLINK);
              break;
            default:
              // Signals off: hand the LEDs back to the firmware's automatic driving signals.
              vehicle.sendIndicator(Vehicle.LED_AUTO, Vehicle.LED_AUTO);
              break;
          }
          if (spoken) sayToUser(say.isEmpty() ? summary : say);
        } else {
          Toast.makeText(requireContext(), R.string.voice_not_connected, Toast.LENGTH_SHORT).show();
        }
        return;
      case SONAR:
        if (localLinkUp()) {
          // Ask for fast reports, give the first one time to arrive, then read and speak it.
          vehicle.setSonarFrequency(200);
          driveHandler.removeCallbacks(sonarReport);
          driveHandler.postDelayed(sonarReport, SONAR_SETTLE_MS);
          if (spoken) sayToUser(say.isEmpty() ? summary : say);
        } else {
          Toast.makeText(requireContext(), R.string.voice_not_connected, Toast.LENGTH_SHORT).show();
        }
        return;
      case SING:
        sayToUser(getString(R.string.voice_song));
        // fall through: the tune plays over a full 360° spin
      case TRIANGLE:
      case CIRCLE:
      case SPIN360:
      case TURN_AROUND:
      case WIGGLE:
        startScript(command);
        if (spoken) sayToUser(say.isEmpty() ? summary : say);
        return;
      default:
        startMoving(command);
        if (spoken) sayToUser(say.isEmpty() ? summary : say);
    }
  }

  private void startMoving(VoiceCommand command) {
    float[] wheels = command.toWheels();
    if (wheels == null) return;
    // The slider rescales the command: at 75 % (the default) the command's own speed is used.
    float scale = speedScale / VoiceCommand.DEFAULT_SPEED;
    driveLeft = clampUnit(wheels[0] * scale);
    driveRight = clampUnit(wheels[1] * scale);
    moveEndsAtMs = SystemClock.uptimeMillis() + command.getDurationMs();
    VoiceRelayServer.get().noteLocalMove(moveEndsAtMs);
    // A live relay connection means the picked target is a shared robot (auto mode only
    // connects when this phone has no link of its own; choosing local disconnects it).
    boolean viaRelay = relayClient != null && relayClient.isConnected();
    if (viaRelay) {
      // The picked target is a robot another phone shares over WiFi.
      relayClient.sendMove(driveLeft, driveRight, command.getDurationMs());
    } else if (localLinkUp()) {
      vehicle.setControl(driveLeft, driveRight);
    } else {
      moveEndsAtMs = 0;
      Toast.makeText(requireContext(), R.string.voice_not_connected, Toast.LENGTH_SHORT).show();
      return;
    }
    driveHandler.removeCallbacks(driveTick);
    driveHandler.postDelayed(driveTick, DRIVE_TICK_MS);
  }

  /** True when this phone itself holds the robot link (USB or Bluetooth). */
  private boolean localLinkUp() {
    return vehicle != null && vehicle.isConnected();
  }

  private void stopMoving() {
    driveHandler.removeCallbacks(driveTick);
    scriptQueue.clear();
    moveEndsAtMs = 0;
    VoiceRelayServer.get().noteLocalStop();
    if (vehicle != null) vehicle.setControl(0f, 0f);
    if (relayClient != null && relayClient.isConnected()) {
      relayClient.sendStop();
    }
  }

  /** Starts a choreographed move (triangle, circle, 360° spin…): its legs queue up as segments. */
  private void startScript(VoiceCommand command) {
    List<float[]> segs = command.toScript();
    if (segs == null || segs.isEmpty()) return;
    scriptQueue.clear();
    scriptQueue.addAll(segs);
    advanceScript();
  }

  /**
   * Drives the next leg of the current script. Each leg is a plain timed move — the same
   * driveTick resend and moveEndsAtMs auto-stop as a voice move; when a leg expires driveTick calls
   * back here until the queue runs out and the robot stops.
   */
  private void advanceScript() {
    if (scriptQueue.isEmpty()) {
      stopMoving();
      return;
    }
    float[] seg = scriptQueue.poll();
    float scale = speedScale / VoiceCommand.DEFAULT_SPEED;
    driveLeft = clampUnit(seg[0] * scale);
    driveRight = clampUnit(seg[1] * scale);
    int durationMs = (int) seg[2];
    moveEndsAtMs = SystemClock.uptimeMillis() + durationMs;
    VoiceRelayServer.get().noteLocalMove(moveEndsAtMs);
    boolean viaRelay = relayClient != null && relayClient.isConnected();
    if (viaRelay) {
      relayClient.sendMove(driveLeft, driveRight, durationMs);
    } else if (localLinkUp()) {
      vehicle.setControl(driveLeft, driveRight);
    } else {
      stopMoving();
      Toast.makeText(requireContext(), R.string.voice_not_connected, Toast.LENGTH_SHORT).show();
      return;
    }
    driveHandler.removeCallbacks(driveTick);
    driveHandler.postDelayed(driveTick, DRIVE_TICK_MS);
  }

  /** Reads the sonar distance once the first report has had time to arrive, then stops the reports. */
  private final Runnable sonarReport =
      () -> {
        if (vehicle == null) return;
        float cm = vehicle.getSonarDistanceCm();
        vehicle.setSonarFrequency(0);
        sayToUser(
            cm >= 0
                ? getString(R.string.voice_sonar_distance, cm)
                : getString(R.string.voice_sonar_unknown));
      };

  private static float clampUnit(float v) {
    return Math.max(-1f, Math.min(1f, v));
  }

  private String describe(VoiceCommand command) {
    switch (command.getAction()) {
      case FORWARD:
        return getString(
            R.string.voice_action_move, getString(R.string.voice_forward), command.getDurationMs());
      case BACKWARD:
        return getString(
            R.string.voice_action_move, getString(R.string.voice_backward), command.getDurationMs());
      case LEFT:
        return getString(
            R.string.voice_action_move, getString(R.string.voice_left), command.getDurationMs());
      case RIGHT:
        return getString(
            R.string.voice_action_move, getString(R.string.voice_right), command.getDurationMs());
      case STOP:
        return getString(R.string.voice_stop);
      case FASTER:
        return getString(R.string.voice_faster);
      case SLOWER:
        return getString(R.string.voice_slower);
      case LIGHTS_ON:
        return getString(R.string.voice_lights_on);
      case LIGHTS_OFF:
        return getString(R.string.voice_lights_off);
      case INDICATOR_LEFT:
        return getString(R.string.voice_indicator_left);
      case INDICATOR_RIGHT:
        return getString(R.string.voice_indicator_right);
      case INDICATOR_HAZARD:
        return getString(R.string.voice_indicator_hazard);
      case INDICATOR_OFF:
        return getString(R.string.voice_indicator_off);
      case SONAR:
        return getString(R.string.voice_sonar);
      case SING:
        return getString(R.string.voice_sing);
      case TRIANGLE:
        return getString(R.string.voice_triangle);
      case CIRCLE:
        return getString(R.string.voice_circle);
      case SPIN360:
        return getString(R.string.voice_spin360);
      case TURN_AROUND:
        return getString(R.string.voice_turn_around);
      case WIGGLE:
        return getString(R.string.voice_wiggle);
      default:
        return getString(R.string.voice_no_match);
    }
  }

  // ---------------------------------------------------------------- state machine

  private void setUiState(UiState state) {
    uiState = state;
    if (binding == null) return;
    binding.micStatusText.setText(stateLabel(state));
    setStateIcon(state);
    if (state != UiState.LISTENING) {
      binding.faceRing.setAlpha(0f);
    }
    if (state != UiState.THINKING) {
      thinkingHandler.removeCallbacksAndMessages(null);
    }
    if (state != UiState.SPEAKING) {
      stopFaceBob();
    }
    updateFace();
    if (state == UiState.SPEAKING && !faceSwapRunning) {
      startFaceBob();
    }
    if (waveformView != null
        && (state == UiState.IDLE || state == UiState.CONNECTING || state == UiState.PAIRING)) {
      waveformView.setLevel(0f);
    }
  }

  private static int stateLabel(UiState state) {
    switch (state) {
      case CONNECTING:
        return R.string.voice_state_connecting;
      case PAIRING:
        return R.string.voice_pair_waiting;
      case LISTENING:
        return R.string.voice_state_listening;
      case THINKING:
        return R.string.voice_state_thinking;
      case SPEAKING:
        return R.string.voice_state_speaking;
      default:
        return R.string.voice_state_idle;
    }
  }

  /** Small state icon in front of the caption (mirrors the iOS SF Symbol row). */
  private void setStateIcon(UiState state) {
    int iconRes;
    switch (state) {
      case CONNECTING:
        iconRes = R.drawable.ic_state_wifi;
        break;
      case PAIRING:
        iconRes = R.drawable.ic_state_link;
        break;
      case LISTENING:
        iconRes = R.drawable.ic_mic;
        break;
      case THINKING:
        iconRes = R.drawable.ic_state_thinking;
        break;
      case SPEAKING:
        iconRes = R.drawable.ic_state_speaking;
        break;
      default:
        iconRes = R.drawable.ic_state_check;
        break;
    }
    Drawable icon = ContextCompat.getDrawable(requireContext(), iconRes);
    if (icon != null) {
      icon = icon.mutate();
      // Accent while the mic/speaker is live, muted grey otherwise (iOS parity).
      boolean live = state == UiState.LISTENING || state == UiState.SPEAKING;
      icon.setTint(live ? ACCENT_COLOR : 0xFF636366);
      binding.micStatusText.setTextColor(live ? ACCENT_COLOR : 0xFF636366);
    }
    binding.micStatusText.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
    binding.micStatusText.setCompoundDrawablePadding(
        (int) (6 * getResources().getDisplayMetrics().density));
  }

  /** THINKING is bounded: without a tts start it falls back to listening (hands-free) / ready. */
  private void armThinkingTimeout() {
    thinkingHandler.removeCallbacksAndMessages(null);
    thinkingHandler.postDelayed(this::resolveThinking, THINKING_TIMEOUT_MS);
  }

  private void resolveThinking() {
    if (uiState != UiState.THINKING) return;
    thinkingHandler.removeCallbacksAndMessages(null);
    turnEmoji = null;
    if (micStreaming || remoteMicActive) {
      setUiState(UiState.LISTENING); // the uplink (ours or the remote mic's) never stopped
    } else if (listenHandsFree && session != null && wsReady) {
      armListening();
    } else {
      setUiState(UiState.IDLE);
    }
  }

  /** TTS playback drained: hands-free re-arms, push-to-talk waits for the next tap. */
  private void finishSpeaking() {
    if (uiState != UiState.SPEAKING) return;
    thinkingHandler.removeCallbacksAndMessages(null);
    turnEmoji = null;
    // A remote mic always re-arms (hands-free semantics), whatever the listen toggle says.
    if (remoteMicActive || (listenHandsFree && session != null && wsReady)) {
      armListening();
    } else {
      setUiState(UiState.IDLE);
    }
  }

  // ---------------------------------------------------------------- emotional face

  /** A new exchange begins: content heuristics on the user's text until the server says otherwise. */
  private void beginExchange(String text) {
    turnEmoji = contentEmoji(text);
    updateFace();
  }

  /** The llm frame's emotion is the primary face source and overrides content heuristics. */
  private void applyServerEmotion(String emotion, String text) {
    String emoji = serverEmoji(emotion, text);
    if (TextUtils.isEmpty(emoji)) return;
    turnEmoji = emoji;
    updateFace();
  }

  private void updateFace() {
    if (binding == null) return;
    showFaceEmoji(turnEmoji != null ? turnEmoji : stateDefaultEmoji(uiState));
    binding.robotFace.setFace(faceEmotion(), uiState == UiState.SPEAKING);
  }

  /** State defaults when nothing else applies: Ready 😊 / Listening 🙂 / Thinking 🤔 / Speaking 😄. */
  private static String stateDefaultEmoji(UiState state) {
    switch (state) {
      case LISTENING:
        return "🙂";
      case THINKING:
        return "🤔";
      case SPEAKING:
        return "😄";
      default:
        return "😊"; // Ready (also connecting/pairing)
    }
  }

  /**
   * Server emotion (llm frame): verbatim when the value already is an emoji, otherwise token →
   * face. Unknown tokens map to 😊.
   */
  private static String serverEmoji(String emotion, String text) {
    if (isEmojiText(emotion)) return emotion.trim();
    if (isEmojiText(text)) return text.trim();
    String token = !emotion.trim().isEmpty() ? emotion : text;
    return tokenEmoji(token.trim().toLowerCase(Locale.ROOT));
  }

  /** happy → 😄, excited → 🤩, sad → 😢, angry → 😠, surprised → 😮, love → 😍, fear → 😨, … */
  private static String tokenEmoji(String token) {
    if (token.contains("excited")) return "🤩";
    if (token.contains("happy")
        || token.contains("laugh")
        || token.contains("funny")
        || token.contains("kiss")
        || token.contains("wink")
        || token.contains("delicious")) return "😄";
    if (token.contains("sleep")) return "😴";
    if (token.contains("confus") || token.contains("silly") || token.contains("puzzl")) return "😕";
    if (token.contains("sad") || token.contains("cry") || token.contains("embarrass")) return "😢";
    if (token.contains("angry")) return "😠";
    if (token.contains("surpris") || token.contains("shock")) return "😮";
    if (token.contains("love")) return "😍";
    if (token.contains("think")) return "🤔";
    if (token.contains("fear") || token.contains("scared") || token.contains("afraid")) return "😨";
    return "😊"; // neutral + unknown tokens
  }

  /**
   * Content heuristics over the request/reply text when the server sent no emotion: question → 🤔,
   * explicit drive command → 😄, stop/emergency → 😮, error/offline response → 😐. Null when nothing
   * applies (the state default then owns the face).
   */
  private static String contentEmoji(String text) {
    if (text == null) return null;
    String trimmed = text.trim();
    if (trimmed.isEmpty()) return null;
    String lower = trimmed.toLowerCase(Locale.ROOT);
    if (lower.endsWith("?")
        || lower.startsWith("what")
        || lower.startsWith("why")
        || lower.startsWith("how")
        || lower.startsWith("tại sao")
        || lower.startsWith("为什么")) {
      return "🤔";
    }
    VoiceCommand.Action action = VoiceInterpreter.interpret(trimmed).getAction();
    if (action == VoiceCommand.Action.FORWARD
        || action == VoiceCommand.Action.BACKWARD
        || action == VoiceCommand.Action.LEFT
        || action == VoiceCommand.Action.RIGHT
        || action == VoiceCommand.Action.FASTER
        || action == VoiceCommand.Action.SLOWER) {
      return "😄"; // explicit drive command
    }
    if (action == VoiceCommand.Action.STOP || lower.contains("emergency") || lower.contains("halt")) {
      return "😮"; // stop / emergency
    }
    if (lower.contains("error")
        || lower.contains("offline")
        || lower.contains("unavailable")
        || lower.contains("failed")
        || lower.contains("not connected")
        || lower.contains("did not understand")) {
      return "😐"; // error / offline response
    }
    return null;
  }

  /** True when the value is a bare emoji (e.g. "😀"), false for tokens like "happy". */
  private static boolean isEmojiText(String value) {
    if (value == null) return false;
    String trimmed = value.trim();
    if (trimmed.isEmpty()) return false;
    boolean hasEmoji = false;
    for (int i = 0; i < trimmed.length(); ) {
      int cp = trimmed.codePointAt(i);
      i += Character.charCount(cp);
      if (Character.isLetterOrDigit(cp)) return false;
      if (isEmojiCodepoint(cp)) hasEmoji = true;
    }
    return hasEmoji;
  }

  private static boolean isEmojiCodepoint(int cp) {
    return (cp >= 0x1F300 && cp <= 0x1FAFF) // pictographs, emoticons, symbols
        || (cp >= 0x2600 && cp <= 0x27BF) // misc symbols + dingbats
        || (cp >= 0x2B00 && cp <= 0x2BFF) // stars, arrows
        || cp == 0x2764 // ❤
        || cp == 0xFE0F; // variation selector-16
  }

  /** Cross-fade + small bounce (≈250 ms) whenever the face changes. */
  private void showFaceEmoji(String emoji) {
    if (binding == null || emoji.equals(shownFaceEmoji)) return;
    boolean first = shownFaceEmoji == null;
    shownFaceEmoji = emoji;
    faceSwapRunning = false;
    stopFaceBob();
    binding.faceEmoji.animate().cancel();
    if (first) {
      binding.faceEmoji.setText(emoji);
      return;
    }
    faceSwapRunning = true;
    binding.faceEmoji
        .animate()
        .alpha(0f)
        .scaleX(0.85f)
        .scaleY(0.85f)
        .setDuration(110)
        .withEndAction(
            () -> {
              if (binding == null) {
                faceSwapRunning = false;
                return;
              }
              binding.faceEmoji.setText(shownFaceEmoji);
              binding.faceEmoji
                  .animate()
                  .alpha(1f)
                  .scaleX(1f)
                  .scaleY(1f)
                  .setDuration(140)
                  .setInterpolator(new OvershootInterpolator(1.4f))
                  .withEndAction(
                      () -> {
                        faceSwapRunning = false;
                        if (uiState == UiState.SPEAKING) startFaceBob();
                      })
                  .start();
            })
        .start();
  }

  /** Gentle idle bob (slow scale 1.0↔1.02) while Speaking. */
  private void startFaceBob() {
    if (faceBobAnimator != null || binding == null) return;
    ValueAnimator bob = ValueAnimator.ofFloat(1f, 1.02f);
    bob.setDuration(1200);
    bob.setRepeatMode(ValueAnimator.REVERSE);
    bob.setRepeatCount(ValueAnimator.INFINITE);
    bob.setInterpolator(new AccelerateDecelerateInterpolator());
    bob.addUpdateListener(
        a -> {
          float s = (float) a.getAnimatedValue();
          if (binding != null && !faceSwapRunning) {
            binding.faceEmoji.setScaleX(s);
            binding.faceEmoji.setScaleY(s);
          }
        });
    faceBobAnimator = bob;
    bob.start();
  }

  private void stopFaceBob() {
    if (faceBobAnimator != null) {
      faceBobAnimator.cancel();
      faceBobAnimator = null;
    }
    if (binding != null) {
      binding.faceEmoji.setScaleX(1f);
      binding.faceEmoji.setScaleY(1f);
    }
  }

  // ---------------------------------------------------------------- ui helpers

  private void updateConnectionText() {
    if (binding == null) return;
    updateSharingPin();
    String ws = getString(wsReady ? R.string.voice_ws_ready : R.string.voice_ws_offline);
    if (relayClient != null && relayClient.isConnected()) {
      // The picked target is a robot another phone shares over WiFi.
      binding.connectionText.setText(
          getString(R.string.voice_relay_connected, relayClient.hostName()) + " · " + ws);
    } else if (vehicle != null && vehicle.isConnected()) {
      // While sharing, the PIN lives in its own card below (the relay serves even before
      // the robot link is up, and remotes need the PIN before anything is accepted).
      String link =
          VoiceRelayServer.get().isRunning()
              ? getString(R.string.voice_sharing_wifi, vehicle.getConnectionType())
              : getString(R.string.voice_connected, vehicle.getConnectionType());
      binding.connectionText.setText(link + " · " + ws);
    } else if (relayClient != null && relayClient.isSearching()) {
      binding.connectionText.setText(getString(R.string.voice_relay_searching) + " · " + ws);
    } else {
      binding.connectionText.setText(getString(R.string.voice_not_connected) + " · " + ws);
    }
  }

  /** The 4-digit PIN other phones type: its own big card whenever the relay serves. */
  private void updateSharingPin() {
    boolean serving = VoiceRelayServer.get().isRunning();
    binding.sharingPinCard.setVisibility(serving ? View.VISIBLE : View.GONE);
    if (serving) {
      binding.sharingPinDigits.setVisibility(View.GONE); // no PIN any more: scan the QR
      // The IP lets a Remote Mic phone connect by hand when WiFi discovery is blocked.
      String ip = org.openbot.app.robot.voice.relay.WifiNet.localIpv4();
      String wifi = org.openbot.app.robot.voice.relay.WifiNet.wifiName(requireContext());
      String hint = getString(R.string.voice_sharing_pin_hint);
      if (ip != null) {
        hint += "\n" + getString(R.string.voice_sharing_ip, ip);
      }
      if (wifi != null) {
        hint += "\n" + getString(R.string.voice_sharing_wifi_name, wifi);
      }
      org.openbot.app.robot.voice.relay.InternetRelayHost host =
          org.openbot.app.robot.voice.relay.InternetRelayHost.get();
      hint +=
          "\n"
              + getString(
                  R.string.voice_internet_status,
                  getString(
                      host.isOnline() ? R.string.voice_internet_online : R.string.voice_internet_offline),
                  host.phoneCount());
      binding.sharingPinHint.setText(hint);
      updateSharingQr(ip, wifi);
    }
  }

  /** The Remote Mic QR (IP + port + PIN) — re-drawn only when its content changes. */
  private String sharingQrText;

  private void updateSharingQr(String ip, String wifi) {
    int port = VoiceRelayServer.get().port();
    if (ip == null || port == 0) {
      binding.sharingQr.setVisibility(View.GONE);
      sharingQrText = null;
      return;
    }
    String text =
        org.openbot.app.robot.voice.relay.RelayQr.build(ip, port, android.os.Build.MODEL, wifi);
    if (!text.equals(sharingQrText)) {
      int px = Math.round(88 * getResources().getDisplayMetrics().density);
      android.graphics.Bitmap qr = org.openbot.app.robot.voice.relay.RelayQr.render(text, px);
      if (qr == null) {
        binding.sharingQr.setVisibility(View.GONE);
        return;
      }
      sharingQrText = text;
      binding.sharingQr.setImageBitmap(qr);
      binding.sharingQr.setOnClickListener(v -> showSharingQrLarge(text));
    }
    binding.sharingQr.setVisibility(View.VISIBLE);
  }

  /** The Internet QR (room + key) for the Internet Mic page on a phone anywhere. */
  private void showInternetQr() {
    if (!isAdded()) return;
    org.openbot.app.robot.voice.relay.InternetRelayHost host =
        org.openbot.app.robot.voice.relay.InternetRelayHost.get();
    String text = host.qrText();
    if (text == null) return;
    int px = Math.round(280 * getResources().getDisplayMetrics().density);
    android.graphics.Bitmap qr = org.openbot.app.robot.voice.relay.RelayQr.render(text, px);
    if (qr == null) return;
    android.widget.ImageView big = new android.widget.ImageView(requireContext());
    big.setImageBitmap(qr);
    big.setBackgroundColor(android.graphics.Color.WHITE);
    int pad = Math.round(16 * getResources().getDisplayMetrics().density);
    big.setPadding(pad, pad, pad, pad);
    big.setAdjustViewBounds(true);
    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
        .setTitle(R.string.voice_internet_qr_title)
        .setMessage(R.string.voice_internet_qr_message)
        .setView(big)
        .setPositiveButton(android.R.string.ok, null)
        .setNeutralButton(
            R.string.voice_internet_new_code,
            (d, w) -> {
              host.newCode();
              showInternetQr();
            })
        .show();
  }

  /** Big QR, easy to scan from across the table. */
  private void showSharingQrLarge(String text) {
    int px = Math.round(280 * getResources().getDisplayMetrics().density);
    android.graphics.Bitmap qr = org.openbot.app.robot.voice.relay.RelayQr.render(text, px);
    if (qr == null || !isAdded()) return;
    android.widget.ImageView big = new android.widget.ImageView(requireContext());
    big.setImageBitmap(qr);
    big.setBackgroundColor(android.graphics.Color.WHITE);
    int pad = Math.round(16 * getResources().getDisplayMetrics().density);
    big.setPadding(pad, pad, pad, pad);
    big.setAdjustViewBounds(true);
    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
        .setTitle(R.string.voice_sharing_qr_title)
        .setMessage(R.string.voice_sharing_qr_message)
        .setView(big)
        .setPositiveButton(android.R.string.ok, null)
        .show();
  }

  /**
   * A reply to the user: goes in the transcript. List-only — this screen has no local TTS; the
   * server's own tts opus frames are the only voice output.
   */
  private void sayToUser(String message) {
    if (TextUtils.isEmpty(message)) return;
    appendBotTurn(message);
  }

  private void appendUserTurn(String text) {
    turns.add(new XiaozhiClient.Turn("user", text));
    trimTurns();
    lastUserText = text;
    lastUserTextAtMs = SystemClock.uptimeMillis();
    appendConversation(getString(R.string.voice_log_you) + " " + text, RowKind.USER);
  }

  private void appendBotTurn(String text) {
    if (TextUtils.isEmpty(text)) return;
    if (turnEmoji == null) {
      turnEmoji = contentEmoji(text); // reply content, when the exchange has no emotion yet
      updateFace();
    }
    turns.add(new XiaozhiClient.Turn("assistant", text));
    trimTurns();
    appendConversation(getString(R.string.voice_log_bot) + " " + text, RowKind.BOT);
  }

  private void trimTurns() {
    while (turns.size() > MAX_CONVERSATION_ENTRIES) {
      turns.remove(0);
    }
  }

  /** Appends one transcript row (bubble/chip), newest last, and keeps the list scrolled down. */
  private void appendConversation(String line, RowKind kind) {
    conversation.add(new ConvRow(kind, line));
    while (conversation.size() > MAX_CONVERSATION_ENTRIES) {
      conversation.remove(0);
    }
    if (binding == null) return;
    addConversationRow(conversation.size() - 1);
    while (binding.conversationList.getChildCount() > MAX_CONVERSATION_ENTRIES) {
      binding.conversationList.removeViewAt(0);
    }
    binding.conversationScroll.post(
        () -> binding.conversationScroll.fullScroll(View.FOCUS_DOWN));
  }

  /** Re-renders the transcript (the fragment kept the rows across view recreation). */
  private void renderConversation() {
    binding.conversationList.removeAllViews();
    for (int i = 0; i < conversation.size(); i++) {
      addConversationRow(i);
    }
    binding.conversationScroll.post(
        () -> binding.conversationScroll.fullScroll(View.FOCUS_DOWN));
  }

  /** One bubble row: You: right-aligned accent bubble, Xiaozhi: left bubble, "»" centered chip. */
  private void addConversationRow(int index) {
    ConvRow row = conversation.get(index);
    TextView bubble = new TextView(requireContext());
    bubble.setText(row.line);
    float d = getResources().getDisplayMetrics().density;
    int padH = (int) (14 * d);
    int padV = (int) (8 * d);
    bubble.setPadding(padH, padV, padH, padV);
    GradientDrawable bg = new GradientDrawable();
    bg.setCornerRadius(20 * d);
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.topMargin = (int) (3 * d);
    lp.bottomMargin = (int) (3 * d);
    switch (row.kind) {
      case USER:
        bubble.setTextSize(15);
        bubble.setTextColor(Color.WHITE);
        bg.setColor(ACCENT_COLOR);
        lp.gravity = Gravity.END;
        lp.leftMargin = (int) (48 * d);
        break;
      case BOT:
        bubble.setTextSize(15);
        bubble.setTextColor(0xFF1C1C1E);
        bg.setColor(0xFFFFFFFF);
        lp.gravity = Gravity.START;
        lp.rightMargin = (int) (48 * d);
        break;
      default:
        // Action chips are pill-shaped secondary capsules.
        bg.setCornerRadius(100 * d);
        bubble.setTextSize(11);
        bubble.setTextColor(0xFF48484A);
        bg.setColor(0xFFE5E5EA);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.leftMargin = (int) (24 * d);
        lp.rightMargin = (int) (24 * d);
        break;
    }
    bubble.setBackground(bg);
    binding.conversationList.addView(bubble, lp);
  }

  private void appendLog(String line) {
    log.insert(0, line + "\n");
    // Keep the log short: count lines and trim the tail.
    int lines = 0;
    for (int i = 0; i < log.length(); i++) {
      if (log.charAt(i) == '\n') lines++;
      if (lines > MAX_LOG_LINES) {
        log.delete(i, log.length());
        break;
      }
    }
    if (binding != null) binding.logText.setText(log.toString().trim());
  }

  private SharedPreferences prefs() {
    return requireContext().getSharedPreferences(XiaozhiClient.PREFS_NAME, Context.MODE_PRIVATE);
  }

  // ---------------------------------------------------------------- level waveform

  /** Lily-style level meter: rounded bars driven by the mic/playback RMS (0..1), flat when idle. */
  private static final class WaveformView extends View {
    private static final int BARS = 14;
    /** Per-bar shape so the row reads as a wave instead of a solid block. */
    private static final float[] SHAPE = {
      0.55f, 0.85f, 1f, 0.7f, 0.9f, 0.6f, 1f, 0.8f, 0.5f, 0.95f, 0.65f, 1f, 0.75f, 0.85f
    };

    private final float[] bars = new float[BARS];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float target;
    private boolean animating;

    private final Runnable tick =
        new Runnable() {
          @Override
          public void run() {
            boolean moving = false;
            for (int i = 0; i < BARS; i++) {
              float goal = target * SHAPE[i];
              float k = goal > bars[i] ? 0.55f : 0.18f; // fast attack, slow release
              bars[i] += (goal - bars[i]) * k;
              if (Math.abs(goal - bars[i]) > 0.01f) {
                moving = true;
              } else {
                bars[i] = goal;
              }
            }
            invalidate();
            if (moving) {
              postOnAnimation(this);
            } else {
              animating = false;
            }
          }
        };

    WaveformView(Context context) {
      super(context);
      paint.setColor(ACCENT_COLOR);
    }

    /** New RMS level (0..1); the bars spring toward it and decay back to flat when it drops. */
    void setLevel(float level) {
      target = Math.max(0f, Math.min(1f, level));
      if (!animating) {
        animating = true;
        postOnAnimation(tick);
      }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
      float w = getWidth();
      float h = getHeight();
      if (w <= 0 || h <= 0) return;
      float slot = w / BARS;
      float barWidth = slot * 0.55f;
      float minH = Math.min(3 * getResources().getDisplayMetrics().density, h * 0.12f);
      float radius = barWidth / 2f;
      for (int i = 0; i < BARS; i++) {
        float barH = minH + bars[i] * (h - minH);
        float left = i * slot + (slot - barWidth) / 2f;
        float top = (h - barH) / 2f;
        canvas.drawRoundRect(left, top, left + barWidth, top + barH, radius, radius, paint);
      }
    }
  }

  // ---------------------------------------------------------------- settings dialog

  private void showXiaozhiSettings() {
    LinearLayout layout = new LinearLayout(requireContext());
    layout.setOrientation(LinearLayout.VERTICAL);
    int pad = (int) (24 * getResources().getDisplayMetrics().density);
    layout.setPadding(pad, pad / 2, pad, 0);

    SharedPreferences prefs = prefs();
    EditText endpoint =
        field(prefs.getString(XiaozhiClient.KEY_ENDPOINT, XiaozhiClient.DEFAULT_ENDPOINT));
    endpoint.setHint(R.string.voice_xiaozhi_endpoint_hint);
    // Prefilled from saved settings so hitting Save without retyping keeps them.
    EditText token = field(prefs.getString(XiaozhiClient.KEY_TOKEN, ""));
    token.setHint(R.string.voice_xiaozhi_token_hint);
    token.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    EditText model = field(prefs.getString(XiaozhiClient.KEY_MODEL, XiaozhiClient.DEFAULT_MODEL));
    model.setHint(R.string.voice_xiaozhi_model_hint);

    // Device login (whisplay-xiaozhi style): the app registers as a Xiaozhi device, shows a
    // verification code, and receives the API key back once the code is entered at xiaozhi.me.
    android.widget.Button signIn = new android.widget.Button(requireContext());
    signIn.setText(R.string.voice_xiaozhi_sign_in);
    android.widget.TextView loginStatus = new android.widget.TextView(requireContext());
    loginStatus.setPadding(0, pad / 4, 0, pad / 4);

    android.widget.Button language = new android.widget.Button(requireContext());
    updateLanguageButton(language);
    language.setOnClickListener(v -> showLanguagePicker(language));

    layout.addView(language);
    layout.addView(signIn);
    layout.addView(loginStatus);
    layout.addView(endpoint);
    layout.addView(token);
    layout.addView(model);

    XiaozhiActivator[] activator = new XiaozhiActivator[1];
    signIn.setOnClickListener(
        v -> {
          signIn.setEnabled(false);
          loginStatus.setText(R.string.voice_xiaozhi_login_waiting);
          activator[0] = new XiaozhiActivator(requireContext());
          activator[0].start(
              new XiaozhiActivator.Listener() {
                @Override
                public void onCode(String code) {
                  loginStatus.setText(getString(R.string.voice_xiaozhi_login_code, code));
                }

                @Override
                public void onBound(String apiToken, String wsUrl, String wsToken) {
                  token.setText(apiToken);
                  prefs.edit().putString(XiaozhiClient.KEY_TOKEN, apiToken).apply();
                  loginStatus.setText(R.string.voice_xiaozhi_login_success);
                  signIn.setEnabled(true);
                  appendLog(getString(R.string.voice_xiaozhi_login_success));
                  // Same landing as the pairing panel: live UI + companion session.
                  onActivated();
                }

                @Override
                public void onError(String message) {
                  loginStatus.setText(getString(R.string.voice_xiaozhi_login_error, message));
                  signIn.setEnabled(true);
                }
              });
        });

    androidx.appcompat.app.AlertDialog dialog =
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.voice_xiaozhi_settings)
            .setMessage(R.string.voice_xiaozhi_settings_help)
            .setView(layout)
            .setPositiveButton(
                R.string.voice_save,
                (d, which) -> {
                  prefs.edit()
                      .putString(XiaozhiClient.KEY_ENDPOINT, endpoint.getText().toString().trim())
                      .putString(XiaozhiClient.KEY_TOKEN, token.getText().toString().trim())
                      .putString(XiaozhiClient.KEY_MODEL, model.getText().toString().trim())
                      .apply();
                  appendLog(getString(R.string.voice_xiaozhi_saved));
                })
            .setNegativeButton(R.string.voice_cancel, null)
            .create();
    dialog.setOnDismissListener(d -> {
      if (activator[0] != null) {
        activator[0].cancel();
      }
    });
    dialog.show();
  }

  private static final String[] VOICE_LANG_TAGS = {
    "vi-VN", "en-US", "zh-CN", "ja-JP", "ko-KR", "th-TH", "fr-FR", "de-DE", "es-ES", "ru-RU",
    "pt-BR", "hi-IN"
  };

  private static final String[] VOICE_LANG_NAMES = {
    "Tiếng Việt", "English", "中文", "日本語", "한국어", "ไทย", "Français", "Deutsch", "Español",
    "Русский", "Português", "हिन्दी"
  };

  private String voiceLanguageTag() {
    String tag = prefs().getString(PREF_VOICE_LANG, DEFAULT_VOICE_LANG);
    return "system".equals(tag) ? Locale.getDefault().toLanguageTag() : tag;
  }

  private Locale voiceLocale() {
    String tag = prefs().getString(PREF_VOICE_LANG, DEFAULT_VOICE_LANG);
    return "system".equals(tag) ? Locale.getDefault() : Locale.forLanguageTag(tag);
  }

  /** Single-choice picker; each language is shown in its own script so everyone finds it. */
  private void showLanguagePicker(android.widget.Button button) {
    String current = prefs().getString(PREF_VOICE_LANG, DEFAULT_VOICE_LANG);
    int checked = VOICE_LANG_TAGS.length; // the "System default" row
    for (int i = 0; i < VOICE_LANG_TAGS.length; i++) {
      if (VOICE_LANG_TAGS[i].equals(current)) {
        checked = i;
        break;
      }
    }
    String[] items = new String[VOICE_LANG_TAGS.length + 1];
    System.arraycopy(VOICE_LANG_NAMES, 0, items, 0, VOICE_LANG_NAMES.length);
    items[items.length - 1] = getString(R.string.voice_language_system);
    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
        .setTitle(R.string.voice_language_title)
        .setSingleChoiceItems(
            items,
            checked,
            (d, which) -> {
              String tag = which < VOICE_LANG_TAGS.length ? VOICE_LANG_TAGS[which] : "system";
              prefs().edit().putString(PREF_VOICE_LANG, tag).apply();
              updateLanguageButton(button);
              d.dismiss();
            })
        .setNegativeButton(R.string.voice_cancel, null)
        .show();
  }

  /**
   * Lets the user pick which robot the voice commands drive: this phone's own Bluetooth/USB
   * link, or a robot another phone shares over WiFi (listed by phone name). Opened by tapping
   * the connection pill; the pick is remembered for the next launch.
   */
  private void showRobotPicker() {
    if (relayClient == null) return;
    List<String> hosts = relayClient.hostNames();
    boolean hasLocal = vehicle != null;
    if (!hasLocal && hosts.isEmpty()) {
      new androidx.appcompat.app.AlertDialog.Builder(requireContext())
          .setTitle(R.string.voice_robot_picker_title)
          .setMessage(R.string.voice_robot_none_found)
          .setPositiveButton(android.R.string.ok, null)
          .show();
      return;
    }
    List<String> labels = new ArrayList<>();
    if (hasLocal) {
      labels.add(getString(R.string.voice_robot_this_phone, vehicle.getConnectionType()));
    }
    for (String host : hosts) {
      labels.add(getString(R.string.voice_robot_wifi_item, host));
    }
    // Row 0 is this phone's link when present; rows above it follow hosts() order.
    int checked = -1;
    if (relayClient.isConnected()) {
      int hostIndex = hosts.indexOf(relayClient.hostName());
      checked = hostIndex >= 0 ? hostIndex + (hasLocal ? 1 : 0) : -1;
    } else if (hasLocal) {
      checked = 0;
    }
    androidx.appcompat.app.AlertDialog.Builder picker =
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.voice_robot_picker_title);
    picker
        .setSingleChoiceItems(
            labels.toArray(new String[0]),
            checked,
            (d, which) -> {
              if (hasLocal && which == 0) {
                relayClient.chooseLocal();
              } else {
                relayClient.chooseHost(hosts.get(which - (hasLocal ? 1 : 0)));
              }
              updateConnectionText();
              d.dismiss();
            })
        .setNegativeButton(R.string.voice_cancel, null)
        .show();
  }

  private void updateLanguageButton(android.widget.Button button) {
    String current = prefs().getString(PREF_VOICE_LANG, DEFAULT_VOICE_LANG);
    String name = getString(R.string.voice_language_system);
    for (int i = 0; i < VOICE_LANG_TAGS.length; i++) {
      if (VOICE_LANG_TAGS[i].equals(current)) {
        name = VOICE_LANG_NAMES[i];
        break;
      }
    }
    button.setText(getString(R.string.voice_language_title) + ": " + name);
  }

  private EditText field(String text) {
    EditText edit = new EditText(requireContext());
    edit.setText(text);
    edit.setSingleLine(true);
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    edit.setLayoutParams(params);
    return edit;
  }

  @Override
  public void onDestroyView() {
    org.openbot.app.robot.voice.relay.InternetRelayHost.get().setListener(null);
    org.openbot.app.robot.voice.relay.InternetRelayHost.get().stop();
    faceHandler.removeCallbacks(faceBack);
    utteranceHandler.removeCallbacksAndMessages(null);
    thinkingHandler.removeCallbacksAndMessages(null);
    levelHandler.removeCallbacksAndMessages(null);
    stopFaceBob();
    shownFaceEmoji = null; // the recreated face view starts from the layout default
    faceSwapRunning = false;
    driveHandler.removeCallbacks(driveTick);
    stopMicCapture();
    if (opusPlayer != null) {
      opusPlayer.release();
      opusPlayer = null;
    }
    opusCodec = null;
    waveformView = null;
    if (pairingActivator != null) {
      pairingActivator.cancel();
      pairingActivator = null;
    }
    if (session != null) {
      session.disconnect(); // intentional: no reconnect after the screen closes
      session = null;
    }
    if (vehicle != null) {
      vehicle.setControl(0f, 0f);
    }
    photoExplainer.markStopped();
    binding = null;
    VoiceRelayServer.get().setMicSink(null);
    VoiceRelayServer.get().setProSource(null);
    // The relay server keeps sharing while the app is open (see MainActivity); only the
    // microphone sink follows this page.
    if (relayClient != null) {
      relayClient.stop();
      relayClient = null;
    }
    super.onDestroyView();
  }
}
