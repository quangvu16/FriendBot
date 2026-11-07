package org.openbot.app.robot.vehicle;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import com.ficat.easyble.BleDevice;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import org.openbot.app.robot.env.GameController;
import org.openbot.app.robot.env.SensorReading;
import org.openbot.app.robot.main.CommonRecyclerViewAdapter;
import org.openbot.app.robot.main.ScanDeviceAdapter;
import org.openbot.app.robot.utils.Enums;

public class Vehicle {

  private final Noise noise = new Noise(1000, 2000, 5000);
  private boolean noiseEnabled = false;

  private int speedMultiplier = 192; // 128,192,255
  private Control control = new Control(0, 0);
  // What actually went to the car after object avoidance. Kept apart from 'control' so a held
  // command can be re-evaluated the moment the obstacle clears, without the driver re-moving the
  // stick. See sendControl().
  private Control sentControl = new Control(0, 0);

  private final SensorReading batteryVoltage = new SensorReading();
  private final SensorReading leftWheelRpm = new SensorReading();
  private final SensorReading rightWheelRpm = new SensorReading();
  // Mid sonar distance in cm, fed by the firmware's periodic 's<cm>' reports (see
  // setSonarFrequency); -1 until the first reading arrives.
  private volatile float sonarDistanceCm = -1f;

  // Object avoidance, ArduPilot Rover style. Applied in setControl() so that every drive source -
  // gamepad, phone sticks, GPS mission, point-goal - is covered by one intercept, the same way
  // ArduPilot applies it inside the navigation stack rather than per mode. The obstacle sectors are
  // currently unfed (DataState.NEVER, pass-through); the law stays as the extension point for a
  // future ranging input. Camera-driven commands pass through the gate unchanged.
  private boolean objectAvoidanceEnabled = false;
  private float objectAvoidanceMargin = 1.0f; // metres, like ArduPilot's AVOID_MARGIN
  private float objectAvoidanceBackupSpeed = 0f; // 0 disables backing away, like AVOID_BACKUP_SPD

  private float minMotorVoltage = 2.5f;
  private float lowBatteryVoltage = 9.0f;
  private float maxBatteryVoltage = 12.6f;

  private UsbConnection usbConnection;
  protected boolean usbConnected;
  private final Context context;
  private final int baudRate;

  private String vehicleType = "";
  private boolean hasVoltageDivider = false;
  private boolean hasBumpSensor = false;
  private boolean hasWheelOdometryFront = false;
  private boolean hasWheelOdometryBack = false;
  private boolean isReady = false;
  private BluetoothManager bluetoothManager;
  SharedPreferences sharedPreferences;
  public String connectionType;

  public float getMinMotorVoltage() {
    return minMotorVoltage;
  }

  public void setMinMotorVoltage(float minMotorVoltage) {
    this.minMotorVoltage = minMotorVoltage;
  }

  public float getLowBatteryVoltage() {
    return lowBatteryVoltage;
  }

  public void setLowBatteryVoltage(float lowBatteryVoltage) {
    this.lowBatteryVoltage = lowBatteryVoltage;
  }

  public float getMaxBatteryVoltage() {
    return maxBatteryVoltage;
  }

  public void setMaxBatteryVoltage(float maxBatteryVoltage) {
    this.maxBatteryVoltage = maxBatteryVoltage;
  }

  public boolean isReady() {
    return isReady;
  }

  public void setReady(boolean ready) {
    isReady = ready;
  }

  public boolean isHasVoltageDivider() {
    return hasVoltageDivider;
  }

  public void setHasVoltageDivider(boolean hasVoltageDivider) {
    this.hasVoltageDivider = hasVoltageDivider;
  }

  public boolean isHasBumpSensor() {
    return hasBumpSensor;
  }

  public void setHasBumpSensor(boolean hasBumpSensor) {
    this.hasBumpSensor = hasBumpSensor;
  }

