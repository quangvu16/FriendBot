package org.openbot.app.robot.vision;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.ImageProxy;
import androidx.navigation.Navigation;
import java.util.List;
import java.util.Locale;
import org.openbot.app.robot.R;
import org.openbot.app.robot.common.CameraFragment;
import org.openbot.app.robot.databinding.FragmentVisionModeBinding;
import org.openbot.app.robot.sensorlink.RemoteSettings;
import org.openbot.app.robot.utils.Enums;
import org.openbot.app.robot.vehicle.Control;

/**
 * Shared plumbing for the camera-based vision modes (follow coloured object, follow face, follow
 * black line, object avoidance, target tracking): the camera overlay, the Auto / speed / camera
 * flip / help controls, and de-duplicated drive output to the vehicle.
 *
 * <p>The perception runs in {@link #processVisionFrame(Bitmap, ImageProxy)} on the camera thread;
 * subclasses report boxes in analysis-frame coordinates via {@link #showBoxes} and drive with
 * {@link #sendControl(Control)}.
 */
public abstract class VisionModeFragment extends CameraFragment {

  protected FragmentVisionModeBinding binding;
  // Written on the UI thread, read on the camera thread.
  protected volatile boolean autoMode = false;

  private volatile Control lastControl = new Control(0, 0);
  private float[] mapScratch = new float[2];

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentVisionModeBinding.inflate(inflater, container, false);
    return inflateFragment(binding, inflater, container);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    // Perception runs at SD by default — plenty for these modes and cheap to scan.
    if (analyserResolutionWanted()) {
      setAnalyserResolution(Enums.Preview.SD.getValue());
    }

    binding.autoSwitch.setOnClickListener(v -> setAutoMode(binding.autoSwitch.isChecked()));
    binding.cameraToggle.setOnClickListener(v -> toggleCamera());
    binding.helpButton.setOnClickListener(
        v -> {
          String guideId = getGuideId();
          if (guideId == null) return;
          Bundle args = new Bundle();
          args.putString(GuideFragment.ARG_GUIDE_ID, guideId);
          Navigation.findNavController(requireView()).navigate(R.id.guideFragment, args);
        });
    binding.speedMode.setOnClickListener(
        v ->
            setSpeedMode(
                Enums.toggleSpeed(
                    Enums.Direction.CYCLIC.getValue(),
                    Enums.SpeedMode.getByID(preferencesManager.getSpeedMode()))));
    setSpeedMode(Enums.SpeedMode.getByID(preferencesManager.getSpeedMode()));

