package org.openbot.app.robot.vision;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
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
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.NotNull;
import org.openbot.app.robot.R;
import org.openbot.app.robot.common.CameraFragment;
import org.openbot.app.robot.databinding.FragmentAiPickBinding;
import org.openbot.app.robot.env.BotToControllerEventBus;
import org.openbot.app.robot.env.ImageUtils;
import org.openbot.app.robot.sensorlink.RemoteSettings;
import org.openbot.app.robot.tflite.Detector;
import org.openbot.app.robot.tflite.Model;
import org.openbot.app.robot.tflite.Network;
import org.openbot.app.robot.tracking.MultiBoxTracker;
import org.openbot.app.robot.utils.CameraUtils;
import org.openbot.app.robot.utils.ConnectionUtils;
import org.openbot.app.robot.utils.Constants;
import org.openbot.app.robot.utils.Enums;
import org.openbot.app.robot.utils.FileUtils;
import org.openbot.app.robot.utils.MovingAverage;
import org.openbot.app.robot.utils.PermissionUtils;
import org.openbot.app.robot.vehicle.Control;
import org.openbot.app.robot.vehicle.Vehicle;
import org.openbot.app.robot.voice.LocalStt;
import org.openbot.app.robot.voice.MicStreamer;
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
 * AI Pick (AI tab): the Object Tracking page's template — {@code fragment_ai_pick.xml} is
 * {@code fragment_object_nav.xml} plus a voice/gripper row and the gripper's sonar-distance
 * setting — with the Xiaozhi AI voice on the microphone. Talk to the assistant hands-free and its
 * robot tools run here with the Voice Control page's semantics: {@code self.robot.move} drives
 * (timed, auto-stop), {@code self.robot.stop} halts, {@code self.robot.sonar} reports the front
 * sonar, {@code self.robot.gripper grab} picks — creep forward until the front sonar reads the set
 * distance ({@link #gripDistanceCm}), then close the claw — and {@code release} opens it. The
 * detector comes from the Model Management catalog and the tracker drives exactly as on Object
 * Tracking while the Auto switch is on. Mirrors AiPickController on iOS.
 */
public class AiPickFragment extends CameraFragment {

  private static final String PREFS = "ai_pick";
  private static final int DEFAULT_GRIP_CM = 5; // the 5 cm rule
  /** Under this sonar distance the object would be crushed → stop, never close. */
  private static final float TOO_CLOSE_CM = 2f;
  private static final int GRIP_CM_MIN = 4;
  private static final int GRIP_CM_MAX = 40;

  /** The pick's creep: slow and ticked often, so the sonar threshold is not overshot. */
  private static final float PICK_SPEED = 0.2f;
  private static final long PICK_TICK_MS = 150;
  private static final long PICK_TIMEOUT_MS = 8000;
  /** Sonar never produced a reading: grab blind after a short creep instead of creeping forever. */
  private static final long PICK_BLIND_MS = 2500;
  /** Approach mode during the creep: firmware stops forward at 15 cm, 4 cm in approach. */
  private static final int PICK_APPROACH_MS = 3000;
  private static final long PICK_APPROACH_RESEND_MS = 2000;
  /** The assistant's window to answer a heard utterance with a tool before the safety net. */
  private static final long AI_UTTERANCE_WAIT_MS = 3500;

  private FragmentAiPickBinding binding;

  // ---- the Object Tracking template (ObjectNavFragment) ---------------------------------------
  private Handler handler;
  private HandlerThread handlerThread;

  private boolean computingNetwork = false;
  private float MINIMUM_CONFIDENCE_TF_OD_API = 0.5f;

  private Detector detector;

  private Matrix frameToCropTransform;
  private Bitmap croppedBitmap;
  private int sensorOrientation;
  private Matrix cropToFrameTransform;

  private MultiBoxTracker tracker;

  private Model model;
  private Network.Device device = Network.Device.CPU;
  private int numThreads = -1;
  /** The "Object" spinner's pick: the tracker's target class. */
  private String classType = "cup";

  private long lastProcessingTimeMs = -1;
  private long frameNum = 0;

  private final boolean isBenchmarkMode = false;
  private long processedFrames = 0;
  private final int movingAvgSize = 100;
  private MovingAverage movingAvgProcessingTimeMs = new MovingAverage(movingAvgSize);

  // ---- voice, gripper, sonar ------------------------------------------------------------------
  private final Handler main = new Handler(Looper.getMainLooper());
  private boolean reversed;
  /** The gripper's pick distance in cm: creep until the front sonar reads ≤ this, then grip. */
  private int gripDistanceCm = DEFAULT_GRIP_CM;

  // ---- Voice AI (Xiaozhi): the conversation uplink and its tts playback ----------------------
  private volatile XiaozhiSession aiSession;
  private volatile boolean aiReady;
  private OpusCodec aiCodec;
  private OpusPlayer aiPlayer;
  private MicStreamer micStreamer;
  private boolean micStreaming;

  /** Offline mic fallback: one on-device recognition while Xiaozhi is unreachable. */
  private LocalStt offlineStt;

  private boolean localVoiceActive;
  /** Paired-path safety net: what the server heard, and whether a robot tool answered it. */
  private String aiUtteranceText = "";
  private boolean aiToolRan;
  private final Runnable aiUtteranceWindow = this::onAiUtteranceWindowClosed;
  /** The user wants the hands-free conversation uplink on. */
  private boolean micOn;

  private TextToSpeech tts;
  private boolean ttsReady;
  private boolean ttsSpeaking;

  // Timed moves, trick scripts and the sonar-gated pick (one driver at a time).
  private float moveLeft;
  private float moveRight;
  private long moveEndsAtMs;
  private final LinkedList<float[]> scriptQueue = new LinkedList<>();
  private boolean pickActive;
  private boolean pickSeenSonar;
  private long pickStartedMs;
  private long lastApproachMs;

  // Photo jobs (📸 button and the AI's photo tool): grab one frame, explain it.
  private final PhotoExplainer photoExplainer = new PhotoExplainer();
  private boolean photoJobActive;
  private String pendingPhotoQuestion;
  /** The photo tool's answer channel; consumed on the main thread by {@link #runPhotoJob}. */
  private volatile XiaozhiSession.PhotoCallback pendingPhotoCallback;
  private final AtomicBoolean grabFrame = new AtomicBoolean();

  // Sighting prompts ("I see a cup — what should I do with it?") are rate-limited.
  private static final long SIGHTING_PROMPT_MS = 20_000;
  private static final long SIGHTING_SAME_CLASS_MS = 60_000;
  private long lastSightingPromptMs;
  private String lastSightingClass = "";
  private long lastSightingClassMs;
  /** The last non-person object the camera saw: "pick it up" / fetch("it") resolves to it. */
  private volatile String lastSeenClass = "";

  // Views over the camera.
  private RobotFaceView face;
  private RobotFaceView.Emotion lastEmotion = RobotFaceView.Emotion.NEUTRAL;
  private TextView bubble;
  private Button micButton;

  private final ActivityResultLauncher<String> micPermission =
      registerForActivityResult(
          new ActivityResultContracts.RequestPermission(),
          granted -> {
            if (granted) setMicOn(true);
            else
              Toast.makeText(
                      requireContext(), R.string.voice_mic_permission_denied, Toast.LENGTH_LONG)
                  .show();
          });

  // ------------------------------------------------------------------ UI

  @Override
  public View onCreateView(
      @NotNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentAiPickBinding.inflate(inflater, container, false);
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
    reversed = p.getBoolean("flip", false);
    gripDistanceCm = clampGripCm(p.getInt("grip_cm", DEFAULT_GRIP_CM));
    binding.gripValue.setText(gripDistanceCm + " cm");
    binding.gripPlus.setOnClickListener(v -> setGripDistanceCm(gripDistanceCm + 2));
    binding.gripMinus.setOnClickListener(v -> setGripDistanceCm(gripDistanceCm - 2));

    classType = preferencesManager.getObjectType();
    if (classType == null || classType.isEmpty()) classType = "cup";
    binding.classType.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          @Override
          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            classType = parent.getItemAtPosition(position).toString();
            preferencesManager.setObjectType(classType);
            setStatus(getString(R.string.aipick_target, classType));
          }

          @Override
          public void onNothingSelected(AdapterView<?> parent) {}
        });
    initDeviceSpinner(binding.deviceSpinner, preferencesManager.getDevice());
    setNumThreads(preferencesManager.getNumThreads());
    binding.threads.setText(String.valueOf(getNumThreads()));

    binding.cameraToggle.setOnClickListener(v -> toggleCamera());

    List<String> models =
        getModelNames(f -> f.type.equals(Model.TYPE.DETECTOR) && f.pathType != Model.PATH_TYPE.URL);
    initModelSpinner(binding.modelSpinner, models, preferencesManager.getAiPickModel());

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

    setupObjectAvoidanceControls(
        binding.objectAvoidance.avoidToggle, binding.objectAvoidance.avoidInfo);
    setupSensorLinkControls(binding.sensorLink.getRoot());

    // ---- the voice/gripper row (the template does not have these)
    micButton = binding.micBtn;
    micButton.setText("🎤 OFF");
    micButton.setOnClickListener(v -> toggleListening());
    binding.photoBtn.setOnClickListener(v -> takePictureAndDescribe());
    binding.gripBtn.setOnClickListener(v -> sonarPick());
    binding.releaseBtn.setOnClickListener(v -> openGripper());
    binding.reverseBtn.setOnClickListener(v -> toggleReverse());
    addOverlayViews();

    // Local text-to-speech for the short status lines ("Grabbed at 12 cm"); the assistant's
    // own tts comes down as opus through the Xiaozhi session.
    tts =
        new TextToSpeech(
            requireContext(),
            status -> {
              ttsReady = status == TextToSpeech.SUCCESS;
              if (ttsReady) {
                tts.setLanguage(Locale.getDefault());
                tts.setOnUtteranceProgressListener(
                    new UtteranceProgressListener() {
                      @Override
                      public void onStart(String id) {
                        main.post(() -> ttsSpeaking = true);
                      }

                      @Override
                      public void onDone(String id) {
                        main.post(
                            () -> {
                              ttsSpeaking = false;
                              maybeRearm();
                            });
                      }

                      @Override
                      public void onError(String id) {
                        main.post(
                            () -> {
                              ttsSpeaking = false;
                              maybeRearm();
                            });
                      }
                    });
              }
            });
    setStatus(getString(R.string.aipick_ready));
  }

  private static int clampGripCm(int cm) {
    return Math.max(GRIP_CM_MIN, Math.min(GRIP_CM_MAX, cm));
  }

  private void setGripDistanceCm(int cm) {
    gripDistanceCm = clampGripCm(cm);
    requireContext()
        .getSharedPreferences(PREFS, 0)
        .edit()
        .putInt("grip_cm", gripDistanceCm)
        .apply();
    binding.gripValue.setText(gripDistanceCm + " cm");
  }

  // ------------------------------------------------------------------ template (ObjectNav)

  private void updateCropImageInfo() {
    frameToCropTransform = null;

    sensorOrientation = 90 - ImageUtils.getScreenOrientation(requireActivity());

    tracker = new MultiBoxTracker(requireContext());

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
    initModelSpinner(binding.modelSpinner, models, preferencesManager.getAiPickModel());
    connectVoiceAi();
    super.onResume();
  }

  @Override
  public synchronized void onPause() {
    micOn = false;
    stopMicSession(true);
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
    binding.controllerContainer.speedInfo.setText(
        getString(
            R.string.speedInfo,
            String.format(
                Locale.US, "%3.0f,%3.0f", vehicle.getLeftWheelRpm(), vehicle.getRightWheelRpm())));
    refreshObjectAvoidanceStatus(binding.objectAvoidance.avoidInfo);
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

  // ------------------------------------------------------------------ camera thread

  @Override
  protected void processFrame(Bitmap bitmap, ImageProxy image) {
    if (tracker == null) updateCropImageInfo();

    ++frameNum;
    // The detector always runs: the page draws the target and asks the Voice AI when it
    // sees something. It never drives — voice commands and the gripper buttons own the wheels.
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
              // All classes, not just the target: the sighting prompt needs whatever
              // else shows up ("what should I do with the cup?").
              final List<Detector.Recognition> results =
                  detector.recognizeImage(croppedBitmap, null);
              lastProcessingTimeMs = SystemClock.elapsedRealtime() - startTime;

              Detector.Recognition bestSeen = null;
              final List<Detector.Recognition> mappedRecognitions = new LinkedList<>();
              for (final Detector.Recognition result : results) {
                final RectF location = result.getLocation();
                if (location == null || result.getConfidence() < MINIMUM_CONFIDENCE_TF_OD_API)
                  continue;
                String title = result.getTitle();
                if (!"person".equals(title)
                    && (bestSeen == null
                        || result.getConfidence() > bestSeen.getConfidence())) {
                  // The sighting that may prompt "what should I do with it?".
                  bestSeen = result;
                }
                if (!classType.equals(title)) continue;
                // Draw only — this page never follows the object.
                cropToFrameTransform.mapRect(location);
                result.setLocation(location);
                mappedRecognitions.add(result);
              }

              tracker.trackResults(mappedRecognitions, frameNum);
              maybeAskAboutSighting(bestSeen);
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
      preferencesManager.setAiPickModel(model.name);
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
    return "AI Pick";
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
            "AI Pick",
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

  private void setEmotion(RobotFaceView.Emotion e, boolean talking) {
    if (face == null || e == null) return;
    lastEmotion = e;
    face.setFace(e, talking);
  }

  private static RobotFaceView.Emotion emotionFromEmoji(String emotion) {
    if (emotion == null || emotion.isEmpty()) return RobotFaceView.Emotion.NEUTRAL;
    switch (emotion) {
      case "😀":
      case "😄":
      case "😁":
      case "🙂":
        return RobotFaceView.Emotion.HAPPY;
      case "😢":
      case "😭":
        return RobotFaceView.Emotion.SAD;
      case "😠":
      case "😡":
        return RobotFaceView.Emotion.ANGRY;
      case "😮":
      case "😲":
      case "😯":
        return RobotFaceView.Emotion.SURPRISED;
      case "😕":
      case "🙃":
      case "🤪":
        return RobotFaceView.Emotion.CONFUSED;
      case "😴":
        return RobotFaceView.Emotion.SLEEPY;
      default:
        return RobotFaceView.Emotion.NEUTRAL;
    }
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

  /** ✋ (and the AI's release): stop whatever owns the wheels, then open the claw. */
  private void openGripper() {
    stopAllMotion(); // halts a pick that is creeping in (and its sonar stream)
    if (!vehicle.isConnected()) {
      say(getString(R.string.voice_not_connected));
      return;
    }
    vehicle.sendGripper(false);
    say(getString(R.string.aipick_released));
  }

  /** "s200" → one live reading → "s0": say the front sonar distance. */
  private void reportSonar() {
    if (!vehicle.isConnected()) {
      say(getString(R.string.voice_not_connected));
      return;
    }
    if (pickActive) {
      say(sonarPhrase()); // the pick's stream is already live
      return;
    }
    vehicle.setSonarFrequency(200); // one report
    main.postDelayed(
        () -> {
          say(sonarPhrase());
          if (!pickActive && vehicle.isConnected()) vehicle.setSonarFrequency(0);
        },
        900);
  }

  private String sonarPhrase() {
    float cm = vehicle.getSonarDistanceCm();
    return cm >= 0
        ? getString(R.string.voice_sonar_distance, cm)
        : getString(R.string.voice_sonar_unknown);
  }

  private void say(String text) {
    showBubble("🤖 " + text);
    if (ttsReady && tts != null) {
      if (micStreaming) stopMicSession(true); // never listen to the phone's own voice
      tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aipick" + SystemClock.uptimeMillis());
    }
  }

  // ------------------------------------------------------------------ mic uplink (Xiaozhi)

  private void toggleListening() {
    if (!micOn
        && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
      micPermission.launch(Manifest.permission.RECORD_AUDIO);
      return;
    }
    setMicOn(!micOn);
  }

  private void setMicOn(boolean on) {
    micOn = on;
    if (micButton != null) micButton.setText(on ? "🎤 ON" : "🎤 OFF");
    if (!on) {
      if (offlineStt != null) offlineStt.stop();
      localVoiceActive = false;
      stopMicSession(true);
      setEmotion(RobotFaceView.Emotion.NEUTRAL, false);
    } else if (aiSession == null || !aiReady) {
      startOfflineVoice(); // not paired / still connecting: hear the driver on-device
    } else {
      armListening();
    }
  }

  /**
   * Offline mic: one on-device recognition into the same tool commands the assistant would
   * send ({@link VoiceInterpreter} keywords), so "go forward", "turn left" and "grip" still
   * drive the robot without Xiaozhi.
   */
  private void startOfflineVoice() {
    if (!isAdded() || localVoiceActive) return;
    if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      micPermission.launch(Manifest.permission.RECORD_AUDIO);
      return;
    }
    if (offlineStt == null) offlineStt = new LocalStt();
    localVoiceActive = true;
    setStatus(getString(R.string.voice_listening));
    offlineStt.start(
        requireContext(),
        new LocalStt.Callback() {
          @Override
          public void onText(String text) {
            if (!isAdded()) return;
            localVoiceActive = false;
            setMicOn(false);
            showBubble("🗣 " + text);
            VoiceCommand command = VoiceInterpreter.interpret(text);
            if (command.getAction() == VoiceCommand.Action.UNKNOWN) {
              setStatus(getString(R.string.voice_no_match));
              return;
            }
            executeAiCommand(command);
          }

          @Override
          public void onError(String message) {
            if (!isAdded()) return;
            localVoiceActive = false;
            setMicOn(false);
            setStatus(message);
          }
        });
  }

  private boolean hasMicPermission() {
    return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED;
  }

  /**
   * Starts (or re-arms) the hands-free uplink: {@code listen start mode=auto} once, then keep
   * streaming opus frames until the assistant starts its tts. Barge-in while it speaks.
   */
  private void armListening() {
    if (localVoiceActive) {
      return; // an offline utterance owns the mic until it finishes
    }
    XiaozhiSession s = aiSession;
    if (s == null) {
      // No session at all: not paired yet (Voice Control page pairs the device).
      micOn = false;
      if (micButton != null) micButton.setText("🎤 OFF");
      setStatus(getString(R.string.aipick_not_paired));
      return;
    }
    if (!aiReady) {
      setStatus(getString(R.string.aipick_not_paired)); // still connecting: re-armed on connect
      return;
    }
    if (!hasMicPermission()) {
      micPermission.launch(Manifest.permission.RECORD_AUDIO);
      return;
    }
    if (micStreaming) return;
    if (aiPlayer != null && aiPlayer.hasPendingAudio()) {
      // Barge-in: cut the assistant off at once, then take the floor.
      aiPlayer.flushAndReset();
      s.sendAbort();
    }
    ensureAiAudio(s.getPlaybackSampleRate());
    s.sendListenStart(XiaozhiSession.MODE_AUTO);
    startMicCapture();
    if (micButton != null) micButton.setText("🎤 ON");
    setEmotion(RobotFaceView.Emotion.LISTENING, false);
  }

  private void startMicCapture() {
    stopMicCapture();
    if (aiCodec == null) {
      Timber.e("No opus codec; microphone uplink not started");
      return;
    }
    final XiaozhiSession target = aiSession;
    micStreamer =
        new MicStreamer(
            aiCodec,
            (frame, rms) -> {
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
  }

  /** Ends the uplink (mic tap off, assistant tts start, or screen exit). */
  private void stopMicSession(boolean notifyServer) {
    boolean wasStreaming = micStreaming;
    stopMicCapture();
    if (notifyServer && wasStreaming && aiSession != null && aiReady) {
      aiSession.sendListenStop();
    }
  }

  /** The exchange is over and nobody is speaking: take the floor again (hands-free). */
  private void maybeRearm() {
    if (!micOn || ttsSpeaking) return;
    if (aiPlayer != null && aiPlayer.hasPendingAudio()) return;
    armListening();
  }

  // ------------------------------------------------------------------ photos and sightings

  /**
   * "pick it up" / fetch("it"): a pronoun or an empty name resolves to the last object the
   * camera saw — the "it" memory the sighting prompt seeds. Otherwise the name stands.
   */
  private String resolveFetchObject(String object) {
    String name = object == null ? "" : object.trim();
    String lower = name.toLowerCase(Locale.US);
    if (name.isEmpty()
        || lower.equals("it")
        || lower.equals("this")
        || lower.equals("that")
        || lower.equals("them")
        || lower.equals("this one")
        || lower.equals("that one")) {
      String seen = lastSeenClass;
      return seen.isEmpty() ? classType : seen;
    }
    return name;
  }

  /** Makes the Voice AI speak by injecting text as user speech; false → the caller says it. */
  private boolean speakViaVoiceAi(String text) {
    XiaozhiSession s = aiSession;
    if (s == null || !aiReady) return false;
    s.sendListenText(
        isVietnamese() ? text + " (Hãy trả lời bằng tiếng Việt.)" : text + " (Answer in English.)");
    return true;
  }

  /** The prompt language mirrors the Voice Grab page: Vietnamese when the device is set to it. */
  private boolean isVietnamese() {
    return Locale.getDefault().getLanguage().equals("vi");
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
              say(answer); // not paired: the phone's own tts
            }
          }
        });
  }

  /**
   * Always-on watcher: when the camera sees an object and nothing else is going on, the Voice
   * AI asks the user what to do with it ("What should I do with the cup?"). The spoken reply
   * comes back through the same hands-free channel, so "pick it up" starts the fetch right
   * away — the seen object is the "it" it resolves to. Rate-limited so it never nags: at most
   * one question per 20 s, and the same object only once a minute.
   */
  private void maybeAskAboutSighting(@Nullable Detector.Recognition seen) {
    if (seen == null) return;
    String cls = seen.getTitle();
    if (cls == null || cls.isEmpty() || "person".equals(cls)) return;
    XiaozhiSession s = aiSession;
    if (s == null || !aiReady || !micOn || ttsSpeaking) return;
    if (aiPlayer != null && aiPlayer.hasPendingAudio()) return; // mid-exchange: not idle
    if (pickActive || moveEndsAtMs != 0 || !scriptQueue.isEmpty()) return;
    lastSeenClass = cls; // "pick it up" resolves to this object
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
            + (isVietnamese() ? " (Hãy hỏi bằng tiếng Việt.)" : " (Ask in English.)"));
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

  // ------------------------------------------------------------------ Voice AI (Xiaozhi)

  /** Connects the Xiaozhi voice session (once paired on Voice Control) for conversation + tools. */
  private void connectVoiceAi() {
    if (aiSession != null || !isAdded()) return;
    SharedPreferences prefs = requireContext().getSharedPreferences(XiaozhiClient.PREFS_NAME, 0);
    String wsToken = prefs.getString(XiaozhiActivator.KEY_WS_TOKEN, "");
    if (wsToken.isEmpty()) return; // not paired: the mic button explains where to pair
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
          if (micOn) armListening();
        }

        @Override
        public void onDisconnected(boolean reconnecting) {
          aiReady = false;
        }

        @Override
        public void onStt(String text) {
          showBubble("🗣 " + text); // what the assistant heard
          // Safety net: if no robot tool answers these words in time, but they are a clear
          // move/turn/grip command the assistant only chatted about, run them here.
          aiUtteranceText = text;
          aiToolRan = false;
          main.removeCallbacks(aiUtteranceWindow);
          main.postDelayed(aiUtteranceWindow, AI_UTTERANCE_WAIT_MS);
        }

        @Override
        public void onAssistantText(String text) {
          showBubble("🤖 " + text); // the opus frames of its tts are the actual voice
        }

        @Override
        public void onSpeaking(boolean isSpeaking) {
          if (isSpeaking) {
            // Never listen to the robot's own voice.
            stopMicSession(true);
            setEmotion(lastEmotion, true);
          } else {
            setEmotion(lastEmotion, false);
            maybeRearm();
          }
        }

        @Override
        public void onAudio(byte[] opus, int sampleRate) {
          ensureAiAudio(sampleRate);
          if (aiPlayer != null) aiPlayer.play(opus);
        }

        @Override
        public void onEmotion(String emotion, String text) {
          setEmotion(emotionFromEmoji(emotion), false);
        }

        @Override
        public void onToolCall(VoiceCommand command) {
          // Same shared tool map as the Voice Control page (XiaozhiSession.toolCommand),
          // executed here with the Voice Control page's semantics.
          aiToolRan = true;
          executeAiCommand(command);
        }

        @Override
        public void onTakePhoto(String question, XiaozhiSession.PhotoCallback callback) {
          aiToolRan = true; // the assistant answered with a tool, not chat
          requestPhoto(question, callback);
        }

        @Override
        public void onFetch(String object, XiaozhiSession.PhotoCallback callback) {
          // "Pick up the …": the sonar-gated pick (creep in, grip at the set distance).
          // "it" resolves to the last object the camera saw.
          aiToolRan = true;
          if (!vehicle.isConnected()) {
            callback.onPhotoResult(null, "Robot not connected");
            return;
          }
          String target = resolveFetchObject(object);
          if (target.isEmpty()) {
            callback.onPhotoResult(null, "No object named");
            return;
          }
          sonarPick();
          String ack = "Reaching for the " + target + " with the gripper.";
          callback.onPhotoResult(ack, ack); // the assistant speaks the ack
        }
      };

  /** AI tts playback; the drain re-arms the uplink. */
  private final OpusPlayer.Listener aiPlayerListener =
      new OpusPlayer.Listener() {
        @Override
        public void onPlaybackLevel(float rms) {
          // The mouth follows the voice's level.
          if (face != null) face.setFace(lastEmotion, rms > 0.02f);
        }

        @Override
        public void onPlaybackDrained() {
          main.post(
              () -> {
                setEmotion(lastEmotion, false);
                maybeRearm();
              });
        }
      };

  /** Builds (or rebuilds) the AI codec + tts playback pipeline at the server's sample rate. */
  private void ensureAiAudio(int playbackSampleRate) {
    if (aiCodec != null && aiCodec.getPlaybackSampleRate() == playbackSampleRate) return;
    boolean resume = micStreaming;
    stopMicCapture(); // the encoder lives in the codec
    if (aiPlayer != null) {
      aiPlayer.release();
      aiPlayer = null;
    }
    aiCodec = new OpusCodec(playbackSampleRate);
    aiPlayer = new OpusPlayer(aiCodec, playbackSampleRate, aiPlayerListener);
    if (resume) startMicCapture(); // the listen session is still open; only the codec changed
  }

  private void releaseAiAudio() {
    if (aiPlayer != null) {
      aiPlayer.release();
      aiPlayer = null;
    }
    aiCodec = null;
  }

  // ------------------------------------------------------------------ the sonar-gated pick

  /**
   * ✊ and the AI's gripper grab: creep forward until the front sonar reads ≤ {@link
   * #gripDistanceCm}, then close the claw. Already in reach → grip at once. The sonar never
   * produced a reading → grab blind after a short creep; it produced readings but the object
   * never came within the setting → stop without gripping and say so.
   */
  private void sonarPick() {
    if (!vehicle.isConnected()) {
      say(getString(R.string.voice_not_connected));
      return;
    }
    if (pickActive) return;
    stopAllMotion(); // one driver at a time (clears any old move/script first)
    float cm = vehicle.getSonarDistanceCm();
    if (cm >= 0 && cm < TOO_CLOSE_CM) {
      say(getString(R.string.vgrab_failed)); // already touching: don't crush it
      return;
    }
    if (cm >= 0 && cm <= gripDistanceCm) {
      closeGrip(cm);
      return;
    }
    vehicle.setSonarFrequency(100); // live distance while creeping in
    lastApproachMs = SystemClock.uptimeMillis();
    vehicle.sendApproach(PICK_APPROACH_MS); // firmware: reach 4 cm instead of stopping at 15 cm
    pickActive = true;
    pickSeenSonar = cm >= 0;
    pickStartedMs = SystemClock.uptimeMillis();
    setStatus(getString(R.string.aipick_picking, gripDistanceCm));
    sendWheels(PICK_SPEED, PICK_SPEED);
    main.removeCallbacks(moveTick);
    main.removeCallbacks(pickTick);
    main.postDelayed(pickTick, PICK_TICK_MS);
  }

  private final Runnable pickTick =
      new Runnable() {
        @Override
        public void run() {
          if (!pickActive) return;
          float cm = vehicle.getSonarDistanceCm();
          if (cm >= 0) pickSeenSonar = true;
          if (cm >= 0 && cm < TOO_CLOSE_CM) {
            stopPickMotion();
            say(getString(R.string.vgrab_failed)); // already touching: don't crush it
            return;
          }
          if (cm >= 0 && cm <= gripDistanceCm) {
            closeGrip(cm);
            return;
          }
          long now = SystemClock.uptimeMillis() - pickStartedMs; // same clock as pickStartedMs
          if (pickSeenSonar ? now >= PICK_TIMEOUT_MS : now >= PICK_BLIND_MS) {
            if (pickSeenSonar) {
              stopPickMotion();
              say(getString(R.string.aipick_too_far, gripDistanceCm));
            } else {
              closeGrip(-1); // no sonar on this robot: grab after the short blind creep
            }
            return;
          }
          if (SystemClock.uptimeMillis() - lastApproachMs > PICK_APPROACH_RESEND_MS) {
            lastApproachMs = SystemClock.uptimeMillis();
            vehicle.sendApproach(PICK_APPROACH_MS);
          }
          sendWheels(PICK_SPEED, PICK_SPEED);
          main.postDelayed(this, PICK_TICK_MS);
        }
      };

  /** The object is at the grip distance (or the blind creep ran out): stop and close the claw. */
  private void closeGrip(float cm) {
    stopPickMotion();
    vehicle.sendGripper(true);
    // The firmware answers g2 after its 700 ms move: ask once the claw has had time.
    main.postDelayed(
        () -> {
          vehicle.requestGripperState();
          main.postDelayed(
              () -> {
                if (vehicle.getGripperState() == Vehicle.GRIP_OPEN) {
                  // Still open: one retry, then accept the answer either way.
                  vehicle.sendGripper(true);
                  main.postDelayed(
                      () -> {
                        vehicle.requestGripperState();
                        main.postDelayed(
                            () -> {
                              if (vehicle.getGripperState() == Vehicle.GRIP_OPEN) {
                                say(getString(R.string.vgrab_failed));
                              } else {
                                reportGrabbed(cm);
                              }
                            },
                            900);
                      },
                      1000);
                } else {
                  // GRIP_CLOSED or unknown (this firmware never reports: old behaviour, trust it).
                  reportGrabbed(cm);
                }
              },
              900);
        },
        1000);
  }

  private void reportGrabbed(float cm) {
    if (cm >= 0) say(getString(R.string.aipick_grabbed, cm));
    else say(getString(R.string.aipick_grabbed_blind));
  }

  /** Stops the creep without gripping (object never came within the setting). */
  private void stopPickMotion() {
    pickActive = false;
    main.removeCallbacks(pickTick);
    if (vehicle != null) vehicle.sendApproach(0); // back to the firmware's 15 cm safety stop
    stopAllMotion(); // the pick's stream ends with the creep
  }

  // ------------------------------------------------------------------ AI tool commands

  /**
   * Runs one MCP tools/call with the Voice Control page's semantics (its tools behave the same
   * on every page): lights, indicators, sonar, gripper and the choreographed tricks, not just
   * drive moves. Silent like the Voice Control page — the assistant's own tts reply is the
   * narration; failures only speak the not-connected line. The gripper's grab is this page's
   * sonar-gated pick.
   */
  /**
   * The assistant heard an utterance but no robot tool answered it within the window: when the
   * words were a plain move/turn/grip command the assistant only chatted about, run them here.
   * Voice Control's utterance-window safety net, on the server-ASR path this page uses.
   */
  private void onAiUtteranceWindowClosed() {
    if (aiToolRan || aiUtteranceText.isEmpty() || !isAdded() || binding == null) return;
    String text = aiUtteranceText;
    aiUtteranceText = "";
    VoiceCommand command = VoiceInterpreter.interpret(text);
    if (command.getAction() != VoiceCommand.Action.UNKNOWN) {
      executeAiCommand(command);
    }
  }

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
        stopAllMotion(); // halts moves, tricks and the pick's creep
        return;
      case GRIP:
        sonarPick();
        return;
      case RELEASE:
        openGripper();
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

  /** Starts a choreographed move (triangle, circle, 360° spin…): its legs queue up as segments. */
  private void startScript(VoiceCommand command) {
    List<float[]> segs = command.toScript();
    if (segs == null || segs.isEmpty()) return;
    stopAllMotion(); // this move owns the wheels (clears any old script)
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

  /** Timed drive from the AI's move tool ("go forward 5 seconds"), auto-stop and resend. */
  private void startManualMove(VoiceCommand cmd) {
    float[] wheels = cmd.toWheels();
    if (wheels == null) return;
    if (!vehicle.isConnected()) {
      say(getString(R.string.voice_not_connected));
      return;
    }
    stopAllMotion(); // this move owns the wheels
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

  /** Stops whatever drives the wheels: manual move, trick script or the pick's creep. */
  private void stopManualMove() {
    pickActive = false;
    scriptQueue.clear();
    main.removeCallbacks(moveTick);
    main.removeCallbacks(pickTick);
    moveEndsAtMs = 0;
    moveLeft = 0;
    moveRight = 0;
    if (vehicle != null) vehicle.setControl(new Control(0, 0));
  }

  /**
   * Voice STOP and "one driver at a time": halt whatever drives the wheels (moves, trick scripts,
   * the pick's creep) and quiet the sensors. This page never follows the object on its own — only
   * voice commands (and the gripper buttons) move it.
   */
  private void stopAllMotion() {
    stopManualMove();
    if (vehicle != null && vehicle.isConnected()) vehicle.setSonarFrequency(0);
  }

  /** Raw wheel write for the timed moves (they resend on purpose). */
  private void sendWheels(float left, float right) {
    if (reversed) vehicle.setControl(new Control(left, right));
    else vehicle.setControl(new Control(-right, -left));
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

  // ------------------------------------------------------------------ lifecycle

  @Override
  public void onDestroyView() {
    micOn = false;
    stopMicSession(false);
    disconnectVoiceAi();
    releaseAiAudio();
    if (vehicle != null) stopManualMove(); // a voice move must not outlive the page
    main.removeCallbacksAndMessages(null);
    if (tts != null) {
      tts.shutdown();
      tts = null;
    }
    if (vehicle != null) {
      vehicle.setSonarFrequency(0);
    }
    if (detector != null) {
      detector.close();
      detector = null;
    }
    if (offlineStt != null) {
      offlineStt.stop();
      offlineStt = null;
    }
    localVoiceActive = false;
    aiUtteranceText = "";
    BotToControllerEventBus.emitEvent(ConnectionUtils.createStatus("FRAGMENT_TYPE", "CLOSE"));
    super.onDestroyView();
  }
}