  public boolean isHasWheelOdometryFront() {
    return hasWheelOdometryFront;
  }

  public void setHasWheelOdometryFront(boolean hasWheelOdometryFront) {
    this.hasWheelOdometryFront = hasWheelOdometryFront;
  }

  public boolean isHasWheelOdometryBack() {
    return hasWheelOdometryBack;
  }

  public void setHasWheelOdometryBack(boolean hasWheelOdometryBack) {
    this.hasWheelOdometryBack = hasWheelOdometryBack;
  }

  public String getVehicleType() {
    return vehicleType;
  }

  public void setVehicleType(String vehicleType) {
    this.vehicleType = vehicleType;
  }

  public void requestVehicleConfig() {
    sendStringToDevice(String.format(Locale.US, "f\n"));
  }

  /**
   * Parse the car's 'f' feature reply. Lenient by design: the vehicle type is everything before the
   * first ':', known feature tags are acted on, and any unknown or retired tag (indicators, sonar readings,
   * proximity, scans, LEDs) is ignored without failing.
   */
  public void processVehicleConfig(String message) {
    setVehicleType(message.split(":")[0]);

    if (message.contains(":v:")) {
      setHasVoltageDivider(true);
      setVoltageFrequency(250);
    }
    if (message.contains(":b:")) {
      setHasBumpSensor(true);
    }
    if (message.contains(":wf:")) {
      setHasWheelOdometryFront(true);
      setWheelOdometryFrequency(500);
    }
    if (message.contains(":wb:")) {
      setHasWheelOdometryBack(true);
      setWheelOdometryFrequency(500);
    }
  }

  protected Enums.DriveMode driveMode = Enums.DriveMode.GAME;
  private final GameController gameController;
  private Timer heartbeatTimer;

  // The big red STOP button. Checked in sendControl(), the one place drive commands leave the app.
  private final EmergencyStop emergencyStop = new EmergencyStop();

  public Vehicle(Context context, int baudRate) {
    this.context = context;
    this.baudRate = baudRate;
    gameController = new GameController(driveMode);
    sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    connectionType = getConnectionPreferences("connection_type", "USB");
    // Stop at once on STOP, and forget the last request on RESUME so the car does not lurch off
    // with a command from before the stop.
    emergencyStop.addListener(
        latched -> {
          control = new Control(0, 0);
          sendControl();
        });
  }

  public EmergencyStop getEmergencyStop() {
    return emergencyStop;
  }

  /** True when the car is reachable over the link chosen in settings (USB or Bluetooth). */
  public boolean isConnected() {
    if ("Bluetooth".equals(getConnectionType())) {
      return bluetoothManager != null && bluetoothManager.isBleConnected();
    }
    return isUsbConnected();
  }

  public float getBatteryVoltage() {
    return batteryVoltage.getReading();
  }

  public int getBatteryPercentage() {
    return (int)
        ((batteryVoltage.getReading() - lowBatteryVoltage)
            * 100
            / (maxBatteryVoltage - lowBatteryVoltage));
  }

  public void setBatteryVoltage(float batteryVoltage) {
    this.batteryVoltage.setReading(batteryVoltage);
  }

  public float getLeftWheelRpm() {
    return leftWheelRpm.getReading();
  }

  public void setLeftWheelRpm(float leftWheelRpm) {
    this.leftWheelRpm.setReading(leftWheelRpm);
  }

  public float getRightWheelRpm() {
    return rightWheelRpm.getReading();
  }

  public void setRightWheelRpm(float rightWheelRpm) {
    this.rightWheelRpm.setReading(rightWheelRpm);
  }

  public float getRotation() {
    float rotation = (getLeftSpeed() - getRightSpeed()) * 180 / (getLeftSpeed() + getRightSpeed());
    if (Float.isNaN(rotation) || Float.isInfinite(rotation)) rotation = 0f;
    return rotation;
  }