    setupModeControls();
    setStatus(autoMode ? getString(R.string.vision_running) : getString(R.string.vision_stopped));
  }

  /** Override to skip the VGA analyser default (e.g. when a mode wants HD). */
  protected boolean analyserResolutionWanted() {
    return true;
  }

  /** Builds the mode-specific row of controls in {@code binding.modePanel}. */
  protected abstract void setupModeControls();

  /** Adds a "label [−] value [+]" stepper to the mode panel. */
  protected void addStepper(
      String label,
      int min,
      int max,
      int step,
      int initial,
      java.util.function.IntConsumer onChange) {
    android.widget.TextView labelView = new android.widget.TextView(requireContext());
    labelView.setText(label);
    labelView.setTextColor(android.graphics.Color.WHITE);
    labelView.setTextSize(13);
    labelView.setPadding(0, 0, (int) (8 * density()), 0);
    binding.modePanel.addView(labelView);

    final int[] value = {initial};
    android.widget.TextView valueView = new android.widget.TextView(requireContext());
    valueView.setText(String.valueOf(initial));
    valueView.setTextColor(android.graphics.Color.WHITE);
    valueView.setTextSize(14);
    valueView.setPadding((int) (6 * density()), 0, (int) (6 * density()), 0);

    android.widget.Button minus = new android.widget.Button(requireContext());
    minus.setText("−");
    minus.setAllCaps(false);
    minus.setTextColor(android.graphics.Color.WHITE);
    minus.setMinWidth(0);
    minus.setMinimumWidth(0);
    minus.setPadding(0, 0, 0, 0);
    minus.setBackgroundResource(R.drawable.button_item_background_black);
    minus.setBackgroundTintList(
        android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#33FFFFFF")));

    android.widget.Button plus = new android.widget.Button(requireContext());
    plus.setText("+");
    plus.setAllCaps(false);
    plus.setTextColor(android.graphics.Color.WHITE);
    plus.setMinWidth(0);
    plus.setMinimumWidth(0);
    plus.setPadding(0, 0, 0, 0);
    plus.setBackgroundResource(R.drawable.button_item_background_black);
    plus.setBackgroundTintList(
        android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#33FFFFFF")));

    minus.setOnClickListener(
        v -> {
          if (value[0] <= min) return;
          value[0] = Math.max(min, value[0] - step);
          valueView.setText(String.valueOf(value[0]));
          onChange.accept(value[0]);
        });
    plus.setOnClickListener(
        v -> {
          if (value[0] >= max) return;
          value[0] = Math.min(max, value[0] + step);
          valueView.setText(String.valueOf(value[0]));
          onChange.accept(value[0]);
        });

    binding.modePanel.addView(minus);
    binding.modePanel.addView(valueView);
    binding.modePanel.addView(plus);
    onChange.accept(initial);
  }

  /** Adds a small action button to the mode panel. */
  protected android.widget.Button addActionButton(String label, View.OnClickListener onClick) {
    android.widget.Button button = new android.widget.Button(requireContext());
    button.setText(label);
    button.setAllCaps(false);
    button.setTextColor(android.graphics.Color.WHITE);
    button.setTextSize(13);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setBackgroundResource(R.drawable.button_item_background_black);
    button.setBackgroundTintList(
        android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#33FFFFFF")));
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.setMarginEnd((int) (8 * density()));
    button.setLayoutParams(lp);
    button.setOnClickListener(onClick);
    binding.modePanel.addView(button);
    return button;
  }

  protected float density() {
    return getResources().getDisplayMetrics().density;
  }

  /** Navigation id of the instruction guide that goes with this mode, or null when there is none. */
  @Nullable
  protected abstract String getGuideId();

  /** Runs on the camera thread for every frame while Auto is on. */
  protected abstract void processVisionFrame(Bitmap frame, ImageProxy proxy);

  @Override
  protected final void processFrame(Bitmap image, ImageProxy proxy) {
    if (!autoMode || binding == null) return;
    processVisionFrame(image, proxy);
  }

  /** Turns Auto on or off and stops the car when turning it off. */
  protected void setAutoMode(boolean enabled) {
    autoMode = enabled;
    if (binding != null) binding.autoSwitch.setChecked(enabled);
    emitNetworkStatus(enabled);
    if (!enabled) {
      sendControl(new Control(0, 0));
      setStatus(getString(R.string.vision_stopped));
    } else {
      setStatus(getString(R.string.vision_running));
    }
  }

  @Override
  protected void handleNetworkCommand() {
    setAutoMode(!autoMode);
  }

  @Override
  protected boolean isNetworkModeEnabled() {
    return autoMode;
  }

  /** Sends a drive command, skipping repeats (the car only hears changes). */
  protected void sendControl(Control control) {
    if (control.getLeft() == lastControl.getLeft() && control.getRight() == lastControl.getRight()) {
      return;
    }
    lastControl = control;
    vehicle.setControl(control);
    float left = vehicle.getLeftSpeed();
    float right = vehicle.getRightSpeed();
    if (binding != null) {
      binding.infoText.post(
          () ->
              binding.infoText.setText(
                  String.format(Locale.US, "left %.0f, right %.0f", left, right)));
    }
  }

  /** Status line in the top-left corner; safe from any thread. */
  protected void setStatus(String status) {
    if (binding != null) binding.statusText.post(() -> binding.statusText.setText(status));
  }

  /** Publishes boxes (analysis-frame coordinates) on the overlay; safe from any thread. */
  protected void showBoxes(List<VisionOverlayView.Box> boxes) {
    if (binding == null) return;
    binding.overlay.setBoxes(boxes);
    binding.overlay.postInvalidate();
  }

  /** Makes sure the overlay knows the frame geometry before the first draw. */
  protected void ensureFrameConfiguration() {
    if (binding == null) return;
    binding.overlay.setMirrored(isFront());
    binding.overlay.setFrameConfiguration(
        getMaxAnalyseImageSize().getWidth(),
        getMaxAnalyseImageSize().getHeight(),
        getSensorOrientation());
  }

  /** Sensor orientation in the same convention the tracker uses (90 minus screen orientation). */
  protected int getSensorOrientation() {
    return 90 - org.openbot.app.robot.env.ImageUtils.getScreenOrientation(requireActivity());
  }

  /** Maps an analysis-frame point to view coordinates; returns false if not mappable. */
  protected boolean frameToView(float frameX, float frameY, float[] outXY) {
    return binding != null && binding.overlay.frameToView(frameX, frameY, outXY);
  }

  /** The fitted preview rectangle in view coordinates. */
  protected android.graphics.RectF getFittedFrameRect() {
    return binding == null ? null : binding.overlay.getFittedFrameRect();
  }

  protected void setSpeedMode(Enums.SpeedMode speedMode) {
    if (speedMode == null || binding == null) return;
    switch (speedMode) {
      case SLOW:
        binding.speedMode.setImageResource(R.drawable.ic_speed_low);
        break;
      case NORMAL:
        binding.speedMode.setImageResource(R.drawable.ic_speed_medium);
        break;
      case FAST:
        binding.speedMode.setImageResource(R.drawable.ic_speed_high);
        break;
    }
    preferencesManager.setSpeedMode(speedMode.getValue());
    vehicle.setSpeedMultiplier(speedMode.getValue());
  }

  @Override
  protected void processUSBData(String data) {
    // The vision modes do not surface wheel telemetry.
  }

  @Override
  protected void processControllerKeyData(String commandType) {
    // The vision modes do not surface controller telemetry.
  }

  @Override
  public String remoteScreenName() {
    return getString(R.string.app_name);
  }

  @Override
  public void onDestroyView() {
    sendControl(new Control(0, 0));
    super.onDestroyView();
    binding = null;
  }
}
