package org.openbot.app.robot.vision;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.camera.core.ImageProxy;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.openbot.app.robot.R;
import org.openbot.app.robot.env.ImageUtils;
import org.openbot.app.robot.tflite.Detector;
import org.openbot.app.robot.tflite.Model;
import org.openbot.app.robot.tflite.Network;
import org.openbot.app.robot.utils.CameraUtils;
import org.openbot.app.robot.vehicle.Control;
import timber.log.Timber;

/**
 * Grab Object: the phone's on-device AI model (the same detector as Object Tracking: MobileNet SSD,
 * 80 everyday objects) finds the chosen object, the robot steers to it, the <b>front sonar</b>
 * measures the real distance, and at the grip distance the robot stops and closes the gripper.
 * All decisions are in {@link GrabSteering} (tested in a simulation); this page feeds it boxes
 * and sonar readings and sends its wheel speeds, approach mode and grip to the robot.
 *
 * <p>Pick the object and the grip distance in the panel, turn Auto on. Release / Grip buttons
 * work any time. If the robot turns away from the object, press "Flip steering" once.
 */
public class GrabObjectFragment extends VisionModeFragment {

  private static final float MIN_CONFIDENCE = 0.5f;
  private static final long APPROACH_RESEND_MS = 2000;
  private static final int APPROACH_MS = 3000;
  private static final String PREFS = "grab_object";

  // Detector (created on the camera thread on the first frame).
  private Detector detector;
  private Bitmap croppedBitmap;
  private Matrix frameToCrop;
  private Matrix cropToFrame;
  private volatile String target = "cup";
  private volatile float grabCm = 8f;
  private volatile boolean flipSteering;
  private volatile GrabSteering steering;
  private long lastApproachMs;
  private boolean holdingAnnounced;
  private TextView distanceText;

  @Override
  protected String getGuideId() {
    return null; // the help button shows a short hint instead
  }

  @Override
  protected void setupModeControls() {
    android.content.SharedPreferences p =
        requireContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    target = p.getString("object", "cup");
    grabCm = p.getInt("grab_cm", 8);
    flipSteering = p.getBoolean("flip", false);
    binding.overlay.setGuides(new float[] {0.39f, 0.61f}); // the "centred" band

    addObjectRow(p);
    addStepper(
        getString(R.string.grab_distance),
        4,
        20,
        1,
        (int) grabCm,
        v -> {
          grabCm = v;
          GrabSteering s = steering;
          if (s != null) s.setGrabCm(v);
          p.edit().putInt("grab_cm", v).apply();
        });
    addButtonsRow(
        new String[] {getString(R.string.pro_grip), getString(R.string.pro_release), getString(R.string.grab_flip)},
        new View.OnClickListener[] {
          v -> manualGrip(true),
          v -> manualGrip(false),
          v -> {
            flipSteering = !flipSteering;
            p.edit().putBoolean("flip", flipSteering).apply();
            Toast.makeText(
                    requireContext(),
                    flipSteering ? R.string.grab_flip_on : R.string.grab_flip_off,
                    Toast.LENGTH_SHORT)
                .show();
          }
        });
    distanceText = new TextView(requireContext());
    distanceText.setTextColor(Color.BLACK);
    int pad = (int) (12 * density());
    distanceText.setPadding((int) (16 * density()), pad, pad, pad);
    distanceText.setText(getString(R.string.grab_sonar, "—"));
    binding.modePanel.addView(distanceText);

    binding.helpButton.setOnClickListener(
        v -> Toast.makeText(requireContext(), R.string.grab_hint, Toast.LENGTH_LONG).show());
    setStatus(getString(R.string.grab_idle, target));
  }