  public int getSpeedPercent() {
    float throttle = (getLeftSpeed() + getRightSpeed()) / 2;
    return Math.abs((int) (throttle * 100 / 255)); // 255 is the max speed
  }

  public String getDriveGear() {
    float throttle = (getLeftSpeed() + getRightSpeed()) / 2;
    if (throttle > 0) return "D";
    if (throttle < 0) return "R";
    return "P";
  }

  public boolean isObjectAvoidanceEnabled() {
    return objectAvoidanceEnabled;
  }

  /**
   * The quick-disable switch ArduPilot insists on ("SAFETY FIRST!") before first use. Turning it
   * off passes every command through untouched from the next {@link #setControl} onwards.
   */
  public void setObjectAvoidanceEnabled(boolean objectAvoidanceEnabled) {
    this.objectAvoidanceEnabled = objectAvoidanceEnabled;
  }

  /** Standoff in metres, like ArduPilot's {@code AVOID_MARGIN}. Non-positive disables the law. */
  public float getObjectAvoidanceMargin() {
    return objectAvoidanceMargin;
  }

  public void setObjectAvoidanceMargin(float objectAvoidanceMargin) {
    this.objectAvoidanceMargin = objectAvoidanceMargin;
  }

  /**
   * Reverse command used to back away from a barrier, in {@code [0, 1]}. 0 disables backing away,
   * matching ArduPilot's {@code AVOID_BACKUP_SPD} default of 0.
   */
  public float getObjectAvoidanceBackupSpeed() {
    return objectAvoidanceBackupSpeed;
  }

  public void setObjectAvoidanceBackupSpeed(float objectAvoidanceBackupSpeed) {
    this.objectAvoidanceBackupSpeed = objectAvoidanceBackupSpeed;
  }

  /** True when the last command was held back by an obstacle, stopped or backing away. */
  public boolean isObjectAvoidanceHolding() {
    return lastAvoidanceDecision != null && lastAvoidanceDecision.isHolding();
  }

  public ObjectAvoidance.Decision getLastAvoidanceDecision() {
    return lastAvoidanceDecision;
  }

  private ObjectAvoidance.Decision lastAvoidanceDecision;

  public Control getControl() {
    return control;
  }

  /**
   * What was actually last sent to the car, after object avoidance. This is what the gauges should
   * read - when an obstacle is holding the car back the speedometer shows 0 even though the stick
   * is still forward.
   */
  public Control getSentControl() {
    return sentControl;
  }

  public void setControl(Control control) {
    this.control = control;
    sendControl();
  }

  public void setControl(float left, float right) {
    setControl(new Control(left, right));
  }

  /**
   * Run the requested command through ArduPilot-style Simple Object Avoidance and send the result.
   *
   * <p>Applied here, in the single chokepoint every drive source passes through, so no mode can
   * escape it - the equivalent of ArduPilot applying avoidance inside the navigation stack ("all
   * modes except MANUAL") rather than per flight mode. The obstacle sector feed is currently
   * unfed ({@code DataState.NEVER}), so the law passes commands through unchanged; it stays in
   * place as the extension point for a future ranging input.
   */
  public void sendControl() {
    // STOP wins over everything, object avoidance included (avoidance may back the car up; STOP
    // must not move it at all).
    if (emergencyStop.isLatched()) {
      sentControl = new Control(0, 0);
      sendStringToDevice("c0,0\n");
      return;
    }
    // Data-collection noise is part of the request, so it is added BEFORE the avoidance law. Added
    // after, it could turn a hold (throttle 0, steering kept) back into motion toward the obstacle.
    float requestedLeft = control.getLeft();
    float requestedRight = control.getRight();
    if (noiseEnabled && noise.getDirection() < 0) requestedLeft -= noise.getValue();
    if (noiseEnabled && noise.getDirection() > 0) requestedRight -= noise.getValue();

    lastAvoidanceDecision =
        ObjectAvoidance.evaluate(
            requestedLeft,
            requestedRight,
            null,
            ObjectAvoidance.DataState.NEVER,
            objectAvoidanceMargin,
            objectAvoidanceBackupSpeed,
            objectAvoidanceEnabled);
    sentControl = new Control(lastAvoidanceDecision.left, lastAvoidanceDecision.right);

    int left = (int) (sentControl.getLeft() * speedMultiplier);
    int right = (int) (sentControl.getRight() * speedMultiplier);

    sendStringToDevice(String.format(Locale.US, "c%d,%d\n", left, right));
  }

