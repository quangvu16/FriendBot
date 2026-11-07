package org.openbot.app.robot.robot;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.Nullable;
import androidx.navigation.Navigation;
import org.jetbrains.annotations.NotNull;
import org.openbot.app.robot.R;
import org.openbot.app.robot.common.ControlsFragment;
import org.openbot.app.robot.databinding.FragmentRobotInfoBinding;
import org.openbot.app.robot.vehicle.RcMode;

/**
 * Car setup for the ESP32-S3 Ackermann car. Shows what the firmware reports: the vehicle type and
 * drive style from its 'f' reply, the link state, the RC receiver mode (once 'm' messages arrive),
 * and the object-avoidance standoff controls, plus motor and steering test buttons.
 */
public class RobotInfoFragment extends ControlsFragment {
  private FragmentRobotInfoBinding binding;
  private RcMode rcMode;

  @Override
  public View onCreateView(
      @NotNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentRobotInfoBinding.inflate(inflater, container, false);
    return binding.getRoot();
  }

  @SuppressLint("RestrictedApi")
  @Override
  public void onViewCreated(@NotNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    setupSensorLinkControls(binding.sensorLink.getRoot());

    if (vehicle == null) {
      mViewModel
          .getVehicle()
          .observe(
              getViewLifecycleOwner(),
              v -> {
                if (v != null && vehicle == null) {
                  vehicle = v;
                  setupRobotInfoUi();
                }
              });
      return;
    }
    setupRobotInfoUi();
  }