  /** "Object  [cup ▾]": every label the AI model knows. */
  private void addObjectRow(android.content.SharedPreferences p) {
    LinearLayout row = new LinearLayout(requireContext());
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setWeightSum(4f);
    TextView label = new TextView(requireContext());
    label.setText(R.string.grab_object);
    label.setTextColor(Color.BLACK);
    label.setGravity(Gravity.CENTER_VERTICAL);
    int v = (int) (12 * density());
    label.setPadding((int) (16 * density()), v, 0, v);
    row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2f));
    Spinner spinner = new Spinner(requireContext());
    spinner.setBackgroundResource(R.drawable.spinner_bg);
    List<String> labels = new ArrayList<>(GRABBABLE);
    if (!labels.contains(target)) labels.add(0, target);
    ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), R.layout.spinner_item, labels);
    adapter.setDropDownViewResource(android.R.layout.simple_dropdown_item_1line);
    spinner.setAdapter(adapter);
    spinner.setSelection(Math.max(0, labels.indexOf(target)));
    spinner.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          @Override
          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            String picked = parent.getItemAtPosition(position).toString();
            if (picked.equals(target)) return;
            target = picked;
            p.edit().putString("object", picked).apply();
            if (autoMode) setAutoMode(false); // a new object: start again on purpose
            setStatus(getString(R.string.grab_idle, target));
          }

          @Override
          public void onNothingSelected(AdapterView<?> parent) {}
        });
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (40 * density()), 2f);
    lp.gravity = Gravity.CENTER_VERTICAL;
    row.addView(spinner, lp);
    binding.modePanel.addView(row);
  }

  private void addButtonsRow(String[] texts, View.OnClickListener[] actions) {
    LinearLayout row = new LinearLayout(requireContext());
    row.setOrientation(LinearLayout.HORIZONTAL);
    int pad = (int) (8 * density());
    row.setPadding(pad, pad / 2, pad, pad / 2);
    for (int i = 0; i < texts.length; i++) {
      Button b = new Button(requireContext());
      b.setText(texts[i]);
      b.setAllCaps(false);
      b.setTextColor(ContextCompat.getColor(requireContext(), R.color.openBotBlue));
      b.setBackgroundResource(R.drawable.spinner_bg);
      b.setStateListAnimator(null);
      b.setOnClickListener(actions[i]);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (40 * density()), 1f);
      lp.setMargins(pad / 2, 0, pad / 2, 0);
      row.addView(b, lp);
    }
    binding.modePanel.addView(row);
  }

  /** Objects the default COCO model knows that a small claw can hold. */
  static final List<String> GRABBABLE =
      java.util.Arrays.asList(
          "cup", "bottle", "sports ball", "apple", "orange", "banana", "teddy bear", "mouse",
          "remote", "cell phone", "book", "scissors", "toothbrush", "wine glass", "bowl", "donut",
          "carrot", "spoon", "clock", "vase");

  private void manualGrip(boolean close) {
    if (!vehicle.isConnected()) {
      Toast.makeText(requireContext(), R.string.voice_not_connected, Toast.LENGTH_SHORT).show();
      return;
    }
    vehicle.sendGripper(close);
    if (!close && steering != null) {
      steering.reset(); // released: ready for the next object
      holdingAnnounced = false;
    }
  }

  @Override
  protected void setAutoMode(boolean enabled) {
    if (enabled) {
      steering = new GrabSteering(target, grabCm);
      holdingAnnounced = false;
      lastApproachMs = 0;
      vehicle.sendGripper(false); // open, ready to grab
      vehicle.setSonarFrequency(100); // live distance: the sonar decides when to grip
      setStatus(getString(R.string.grab_searching, target));
    } else {
      vehicle.sendApproach(0);
      vehicle.setSonarFrequency(0);
    }
    super.setAutoMode(enabled);
  }

  // ------------------------------------------------------------------ camera thread

  @Override
  protected void processVisionFrame(Bitmap frame, ImageProxy proxy) {
    ensureFrameConfiguration();
    if (detector == null && !createDetector()) {
      setStatus(getString(R.string.grab_no_model));
      sendTrackingControl(new Control(0, 0));
      return;
    }
    GrabSteering s = steering;
    if (s == null) return;

    Canvas canvas = new Canvas(croppedBitmap);
    canvas.drawBitmap(isFront() ? CameraUtils.flipBitmapHorizontal(frame) : frame, frameToCrop, null);
    List<Detector.Recognition> results;
    try {
      results = detector.recognizeImage(croppedBitmap, target);
    } catch (IllegalArgumentException e) {
      results = new ArrayList<>();
    }
    Detector.Recognition best = null;
    for (Detector.Recognition r : results) {
      if (r.getLocation() == null || r.getConfidence() < MIN_CONFIDENCE) continue;
      if (!target.equals(r.getTitle())) continue;
      if (best == null || r.getConfidence() > best.getConfidence()) best = r;
    }

    boolean seen = false;
    float offset = 0f;
    float height = 0f;
    List<VisionOverlayView.Box> boxes = new ArrayList<>();
    RectF fitted = getFittedFrameRect();
    if (best != null && fitted != null) {
      RectF box = new RectF(best.getLocation());
      cropToFrame.mapRect(box); // detector input → analysis frame
      float[] a = new float[2];
      float[] b = new float[2];
      if (frameToView(box.left, box.top, a) && frameToView(box.right, box.bottom, b)) {
        RectF view = new RectF(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1]));
        offset = (view.centerX() - fitted.centerX()) / (fitted.width() / 2f);
        height = view.height() / fitted.height();
        seen = true;
        boxes.add(
            new VisionOverlayView.Box(
                box,
                String.format(Locale.US, "%s %.0f%%", target, best.getConfidence() * 100),
                Color.GREEN,
                5f));
      }
    }
    showBoxes(boxes);

    float sonar = vehicle.getSonarDistanceCm();
    long now = SystemClock.uptimeMillis();
    GrabSteering.Out out = s.step(now, seen, offset, height, sonar);
    if (out.approach && now - lastApproachMs > APPROACH_RESEND_MS) {
      lastApproachMs = now;
      vehicle.sendApproach(APPROACH_MS); // firmware: reach 4 cm instead of stopping at 15 cm
    }
    if (out.grip) {
      sendTrackingControl(new Control(0, 0));
      vehicle.sendGripper(true);
    } else {
      float l = flipSteering ? out.right : out.left;
      float r = flipSteering ? out.left : out.right;
      sendTrackingControl(new Control(l, r));
    }
    setStatus(out.status);
    if (distanceText != null) {
      String d = sonar > 0 ? String.format(Locale.US, "%.0f cm", sonar) : "—";
      distanceText.post(() -> distanceText.setText(getString(R.string.grab_sonar, d)));
    }
    if ((out.state == GrabSteering.State.HOLDING || out.state == GrabSteering.State.LOST)
        && !holdingAnnounced) {
      holdingAnnounced = true;
      boolean holding = out.state == GrabSteering.State.HOLDING;
      binding.statusText.post(
          () -> {
            setAutoMode(false);
            setStatus(holding ? getString(R.string.grab_holding, target) : getString(R.string.grab_lost, target));
          });
    }
  }

  /** The first DETECTOR model on the phone (the Object Tracking choice when it is one). */
  private boolean createDetector() {
    try {
      Model model = null;
      String wanted = preferencesManager.getObjectNavModel();
      for (Model m : masterList) {
        if (m.type != Model.TYPE.DETECTOR || m.pathType == Model.PATH_TYPE.URL) continue;
        if (model == null) model = m;
        if (wanted != null && !wanted.isEmpty() && m.name.contains(wanted)) {
          model = m;
          break;
        }
      }
      if (model == null) return false;
      detector = Detector.create(requireActivity(), model, Network.Device.CPU, 2);
      croppedBitmap =
          Bitmap.createBitmap(detector.getImageSizeX(), detector.getImageSizeY(), Bitmap.Config.ARGB_8888);
      frameToCrop =
          ImageUtils.getTransformationMatrix(
              getMaxAnalyseImageSize().getWidth(),
              getMaxAnalyseImageSize().getHeight(),
              croppedBitmap.getWidth(),
              croppedBitmap.getHeight(),
              getSensorOrientation(),
              detector.getCropRect(),
              detector.getMaintainAspect());
      cropToFrame = new Matrix();
      frameToCrop.invert(cropToFrame);
      Timber.i("Grab Object: detector %s ready", model.name);
      return true;
    } catch (Exception e) {
      Timber.e(e, "Grab Object: no detector");
      detector = null;
      return false;
    }
  }

  @Override
  public void onDestroyView() {
    if (vehicle != null) {
      vehicle.sendApproach(0);
      vehicle.setSonarFrequency(0);
    }
    if (detector != null) {
      detector.close();
      detector = null;
    }
    super.onDestroyView();
  }

  @Nullable
  @Override
  public String remoteScreenName() {
    return "Grab Object";
  }
}
