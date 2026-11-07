package org.openbot.app.robot.env;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import org.openbot.app.robot.vehicle.Control;

/**
 * Tilt drive mode: the phone's gravity vector drives the robot ("only use phone sensors to move").
 *
 * <p>Neutral posture is the phone held flat, screen up - like tilting a tray to roll a marble.
 * Throttle is the forward tilt of the phone's top edge (top edge down = forward); steering is the
 * roll (right edge down = turn right). The mapping is rotation-aware: the gravity components are
 * first resolved against the phone's own axes (portrait baseline) and then swapped/signed for the
 * current display rotation so "the top edge" always means the on-screen top edge in landscape.
 *
 * <p>Angle to output: {@code |tilt| < 4} degrees gives 0 (dead zone), 35 degrees or more is full
 * output, linear in between. The two outputs go through the arcade mix {@code left = throttle -
 * steering}, {@code right = throttle + steering}, clamped to {@code [-1, 1]}. {@link Control} then
 * applies the usual speed scale ({@code speedMultiplier} 128/192/255), so the wire values are the
 * same -255...255 range as every other drive path, and {@code Vehicle.sendControl()} applies the
 * avoidance gate and the STOP latch to tilt like to any other mode.
 *
 * <p>Cadence matches the other motion paths: a new value is sent the moment it changes, the held
 * value is re-sent every 250 ms so the firmware heartbeat failsafe stays fed, and returning to
 * neutral sends {@code 0,0} immediately rather than waiting for the failsafe. {@link #stop()} also
 * sends a final 0,0, so leaving the mode or the screen stops the robot.
 *
 * <p>Sensor input: {@link Sensor#TYPE_GRAVITY} at ~30 Hz when the device has one, otherwise {@link
 * Sensor#TYPE_ACCELEROMETER} at ~30 Hz passed through a low-pass filter. No runtime permission is
 * needed for either.
 */
public class TiltDriver implements SensorEventListener {

  /** Drive values in {@code [-1, 1]}, the same domain as {@link Control}. */
  public interface Listener {
    void onTilt(float left, float right);
  }

  /** Below this tilt angle the output is 0. */
  private static final float DEAD_ZONE_DEG = 4f;

  /** At this tilt angle the output is full (1.0). */
  private static final float FULL_TILT_DEG = 35f;

  /** Resend cadence for a held command, matching the other motion paths (see class comment). */
  private static final long RESEND_MS = 250;

  /** ~30 Hz. */
  private static final int SAMPLING_US = 33_333;

  /** Low-pass filter weight for the accelerometer fallback (heavier = smoother). */
  private static final float LPF_ALPHA = 0.8f;

  private final SensorManager sensorManager;
  private final Sensor gravitySensor;
  private final Sensor accelerometer;

  private final Handler handler = new Handler(Looper.getMainLooper());
  private final float[] lpfGravity = new float[3];
  private boolean lpfPrimed;

  private Listener listener;
  private int displayRotation = Surface.ROTATION_0;
  private boolean running;
  private float lastLeft;
  private float lastRight;

  private final Runnable resendTask =
      new Runnable() {
        @Override
        public void run() {
          if (!running) {
            return;
          }
          emit(lastLeft, lastRight);
          handler.postDelayed(this, RESEND_MS);
        }
      };

  public TiltDriver(Context context) {
    sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    gravitySensor =
        sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
    accelerometer =
        sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
  }

  /** True when the device reports a gravity or acceleration vector at all. */
  public boolean hasSensor() {
    return gravitySensor != null || accelerometer != null;
  }

  /**
   * Start driving. {@code rotation} is one of the {@code Surface.ROTATION_*} values; it decides how
   * the phone's own axes map onto the on-screen top edge and right edge.
   */
  public void start(Listener listener, int rotation) {
    stop();
    if (listener == null || sensorManager == null || !hasSensor()) {
      return;
    }
    this.listener = listener;
    this.displayRotation = rotation;
    this.lpfPrimed = false;
    this.running = true;
    Sensor sensor = gravitySensor != null ? gravitySensor : accelerometer;
    sensorManager.registerListener(this, sensor, SAMPLING_US);
    handler.postDelayed(resendTask, RESEND_MS);
  }

