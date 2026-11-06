package org.openbot.app.robot.voice;

/**
 * One understood voice command, ready to drive the robot.
 *
 * <p>The same model is used whether the words were understood by the Xiaozhi LLM or by the local
 * keyword interpreter, and whether they came from the microphone or from the on-screen buttons.
 * Actions map onto the firmware's {@code c<left>,<right>} drive values:
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
    UNKNOWN
  }

  public static final int DEFAULT_DURATION_MS = 1200;
  public static final float DEFAULT_SPEED = 0.75f;
  public static final int MAX_DURATION_MS = 8000;

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
        || action == Action.RIGHT;
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
}
