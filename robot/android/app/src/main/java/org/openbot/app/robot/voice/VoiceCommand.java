package org.openbot.app.robot.voice;

import java.util.ArrayList;
import java.util.List;

/**
 * One understood voice command, ready to drive the robot.
 *
 * <p>The same model is used whether the words were understood by the Xiaozhi LLM or by the local
 * keyword interpreter, and whether they came from the microphone or from the on-screen buttons.
 * Actions map onto the firmware's drive and accessory messages:
 *
 * <ul>
 *   <li>{@code FORWARD} drives both sides forward at {@code speed}
 *   <li>{@code BACKWARD} drives both sides backward
 *   <li>{@code LEFT} spins in place to the left (left side backward, right side forward)
 *   <li>{@code RIGHT} spins in place to the right
 *   <li>{@code STOP} sends {@code c0,0}
 *   <li>{@code FASTER} / {@code SLOWER} adjust the speed used by the next commands
 *   <li>{@code CHAT} is pure conversation (the REST brain's {@code "none"} action): the robot
 *       stops and only the spoken reply happens
 *   <li>{@code LIGHTS_ON} / {@code LIGHTS_OFF} send the firmware's {@code l<front>,<back>} lights
 *   <li>{@code SONAR} asks for sonar readings ({@code s<interval>}); the firmware answers with
 *       {@code s<cm>} distance reports
 *   <li>{@code SING}, {@code TRIANGLE}, {@code CIRCLE}, {@code SPIN360}, {@code TURN_AROUND} and
 *       {@code WIGGLE} are choreographed moves made of several drive segments ({@link #toScript()})
 * </ul>
 *
 * A moving command runs for {@code durationMs} and then auto-stops. While it runs the screen keeps
 * resending the drive values, which also feeds the firmware's heartbeat failsafe.
 */
public class VoiceCommand {

  public enum Action {
    FORWARD,
    BACKWARD,
    LEFT,
    RIGHT,
    STOP,
    FASTER,
    SLOWER,
    /** Pure conversation (REST action {@code "none"}); halts the robot and just talks. */
    CHAT,
    /** Front/back lights on (firmware {@code l255,255}). */
    LIGHTS_ON,
    /** Front/back lights off (firmware {@code l0,0}). */
    LIGHTS_OFF,
    /** Left indicator blinking (firmware {@code i1,0}). */
    INDICATOR_LEFT,
    /** Right indicator blinking (firmware {@code i0,1}). */
    INDICATOR_RIGHT,
    /** Both indicators blinking (firmware {@code i1,1}). */
    INDICATOR_HAZARD,
    /** All indicators off (firmware {@code i0,0}). */
    INDICATOR_OFF,
    /** Report the sonar distance in cm (firmware {@code s<interval>} / {@code s<cm>}). */
    SONAR,
    /** Sing a short tune and turn around (one 360° spin). */
    SING,
    /** Drive an equilateral triangle: forward, turn 120°, three times. */
    TRIANGLE,
    /** Drive one full circle (inside wheel slower than the outside wheel). */
    CIRCLE,
    /** Spin in place a full 360°. */
    SPIN360,
    /** Spin in place 180° ("turn around"). */
    TURN_AROUND,
    /** Swing left-right-left-right in place ("turn left and right"). */
    WIGGLE,
    UNKNOWN
  }

  public static final int DEFAULT_DURATION_MS = 1200;
  public static final float DEFAULT_SPEED = 0.75f;
  /** Voices may ask for a long move ("go forward for 10 seconds"); the resend loop covers it. */
  public static final int MAX_DURATION_MS = 15000;

  /**
   * Segment timings for the choreographed moves at {@link #DEFAULT_SPEED}, tuned for the FriendBot
   * chassis: a 360° spin takes about 2.6 s at speed 0.75, so a 180° turn is half of that and a 120°
   * triangle corner a third.
   */
  private static final long SPIN_360_MS = 2600;
  private static final long TURN_MS = 1300;
  private static final long TRIANGLE_TURN_MS = 870;
  private static final long TRIANGLE_FORWARD_MS = 1300;
  private static final long CIRCLE_MS = 5200;
  private static final long WIGGLE_MS = 550;

  private final Action action;
  private final float speed;
  private final int durationMs;
  private final String rawText;
  private final String source;

  private VoiceCommand(Action action, float speed, int durationMs, String rawText, String source) {
    this.action = action;
    this.speed = clampSpeed(speed);
    this.durationMs = clampDuration(durationMs);
    this.rawText = rawText == null ? "" : rawText;
    this.source = source == null ? "local" : source;
  }

  public static VoiceCommand of(Action action, String rawText, String source) {
    return new VoiceCommand(action, DEFAULT_SPEED, DEFAULT_DURATION_MS, rawText, source);
  }

  public static VoiceCommand of(
      Action action, float speed, int durationMs, String rawText, String source) {
    return new VoiceCommand(action, speed, durationMs, rawText, source);
  }

  private static float clampSpeed(float speed) {
    if (speed <= 0f) return DEFAULT_SPEED;
    return Math.min(1f, Math.max(0.15f, speed));
  }

  private static int clampDuration(int durationMs) {
    if (durationMs <= 0) return DEFAULT_DURATION_MS;
    return Math.min(MAX_DURATION_MS, durationMs);
  }

  public Action getAction() {
    return action;
  }

  public float getSpeed() {
    return speed;
  }

  public int getDurationMs() {
    return durationMs;
  }

  public String getRawText() {
    return rawText;
  }

  public String getSource() {
    return source;
  }

  /** True for commands that make the robot move and so need the auto-stop timer. */
  public boolean isMotion() {
    return action == Action.FORWARD
        || action == Action.BACKWARD
        || action == Action.LEFT
        || action == Action.RIGHT
        || toScript() != null;
  }

  /** Left/right drive values in the app's -1..1 range, or null when this is not a drive command. */
  public float[] toWheels() {
    switch (action) {
      case FORWARD:
        return new float[] {speed, speed};
      case BACKWARD:
        return new float[] {-speed, -speed};
      case LEFT:
        return new float[] {-speed, speed};
      case RIGHT:
        return new float[] {speed, -speed};
      case STOP:
        return new float[] {0f, 0f};
      default:
        return null;
    }
  }

  /**
   * The choreographed moves as drive segments {@code {left, right, durationMs}} — one segment per
   * leg of the trick, played back in order. Null for commands that are not scripts.
   */
  public List<float[]> toScript() {
    List<float[]> segs = new ArrayList<>();
    float s = speed;
    switch (action) {
      case SPIN360:
        segs.add(new float[] {-s, s, SPIN_360_MS});
        break;
      case TURN_AROUND:
        segs.add(new float[] {-s, s, TURN_MS});
        break;
      case WIGGLE:
        segs.add(new float[] {-s, s, WIGGLE_MS});
        segs.add(new float[] {s, -s, WIGGLE_MS});
        segs.add(new float[] {-s, s, WIGGLE_MS});
        segs.add(new float[] {s, -s, WIGGLE_MS});
        break;
      case TRIANGLE:
        for (int i = 0; i < 3; i++) {
          segs.add(new float[] {s, s, TRIANGLE_FORWARD_MS});
          segs.add(new float[] {-s, s, TRIANGLE_TURN_MS});
        }
        break;
      case CIRCLE:
        // Slow inside wheel traces one full lap to the left.
        segs.add(new float[] {0.35f * s, s, CIRCLE_MS});
        break;
      case SING:
        segs.add(new float[] {-s, s, SPIN_360_MS});
        break;
      default:
        return null;
    }
    return segs;
  }
}