  /**
   * Stop driving. Sends a final {@code 0,0} so leaving the mode or the screen stops the robot, and
   * no further updates are delivered.
   */
  public void stop() {
    if (!running) {
      return;
    }
    running = false;
    handler.removeCallbacks(resendTask);
    if (sensorManager != null) {
      sensorManager.unregisterListener(this);
    }
    emit(0f, 0f);
    listener = null;
    lastLeft = 0f;
    lastRight = 0f;
  }

  /** Update the display rotation (e.g. after a configuration change) without restarting. */
  public void setDisplayRotation(int rotation) {
    displayRotation = rotation;
  }

  @Override
  public void onSensorChanged(SensorEvent event) {
    if (!running) {
      return;
    }
    if (event.sensor.getType() == Sensor.TYPE_GRAVITY) {
      System.arraycopy(event.values, 0, lpfGravity, 0, 3);
      lpfPrimed = true;
    } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
      if (!lpfPrimed) {
        System.arraycopy(event.values, 0, lpfGravity, 0, 3);
        lpfPrimed = true;
      } else {
        for (int i = 0; i < 3; i++) {
          lpfGravity[i] = LPF_ALPHA * lpfGravity[i] + (1f - LPF_ALPHA) * event.values[i];
        }
      }
    } else {
      return;
    }
    update();
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {
    // Not used: gravity is either reported or the screen stays at 0,0.
  }

  private void update() {
    // Gravity in phone coordinates: +x right edge, +y top edge, +z out of the screen. The phone is
    // level (screen up) when the vector is (0, 0, g). Tilt angles of the top edge and right edge
    // away from level:
    float topDownDeg = (float) Math.toDegrees(Math.atan2(-lpfGravity[1], lpfGravity[2]));
    float rightDownDeg = (float) Math.toDegrees(Math.atan2(-lpfGravity[0], lpfGravity[2]));

    float throttle;
    float steering;
    switch (displayRotation) {
      case Surface.ROTATION_90: // landscape, phone rotated clockwise: on-screen top is the phone's
        // left edge, on-screen right is the phone's top edge
        throttle = -rightDownDeg;
        steering = topDownDeg;
        break;
      case Surface.ROTATION_180:
        throttle = -topDownDeg;
        steering = -rightDownDeg;
        break;
      case Surface.ROTATION_270: // landscape, phone rotated counter-clockwise: on-screen top is
        // the phone's right edge, on-screen right is the phone's bottom edge
        throttle = rightDownDeg;
        steering = -topDownDeg;
        break;
      case Surface.ROTATION_0:
      default: // portrait baseline: on-screen top is the phone's top edge
        throttle = topDownDeg;
        steering = rightDownDeg;
        break;
    }

    float t = mapTilt(throttle);
    float s = mapTilt(steering);
    float left = clamp(t - s);
    float right = clamp(t + s);

    // Returning to neutral sends 0,0 right away (do not wait for the 250 ms resend).
    if (t == 0f && s == 0f) {
      if (lastLeft != 0f || lastRight != 0f) {
        emit(0f, 0f);
      }
      lastLeft = 0f;
      lastRight = 0f;
      return;
    }
    if (left != lastLeft || right != lastRight) {
      lastLeft = left;
      lastRight = right;
      emit(left, right);
    }
  }

  /** Dead zone at 4 degrees, full output at 35 degrees, linear in between. */
  private static float mapTilt(float deg) {
    float abs = Math.abs(deg);
    if (abs < DEAD_ZONE_DEG) {
      return 0f;
    }
    float mag = (abs - DEAD_ZONE_DEG) / (FULL_TILT_DEG - DEAD_ZONE_DEG);
    if (mag > 1f) {
      mag = 1f;
    }
    return deg < 0f ? -mag : mag;
  }

  private static float clamp(float v) {
    if (v > 1f) {
      return 1f;
    }
    if (v < -1f) {
      return -1f;
    }
    return v;
  }

  private void emit(float left, float right) {
    if (listener != null) {
      listener.onTilt(left, right);
    }
  }
}
