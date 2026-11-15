package org.openbot.app.robot.vision;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageProxy;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;
import androidx.navigation.Navigation;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.NotNull;
import org.openbot.app.robot.R;
import org.openbot.app.robot.common.CameraFragment;
import org.openbot.app.robot.databinding.FragmentVoiceGrabBinding;
import org.openbot.app.robot.env.BorderedText;
import org.openbot.app.robot.env.BotToControllerEventBus;
import org.openbot.app.robot.env.ImageUtils;
import org.openbot.app.robot.sensorlink.RemoteSettings;
import org.openbot.app.robot.tflite.Detector;
import org.openbot.app.robot.tflite.Model;
import org.openbot.app.robot.utils.FileUtils;
import org.openbot.app.robot.tflite.Network;
import org.openbot.app.robot.tracking.MultiBoxTracker;
import org.openbot.app.robot.utils.CameraUtils;
import org.openbot.app.robot.utils.ConnectionUtils;
import org.openbot.app.robot.utils.Constants;
import org.openbot.app.robot.utils.Enums;
import org.openbot.app.robot.utils.MovingAverage;
import org.openbot.app.robot.utils.PermissionUtils;
import org.openbot.app.robot.vehicle.Control;
import org.openbot.app.robot.vehicle.Vehicle;
import org.openbot.app.robot.voice.OpusCodec;
import org.openbot.app.robot.voice.OpusPlayer;
import org.openbot.app.robot.voice.PhotoExplainer;
import org.openbot.app.robot.voice.RobotFaceView;
import org.openbot.app.robot.voice.VoiceCommand;
import org.openbot.app.robot.voice.VoiceInterpreter;
import org.openbot.app.robot.voice.XiaozhiActivator;
import org.openbot.app.robot.voice.XiaozhiClient;
import org.openbot.app.robot.voice.XiaozhiSession;
import timber.log.Timber;

/**
 * Voice Grab (AI tab): the Object Tracking page's template — {@code fragment_voice_grab.xml} is
 * {@code fragment_object_nav.xml} plus a voice/gripper row — with talking layered on. The page
 * behaves exactly like Object Tracking (same bottom sheet: model, object, confidence, device,
 * threads, auto switch, dynamic speed; {@link MultiBoxTracker} draws and steers) and adds voice
 * (English or Vietnamese).
 *
 * <p>Four fetch commands, and the object to recognize always comes from the words, never from the
 * "Object" spinner (that spinner stays the template's tracker target while no mission runs):
 *
 * <ul>
 *   <li>"pick up the cup" → find, centre, stop by sonar, grip, carry to the <b>person</b>.
 *   <li>"bring the cup to the table" → … grip, carry to the <b>new place</b> (the landmark said).
 *   <li>"find the ball" → … grip, carry to the <b>old place</b> (where the robot was asked).
 *   <li>"put the cup next to the bottle" → … grip, carry next to the <b>other object</b>.
 * </ul>
 *
 * <p>Each ends with "What should I do next?". Also "put it down", "follow me", "stop", "hello",
 * "thank you" and the plain drive words ({@link VoiceInterpreter}). While no voice mission is
 * running the template's {@link MultiBoxTracker#updateTarget()} drives, as on Object Tracking. A
 * mission hands the wheels to {@link VoiceGrabBrain} (tested in a simulation) which uses the front
 * sonar and the gripper; words: {@link VoiceGrabParser}. Mirrors VoiceGrabController on iOS.
 */
public class VoiceGrabFragment extends CameraFragment {

  private static final float TEXT_SIZE_DIP = 10;
  private static final String PREFS = "voice_grab";
  /** Claw words the fetch parser does not know ("grab"/"hold" are already its pick words). */
  private static final String[] GRIP_WORDS = {"grip", "grip it", "close the claw", "close claw", "kep"};
  private static final long APPROACH_RESEND_MS = 2000;
  private static final int APPROACH_MS = 3000;
  /** The "I see a …, what should I do?" prompt: at most one every 20 s (any object). */
  private static final long SIGHTING_PROMPT_MS = 20_000;
  /** …and the same object at most once a minute, so it never nags. */
  private static final long SIGHTING_SAME_CLASS_MS = 60_000;
  /** Frames the wanted object must be seen in a row (with colour) before the grab starts. */
  private static final int CONFIRM_FRAMES = 3;
  /** …each at least this confident (the spec's "about 60%"). */
  private static final float CONFIRM_CONFIDENCE = 0.6f;

  private FragmentVoiceGrabBinding binding;

  // ---- the Object Tracking template (ObjectNavFragment) ---------------------------------------
  private Handler handler;
  private HandlerThread handlerThread;

  private boolean computingNetwork = false;
  private float MINIMUM_CONFIDENCE_TF_OD_API = 0.5f;

  private Detector detector;

  private boolean mirrorControl;
  private Matrix frameToCropTransform;
  private Bitmap croppedBitmap;
  private int sensorOrientation;
  private Matrix cropToFrameTransform;

  private MultiBoxTracker tracker;

  private Model model;
  private Network.Device device = Network.Device.CPU;
  private int numThreads = -1;
  /** The "Object" spinner's pick: the template tracker's target while no voice mission runs. */
  private String classType = "cup";

  private long lastProcessingTimeMs = -1;
  private long frameNum = 0;

  private final boolean isBenchmarkMode = false;
  private long processedFrames = 0;
  private final int movingAvgSize = 100;
  private MovingAverage movingAvgProcessingTimeMs = new MovingAverage(movingAvgSize);

  // ---- voice, gripper, sonar ------------------------------------------------------------------
  private final Handler main = new Handler(Looper.getMainLooper());
  private final VoiceGrabBrain brain = new VoiceGrabBrain();
  private long lastApproachMs;
  /** Consecutive frames the wanted object matched (colour included); guarded by {@code brain}. */
  private int objConfirmStreak;
  /** Class titles the last frame saw; "what do you see?" reads these. Guarded by {@code brain}. */
  private final List<String> lastSeenTitles = new ArrayList<>();

  // The sonar-gated grab ("grip", ✊ and the AI's gripper grab): creep toward the object
  // and close the claw once the front sonar reads within 5 cm of it (the "5 cm rule").
  private boolean gripPickActive;
  private boolean gripPickSeenSonar;
  private boolean gripPickSpeak;
  private long gripPickStartMs;
  private static final float GRIP_PICK_SPEED = 0.2f; // slow creep toward the object
  private static final long GRIP_PICK_TICK_MS = 150;
  private static final long GRIP_PICK_TIMEOUT_MS = 8000;
  private static final long GRIP_PICK_BLIND_MS = 2500;
  /** The "5 cm rule": the claw only closes once the front sonar reads within this of the object. */
  private static final float GRIP_AT_CM = 5f;
  /** Under this the object is already under the claw body: stop, never close. */
  private static final float GRIP_TOO_CLOSE_CM = 2f;

  private SpeechRecognizer recognizer;
  private TextToSpeech tts;
  private boolean ttsReady;
  private boolean listening; // the user wants the robot to listen
  private boolean speaking;
  private boolean vietnamese;
  private boolean reversed;

  // ---- Voice AI (Xiaozhi): photo descriptions and "what should I do with it?" prompts ------
  private volatile XiaozhiSession aiSession;
  private volatile boolean aiReady;
  private final PhotoExplainer photoExplainer = new PhotoExplainer();
  private OpusCodec aiCodec;
  private OpusPlayer aiPlayer;
  /** A photo job asked the camera loop for one frame (set on main, grabbed on the camera thread). */
  private final AtomicBoolean grabFrame = new AtomicBoolean();
  /** The photo job waiting for that frame; consumed on the main thread by {@link #runPhotoJob}. */
  private volatile String pendingPhotoQuestion;
  private volatile XiaozhiSession.PhotoCallback pendingPhotoCallback;
  private volatile boolean photoJobActive;
  private long lastSightingPromptMs;
  private String lastSightingClass = "";
  private long lastSightingClassMs;

  // Plain drive words: timed moves with auto-stop and a resend loop (BLE drops packets).
  private float moveLeft;
  private float moveRight;
  private long moveEndsAtMs;

  // Views over the camera.
  private RobotFaceView face;
  private RobotFaceView.Emotion lastEmotion = RobotFaceView.Emotion.NEUTRAL;
  private TextView bubble;
  private Button micButton;
  private Button langButton;

  private final ActivityResultLauncher<String> micPermission =
      registerForActivityResult(
          new ActivityResultContracts.RequestPermission(),
          granted -> {
            if (granted) setListening(true);
            else
              Toast.makeText(
                      requireContext(), R.string.voice_mic_permission_denied, Toast.LENGTH_LONG)
                  .show();
          });

  // ------------------------------------------------------------------ UI

  @Override
  public View onCreateView(
      @NotNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentVoiceGrabBinding.inflate(inflater, container, false);
    return inflateFragment(binding, inflater, container);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    BotToControllerEventBus.emitEvent(
        ConnectionUtils.createStatus(
            "FRAGMENT_TYPE", Enums.FragmentType.OBJECTDETECTION.getFragment()));
    binding.confidenceValue.setText((int) (MINIMUM_CONFIDENCE_TF_OD_API * 100) + "%");
    binding.plusConfidence.setOnClickListener(
        v -> {
          String trimConfValue = binding.confidenceValue.getText().toString().trim();
          int confValue = Integer.parseInt(trimConfValue.substring(0, trimConfValue.length() - 1));
          if (confValue >= 95) return;
          confValue += 5;
          binding.confidenceValue.setText(confValue + "%");
          MINIMUM_CONFIDENCE_TF_OD_API = confValue / 100f;
        });
    binding.minusConfidence.setOnClickListener(
        v -> {
          String trimConfValue = binding.confidenceValue.getText().toString().trim();
          int confValue = Integer.parseInt(trimConfValue.substring(0, trimConfValue.length() - 1));
          if (confValue <= 5) return;
          confValue -= 5;
          binding.confidenceValue.setText(confValue + "%");
          MINIMUM_CONFIDENCE_TF_OD_API = confValue / 100f;
        });

    binding.controllerContainer.speedInfo.setText(getString(R.string.speedInfo, "---,---"));

    if (vehicle.getConnectionType().equals("USB")) {
      binding.usbToggle.setVisibility(View.VISIBLE);
      binding.bleToggle.setVisibility(View.GONE);
    } else if (vehicle.getConnectionType().equals("Bluetooth")) {
      binding.bleToggle.setVisibility(View.VISIBLE);
      binding.usbToggle.setVisibility(View.GONE);
    }

    SharedPreferences p = requireContext().getSharedPreferences(PREFS, 0);
    vietnamese = p.getBoolean("vi", Locale.getDefault().getLanguage().equals("vi"));
    reversed = p.getBoolean("flip", false);

    classType = preferencesManager.getObjectType();
    if (classType == null || classType.isEmpty()) classType = "cup";
    binding.classType.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          @Override
          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            classType = parent.getItemAtPosition(position).toString();
            preferencesManager.setObjectType(classType);
            setStatus(getString(R.string.grab_idle, classType));
          }

