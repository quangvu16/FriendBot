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
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import java.util.List;
import java.util.Locale;
import org.openbot.app.robot.R;
import org.openbot.app.robot.common.CameraFragment;
import org.openbot.app.robot.databinding.FragmentVisionModeBinding;
import org.openbot.app.robot.sensorlink.RemoteSettings;
import org.openbot.app.robot.utils.Constants;
import org.openbot.app.robot.utils.Enums;
import org.openbot.app.robot.utils.PermissionUtils;
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

    binding.controllerContainer.speedInfo.setText(getString(R.string.speedInfo, "---,---"));
    binding.cameraToggle.setOnClickListener(v -> toggleCamera());
    binding.helpRow.setVisibility(getGuideId() == null ? View.GONE : View.VISIBLE);
    binding.helpButton.setOnClickListener(
        v -> {
          String guideId = getGuideId();
          if (guideId == null) return;
          Bundle args = new Bundle();
          args.putString(GuideFragment.ARG_GUIDE_ID, guideId);
          Navigation.findNavController(requireView()).navigate(R.id.guideFragment, args);
        });

    // USB / Bluetooth connection toggles, same as the Autopilot template.
    if (vehicle.getConnectionType().equals("USB")) {
      binding.usbToggle.setVisibility(View.VISIBLE);
      binding.bleToggle.setVisibility(View.GONE);
    } else if (vehicle.getConnectionType().equals("Bluetooth")) {
      binding.bleToggle.setVisibility(View.VISIBLE);
      binding.usbToggle.setVisibility(View.GONE);
    }
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

    // The control panel is the same hideable bottom sheet as the Autopilot page:
    // start expanded, drag the handle down to hide it.
    BottomSheetBehavior.from(binding.controlPanel).setState(BottomSheetBehavior.STATE_EXPANDED);

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

    setupModeControls();
    setStatus(autoMode ? getString(R.string.vision_running) : getString(R.string.vision_stopped));
  }

  /** Override to skip the VGA analyser default (e.g. when a mode wants HD). */
  protected boolean analyserResolutionWanted() {
    return true;
  }

  /** Builds the mode-specific row of controls in {@code binding.modePanel}. */
  protected abstract void setupModeControls();

  /**
   * Adds a "label [− value +]" row to the mode panel, laid out exactly like the Autopilot page's
   * Threads row: a label (black, default size, paddingStart 16dp, paddingVertical 12dp) and a
   * spinner_bg chip holding the ic_baseline_remove / ic_baseline_add icons around the value.
   */
  protected void addStepper(
      String label,
      int min,
      int max,
      int step,
      int initial,
      java.util.function.IntConsumer onChange) {
    LinearLayout row = newRow();
    row.addView(newRowLabel(label), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3f));

    final int[] value = {initial};

    LinearLayout chip = new LinearLayout(requireContext());
    chip.setOrientation(LinearLayout.HORIZONTAL);
    chip.setGravity(android.view.Gravity.START);
    chip.setBackgroundResource(R.drawable.spinner_bg);

    android.widget.ImageView minus = new android.widget.ImageView(requireContext());
    minus.setImageResource(R.drawable.ic_baseline_remove);
    minus.setContentDescription(getString(R.string.minus));

    android.widget.TextView valueView = new android.widget.TextView(requireContext());
    valueView.setText(String.valueOf(initial));
    valueView.setTextColor(android.graphics.Color.BLACK);
    valueView.setGravity(android.view.Gravity.CENTER);
    int pad = (int) (4 * density());
    valueView.setPadding(pad, pad, pad, pad);

    android.widget.ImageView plus = new android.widget.ImageView(requireContext());
    plus.setImageResource(R.drawable.ic_baseline_add);
    plus.setContentDescription(getString(R.string.plus));

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

    // Same weights as fragment_autopilot.xml: minus 1.5, value 3, plus 1.5.
    chip.addView(minus, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.5f));
    chip.addView(valueView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3f));
    chip.addView(plus, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.5f));
    row.addView(chip, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

    binding.modePanel.addView(row);
    onChange.accept(initial);
  }

  /**
   * Adds a "label [action]" row to the mode panel. The action is a spinner_bg chip with
   * openBotBlue text at the default size, the same chip style as the Threads stepper.
   */
  protected android.widget.Button addActionButton(String label, View.OnClickListener onClick) {
    LinearLayout row = newRow();
    row.addView(
        newRowLabel(getString(R.string.tile_target_tracking)),
        new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2f));

    android.widget.Button button = new android.widget.Button(requireContext());
    button.setText(label);
    button.setAllCaps(false);
    button.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.openBotBlue));
    button.setBackgroundResource(R.drawable.spinner_bg);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setMinHeight(0);
    button.setMinimumHeight(0);
    button.setStateListAnimator(null);
    button.setOnClickListener(onClick);
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(0, (int) (40 * density()), 2f);
    lp.gravity = android.view.Gravity.CENTER_VERTICAL;
    row.addView(button, lp);

    binding.modePanel.addView(row);
    return button;
  }

  /**
   * Adds a "label [colour swatches]" row to the mode panel: one tappable square per colour, the
   * selected one ringed in openBotBlue.
   */
  protected void addColorRow(
      String label, String[] names, int[] colors, java.util.function.IntConsumer onChange) {
    LinearLayout row = newRow();
    row.addView(
        newRowLabel(label),
        new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2f));

    LinearLayout swatches = new LinearLayout(requireContext());
    swatches.setOrientation(LinearLayout.HORIZONTAL);
    swatches.setGravity(android.view.Gravity.CENTER_VERTICAL);

    int chipSize = (int) (28 * density());
    int gap = (int) (5 * density());
    int ringWidth = (int) (3 * density());
    int ringColor =
        androidx.core.content.ContextCompat.getColor(requireContext(), R.color.openBotBlue);
    android.graphics.drawable.GradientDrawable[] backgrounds =
        new android.graphics.drawable.GradientDrawable[colors.length];

    for (int i = 0; i < colors.length; i++) {
      android.graphics.drawable.GradientDrawable background =
          new android.graphics.drawable.GradientDrawable();
      background.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
      background.setCornerRadius(8 * density());
      background.setColor(colors[i]);
      backgrounds[i] = background;

      View chip = new View(requireContext());
      chip.setBackground(background);
      chip.setContentDescription(names[i]);
      LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(chipSize, chipSize);
      chipLp.rightMargin = gap;
      final int index = i;
      chip.setOnClickListener(
          v -> {
            for (int j = 0; j < backgrounds.length; j++) {
              if (j == index) backgrounds[j].setStroke(ringWidth, ringColor);
              else backgrounds[j].setStroke((int) density(), 0xFFBBBBBB);
            }
            onChange.accept(colors[index]);
          });
      swatches.addView(chip, chipLp);
    }

    row.addView(
        swatches, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2f));
    binding.modePanel.addView(row);
    swatches.getChildAt(0).performClick();
  }

  /** A horizontal row with weightSum 4, like the rows of the Autopilot bottom sheet. */
  private LinearLayout newRow() {
    LinearLayout row = new LinearLayout(requireContext());
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setWeightSum(4f);
    row.setLayoutParams(
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    return row;
  }

  /** Row label in the Autopilot style: black, default size, paddingStart 16dp, paddingVertical 12dp. */
  private android.widget.TextView newRowLabel(String text) {
    android.widget.TextView tv = new android.widget.TextView(requireContext());
    tv.setText(text);
    tv.setTextColor(android.graphics.Color.BLACK);
    tv.setGravity(android.view.Gravity.CENTER_VERTICAL);
    int v = (int) (12 * density());
    tv.setPadding((int) (16 * density()), v, 0, v);
    return tv;
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
    // The robot's motor channels are cross-wired with inverted polarity: negating both wheels
    // fixes forward/back but also mirrors left/right. Negate-and-swap instead — that reverses the
    // throttle and keeps the commanded turn direction.
    vehicle.setControl(new Control(-control.getRight(), -control.getLeft()));
    float left = vehicle.getLeftSpeed();
    float right = vehicle.getRightSpeed();
    if (binding != null) {
      binding.controllerContainer.controlInfo.post(
          () ->
              binding.controllerContainer.controlInfo.setText(
                  String.format(Locale.US, "%.0f,%.0f", left, right)));
    }
  }

  /**
   * Steers toward the side of the screen where the target appears: left of the preview turns the
   * robot left, right of the preview turns it right.
   */
  protected void sendTurn(boolean screenLeft, String status) {
    setStatus((screenLeft ? "◀ " : "▶ ") + status);
    sendControl(screenLeft ? new Control(-1f, 1f) : new Control(1f, -1f));
  }

  /**
   * Sends a drive command exactly like the AI tab's Object Tracking page
   * (ObjectNavFragment.handleDriveCommand): the control goes to the vehicle as-is, with no
   * polarity or left/right correction. Repeats are still skipped, and lastControl is kept in
   * step so a later stop through sendControl(0, 0) is never skipped.
   */
  protected void sendTrackingControl(Control control) {
    if (control.getLeft() == lastControl.getLeft() && control.getRight() == lastControl.getRight()) {
      return;
    }
    lastControl = control;
    vehicle.setControl(control);
    float left = vehicle.getLeftSpeed();
    float right = vehicle.getRightSpeed();
    if (binding != null) {
      binding.controllerContainer.controlInfo.post(
          () ->
              binding.controllerContainer.controlInfo.setText(
                  String.format(Locale.US, "%.0f,%.0f", left, right)));
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
        binding.controllerContainer.speedMode.setImageResource(R.drawable.ic_speed_low);
        break;
      case NORMAL:
        binding.controllerContainer.speedMode.setImageResource(R.drawable.ic_speed_medium);
        break;
      case FAST:
        binding.controllerContainer.speedMode.setImageResource(R.drawable.ic_speed_high);
        break;
    }
    preferencesManager.setSpeedMode(speedMode.getValue());
    vehicle.setSpeedMultiplier(speedMode.getValue());
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

  @Override
  protected void processUSBData(String data) {
    binding.controllerContainer.speedInfo.setText(
        getString(
            R.string.speedInfo,
            String.format(
                Locale.US, "%3.0f,%3.0f", vehicle.getLeftWheelRpm(), vehicle.getRightWheelRpm())));
  }

  @Override
  protected void processControllerKeyData(String commandType) {
    switch (commandType) {
      case Constants.CMD_DRIVE:
        binding.controllerContainer.controlInfo.setText(
            String.format(Locale.US, "%.0f,%.0f", vehicle.getLeftSpeed(), vehicle.getRightSpeed()));
        break;

      case Constants.CMD_DRIVE_MODE:
        setDriveMode(Enums.switchDriveMode(vehicle.getDriveMode()));
        break;

      case Constants.CMD_SPEED_DOWN:
        setSpeedMode(
            Enums.toggleSpeed(
                Enums.Direction.DOWN.getValue(),
                Enums.SpeedMode.getByID(preferencesManager.getSpeedMode())));
        break;

      case Constants.CMD_SPEED_UP:
        setSpeedMode(
            Enums.toggleSpeed(
                Enums.Direction.UP.getValue(),
                Enums.SpeedMode.getByID(preferencesManager.getSpeedMode())));
        break;
    }
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