  private Timer noiseTimer;

  public void toggleNoise() {
    if (noiseEnabled) stopNoise();
    else startNoise();
  }

  public boolean isNoiseEnabled() {
    return noiseEnabled;
  }

  public void setDriveMode(Enums.DriveMode driveMode) {
    this.driveMode = driveMode;
    gameController.setDriveMode(driveMode);
  }

  public Enums.DriveMode getDriveMode() {
    return driveMode;
  }

  public GameController getGameController() {
    return gameController;
  }

  private class NoiseTask extends TimerTask {
    @Override
    public void run() {
      noise.update();
      sendControl();
    }
  }

  public void startNoise() {
    noiseTimer = new Timer();
    NoiseTask noiseTask = new NoiseTask();
    noiseTimer.schedule(noiseTask, 0, 50); // no delay 50ms intervals
    noiseEnabled = true;
  }

  public void stopNoise() {
    noiseEnabled = false;
    noiseTimer.cancel();
    sendControl();
  }

  public int getSpeedMultiplier() {
    return speedMultiplier;
  }

  public void setSpeedMultiplier(int speedMultiplier) {
    this.speedMultiplier = speedMultiplier;
  }

  public UsbConnection getUsbConnection() {
    return usbConnection;
  }

  public void connectUsb() {
    if (usbConnection == null) usbConnection = new UsbConnection(context, baudRate);
    usbConnected = usbConnection.startUsbConnection();
    if (usbConnected) {
      if (heartbeatTimer == null) {
        startHeartbeat();
      }
    }
  }

  public void disconnectUsb() {
    if (usbConnection != null) {
      stopBot();
      stopHeartbeat();
      usbConnection.stopUsbConnection();
      usbConnection = null;
      usbConnected = false;
    }
  }

  public boolean isUsbConnected() {
    return usbConnected;
  }

  private void sendStringToDevice(String message) {
    if (getConnectionType().equals("USB") && usbConnection != null) {
      usbConnection.send(message);
    } else if (getConnectionType().equals("Bluetooth")
        && bluetoothManager != null
        && bluetoothManager.isBleConnected()) {
      sendStringToBle(message);
    }
  }

  public float getLeftSpeed() {
    return sentControl.getLeft() * speedMultiplier;
  }

  public float getRightSpeed() {
    return sentControl.getRight() * speedMultiplier;
  }

  protected void sendHeartbeat(int timeout_ms) {
    sendStringToDevice(String.format(Locale.getDefault(), "h%d\n", timeout_ms));
  }

  protected void setVoltageFrequency(int interval_ms) {
    sendStringToDevice(String.format(Locale.getDefault(), "v%d\n", interval_ms));
  }

  protected void setWheelOdometryFrequency(int interval_ms) {
    sendStringToDevice(String.format(Locale.getDefault(), "w%d\n", interval_ms));
  }

  /** Front/back lights, firmware {@code l<front>,<back>} (0–255 each). */
  public void sendLight(int front, int back) {
    sendStringToDevice(
        String.format(
            Locale.US, "l%d,%d\n", clampToByte(front), clampToByte(back)));
  }

  /** FriendBot firmware LED modes for {@link #sendIndicator}. */
  public static final int LED_OFF = 0;
  public static final int LED_ON = 1;
  public static final int LED_BLINK = 2;
  /** The firmware shows the driving state itself (turning side blinks, both blink when stopped). */
  public static final int LED_AUTO = 3;