          @Override
          public void onNothingSelected(AdapterView<?> parent) {}
        });
    initDeviceSpinner(binding.deviceSpinner, preferencesManager.getDevice());
    setNumThreads(preferencesManager.getNumThreads());
    binding.threads.setText(String.valueOf(getNumThreads()));

    binding.cameraToggle.setOnClickListener(v -> toggleCamera());

    binding.mirrorControl.setOnClickListener(v -> mirrorControl());

    List<String> models =
        getModelNames(f -> f.type.equals(Model.TYPE.DETECTOR) && f.pathType != Model.PATH_TYPE.URL);
    initModelSpinner(binding.modelSpinner, models, preferencesManager.getVoiceGrabModel());

    setAnalyserResolution(Enums.Preview.HD.getValue());
    binding.deviceSpinner.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          @Override
          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            String selected = parent.getItemAtPosition(position).toString();
            setDevice(Network.Device.valueOf(selected.toUpperCase()));
          }

          @Override
          public void onNothingSelected(AdapterView<?> parent) {}
        });

    binding.plus.setOnClickListener(
        v -> {
          String threads = binding.threads.getText().toString().trim();
          int numThreads = Integer.parseInt(threads);
          if (numThreads >= 9) return;
          setNumThreads(++numThreads);
          binding.threads.setText(String.valueOf(numThreads));
        });
    binding.minus.setOnClickListener(
        v -> {
          String threads = binding.threads.getText().toString().trim();
          int numThreads = Integer.parseInt(threads);
          if (numThreads == 1) return;
          setNumThreads(--numThreads);
          binding.threads.setText(String.valueOf(numThreads));
        });
    BottomSheetBehavior.from(binding.aiBottomSheet).setState(BottomSheetBehavior.STATE_EXPANDED);

    mViewModel
        .getUsbStatus()
        .observe(getViewLifecycleOwner(), status -> binding.usbToggle.setChecked(status));

    binding.usbToggle.setChecked(vehicle.isUsbConnected());
    binding.bleToggle.setChecked(vehicle.bleConnected());

    binding.usbToggle.setOnClickListener(
        v -> {
          binding.usbToggle.setChecked(vehicle.isUsbConnected());
          Navigation.findNavController(requireView()).navigate(R.id.open_usb_fragment);
        });
    binding.bleToggle.setOnClickListener(
        v -> {
          binding.bleToggle.setChecked(vehicle.bleConnected());
          Navigation.findNavController(requireView()).navigate(R.id.open_bluetooth_fragment);
        });

    setSpeedMode(Enums.SpeedMode.getByID(preferencesManager.getSpeedMode()));
    setControlMode(Enums.ControlMode.getByID(preferencesManager.getControlMode()));
    setDriveMode(Enums.DriveMode.getByID(preferencesManager.getDriveMode()));

    binding.controllerContainer.controlMode.setOnClickListener(
        v -> {
          Enums.ControlMode controlMode =
              Enums.ControlMode.getByID(preferencesManager.getControlMode());
          if (controlMode != null) setControlMode(Enums.switchControlMode(controlMode));
        });
    binding.controllerContainer.driveMode.setOnClickListener(
        v -> setDriveMode(Enums.switchDriveMode(vehicle.getDriveMode())));

    binding.controllerContainer.speedMode.setOnClickListener(
        v ->
            setSpeedMode(
                Enums.toggleSpeed(
                    Enums.Direction.CYCLIC.getValue(),
                    Enums.SpeedMode.getByID(preferencesManager.getSpeedMode()))));

    binding.autoSwitch.setOnClickListener(v -> setAutoMode(binding.autoSwitch.isChecked()));
    setupObjectAvoidanceControls(
        binding.objectAvoidance.avoidToggle, binding.objectAvoidance.avoidInfo);
    setupSensorLinkControls(binding.sensorLink.getRoot());
    binding.dynamicSpeed.setChecked(preferencesManager.getDynamicSpeed());
    binding.dynamicSpeed.setOnClickListener(
        v -> {
          preferencesManager.setDynamicSpeed(binding.dynamicSpeed.isChecked());
          if (tracker != null) tracker.setDynamicSpeed(binding.dynamicSpeed.isChecked());
        });

    // ---- the voice/gripper row (the template does not have these)
    micButton = binding.micBtn;
    langButton = binding.langBtn;
    langButton.setText(vietnamese ? "VI" : "EN");
    micButton.setOnClickListener(v -> toggleListening());
    langButton.setOnClickListener(v -> toggleLanguage());
    binding.gripBtn.setOnClickListener(v -> creepAndGrip(false));
    binding.releaseBtn.setOnClickListener(v -> manualGrip(false));
    binding.reverseBtn.setOnClickListener(v -> toggleReverse());
    binding.photoBtn.setOnClickListener(v -> takePictureAndDescribe());
    addOverlayViews();

    // Speech: Android's recognizer + text-to-speech (no Xiaozhi needed).
    tts =
        new TextToSpeech(
            requireContext(),
            status -> {
              ttsReady = status == TextToSpeech.SUCCESS;
              if (ttsReady) {
                tts.setLanguage(locale());
                tts.setOnUtteranceProgressListener(
                    new UtteranceProgressListener() {
                      @Override
                      public void onStart(String id) {
                        main.post(() -> setSpeaking(true));
                      }

                      @Override
                      public void onDone(String id) {
                        main.post(() -> setSpeaking(false));
                      }

                      @Override
                      public void onError(String id) {
                        main.post(() -> setSpeaking(false));
                      }
                    });
              }
            });
    if (SpeechRecognizer.isRecognitionAvailable(requireContext())) {
      recognizer = SpeechRecognizer.createSpeechRecognizer(requireContext());
      recognizer.setRecognitionListener(recognitionListener);
    }
    // Start listening at once (asks for the microphone the first time).
    main.postDelayed(this::toggleListening, 600);
    setStatus(getString(R.string.vgrab_ready));
  }

  private boolean autoModeOn() {
    return binding != null && binding.autoSwitch.isChecked();
  }

  private void mirrorControl() {
    mirrorControl = !mirrorControl;
  }

  // ------------------------------------------------------------------ template (ObjectNav)

  private void updateCropImageInfo() {
    frameToCropTransform = null;

    sensorOrientation = 90 - ImageUtils.getScreenOrientation(requireActivity());

    final float textSizePx =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
    BorderedText borderedText = new BorderedText(textSizePx);
    borderedText.setTypeface(Typeface.MONOSPACE);

    tracker = new MultiBoxTracker(requireContext());
    tracker.setDynamicSpeed(preferencesManager.getDynamicSpeed());

    Timber.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

    recreateNetwork(getModel(), getDevice(), getNumThreads());
    if (detector == null) {
      Timber.e("No network on preview!");
      return;
    }

    binding.trackingOverlay.addCallback(
        canvas -> {
          if (tracker != null) {
            tracker.draw(canvas);
          }
        });
    tracker.setFrameConfiguration(
        getMaxAnalyseImageSize().getWidth(),
        getMaxAnalyseImageSize().getHeight(),
        sensorOrientation);
  }

  protected void onInferenceConfigurationChanged() {
    computingNetwork = false;
    if (croppedBitmap == null) {
      // Defer creation until we're getting camera frames.
      return;
    }
    final Network.Device device = getDevice();
    final Model model = getModel();
    final int numThreads = getNumThreads();
    runInBackground(() -> recreateNetwork(model, device, numThreads));
  }

  private void recreateNetwork(Model model, Network.Device device, int numThreads) {
    resetFpsUi();
    if (model == null) return;
    if (tracker != null) tracker.clearTrackedObjects();
    if (detector != null) {
      Timber.d("Closing detector.");
      detector.close();
      detector = null;
    }

    try {
      Timber.d("Creating detector (model=%s, device=%s, numThreads=%d)", model, device, numThreads);
      detector = Detector.create(requireActivity(), model, device, numThreads);

      assert detector != null;
      croppedBitmap =
          Bitmap.createBitmap(
              detector.getImageSizeX(), detector.getImageSizeY(), Bitmap.Config.ARGB_8888);
      frameToCropTransform =
          ImageUtils.getTransformationMatrix(
              getMaxAnalyseImageSize().getWidth(),
              getMaxAnalyseImageSize().getHeight(),
              croppedBitmap.getWidth(),
              croppedBitmap.getHeight(),
              sensorOrientation,
              detector.getCropRect(),
              detector.getMaintainAspect());

      cropToFrameTransform = new Matrix();
      frameToCropTransform.invert(cropToFrameTransform);

      requireActivity()
          .runOnUiThread(
              () -> {
                ArrayAdapter<String> adapter =
                    new ArrayAdapter<>(getContext(), R.layout.spinner_item, detector.getLabels());
                adapter.setDropDownViewResource(android.R.layout.simple_dropdown_item_1line);
                binding.classType.setAdapter(adapter);
                int selection = detector.getLabels().indexOf(classType);
                binding.classType.setSelection(Math.max(0, selection));
                binding.inputResolution.setText(
                    String.format(
                        Locale.getDefault(),
                        "%dx%d",
                        detector.getImageSizeX(),
                        detector.getImageSizeY()));
              });

    } catch (IllegalArgumentException | IOException e) {
      String msg = "Failed to create network.";
      Timber.e(e, msg);
      requireActivity()
          .runOnUiThread(
              () ->
                  Toast.makeText(
                          requireContext().getApplicationContext(),
                          e.getMessage(),
                          Toast.LENGTH_LONG)
                      .show());
    }
  }

  @Override
  public synchronized void onResume() {
    croppedBitmap = null;
    tracker = null;
    handlerThread = new HandlerThread("inference");
    handlerThread.start();
    handler = new Handler(handlerThread.getLooper());
    binding.bleToggle.setChecked(vehicle.bleConnected());
    // Reload the model catalog so models added or downloaded in Model Management
    // are offered here too.
    List<Model> freshList = FileUtils.loadConfigJSONFromAsset(requireActivity());
    if (freshList != null) {
      masterList = freshList;
    }
    List<String> models =
        getModelNames(f -> f.type.equals(Model.TYPE.DETECTOR) && f.pathType != Model.PATH_TYPE.URL);
    initModelSpinner(binding.modelSpinner, models, preferencesManager.getVoiceGrabModel());
    connectVoiceAi();
    super.onResume();
    if (vehicle != null && vehicle.isConnected()) {
      vehicle.requestGripperState(); // ask the firmware for the claw state
    }
    refreshGripperState();
  }

  @Override
  public synchronized void onPause() {
    setListening(false);
    disconnectVoiceAi();
    releaseAiAudio();
    handlerThread.quitSafely();
    try {
      handlerThread.join();
      handlerThread = null;
      handler = null;
    } catch (final InterruptedException e) {
      e.printStackTrace();
    }
    super.onPause();
  }

  protected synchronized void runInBackground(final Runnable r) {
    if (handler != null) {
      handler.post(r);
    }
  }

  @Override
  protected void processUSBData(String data) {
    // The claw state is the one line this page reads: the digital firmware answers
    // g0/g1 after every move (the base observer already stored it on the vehicle).
    if (!data.isEmpty() && data.charAt(0) == 'g') {
      refreshGripperState();
    }
    binding.controllerContainer.speedInfo.setText(
        getString(
            R.string.speedInfo,
            String.format(
                Locale.US, "%3.0f,%3.0f", vehicle.getLeftWheelRpm(), vehicle.getRightWheelRpm())));
    refreshObjectAvoidanceStatus(binding.objectAvoidance.avoidInfo);
  }

  /** Mirror the firmware's claw report on the state line under the ✊/✋ buttons. */
  private void refreshGripperState() {
    if (binding == null || vehicle == null) {
      return;
    }
    switch (vehicle.getGripperState()) {
      case Vehicle.GRIP_OPEN:
        binding.gripperStateText.setText(R.string.hardware_state_open);
        break;
      case Vehicle.GRIP_CLOSED:
        binding.gripperStateText.setText(R.string.hardware_state_closed);
        break;
      default:
        binding.gripperStateText.setText(R.string.hardware_state_unknown);
        break;
    }
  }

  @Override
  protected void processControllerKeyData(String commandType) {
    switch (commandType) {
      case Constants.CMD_DRIVE:
        binding.controllerContainer.controlInfo.setText(
            String.format(Locale.US, "%.0f,%.0f", vehicle.getLeftSpeed(), vehicle.getRightSpeed()));
        break;
    }
  }

  @Override
  protected void handleNetworkCommand() {
    setAutoModeWithAudio(!autoModeOn());
  }

  @Override
  protected boolean isNetworkModeEnabled() {
    return autoModeOn();
  }

  private void setAutoModeWithAudio(boolean b) {
    setAutoMode(b);

    if (b) audioPlayer.play(voice, "network_enabled.mp3");
    else audioPlayer.playDriveMode(voice, vehicle.getDriveMode());
  }

  /** The template's auto switch, plus this page's sonar stream and voice-brain reset. */
  private void setAutoMode(boolean enabled) {
    stopManualMove(); // one driver at a time
    binding.autoSwitch.setChecked(enabled);
    emitNetworkStatus(enabled);

    binding.controllerContainer.controlMode.setEnabled(!enabled);
    binding.controllerContainer.driveMode.setEnabled(!enabled);
    binding.controllerContainer.speedMode.setEnabled(!enabled);

    binding.controllerContainer.controlMode.setAlpha(enabled ? 0.5f : 1f);
    binding.controllerContainer.driveMode.setAlpha(enabled ? 0.5f : 1f);
    binding.controllerContainer.speedMode.setAlpha(enabled ? 0.5f : 1f);

    resetFpsUi();
    if (enabled) {
      vehicle.setSonarFrequency(100); // live distance for reaching, stopping, following
    } else {
      if (handler != null) {
        handler.postDelayed(() -> vehicle.setControl(0, 0), Math.max(lastProcessingTimeMs, 50));
      } else {
        vehicle.setControl(0, 0);
      }
      vehicle.sendApproach(0);
      vehicle.setSonarFrequency(0);
      synchronized (brain) {
        brain.command(VoiceGrabParser.parse("stop"), SystemClock.uptimeMillis());
      }
    }
  }

  // ------------------------------------------------------------------ camera thread

  @Override
  protected void processFrame(Bitmap bitmap, ImageProxy image) {
    if (tracker == null) updateCropImageInfo();

    ++frameNum;
    // The detector runs even with the auto switch off: the page always tracks, always
    // draws, and always asks the Voice AI when it sees something — only the follow-drive
    // (and the mission drive) still need the auto switch.
    if (binding != null) {
      // If network is busy, return.
      if (computingNetwork) {
        return;
      }

      computingNetwork = true;
      runInBackground(
          () -> {
            if (grabFrame.compareAndSet(true, false)) {
              // One frame is enough for the photo tool / the 📸 button.
              final byte[] jpeg = jpegOf(bitmap);
              main.post(() -> runPhotoJob(jpeg));
            }
            final Canvas canvas = new Canvas(croppedBitmap);
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
              canvas.drawBitmap(
                  CameraUtils.flipBitmapHorizontal(bitmap), frameToCropTransform, null);
            } else {
              canvas.drawBitmap(bitmap, frameToCropTransform, null);
            }

            if (detector != null) {
              final long startTime = SystemClock.elapsedRealtime();
              // Every class, not just the target: the voice brain also needs the person
              // ("bring it to me", "follow me").
              final List<Detector.Recognition> results =
                  detector.recognizeImage(croppedBitmap, null);
              lastProcessingTimeMs = SystemClock.elapsedRealtime() - startTime;

              String wanted;
              String wantedDest;
              boolean wantsPerson;
              boolean wantsDest;
              boolean mission;
              String color;
              synchronized (brain) {
                // The words' object and destination — never the "Object" spinner.
                wanted = brain.object();
                wantsPerson = brain.wantsPerson();
                wantsDest = brain.wantsDest();
                wantedDest = brain.dest();
                color = brain.color();
                mission = brain.mode() != VoiceGrabBrain.Mode.IDLE;
              }

              // The grab candidate: the wanted object, ≥ ~60% confident, in the asked-for colour.
              // Its box is still in crop space (exactly what the detector saw), so the colour
              // sample runs against croppedBitmap before the frame mapping below.
              Detector.Recognition objCand = null;
              if (mission && !wanted.isEmpty()) {
                for (final Detector.Recognition result : results) {
                  final RectF loc = result.getLocation();
                  if (loc == null || result.getConfidence() < CONFIRM_CONFIDENCE) continue;
                  if (!wanted.equals(result.getTitle())) continue;
                  if (objCand == null || result.getConfidence() > objCand.getConfidence())
                    objCand = result;
                }
                if (objCand != null
                    && !colorMatches(color, croppedBitmap, objCand.getLocation())) {
                  objCand = null;
                }
              }
              // Three matching frames in a row before the brain is told the object is there.
              final boolean confirmed;
              synchronized (brain) {
                if (mission) {
                  if (objCand != null) objConfirmStreak++;
                  else objConfirmStreak = 0;
                  confirmed = objConfirmStreak >= CONFIRM_FRAMES;
                } else {
                  confirmed = false;
                }
              }

              Detector.Recognition bestObject = confirmed ? objCand : null;
              Detector.Recognition bestPerson = null;
              Detector.Recognition bestDest = null;
              Detector.Recognition bestSeen = null;
              final List<String> seenTitles = new ArrayList<>();
              final List<Detector.Recognition> mappedRecognitions = new LinkedList<>();
              for (final Detector.Recognition result : results) {
                final RectF location = result.getLocation();
                if (location == null
                    || result.getConfidence() < MINIMUM_CONFIDENCE_TF_OD_API) continue;
                String title = result.getTitle();
                if (!seenTitles.contains(title)) seenTitles.add(title);
                if (!mission
                    && !"person".equals(title)
                    && (bestSeen == null
                        || result.getConfidence() > bestSeen.getConfidence())) {
                  // The sighting that may prompt "what should I do with it?".
                  bestSeen = result;
                }
                if (mission) {
                  if (wantsPerson
                      && "person".equals(title)
                      && (bestPerson == null
                          || result.getConfidence() > bestPerson.getConfidence()))
                    bestPerson = result;
                  if (wantsDest
                      && wantedDest.equals(title)
                      && (bestDest == null
                          || result.getConfidence() > bestDest.getConfidence()))
                    bestDest = result;
                }
                boolean draw;
                if (mission) {
                  // The template's spinner target is not drawn during a mission: only the
                  // confirmed object, the person and the destination are.
                  draw =
                      (confirmed && wanted.equals(title))
                          || (wantsPerson && "person".equals(title))
                          || (wantsDest && wantedDest.equals(title));
                } else {
                  draw = classType.equals(title);
                }
                if (draw) {
                  cropToFrameTransform.mapRect(location);
                  result.setLocation(location);
                  mappedRecognitions.add(result);
                }
              }
              synchronized (brain) {
                lastSeenTitles.clear();
                lastSeenTitles.addAll(seenTitles);
              }
              // objCand is one of `results`, mapped in-loop above when drawn, so measure()
              // sees the crop→frame box it expects.

              tracker.trackResults(mappedRecognitions, frameNum);
              synchronized (brain) {
                if (mission) {
                  float[] o = measure(bestObject);
                  float[] p = wantsPerson ? measure(bestPerson) : null;
                  float[] d = wantsDest ? measure(bestDest) : null;
                  VoiceGrabBrain.Out out =
                      brain.step(
                          SystemClock.uptimeMillis(),
                          o != null,
                          o != null ? o[0] : 0,
                          o != null ? o[1] : 0,
                          p != null,
                          p != null ? p[0] : 0,
                          p != null ? p[1] : 0,
                          d != null,
                          d != null ? d[0] : 0,
                          d != null ? d[1] : 0,
                          vehicle.getSonarDistanceCm(),
                          vehicle.getGripperState());
                  apply(out, true);
                } else if (autoModeOn()) {
                  // No voice mission: plain Object Tracking behaviour (the template).
                  Control drive = tracker.updateTarget();
                  if (mirrorControl) drive = drive.mirror();
                  handleDriveCommand(drive);
                }
              }
              if (!mission) maybeAskAboutSighting(bestSeen);
              binding.trackingOverlay.postInvalidate();
            }

            computingNetwork = false;
          });
      if (lastProcessingTimeMs > 0) {
        if (isBenchmarkMode) {
          double avgProcessingTimeMs = movingAvgProcessingTimeMs.next(lastProcessingTimeMs);
          processedFrames += 1;
          if (processedFrames >= movingAvgSize) updateFpsUi(avgProcessingTimeMs);
        } else updateFpsUi(lastProcessingTimeMs);
      }
    }
  }

  /**
   * Offset (-1 left … +1 right) and height (0…1) of a detection in analysis-frame coordinates
   * (the same axis handling as {@link MultiBoxTracker#updateTarget()}).
   */
  @Nullable
  private float[] measure(@Nullable Detector.Recognition r) {
    if (r == null) return null;
    RectF box = new RectF(r.getLocation()); // already crop→frame mapped
    boolean rotated = sensorOrientation % 180 == 90;
    float frameW = getMaxAnalyseImageSize().getWidth();
    float frameH = getMaxAnalyseImageSize().getHeight();
    float imgW = rotated ? frameH : frameW;
    float imgH = rotated ? frameW : frameH;
    float centerX = rotated ? box.centerY() : box.centerX();
    float offset = 2f * centerX / imgW - 1f;
    float height = (rotated ? box.width() : box.height()) / imgH;
    return new float[] {offset, height};
  }

  /**
   * Does the box mostly show the asked-for colour? Samples a 5×5 grid inside the middle 50% of
   * the box ({@code box} in {@code bitmap} coordinates — the detector's crop space, unmapped).
   * A nil/empty ask passes; a mostly-grey box fails a coloured ask.
   */
  private static boolean colorMatches(String color, Bitmap bitmap, RectF box) {
    if (color == null || color.isEmpty() || bitmap == null || box == null) return true;
    float left = box.left + box.width() * 0.25f;
    float top = box.top + box.height() * 0.25f;
    float right = box.right - box.width() * 0.25f;
    float bottom = box.bottom - box.height() * 0.25f;
    if (right - left < 4 || bottom - top < 4) return false; // too small to sample
    int samples = 0;
    int coloured = 0;
    int matches = 0;
    float[] hsv = new float[3];
    for (int gy = 0; gy < 5; gy++) {
      for (int gx = 0; gx < 5; gx++) {
        int px = (int) (left + (right - left) * (gx + 0.5f) / 5f);
        int py = (int) (top + (bottom - top) * (gy + 0.5f) / 5f);
        if (px < 0 || py < 0 || px >= bitmap.getWidth() || py >= bitmap.getHeight()) continue;
        samples++;
        Color.colorToHSV(bitmap.getPixel(px, py), hsv);
        if (hsv[1] < 0.3f) continue; // grey / white / black: not a colour vote
        coloured++;
        if (hueMatches(color, hsv[0])) matches++;
      }
    }
    if (samples == 0 || coloured * 2 < samples) return false; // mostly grey → mismatch
    return matches * 2 >= coloured; // most coloured pixels are the asked-for hue
  }

  /** Hue band per canonical colour name (red wraps through 0°). */
  private static boolean hueMatches(String want, float hue) {
    switch (want) {
      case "red":
        return hue < 15 || hue >= 345;
      case "blue":
        return hue >= 190 && hue < 250;
      case "green":
        return hue >= 80 && hue < 160;
      case "yellow":
        return hue >= 40 && hue < 70;
      default:
        return true;
    }
  }

  private void updateFpsUi(double processingTimeMs) {
    requireActivity()
        .runOnUiThread(
            () ->
                binding.inferenceInfo.setText(
                    String.format(Locale.US, "%.1f fps", 1000.f / processingTimeMs)));
  }

  private void resetFpsUi() {
    processedFrames = 0;
    movingAvgProcessingTimeMs = new MovingAverage(movingAvgSize);
    requireActivity().runOnUiThread(() -> binding.inferenceInfo.setText(R.string.time_fps));
  }

  /** The template's drive path (ObjectNavFragment.handleDriveCommand). */
  protected void handleDriveCommand(Control control) {
    vehicle.setControl(control);
    float left = vehicle.getLeftSpeed();
    float right = vehicle.getRightSpeed();
    requireActivity()
        .runOnUiThread(
            () ->
                binding.controllerContainer.controlInfo.setText(
                    String.format(Locale.US, "%.0f,%.0f", left, right)));
  }

  protected Model getModel() {
    return model;
  }

  @Override
  protected void setModel(Model model) {
    // Compare by name: the master list is reloaded when coming back from Model
    // Management, which builds new Model instances for the same models.
    if (this.model == null || !this.model.name.equals(model.name)) {
      Timber.d("Updating  model: %s", model);
      this.model = model;
      preferencesManager.setVoiceGrabModel(model.name);
      onInferenceConfigurationChanged();
    }
  }

  protected Network.Device getDevice() {
    return device;
  }

  private void setDevice(Network.Device device) {
    if (this.device != device) {
      Timber.d("Updating  device: %s", device);
      this.device = device;
      final boolean threadsEnabled = device == Network.Device.CPU;
      binding.plus.setEnabled(threadsEnabled);
      binding.minus.setEnabled(threadsEnabled);
      binding.threads.setText(threadsEnabled ? String.valueOf(numThreads) : "N/A");
      if (threadsEnabled) binding.threads.setTextColor(Color.BLACK);
      else binding.threads.setTextColor(Color.GRAY);
      preferencesManager.setDevice(device.ordinal());
      onInferenceConfigurationChanged();
    }
  }

  protected int getNumThreads() {
    return numThreads;
  }

  private void setNumThreads(int numThreads) {
    if (this.numThreads != numThreads) {
      Timber.d("Updating  numThreads: %s", numThreads);
      this.numThreads = numThreads;
      preferencesManager.setNumThreads(numThreads);
      onInferenceConfigurationChanged();
    }
  }

  // region Browser settings (Sensor link page)

  @Override
  protected String remoteScreenName() {
    return "Voice Grab";
  }

  @Override
  protected void onRemoteSpeedMode(Enums.SpeedMode mode) {
    setSpeedMode(mode);
  }

  @Override
  protected void addScreenSettings(java.util.List<RemoteSettings.Setting> out) {
    out.add(
        RemoteSettings.Setting.range(
            "tracking.confidence",
            "Voice Grab",
            "Detection confidence (%)",
            5,
            95,
            5,
            () -> Math.round(MINIMUM_CONFIDENCE_TF_OD_API * 100),
            v -> {
              int c = (int) Math.round((Double) v);
              MINIMUM_CONFIDENCE_TF_OD_API = c / 100f;
              if (binding != null) binding.confidenceValue.setText(c + "%");
            }));
    out.add(
        RemoteSettings.Setting.bool(
            "tracking.dynamicSpeed",
            "Voice Grab",
            "Slow down when the object is close",
            () -> preferencesManager.getDynamicSpeed(),
            v -> {
              boolean on = (Boolean) v;
              preferencesManager.setDynamicSpeed(on);
              if (tracker != null) tracker.setDynamicSpeed(on);
              if (binding != null) binding.dynamicSpeed.setChecked(on);
            }));
  }

  // endregion

  private void setSpeedMode(Enums.SpeedMode speedMode) {
    if (speedMode != null) {
      switch (speedMode) {
        case SLOW:
          binding.controllerContainer.speedMode.setImageResource(R.drawable.ic_speed_low);
          break;
        case NORMAL:
          binding.controllerContainer.speedMode.setImageResource(R.drawable.ic_speed_medium);
          break;
        case FAST:
          binding.controllerContainer.speedMode.setImageResource(R.drawable.ic_speed_high);
          break;
      }

      Timber.d("Updating  controlSpeed: %s", speedMode);
      preferencesManager.setSpeedMode(speedMode.getValue());
      vehicle.setSpeedMultiplier(speedMode.getValue());
    }
  }

  private void setControlMode(Enums.ControlMode controlMode) {
    if (controlMode != null) {
      switch (controlMode) {
        case GAMEPAD:
          binding.controllerContainer.controlMode.setImageResource(R.drawable.ic_controller);
          disconnectPhoneController();
          break;
        case PHONE:
          binding.controllerContainer.controlMode.setImageResource(R.drawable.ic_phone);
          if (!PermissionUtils.hasControllerPermissions(requireActivity()))
            requestPermissionLauncher.launch(Constants.PERMISSIONS_CONTROLLER);
          else connectPhoneController();
          break;
        case WEBSERVER:
          binding.controllerContainer.controlMode.setImageResource(R.drawable.ic_server);
          if (!PermissionUtils.hasControllerPermissions(requireActivity()))
            requestPermissionLauncher.launch(Constants.PERMISSIONS_CONTROLLER);
          else connectWebController();
          break;
      }
      Timber.d("Updating  controlMode: %s", controlMode);
      preferencesManager.setControlMode(controlMode.getValue());
    }
  }

  protected void setDriveMode(Enums.DriveMode driveMode) {
    if (driveMode != null) {
      switch (driveMode) {
        case DUAL:
          binding.controllerContainer.driveMode.setImageResource(R.drawable.ic_dual);
          break;
        case GAME:
          binding.controllerContainer.driveMode.setImageResource(R.drawable.ic_game);
          break;
        case JOYSTICK:
          binding.controllerContainer.driveMode.setImageResource(R.drawable.ic_joystick);
          break;
      }

      Timber.d("Updating  driveMode: %s", driveMode);
      vehicle.setDriveMode(driveMode);
      preferencesManager.setDriveMode(driveMode.getValue());
    }
  }

  private void connectPhoneController() {
    phoneController.connect(requireContext());
    Enums.DriveMode savedDriveMode = Enums.DriveMode.getByID(preferencesManager.getDriveMode());
    // Currently only dual drive mode supported
    setDriveMode(Enums.DriveMode.DUAL);
    binding.controllerContainer.driveMode.setAlpha(0.5f);
    binding.controllerContainer.driveMode.setEnabled(false);
    preferencesManager.setDriveMode(savedDriveMode.getValue());
  }

  private void connectWebController() {
    phoneController.connectWebServer();
    Enums.DriveMode savedDriveMode = Enums.DriveMode.getByID(preferencesManager.getDriveMode());
    // Currently only dual drive mode supported
    setDriveMode(Enums.DriveMode.GAME);
    binding.controllerContainer.driveMode.setAlpha(0.5f);
    binding.controllerContainer.driveMode.setEnabled(false);
    preferencesManager.setDriveMode(savedDriveMode.getValue());
  }

  private void disconnectPhoneController() {
    phoneController.disconnect();
    setDriveMode(Enums.DriveMode.getByID(preferencesManager.getDriveMode()));
    binding.controllerContainer.driveMode.setEnabled(true);
    binding.controllerContainer.driveMode.setAlpha(1.0f);
  }

  // ------------------------------------------------------------------ voice and gripper

  /** The robot's face (top right) and what was said (a bubble under the status line). */
  private void addOverlayViews() {
    ViewGroup root = binding.getRoot();
    face = new RobotFaceView(requireContext());
    int size = (int) (130 * getResources().getDisplayMetrics().density);
    CoordinatorLayout.LayoutParams flp =
        new CoordinatorLayout.LayoutParams(size, (int) (size * 0.8f));
    flp.gravity = Gravity.TOP | Gravity.END;
    flp.setMargins(
        0, (int) (8 * getResources().getDisplayMetrics().density), (int) (8 * getResources().getDisplayMetrics().density), 0);
    GradientDrawable faceBg = new GradientDrawable();
    faceBg.setColor(0xCC05070B);
    faceBg.setCornerRadius(18 * getResources().getDisplayMetrics().density);
    face.setBackground(faceBg);
    root.addView(face, flp);
    face.setFace(RobotFaceView.Emotion.NEUTRAL, false);

    bubble = new TextView(requireContext());
    bubble.setTextColor(Color.WHITE);
    bubble.setTextSize(15);
    int pad = (int) (10 * getResources().getDisplayMetrics().density);
    bubble.setPadding(pad, pad / 2, pad, pad / 2);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(0xB0000000);
    bg.setCornerRadius(12 * getResources().getDisplayMetrics().density);
    bubble.setBackground(bg);
    bubble.setVisibility(View.GONE);
    CoordinatorLayout.LayoutParams blp =
        new CoordinatorLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    blp.gravity = Gravity.TOP | Gravity.START;
    blp.setMargins(
        (int) (12 * getResources().getDisplayMetrics().density),
        (int) (52 * getResources().getDisplayMetrics().density),
        size + (int) (20 * getResources().getDisplayMetrics().density),
        0);
    root.addView(bubble, blp);
  }

  private void showBubble(String text) {
    if (bubble == null) return;
    bubble.setText(text);
    bubble.setVisibility(View.VISIBLE);
  }

  private void setStatus(String text) {
    if (binding == null) return;
    binding.voiceStatus.post(() -> binding.voiceStatus.setText(text));
  }

  private void toggleLanguage() {
    vietnamese = !vietnamese;
    requireContext()
        .getSharedPreferences(PREFS, 0)
        .edit()
        .putBoolean("vi", vietnamese)
        .apply();
    langButton.setText(vietnamese ? "VI" : "EN");
    if (ttsReady) tts.setLanguage(locale());
    if (listening) restartListening();
  }

  private void toggleReverse() {
    reversed = !reversed;
    requireContext()
        .getSharedPreferences(PREFS, 0)
        .edit()
        .putBoolean("flip", reversed)
        .apply();
    Toast.makeText(
            requireContext(),
            reversed ? R.string.grab_flip_on : R.string.grab_flip_off,
            Toast.LENGTH_SHORT)
        .show();
  }

  private void manualGrip(boolean close) {
    if (!vehicle.isConnected()) {
      Toast.makeText(requireContext(), R.string.voice_not_connected, Toast.LENGTH_SHORT).show();
      return;
    }
    vehicle.sendGripper(close);
    synchronized (brain) {
      brain.setHolding(close);
    }
  }

  /**
   * ✊, the claw words and the AI's gripper grab: creep toward the object and close the claw
   * once the front sonar reads within 5 cm of it (the "5 cm rule", AiPick parity). Already in
   * reach → grip at once; no sonar at all → grab after a short blind creep; it produced
   * readings but the object never came within 5 cm → stop without gripping.
   */
  private void creepAndGrip(boolean speak) {
    if (!vehicle.isConnected()) {
      if (speak) say(getString(R.string.voice_not_connected));
      else Toast.makeText(requireContext(), R.string.voice_not_connected, Toast.LENGTH_SHORT).show();
      return;
    }
    if (gripPickActive) return;
    setAutoMode(false); // one driver at a time (clears any mission or manual move first)
    float cm = vehicle.getSonarDistanceCm();
    if (cm >= 0 && cm < GRIP_TOO_CLOSE_CM) {
      gripPickFail(speak); // already touching it: back off, don't crush
      return;
    }
    if (cm >= 0 && cm <= GRIP_AT_CM) {
      manualGrip(true);
      if (speak) say(getString(R.string.vgrab_gripped));
      return;
    }
    vehicle.setSonarFrequency(100); // live distance while creeping in
    lastApproachMs = SystemClock.uptimeMillis();
    vehicle.sendApproach(APPROACH_MS); // firmware: reach 4 cm instead of stopping at 15 cm
    gripPickActive = true;
    gripPickSpeak = speak;
    gripPickSeenSonar = cm >= 0;
    gripPickStartMs = SystemClock.uptimeMillis();
    setStatus(getString(R.string.aipick_picking, (int) GRIP_AT_CM));
    sendWheels(GRIP_PICK_SPEED, GRIP_PICK_SPEED);
    main.removeCallbacks(gripPickTick);
    main.postDelayed(gripPickTick, GRIP_PICK_TICK_MS);
  }

  /** One creep step: read the sonar, grip / stop / keep creeping (AiPick's pickTick pattern). */
  private final Runnable gripPickTick =
      new Runnable() {
        @Override
        public void run() {
          if (!gripPickActive) return;
          float cm = vehicle.getSonarDistanceCm();
          if (cm >= 0) gripPickSeenSonar = true;
          if (cm >= 0 && cm < GRIP_TOO_CLOSE_CM) {
            stopGripPick();
            gripPickFail(gripPickSpeak);
            return;
          }
          if (cm >= 0 && cm <= GRIP_AT_CM) {
            stopGripPick();
            manualGrip(true);
            if (gripPickSpeak) say(getString(R.string.vgrab_gripped));
            return;
          }
          long elapsed = SystemClock.uptimeMillis() - gripPickStartMs;
          if (gripPickSeenSonar ? elapsed >= GRIP_PICK_TIMEOUT_MS : elapsed >= GRIP_PICK_BLIND_MS) {
            stopGripPick();
            if (gripPickSeenSonar) {
              // Readings but never within 5 cm: stop without gripping.
              setStatus(getString(R.string.aipick_too_far, (int) GRIP_AT_CM));
            } else {
              manualGrip(true); // no sonar on this robot: grab after the short blind creep
              if (gripPickSpeak) say(getString(R.string.vgrab_gripped));
            }
            return;
          }
          if (SystemClock.uptimeMillis() - lastApproachMs > APPROACH_RESEND_MS) {
            lastApproachMs = SystemClock.uptimeMillis();
            vehicle.sendApproach(APPROACH_MS);
          }
          sendWheels(GRIP_PICK_SPEED, GRIP_PICK_SPEED); // resend: BLE drops single writes
          main.postDelayed(this, GRIP_PICK_TICK_MS);
        }
      };

  private void gripPickFail(boolean speak) {
    if (speak) say(getString(R.string.vgrab_failed));
    else setStatus(getString(R.string.vgrab_failed));
  }

  /** Halts the creep (wheels, sonar stream) but leaves the gripper alone. */
  private void stopGripPick() {
    gripPickActive = false;
    main.removeCallbacks(gripPickTick);
    stopManualMove();
    if (!autoModeOn() && vehicle != null && vehicle.isConnected()) vehicle.setSonarFrequency(0);
  }

  private Locale locale() {
    return vietnamese ? new Locale("vi", "VN") : Locale.US;
  }

  // ------------------------------------------------------------------ listening / speaking

  private void toggleListening() {
    if (!listening
        && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
      micPermission.launch(Manifest.permission.RECORD_AUDIO);
      return;
    }
    setListening(!listening);
  }

  private void setListening(boolean on) {
    listening = on && recognizer != null;
    if (micButton != null) micButton.setText(listening ? "🎤 ON" : "🎤 OFF");
    if (recognizer == null) {
      if (on && isAdded())
        Toast.makeText(requireContext(), R.string.vgrab_no_speech, Toast.LENGTH_LONG).show();
      return;
    }
    if (listening && !speaking) startRecognizer();
    else recognizer.cancel();
    setFace(listening ? VoiceGrabBrain.Face.LISTENING : VoiceGrabBrain.Face.NEUTRAL, false);
  }

  private void restartListening() {
    if (recognizer == null) return;
    recognizer.cancel();
    if (listening && !speaking) main.postDelayed(this::startRecognizer, 250);
  }

  private void startRecognizer() {
    if (recognizer == null || !listening || speaking) return;
    Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
    i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, vietnamese ? "vi-VN" : "en-US");
    i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
    i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
    try {
      recognizer.startListening(i);
    } catch (RuntimeException e) {
      Timber.w(e, "Voice Grab: recognizer start failed");
    }
  }

  private final RecognitionListener recognitionListener =
      new RecognitionListener() {
        @Override
        public void onResults(Bundle results) {
          ArrayList<String> heard =
              results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
          if (heard != null && !heard.isEmpty()) onHeard(heard);
          restartListening();
        }

        @Override
        public void onError(int error) {
          if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            // The mic permission was revoked mid-session: stop the silent retry loop and say why.
            setListening(false);
            if (isAdded()) {
              Toast.makeText(
                      requireContext(), R.string.voice_mic_permission_denied, Toast.LENGTH_LONG)
                  .show();
            }
            return;
          }
          // No speech / timeout are normal while nobody talks: just listen again.
          main.postDelayed(
              VoiceGrabFragment.this::restartListening,
              error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 800 : 300);
        }

        @Override
        public void onReadyForSpeech(Bundle params) {}

        @Override
        public void onBeginningOfSpeech() {}

        @Override
        public void onRmsChanged(float rmsdB) {}

        @Override
        public void onBufferReceived(byte[] buffer) {}

        @Override
        public void onEndOfSpeech() {}

        @Override
        public void onPartialResults(Bundle partialResults) {}

        @Override
        public void onEvent(int eventType, Bundle params) {}
      };

  /** What the recognizer heard (best guesses first): the first one that means something wins. */
  private void onHeard(List<String> guesses) {
    VoiceGrabParser.Request request = null;
    String text = guesses.get(0);
    for (String g : guesses) {
      VoiceGrabParser.Request r = VoiceGrabParser.parse(g);
      if (r.intent != VoiceGrabParser.Intent.UNKNOWN) {
        request = r;
        text = g;
        break;
      }
    }
    if (request == null) request = VoiceGrabParser.parse(text);
    showBubble("🗣 " + text);
    Timber.i("Voice Grab heard '%s' -> %s", text, request);
    if (request.intent == VoiceGrabParser.Intent.UNKNOWN) {
      // Claw words and the Voice Control page's plain drive words are not in the fetch
      // parser's vocabulary; handle them before giving up.
      String folded = text.toLowerCase(Locale.ROOT);
      for (String w : GRIP_WORDS) {
        if (VoiceInterpreter.containsWord(folded, w)) {
          creepAndGrip(true); // creep in and grip under sonar management (5 cm rule)
          return;
        }
      }
      VoiceCommand cmd = VoiceInterpreter.interpret(text);
      switch (cmd.getAction()) {
        case FORWARD:
        case BACKWARD:
        case LEFT:
        case RIGHT:
          startManualMove(cmd);
          return;
        case SONAR:
          reportSonar();
          return;
        case FASTER:
          cycleSpeed(true);
          return;
        case SLOWER:
          cycleSpeed(false);
          return;
        case GRIP:
          creepAndGrip(true); // creep in and grip under sonar management (5 cm rule)
          return;
        case RELEASE:
          manualGrip(false);
          say(getString(R.string.vgrab_released));
          return;
        default:
          break; // let the brain answer "Sorry, I didn't get that"
      }
    }
    if (request.intent == VoiceGrabParser.Intent.SEE) {
      answerSee();
      return;
    }
    if (request.intent == VoiceGrabParser.Intent.CHECK) {
      answerCheck();
      return;
    }
    // The object to recognize comes from the words (or the brain's memory of them): the
    // "Object" spinner never feeds the mission — it stays the template's tracker target.
    boolean drives =
        request.intent == VoiceGrabParser.Intent.PICK
            || request.intent == VoiceGrabParser.Intent.BRING
            || request.intent == VoiceGrabParser.Intent.FIND
            || request.intent == VoiceGrabParser.Intent.NEXT_TO
            || request.intent == VoiceGrabParser.Intent.PUT_DOWN
            || request.intent == VoiceGrabParser.Intent.FOLLOW
            || request.intent == VoiceGrabParser.Intent.APPROACH;
    if (drives && !vehicle.isConnected()) {
      say(getString(R.string.voice_not_connected));
      return;
    }
    VoiceGrabBrain.Out reply = null;
    boolean active;
    synchronized (brain) {
      objConfirmStreak = 0; // a new command starts the three-frame count from scratch
      brain.command(request, SystemClock.uptimeMillis());
      active = brain.mode() != VoiceGrabBrain.Mode.IDLE;
    }
    if (drives && active && !autoModeOn()) {
      setAutoMode(true); // the camera loop runs the brain
    }
    if (!drives || !active || !autoModeOn()) {
      synchronized (brain) {
        // "hello" / "thank you" / "stop" / unknown, or a fetch command that started no
        // mission: answer straight away (the camera loop never steps an idle brain).
        reply = brain.step(SystemClock.uptimeMillis(), false, 0, 0, false, 0, 0, false, 0, 0, -1);
      }
    }
    if (reply != null) apply(reply, false);
  }

  /** "what do you see?": read out the classes the last frame saw (at most three, with articles). */
  private void answerSee() {
    List<String> titles;
    synchronized (brain) {
      titles = new ArrayList<>(lastSeenTitles);
    }
    if (titles.isEmpty()) {
      say(getString(R.string.vgrab_see_none));
      return;
    }
    int n = Math.min(3, titles.size());
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < n; i++) {
      if (i > 0) b.append(i == n - 1 ? " and " : ", ");
      b.append(article(titles.get(i)));
    }
    say(getString(R.string.vgrab_see, b));
  }

  /** "a cup" / "an orange" — the read-out article. */
  private static String article(String name) {
    if (name == null || name.isEmpty()) return "";
    String first = name.substring(0, 1).toLowerCase(Locale.ROOT);
    return ("aeiou".contains(first) ? "an " : "a ") + name;
  }

  /** "did you pick it up?": answer from the claw's own report (unknown reads as "no"). */
  private void answerCheck() {
    int state = vehicle.getGripperState();
    if (state >= 0) {
      reportCheck(state);
      return;
    }
    if (!vehicle.isConnected()) {
      say(getString(R.string.voice_not_connected));
      return;
    }
    vehicle.requestGripperState(); // "g2": the firmware answers after its ~700 ms move
    main.postDelayed(() -> reportCheck(vehicle.getGripperState()), 900);
  }

  private void reportCheck(int state) {
    say(getString(
        state == Vehicle.GRIP_CLOSED ? R.string.vgrab_check_yes : R.string.vgrab_check_no));
  }

  private void setSpeaking(boolean on) {
    speaking = on;
    if (!on && face != null) face.setFace(lastEmotion, false); // mouth stops with the voice
    if (recognizer == null) return;
    if (on) recognizer.cancel(); // never listen to itself
    else restartListening();
  }

  private void say(String text) {
    showBubble("🤖 " + text);
    if (ttsReady && tts != null) {
      tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vgrab" + SystemClock.uptimeMillis());
    }
  }

  private void setFace(VoiceGrabBrain.Face f, boolean talking) {
    if (face == null || f == null) return;
    RobotFaceView.Emotion e;
    try {
      e = RobotFaceView.Emotion.valueOf(f.name());
    } catch (IllegalArgumentException ex) {
      e = RobotFaceView.Emotion.NEUTRAL;
    }
    lastEmotion = e;
    face.setFace(e, talking);
  }

  // ------------------------------------------------------------------ Voice AI (Xiaozhi)

  /** Connects the Xiaozhi voice session (once paired on Voice Control) for AI speech and tools. */
  private void connectVoiceAi() {
    if (aiSession != null || !isAdded()) return;
    SharedPreferences prefs = requireContext().getSharedPreferences(XiaozhiClient.PREFS_NAME, 0);
    String wsToken = prefs.getString(XiaozhiActivator.KEY_WS_TOKEN, "");
    if (wsToken.isEmpty()) return; // not paired: the local TTS says it instead
    aiSession =
        new XiaozhiSession(
            prefs.getString(XiaozhiActivator.KEY_WS_URL, ""),
            wsToken,
            XiaozhiActivator.deviceId(requireContext()),
            XiaozhiActivator.clientId(requireContext()),
            aiSessionListener);
    aiSession.connect();
  }

  private void disconnectVoiceAi() {
    aiReady = false;
    XiaozhiSession s = aiSession;
    aiSession = null;
    if (s != null) s.disconnect();
  }

  /** Everything the AI session reports (callbacks arrive on the main thread). */
  private final XiaozhiSession.Listener aiSessionListener =
      new XiaozhiSession.Listener() {
        @Override
        public void onConnected() {
          aiReady = true;
        }

        @Override
        public void onDisconnected(boolean reconnecting) {
          aiReady = false;
        }

        @Override
        public void onStt(String text) {}

        @Override
        public void onAssistantText(String text) {
          showBubble("🤖 " + text); // the opus frames of its tts are the actual voice
        }

        @Override
        public void onSpeaking(boolean isSpeaking) {
          // Never listen to the robot's own voice; re-arm once the audio has drained.
          if (isSpeaking) setSpeaking(true);
          else if (aiPlayer == null || !aiPlayer.hasPendingAudio()) setSpeaking(false);
        }

        @Override
        public void onAudio(byte[] opus, int sampleRate) {
          ensureAiAudio(sampleRate);
          if (aiPlayer != null) aiPlayer.play(opus);
        }

        @Override
        public void onEmotion(String emotion, String text) {}

        @Override
        public void onToolCall(VoiceCommand command) {
          // Same shared tool map as the Voice Control page (XiaozhiSession.toolCommand),
          // executed here with the Voice Control page's semantics.
          executeAiCommand(command);
        }

        @Override
        public void onTakePhoto(String question, XiaozhiSession.PhotoCallback callback) {
          requestPhoto(question, callback);
        }

        @Override
        public void onFetch(String object, XiaozhiSession.PhotoCallback callback) {
          startAiFetch(object, callback);
        }
      };

  /** AI tts playback: nothing to draw here; the drain only re-arms the local listener. */
  private final OpusPlayer.Listener aiPlayerListener =
      new OpusPlayer.Listener() {
        @Override
        public void onPlaybackLevel(float rms) {}

        @Override
        public void onPlaybackDrained() {
          main.post(
              () -> {
                if (!speaking && listening) return; // already re-armed
                if (aiPlayer == null || !aiPlayer.hasPendingAudio()) setSpeaking(false);
              });
        }
      };

  /** Builds (or rebuilds) the AI tts playback pipeline at the server's sample rate. */
  private void ensureAiAudio(int playbackSampleRate) {
    if (aiCodec != null && aiCodec.getPlaybackSampleRate() == playbackSampleRate) return;
    releaseAiAudio();
    aiCodec = new OpusCodec(playbackSampleRate);
    aiPlayer = new OpusPlayer(aiCodec, playbackSampleRate, aiPlayerListener);
  }

  private void releaseAiAudio() {
    if (aiPlayer != null) {
      aiPlayer.release();
      aiPlayer = null;
    }
    aiCodec = null;
  }

  /** Makes the Voice AI speak by injecting text as user speech; false → the caller says it. */
  private boolean speakViaVoiceAi(String text) {
    XiaozhiSession s = aiSession;
    if (s == null || !aiReady) return false;
    s.sendListenText(
        vietnamese ? text + " (Hãy trả lời bằng tiếng Việt.)" : text + " (Answer in English.)");
    return true;
  }

  /** 📸: describe the next camera frame (the Voice AI speaks the answer and shows it too). */
  private void takePictureAndDescribe() {
    if (photoJobActive) return;
    photoJobActive = true;
    pendingPhotoQuestion = "Describe what the camera sees in detail.";
    pendingPhotoCallback = null;
    grabFrame.set(true);
  }

  /** The AI's photo tool: same grab, but the answer goes back as the tool result it speaks. */
  private void requestPhoto(String question, XiaozhiSession.PhotoCallback callback) {
    if (!isAdded() || binding == null) {
      callback.onPhotoResult(null, "screen closed");
      return;
    }
    if (photoJobActive) {
      callback.onPhotoResult(null, "camera busy");
      return;
    }
    photoJobActive = true;
    pendingPhotoQuestion =
        question == null || question.isEmpty()
            ? "Describe what the camera sees in detail."
            : question;
    pendingPhotoCallback = callback;
    grabFrame.set(true);
  }

  /** One grabbed frame on its way to the vision explain service (main thread). */
  private void runPhotoJob(@Nullable byte[] jpeg) {
    String question = pendingPhotoQuestion;
    XiaozhiSession.PhotoCallback tool = pendingPhotoCallback;
    pendingPhotoQuestion = null;
    pendingPhotoCallback = null;
    photoJobActive = false;
    if (jpeg == null) {
      if (tool != null) tool.onPhotoResult(null, "Failed to capture photo");
      else say("Failed to capture photo");
      return;
    }
    XiaozhiSession active = aiSession;
    photoExplainer.explainJpeg(
        requireContext().getApplicationContext(),
        question,
        active == null ? "" : active.visionExplainUrl(),
        active == null ? "" : active.visionExplainToken(),
        jpeg,
        new PhotoExplainer.Callback() {
          @Override
          public void onCaptured(@Nullable byte[] still) {}

          @Override
          public void onExplained(@Nullable String photoJson, @NonNull String error) {
            if (tool != null) {
              // The tool result is spoken by the AI's own tts; just note it here.
              if (photoJson != null) showBubble("📸 " + extractPhotoAnswer(photoJson));
              tool.onPhotoResult(photoJson, error);
              return;
            }
            if (photoJson == null) {
              say(error);
              return;
            }
            String answer = extractPhotoAnswer(photoJson);
            showBubble("📸 " + answer);
            if (!speakViaVoiceAi(
                "Tell the user briefly what the robot camera sees right now: " + answer)) {
              say(answer);
            }
          }
        });
  }

  /**
   * Always-on watcher: when the camera sees an object and no mission runs, the Voice AI asks
   * the user what to do with it ("What should I do with the cup?"). The spoken reply comes back
   * through the local recognizer, so "pick it up" starts the fetch right away. Rate-limited so
   * it never nags: at most one question per 20 s, and the same object only once a minute.
   */
  private void maybeAskAboutSighting(@Nullable Detector.Recognition seen) {
    if (seen == null) return;
    String cls = seen.getTitle();
    if (cls == null || cls.isEmpty() || "person".equals(cls)) return;
    XiaozhiSession s = aiSession;
    if (s == null || !aiReady || !listening || speaking) return;
    synchronized (brain) {
      if (brain.mode() != VoiceGrabBrain.Mode.IDLE) return;
      brain.rememberObject(cls); // "pick it up" resolves to this object
    }
    long now = SystemClock.uptimeMillis();
    if (now - lastSightingPromptMs < SIGHTING_PROMPT_MS) return;
    if (cls.equals(lastSightingClass) && now - lastSightingClassMs < SIGHTING_SAME_CLASS_MS) return;
    lastSightingPromptMs = now;
    lastSightingClass = cls;
    lastSightingClassMs = now;
    s.sendListenText(
        "The robot camera sees a "
            + cls
            + ". In one short question, ask the user what to do with it."
            + (vietnamese ? " (Hãy hỏi bằng tiếng Việt.)" : " (Ask in English.)"));
  }

  /** The AI's fetch tool: start the local brain's fetch mission ("pick up the …"). */
  private void startAiFetch(String object, XiaozhiSession.PhotoCallback callback) {
    if (!isAdded() || binding == null) {
      callback.onPhotoResult(null, "screen closed");
      return;
    }
    String target = object == null ? "" : object.trim();
    if (target.isEmpty()) {
      callback.onPhotoResult(null, "No object named");
      return;
    }
    if (!vehicle.isConnected()) {
      callback.onPhotoResult(null, "Robot not connected");
      return;
    }
    boolean started;
    boolean busy;
    synchronized (brain) {
      busy = brain.mode() != VoiceGrabBrain.Mode.IDLE;
      if (busy) {
        started = false;
      } else {
        objConfirmStreak = 0; // a new command starts the three-frame count from scratch
        brain.command(VoiceGrabParser.parse("pick up the " + target), SystemClock.uptimeMillis());
        started = brain.mode() != VoiceGrabBrain.Mode.IDLE;
      }
    }
    if (!started) {
      callback.onPhotoResult(null, busy ? "The robot is already busy" : "I didn't get that");
      return;
    }
    if (!autoModeOn()) setAutoMode(true); // the camera loop runs the brain
    String ack = "Fetching the " + target + ".";
    callback.onPhotoResult(ack, ack); // the assistant speaks the ack
  }

  /** The vision explain service's answer inside its JSON photo result. */
  private static String extractPhotoAnswer(String photoJson) {
    try {
      return JsonParser.parseString(photoJson).getAsJsonObject().get("answer").getAsString();
    } catch (Exception e) {
      return photoJson;
    }
  }

  /** The camera frame as JPEG bytes for the vision explain upload. */
  private static byte[] jpegOf(Bitmap bitmap) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out);
    return out.toByteArray();
  }

  // ------------------------------------------------------------------ AI tool commands

  /**
   * Runs one MCP tools/call with the Voice Control page's semantics (its tools behave the same
   * on every page): lights, indicators, sonar, gripper and the choreographed tricks, not just
   * drive moves. Silent like the Voice Control page — the assistant's own tts reply is the
   * narration; failures only speak the not-connected line.
   */
  private void executeAiCommand(VoiceCommand command) {
    switch (command.getAction()) {
      case FASTER:
        cycleSpeed(true);
        return;
      case SLOWER:
        cycleSpeed(false);
        return;
      case CHAT:
      case UNKNOWN:
      case STOP:
        setAutoMode(false); // halts the mission and the wheels (stopManualMove runs inside)
        return;
      case GRIP:
        creepAndGrip(false); // creep in and grip under sonar management (5 cm rule)
        return;
      case RELEASE:
        manualGrip(false);
        return;
      case LIGHTS_ON:
        if (vehicle.isConnected()) {
          vehicle.sendLight(255, 255); // stock OpenBot boards
          vehicle.sendIndicator(Vehicle.LED_ON, Vehicle.LED_ON); // FriendBot firmware LEDs
        } else {
          say(getString(R.string.voice_not_connected));
        }
        return;
      case LIGHTS_OFF:
        if (vehicle.isConnected()) {
          vehicle.sendLight(0, 0);
          vehicle.sendIndicator(Vehicle.LED_OFF, Vehicle.LED_OFF);
        } else {
          say(getString(R.string.voice_not_connected));
        }
        return;
      case INDICATOR_LEFT:
      case INDICATOR_RIGHT:
      case INDICATOR_HAZARD:
      case INDICATOR_OFF:
        if (vehicle.isConnected()) {
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
        } else {
          say(getString(R.string.voice_not_connected));
        }
        return;
      case SONAR:
        reportSonar();
        return;
      case SING:
      case TRIANGLE:
      case CIRCLE:
      case SPIN360:
      case TURN_AROUND:
      case WIGGLE:
        startScript(command);
        return;
      default:
        startManualMove(command);
    }
  }

  /** The queued legs of a choreographed trick; moveTick advances them as each leg expires. */
  private final LinkedList<float[]> scriptQueue = new LinkedList<>();

  /** Starts a choreographed move (triangle, circle, 360° spin…): its legs queue up as segments. */
  private void startScript(VoiceCommand command) {
    List<float[]> segs = command.toScript();
    if (segs == null || segs.isEmpty()) return;
    setAutoMode(false); // one driver at a time (clears any old script first)
    scriptQueue.addAll(segs);
    advanceScript();
  }

  /** Drives the next leg of the current script; when the queue runs out the robot stops. */
  private void advanceScript() {
    if (scriptQueue.isEmpty()) {
      stopManualMove();
      return;
    }
    float[] seg = scriptQueue.poll();
    if (!vehicle.isConnected()) {
      scriptQueue.clear();
      say(getString(R.string.voice_not_connected));
      return;
    }
    moveLeft = seg[0];
    moveRight = seg[1];
    moveEndsAtMs = SystemClock.uptimeMillis() + (long) seg[2];
    sendWheels(moveLeft, moveRight);
    main.removeCallbacks(moveTick);
    main.postDelayed(moveTick, 250);
  }

  // ------------------------------------------------------------------ plain drive words

  /** Timed drive from the plain words ("go forward 5 seconds"), auto-stop and resend. */
  private void startManualMove(VoiceCommand cmd) {
    float[] wheels = cmd.toWheels();
    if (wheels == null) return;
    if (!vehicle.isConnected()) {
      say(getString(R.string.voice_not_connected));
      return;
    }
    setAutoMode(false); // the brain stops; this move owns the wheels
    moveLeft = wheels[0];
    moveRight = wheels[1];
    moveEndsAtMs = SystemClock.uptimeMillis() + cmd.getDurationMs();
    sendWheels(moveLeft, moveRight);
    main.removeCallbacks(moveTick);
    main.postDelayed(moveTick, 250);
  }

  private final Runnable moveTick =
      new Runnable() {
        @Override
        public void run() {
          if (SystemClock.uptimeMillis() >= moveEndsAtMs) {
            if (!scriptQueue.isEmpty()) advanceScript();
            else stopManualMove();
          } else {
            sendWheels(moveLeft, moveRight);
            main.postDelayed(this, 250);
          }
        }
      };

  private void stopManualMove() {
    gripPickActive = false; // any motion stop halts the sonar-gated creep
    main.removeCallbacks(gripPickTick);
    if (vehicle != null) vehicle.sendApproach(0); // back to the firmware's 15 cm safety stop
    scriptQueue.clear();
    main.removeCallbacks(moveTick);
    moveEndsAtMs = 0;
    moveLeft = 0;
    moveRight = 0;
    if (vehicle != null) vehicle.setControl(new Control(0, 0));
  }

  /** Raw wheel write for the timed manual moves (they resend on purpose). */
  private void sendWheels(float left, float right) {
    if (reversed) vehicle.setControl(new Control(left, right));
    else vehicle.setControl(new Control(-right, -left));
  }

  private void reportSonar() {
    if (!vehicle.isConnected()) {
      say(getString(R.string.voice_not_connected));
      return;
    }
    if (autoModeOn()) {
      say(sonarPhrase()); // already streaming
      return;
    }
    vehicle.setSonarFrequency(200); // one report
    main.postDelayed(
        () -> {
          say(sonarPhrase());
          if (!autoModeOn() && vehicle.isConnected()) vehicle.setSonarFrequency(0);
        },
        900);
  }

  private String sonarPhrase() {
    float cm = vehicle.getSonarDistanceCm();
    return cm >= 0
        ? getString(R.string.voice_sonar_distance, cm)
        : getString(R.string.voice_sonar_unknown);
  }

  private void cycleSpeed(boolean up) {
    Enums.SpeedMode cur = Enums.SpeedMode.getByID(preferencesManager.getSpeedMode());
    if (cur == null) cur = Enums.SpeedMode.NORMAL;
    Enums.SpeedMode next;
    if (up) {
      next = cur == Enums.SpeedMode.SLOW ? Enums.SpeedMode.NORMAL : Enums.SpeedMode.FAST;
    } else {
      next = cur == Enums.SpeedMode.FAST ? Enums.SpeedMode.NORMAL : Enums.SpeedMode.SLOW;
    }
    setSpeedMode(next);
    setStatus("Speed: " + next.name().toLowerCase(Locale.US));
  }

  // ------------------------------------------------------------------ the brain's decisions

  /** Carries out one decision: wheels, claw, approach mode, voice, face, status. */
  private void apply(VoiceGrabBrain.Out out, boolean fromCamera) {
    if (fromCamera) {
      long now = SystemClock.uptimeMillis();
      if (out.approach && now - lastApproachMs > APPROACH_RESEND_MS) {
        lastApproachMs = now;
        vehicle.sendApproach(APPROACH_MS); // firmware: reach 4 cm instead of stopping at 15 cm
      }
      drive(out.grip ? 0 : out.left, out.grip ? 0 : out.right);
    }
    if (out.grip) vehicle.sendGripper(true);
    if (out.release) vehicle.sendGripper(false);
    setStatus(out.status);
    if (out.say != null || out.face != null) {
      VoiceGrabBrain.Say line = out.say;
      String objectName = out.sayObject;
      VoiceGrabBrain.Face f = out.face;
      main.post(
          () -> {
            if (f != null) setFace(f, line != null);
            if (line != null) say(phrase(line, objectName));
          });
    }
  }

  /** The brain's wheel convention; "⇄" switches (shared with the plain drive words). */
  private void drive(float left, float right) {
    if (reversed) vehicle.setControl(new Control(left, right));
    else vehicle.setControl(new Control(-right, -left)); // motor channels cross-wired
  }

  // ------------------------------------------------------------------ words

  private String phrase(VoiceGrabBrain.Say s, String object) {
    String thing = vietnamese ? viName(object) : object;
    switch (s) {
      case OK_GET:
        return vietnamese ? "Được, mình đi lấy " + thing + " nhé." : "Okay, I'll get the " + thing + ".";
      case SEE_IT:
        return vietnamese ? "Mình thấy " + thing + " rồi!" : "I see the " + thing + "!";
      case GOT_IT:
        return vietnamese ? "Lấy được rồi!" : "Got it!";
      case WHERE_TO:
        return vietnamese ? "Mình mang nó đi đâu?" : "Where should I take it?";
      case COMING:
        return vietnamese ? "Mình đi ngay!" : "On my way!";
      case HERE_YOU_ARE:
        return vietnamese ? "Của bạn đây!" : "Here you are!";
      case ARRIVED:
        return vietnamese ? "Đến nơi rồi." : "Here we are.";
      case ASK_NEXT:
        return vietnamese ? "Bạn cần mình làm gì tiếp?" : "What should I do next?";
      case THERE_YOU_GO:
        return vietnamese ? "Xong rồi nhé." : "There you go.";
      case CANT_FIND:
        return vietnamese ? "Mình không tìm thấy " + thing + "." : "I can't find the " + thing + ".";
      case FOLLOWING:
        return vietnamese ? "Mình đi theo bạn đây." : "I'm following you.";
      case WHICH_OBJECT:
        return vietnamese ? "Bạn muốn mình lấy vật gì?" : "Which object should I get?";
      case STOPPED:
        return vietnamese ? "Mình dừng rồi." : "Okay, I stopped.";
      case NOT_HOLDING:
        return vietnamese ? "Mình đâu có cầm gì." : "I'm not holding anything.";
      case HELLO:
        return vietnamese ? "Xin chào! Bạn cần mình lấy gì?" : "Hello! What can I get for you?";
      case THANKS:
        return vietnamese ? "Không có gì!" : "You're welcome!";
      case CANT_GRAB:
        return vietnamese ? "Mình không kẹp được." : "I can't grab it.";
      case DIDNT_GET:
      default:
        return vietnamese
            ? "Xin lỗi, mình chưa hiểu. Thử nói: lấy cái cốc."
            : "Sorry, I didn't get that. Try: pick up the cup.";
    }
  }

  private static String viName(String label) {
    switch (label == null ? "" : label) {
      case "cup":
        return "cái cốc";
      case "bottle":
        return "cái chai";
      case "sports ball":
        return "quả bóng";
      case "apple":
        return "quả táo";
      case "orange":
        return "quả cam";
      case "banana":
        return "quả chuối";
      case "teddy bear":
        return "gấu bông";
      case "book":
        return "quyển sách";
      case "cell phone":
        return "điện thoại";
      case "remote":
        return "cái điều khiển";
      case "mouse":
        return "con chuột";
      case "scissors":
        return "cái kéo";
      case "bowl":
        return "cái bát";
      case "spoon":
        return "cái thìa";
      case "dining table":
        return "cái bàn";
      case "chair":
        return "cái ghế";
      case "bed":
        return "cái giường";
      case "couch":
        return "ghế sofa";
      case "tv":
        return "cái tivi";
      case "laptop":
        return "máy tính";
      case "vase":
        return "lọ hoa";
      case "clock":
        return "đồng hồ";
      default:
        return label == null ? "" : label;
    }
  }

  // ------------------------------------------------------------------ lifecycle

  @Override
  public void onDestroyView() {
    disconnectVoiceAi();
    releaseAiAudio();
    if (vehicle != null) stopManualMove(); // a plain "go forward" must not outlive the page
    main.removeCallbacksAndMessages(null);
    if (recognizer != null) {
      recognizer.destroy();
      recognizer = null;
    }
    if (tts != null) {
      tts.shutdown();
      tts = null;
    }
    if (vehicle != null) {
      vehicle.sendApproach(0);
      vehicle.setSonarFrequency(0);
    }
    if (detector != null) {
      detector.close();
      detector = null;
    }
    BotToControllerEventBus.emitEvent(ConnectionUtils.createStatus("FRAGMENT_TYPE", "CLOSE"));
    super.onDestroyView();
  }
}
