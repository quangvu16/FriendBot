package org.openbot.app.robot.hardware;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.Nullable;
import org.jetbrains.annotations.NotNull;
import org.openbot.app.robot.R;
import org.openbot.app.robot.common.ControlsFragment;
import org.openbot.app.robot.databinding.FragmentHardwareTestBinding;
import org.openbot.app.robot.vehicle.Vehicle;

/**
 * Hardware Test (General tab): toggle the gripper and the two LEDs, hold-to-drive the motors, and
 * watch the middle sonar report live while the page is open. Everything here is a plain firmware
 * command: g0/g1/g2 for the claw, i&lt;left&gt;,&lt;right&gt; for the LEDs, s&lt;ms&gt; for the sonar
 * stream and c&lt;left&gt;,&lt;right&gt; for the drive.
 */
public class HardwareTestFragment extends ControlsFragment {
  private FragmentHardwareTestBinding binding;

  /** Motors: a held direction button stops on its own if the user keeps holding past this. */
  private static final long MOTOR_MAX_HOLD_MS = 3000;

  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  private final Runnable motorAutoStop = () -> stopMotors();

  /** LED states the switches show; the firmware does not echo them back, so we keep them here. */
  private boolean leftLedOn = false;

  private boolean rightLedOn = false;

  /** True while {@link #refreshDisplay()} writes the gripper switch, so the listener stays quiet. */
  private boolean syncingUi = false;

  @Override
  public View onCreateView(
      @NotNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentHardwareTestBinding.inflate(inflater, container, false);
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
                  setupHardwareTestUi();
                }
              });
      return;
    }
    setupHardwareTestUi();
  }

  private void setupHardwareTestUi() {
    if (vehicle == null || binding == null) {
      return;
    }

    binding.gripperSwitch.setOnCheckedChangeListener(
        (buttonView, isChecked) -> {
          if (syncingUi) return;
          vehicle.sendGripper(isChecked);
        });

    binding.leftLedSwitch.setOnCheckedChangeListener(
        (buttonView, isChecked) -> {
          if (syncingUi) return;
          leftLedOn = isChecked;
          sendLeds();
        });
    binding.rightLedSwitch.setOnCheckedChangeListener(
        (buttonView, isChecked) -> {
          if (syncingUi) return;
          rightLedOn = isChecked;
          sendLeds();
        });

    // Motors: hold a direction to drive. setControl respects the e-stop latch and the speed
    // multiplier, so this page drives exactly like every other page — the hint points at RESUME
    // for exactly that reason.
    binding.motorLeft.setOnTouchListener((v, event) -> holdToDrive(event, -1f, 1f));
    binding.motorForward.setOnTouchListener((v, event) -> holdToDrive(event, 1f, 1f));
    binding.motorBack.setOnTouchListener((v, event) -> holdToDrive(event, -1f, -1f));
    binding.motorRight.setOnTouchListener((v, event) -> holdToDrive(event, 1f, -1f));
    binding.motorStop.setOnClickListener(v -> stopMotors());

    refreshDisplay();
    startReports();
  }

  /** Drives while the direction button is held; any release (or the 3 s cap) stops at once. */
  private boolean holdToDrive(MotionEvent event, float left, float right) {
    switch (event.getAction()) {
      case MotionEvent.ACTION_DOWN:
        if (vehicle != null) {
          vehicle.setControl(left, right);
        }
        mainHandler.removeCallbacks(motorAutoStop);
        mainHandler.postDelayed(motorAutoStop, MOTOR_MAX_HOLD_MS);
        return true;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        stopMotors();
        return true;
      default:
        return false;
    }
  }

  /** Wheels off: the release handler, the auto-stop timer and the way out of the page. */
  private void stopMotors() {
    mainHandler.removeCallbacks(motorAutoStop);
    if (vehicle != null) {
      vehicle.setControl(0, 0);
    }
  }

  private void sendLeds() {
    if (vehicle == null) {
      return;
    }
    vehicle.sendIndicator(
        leftLedOn ? Vehicle.LED_ON : Vehicle.LED_OFF,
        rightLedOn ? Vehicle.LED_ON : Vehicle.LED_OFF);
  }

  /** Ask the firmware for the claw state and start the sonar stream while the page is open. */
  private void startReports() {
    if (vehicle == null || !vehicle.isConnected()) {
      return;
    }
    vehicle.requestGripperState();
    vehicle.setSonarFrequency(500);
  }

  private void refreshDisplay() {
    if (vehicle == null || binding == null) {
      return;
    }

    int gripperState = vehicle.getGripperState();
    switch (gripperState) {
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
    if (gripperState >= 0) {
      boolean closed = gripperState == Vehicle.GRIP_CLOSED;
      if (binding.gripperSwitch.isChecked() != closed) {
        syncingUi = true;
        binding.gripperSwitch.setChecked(closed);
        syncingUi = false;
      }
    }

    float cm = vehicle.getSonarDistanceCm();
    if (cm < 0) {
      binding.sonarText.setText(R.string.hardware_sonar_none);
    } else {
      binding.sonarText.setText(getString(R.string.hardware_sonar_value, Math.round(cm)));
    }
    binding.sonarBar.setProgress(cm < 0 ? 0 : Math.round(Math.min(cm, 200f) / 200f * 100));
  }

  @Override
  protected void processControllerKeyData(String command) {
    // Do nothing
  }

  @Override
  protected void processUSBData(String data) {
    if (binding == null || data.isEmpty()) {
      return;
    }
    // The base observer has already stored 's' and 'g' payloads on the vehicle;
    // the other lines (heartbeats, drive echoes, ...) do not change this screen.
    char header = data.charAt(0);
    if (header == 's' || header == 'g') {
      refreshDisplay();
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    startReports();
  }

  @Override
  public void onPause() {
    stopMotors(); // never leave the wheels running behind this page
    if (vehicle != null) {
      vehicle.setSonarFrequency(0); // stop the stream while the page is away
    }
    super.onPause();
  }

  @Override
  protected String remoteScreenName() {
    return "Hardware Test";
  }
}