  private void setupRobotInfoUi() {
    if (vehicle == null || binding == null) {
      return;
    }

    String connectionType = vehicle.getConnectionType();
    if (connectionType != null && connectionType.equals("USB")) {
      binding.usbToggle.setVisibility(View.VISIBLE);
      binding.bleToggle.setVisibility(View.GONE);
    } else if (connectionType != null && connectionType.equals("Bluetooth")) {
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

    binding.usbToggle.setOnCheckedChangeListener((buttonView, isChecked) -> refreshGui());
    binding.bleToggle.setOnCheckedChangeListener((buttonView, isChecked) -> refreshGui());
    binding.refreshToggle.setOnClickListener(v -> refreshGui());

    binding.motorsForwardButton.setOnClickListener(v -> vehicle.setControl(0.75f, 0.75f));

    binding.motorsBackwardButton.setOnClickListener(v -> vehicle.setControl(-0.75f, -0.75f));

    binding.motorsStopButton.setOnClickListener(v -> vehicle.setControl(0.0f, 0.0f));

    // Steering test with the wheels still: the servo turns for about a second (the heartbeat then
    // stops and straightens the car). Same throttle/steer mixing as everywhere else in the app.
    binding.steerLeftButton.setOnClickListener(v -> steer(1));
    binding.steerCenterButton.setOnClickListener(v -> steer(0));
    binding.steerRightButton.setOnClickListener(v -> steer(-1));

    mViewModel.getRcMode().observe(getViewLifecycleOwner(), m -> {
      rcMode = m;
      showRcMode();
    });

    // AVOID_MARGIN analog. The quick-disable switch is on FreeRoam where it can be reached in a
    // hurry; this is the standoff itself.
    float margin = preferencesManager.getObjectAvoidanceMargin();
    binding.avoidMarginSlider.setValue(margin);
    binding.avoidMarginInfo.setText(getString(R.string.avoidMarginValue, margin));
    vehicle.setObjectAvoidanceMargin(margin);
    binding.avoidMarginSlider.addOnChangeListener(
        (slider, value, fromUser) -> {
          vehicle.setObjectAvoidanceMargin(value);
          binding.avoidMarginInfo.setText(getString(R.string.avoidMarginValue, value));
          if (fromUser) {
            preferencesManager.setObjectAvoidanceMargin(value);
            vehicle.sendControl();
          }
        });

    // AVOID_BACKUP_SPD analog. Clamped into the slider's range so an old or hand-edited preference
    // can never crash the Slider (it throws on out-of-range values) and snapped to its 0.05 step.
    float backup = preferencesManager.getObjectAvoidanceBackup();
    backup = Math.max(0f, Math.min(0.5f, Math.round(backup * 20f) / 20f));
    binding.avoidBackupSlider.setValue(backup);
    vehicle.setObjectAvoidanceBackupSpeed(backup);
    showBackupSpeed(backup);
    binding.avoidBackupSlider.addOnChangeListener(
        (slider, value, fromUser) -> {
          vehicle.setObjectAvoidanceBackupSpeed(value);
          showBackupSpeed(value);
          if (fromUser) {
            preferencesManager.setObjectAvoidanceBackup(value);
            vehicle.sendControl();
          }
        });

    refreshGui();
  }

  private void steer(double s) {
    // Throttle/steer tank mix: left = (throttle - steer)/2, right = (throttle + steer)/2.
    float left = (float) ((0 - s) / 2);
    float right = (float) ((0 + s) / 2);
    left = Math.max(-1f, Math.min(1f, left));
    right = Math.max(-1f, Math.min(1f, right));
    vehicle.setControl(left, right);
  }

  private void showRcMode() {
    if (binding == null) return;
    binding.featRc.setChecked(rcMode != null);
    if (rcMode == null) {
      binding.rcModeInfo.setText(R.string.n_a);
      return;
    }
    switch (rcMode) {
      case MANUAL:
        binding.rcModeInfo.setText(R.string.setup_rc_manual);
        break;
      case AUTO:
        binding.rcModeInfo.setText(R.string.setup_rc_auto);
        break;
      case HOLD:
        binding.rcModeInfo.setText(R.string.setup_rc_hold);
        break;
      default:
        binding.rcModeInfo.setText(R.string.setup_rc_lost);
        break;
    }
  }

  private void showLink() {
    if (vehicle.isUsbConnected()) binding.linkInfo.setText(R.string.setup_link_usb);
    else if (vehicle.bleConnected()) binding.linkInfo.setText(R.string.setup_link_ble);
    else binding.linkInfo.setText(R.string.setup_link_none);
  }

  private void showBackupSpeed(float value) {
    if (value <= 0f) {
      binding.avoidBackupInfo.setText(R.string.avoidBackupOff);
    } else {
      binding.avoidBackupInfo.setText(getString(R.string.avoidBackupValue, value * 100f));
    }
  }

  private void refreshGui() {
    if (vehicle == null || binding == null) {
      return;
    }
    updateGui(false);
    binding.refreshToggle.setChecked(false);
    if (vehicle.isReady()) {
      vehicle.requestVehicleConfig();
    }
  }

  private void updateGui(boolean isConnected) {
    showLink();
    if (isConnected) {
      String type = vehicle.getVehicleType();
      binding.robotTypeInfo.setText(type);
      boolean ackermann = type != null && type.startsWith("RC_");
      binding.robotIcon.setImageResource(ackermann ? R.drawable.rc_car : R.drawable.ic_openbot);
      binding.driveInfo.setText(ackermann ? R.string.setup_drive_ackermann : R.string.setup_drive_unknown);
    } else {
      binding.robotTypeInfo.setText(getString(R.string.n_a));
      binding.robotIcon.setImageResource(R.drawable.ic_openbot);
      binding.driveInfo.setText(R.string.setup_drive_unknown);
    }
    showRcMode();
  }

  @Override
  protected void processControllerKeyData(String command) {
    // Do nothing
  }

  @Override
  protected void processUSBData(String data) {
    if (vehicle == null || binding == null) {
      return;
    }
    if (!vehicle.isReady()) {
      vehicle.setReady(true);
      vehicle.requestVehicleConfig();
    }
    char header = data.charAt(0);
    // String body = data.substring(1);
    // int type = -1;
    switch (header) {
      case 'r':
        vehicle.requestVehicleConfig();
      case 'f':
        binding.refreshToggle.setChecked(vehicle.isReady());
        updateGui(vehicle.isReady());
        break;
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    binding.bleToggle.setChecked(vehicle.bleConnected());
  }

  @Override
  protected String remoteScreenName() {
    return "Car setup";
  }
}
