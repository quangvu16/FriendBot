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
    labelView.setTextColor(android.graphics.Color.BLACK);
    labelView.setTextSize(13);
    labelView.setPadding(0, 0, (int) (8 * density()), 0);
    binding.modePanel.addView(labelView);

    final int[] value = {initial};
    android.widget.TextView valueView = new android.widget.TextView(requireContext());
    valueView.setText(String.valueOf(initial));
    valueView.setTextColor(android.graphics.Color.BLACK);
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
        androidx.core.content.ContextCompat.getColorStateList(requireContext(), R.color.openBotBlue));

    android.widget.Button plus = new android.widget.Button(requireContext());
    plus.setText("+");
    plus.setAllCaps(false);
    plus.setTextColor(android.graphics.Color.WHITE);
    plus.setMinWidth(0);
    plus.setMinimumWidth(0);
    plus.setPadding(0, 0, 0, 0);
    plus.setBackgroundResource(R.drawable.button_item_background_black);
    plus.setBackgroundTintList(
        androidx.core.content.ContextCompat.getColorStateList(requireContext(), R.color.openBotBlue));

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
        androidx.core.content.ContextCompat.getColorStateList(requireContext(), R.color.openBotBlue));
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
    // The robot's drive polarity is opposite the app convention: negate both
    // wheels so the commanded motion is what the robot physically does.
    vehicle.setControl(new Control(-control.getLeft(), -control.getRight()));
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