  /**
   * Left/right LEDs, firmware {@code i<left>,<right>}: each one of {@link #LED_OFF}, {@link
   * #LED_ON}, {@link #LED_BLINK} or {@link #LED_AUTO} (see openbot-esp32-mtv.ino).
   */
  public void sendIndicator(int left, int right) {
    sendStringToDevice(
        String.format(
            Locale.US,
            "i%d,%d\n",
            Math.max(LED_OFF, Math.min(LED_AUTO, left)),
            Math.max(LED_OFF, Math.min(LED_AUTO, right))));
  }

  /**
   * Sonar reporting interval in ms (firmware {@code s<interval>}); 0 stops the reports. Each report
   * comes back as {@code s<cm>} and lands in {@link #setSonarDistanceCm}.
   */
  public void setSonarFrequency(int interval_ms) {
    sendStringToDevice(String.format(Locale.US, "s%d\n", interval_ms));
  }

  /** Last mid-sonar distance in cm, or -1 when no report has arrived yet. */
  public float getSonarDistanceCm() {
    return sonarDistanceCm;
  }

  public void setSonarDistanceCm(float distanceCm) {
    sonarDistanceCm = distanceCm;
  }

  private static int clampToByte(int v) {
    return Math.max(0, Math.min(255, v));
  }

  private class HeartBeatTask extends TimerTask {

    @Override
    public void run() {
      sendHeartbeat(750);
    }
  }

  public void startHeartbeat() {
    heartbeatTimer = new Timer();
    HeartBeatTask heartBeatTask = new HeartBeatTask();
    heartbeatTimer.schedule(heartBeatTask, 250, 250); // 250ms delay and 250ms intervals
  }

  public void stopHeartbeat() {
    if (heartbeatTimer != null) {
      heartbeatTimer.cancel();
      heartbeatTimer.purge();
      heartbeatTimer = null;
    }
  }

  public void stopBot() {
    Control control = new Control(0, 0);
    setControl(control);
  }

  public ScanDeviceAdapter getBleAdapter() {
    return bluetoothManager.adapter;
  }

  public void setBleAdapter(
      ScanDeviceAdapter adapter,
      @NonNull CommonRecyclerViewAdapter.OnItemClickListener onItemClickListener) {
    bluetoothManager.adapter = adapter;
    bluetoothManager.adapter.setOnItemClickListener(onItemClickListener);
  }

  public void startScan() {
    bluetoothManager.startScan();
  }

  public void stopScan() {
    bluetoothManager.stopScan();
  }

  public List<BleDevice> getDeviceList() {
    return bluetoothManager.deviceList;
  }

  public void setBleDevice(BleDevice device) {
    bluetoothManager.bleDevice = device;
  }

  public BleDevice getBleDevice() {
    return bluetoothManager.bleDevice;
  }

  public void toggleConnection(int position, BleDevice device) {
    bluetoothManager.toggleConnection(position, device);
  }

  public void initBle() {
    if (bluetoothManager != null) return;
    bluetoothManager = new BluetoothManager(context);
  }

  private void sendStringToBle(String message) {
    bluetoothManager.write(message);
  }

  public boolean bleConnected() {
    return bluetoothManager.isBleConnected();
  }

  private void setConnectionPreferences(String name, String value) {
    SharedPreferences.Editor editor = sharedPreferences.edit();
    editor.putString(name, value);
    editor.apply();
  }

  private String getConnectionPreferences(String name, String defaultValue) {
    try {
      if (sharedPreferences != null) {
        return sharedPreferences.getString(name, defaultValue);
      } else return defaultValue;
    } catch (ClassCastException e) {
      return defaultValue;
    }
  }

  public String getConnectionType() {
    return getConnectionPreferences("connection_type", "USB");
  }
}
