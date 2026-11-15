package org.openbot.app.robot.voice;

import android.os.Handler;
import androidx.annotation.Nullable;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Pick up the red cup": finds an object with the phone camera and the vision service, steers the
 * robot to it step by step, and closes the gripper.
 *
 * <p>Why step by step (visual servoing): one photo cannot tell exactly how far or how far to the
 * side the object is. So the robot looks, makes one short move (turn toward the object, or a short
 * drive), stops, and looks again. Small errors are corrected every step instead of adding up.
 *
 * <ol>
 *   <li>Open the gripper.
 *   <li>Look: ask the vision service where the object is ({@code FOUND x=.. w=..} or {@code NONE}).
 *   <li>Not seen: turn 45° and look again (one full circle at most).
 *   <li>Off to one side: turn toward it.
 *   <li>Centred but small (far): drive forward a little.
 *   <li>Centred and big, or the sonar says it is close: final approach — creep toward the
 *       object with the front sonar managing the distance, and close the gripper only once
 *       the sonar reads within 5 cm of it (the "5 cm rule").
 * </ol>
 *
 * <p>Everything runs on one Handler (the main thread); every robot action calls back when done.
 * {@link #cancel()} (STOP) ends it at once. The robot calls are an interface so the logic can be
 * tested without a phone.
 */
public final class ObjectFetcher {

  /** What the fetcher needs from the voice page. All calls on the handler's thread. */
  public interface Robot {
    /** Take a photo and ask the vision service {@code question}; answer text, or null on failure. */
    void look(String question, Answer answer);

    /** Drive both wheels (-1..1) for {@code ms}, then stop and call {@code done}. */
    void drive(float left, float right, int ms, Runnable done);

    /** Close (true) or open (false) the gripper. */
    void gripper(boolean close);

    /** Let the robot get close to an object for {@code ms} (firmware {@code a<ms>}). */
    void approach(int ms);

    /** Front sonar distance in cm, or a negative number when unknown. */
    float sonarCm();

    /** A short progress line for the conversation list ("Looking for the cup…"). */
    void progress(String text);
  }

  public interface Answer {
    void onAnswer(@Nullable String text);
  }

  public interface Done {
    /** {@code ok}: holding the object. {@code message}: what to tell the user. */
    void onDone(boolean ok, String message);
  }

  // ---- Tuning (FriendBot chassis; see the README "Gripper" section) ----------------------------
  /** A 360° spin takes this long at TURN_SPEED (VoiceCommand: 2.6 s at 0.75). */
  static final int SPIN_360_MS = 3250;
  static final float TURN_SPEED = 0.6f;
  static final float DRIVE_SPEED = 0.5f;
  static final float FINAL_SPEED = 0.2f; // slow creep toward the object (sonar-gated)
  /** Half the camera's horizontal view, in degrees (phone main camera, about 60° wide). */
  static final float HALF_FOV_DEG = 30f;
  /** The object counts as centred when it is this close to the middle (x 0..100)… */
  static final float CENTRE_TOLERANCE = 10f;
  /** …or this close once it looks big (near): the claw is wider than the aim error then. */
  static final float CENTRE_TOLERANCE_NEAR = 18f;
  /** Small corrections turn slower, so the shortest turn stays smaller than the centre window. */
  static final float FINE_TURN_SPEED = 0.45f;
  static final float FINE_TURN_DEG = 15f;
  static final int MIN_TURN_MS = 120;
  /** The object is close enough to grab when it is this wide (% of the photo)… */
  static final float GRAB_WIDTH = 38f;
  /** …or when the front sonar reads this close (cm). */
  static final float GRAB_SONAR_CM = 20f;
  /** The "5 cm rule": the claw only closes once the front sonar reads within this of the object. */
  static final float GRIP_CM = 5f;
  /** Under this the object is already under the claw body: stop, never close. */
  static final float TOO_CLOSE_CM = 2f;
  /** One sonar-gated creep step: short drive, settle for a fresh reading, decide again. */
  static final int CREEP_STEP_MS = 250;
  static final long CREEP_SETTLE_MS = 250;
  static final int MAX_CREEP_STEPS = 16;
  /** Without a working sonar: this many blind steps, then grab anyway. */
  static final int BLIND_CREEP_STEPS = 5;
  static final int SEARCH_TURN_DEG = 45;
  static final int MAX_LOOKS = 24;
  static final int MAX_MISSES = 9; // 8 × 45° = one full circle, plus one more look
  /** Let the robot stop rocking before the next photo. */
  static final long SETTLE_MS = 350;
  static final long GRIPPER_OPEN_MS = 700;
  static final long GRIPPER_CLOSE_MS = 1000;

  private final Robot robot;
  private final Handler handler;
  private String object = "";
  private Done done;
  private int run; // bumps on start/cancel: late callbacks of an old run are ignored
  private boolean running;
  private int looks;
  private int misses;

  public ObjectFetcher(Robot robot, Handler handler) {
    this.robot = robot;
    this.handler = handler;
  }

  public boolean isRunning() {
    return running;
  }

  /** Starts fetching {@code object} ("the red cup"). A running fetch is cancelled first. */
  public void start(String object, Done done) {
    cancel();
    this.object = object == null || object.trim().isEmpty() ? "object in front of the robot" : object.trim();
    this.done = done;
    running = true;
    looks = 0;
    misses = 0;
    int me = ++run;
    robot.progress("Fetching " + this.object + ": opening the gripper");
    robot.gripper(false);
    handler.postDelayed(() -> look(me), GRIPPER_OPEN_MS);
  }

  /** STOP: ends the fetch at once (the caller stops the motors). */
  public void cancel() {
    run++;
    if (running) {
      running = false;
      robot.approach(0); // a creep's approach mode ends with the fetch
      Done d = done;
      done = null;
      if (d != null) d.onDone(false, "Fetch stopped.");
    }
  }

  private boolean alive(int me) {
    return running && me == run;
  }

  private void finish(int me, boolean ok, String message) {
    if (!alive(me)) return;
    running = false;
    Done d = done;
    done = null;
    if (d != null) d.onDone(ok, message);
  }

  /** The question that makes the vision model answer in a format we can read. */
  static String question(String object) {
    return "Is there " + object + " in this photo? Answer in exactly this format and nothing else:"
        + " FOUND x=<number> w=<number> . x is where the centre of " + object + " is, from the left"
        + " edge 0 to the right edge 100. w is how wide it looks, in percent of the photo width."
        + " If you cannot see it, answer exactly: NONE";
  }

  private void look(int me) {
    if (!alive(me)) return;
    if (++looks > MAX_LOOKS) {
      finish(me, false, "I could not reach " + object + " - please point me at it and try again.");
      return;
    }
    robot.look(
        question(object),
        text -> {
          if (!alive(me)) return;
          decide(me, parse(text));
        });
  }

  private void decide(int me, @Nullable Sighting s) {
    float sonar = robot.sonarCm();
    if (s == null) {
      // Not seen (or the vision service failed): search by turning, one full circle at most.
      if (++misses > MAX_MISSES) {
        finish(me, false, "I looked all around but could not find " + object + ".");
        return;
      }
      robot.progress("Looking for " + object + " (turning)");
      turn(me, SEARCH_TURN_DEG);
      return;
    }
    misses = 0;
    float offset = s.x - 50f; // -50 left … +50 right
    float tolerance = s.width >= 25f ? CENTRE_TOLERANCE_NEAR : CENTRE_TOLERANCE;
    if (Math.abs(offset) > tolerance) {
      float deg = offset / 50f * HALF_FOV_DEG;
      robot.progress(String.format(Locale.US, "I see %s - turning %s %.0f°", object, deg < 0 ? "left" : "right", Math.abs(deg)));
      turn(me, deg);
      return;
    }
    boolean close = s.width >= GRAB_WIDTH || (sonar > 0 && sonar <= GRAB_SONAR_CM);
    if (!close) {
      int ms = s.width < 10 ? 1100 : s.width < 20 ? 750 : s.width < 30 ? 500 : 320;
      robot.progress("Driving to " + object);
      robot.drive(DRIVE_SPEED, DRIVE_SPEED, ms, () -> settleThenLook(me));
      return;
    }
    // Final approach: creep toward the object with the sonar managing the distance.
    robot.progress("Picking up " + object);
    creepThenGrip(me, 0, false);
  }

  /**
   * The final approach: creep toward the object with the front sonar managing the distance and
   * close the claw only once it reads within 5 cm (the "5 cm rule"). Already in reach → grip at
   * once; the sonar never produced a reading → grab after a short blind creep; it produced
   * readings but the object never came within 5 cm → stop without gripping.
   */
  private void creepThenGrip(int me, int step, boolean sonarSeen) {
    if (!alive(me)) return;
    boolean seen = sonarSeen;
    float d = robot.sonarCm();
    if (d >= 0) {
      if (d < TOO_CLOSE_CM) {
        robot.approach(0);
        finish(me, false, "I am too close to " + object + " to grab it.");
        return;
      }
      if (d <= GRIP_CM) {
        grip(me);
        return;
      }
      seen = true;
    }
    if (seen ? step >= MAX_CREEP_STEPS : step >= BLIND_CREEP_STEPS) {
      if (seen) {
        robot.approach(0);
        finish(me, false, "I could not get within " + (int) GRIP_CM + " cm of " + object + ".");
      } else {
        grip(me); // no sonar on this robot: grab after the short blind creep
      }
      return;
    }
    // Firmware safety stops forward at 15 cm; approach mode lets it creep on to 4 cm.
    robot.approach(2000);
    final boolean seenNow = seen; // the lambda needs an effectively-final copy
    robot.drive(
        FINAL_SPEED,
        FINAL_SPEED,
        CREEP_STEP_MS,
        () -> handler.postDelayed(() -> creepThenGrip(me, step + 1, seenNow), CREEP_SETTLE_MS));
  }

  /** The object is at the grip distance (or the blind creep ran out): close the claw. */
  private void grip(int me) {
    if (!alive(me)) return;
    robot.gripper(true);
    handler.postDelayed(
        () -> finish(me, true, "I am holding " + object + ". Where should I take it?"),
        GRIPPER_CLOSE_MS);
  }

  /** Turns {@code deg} (negative = left) in place, then looks again. */
  private void turn(int me, float deg) {
    boolean fine = Math.abs(deg) < FINE_TURN_DEG;
    float speed = fine ? FINE_TURN_SPEED : TURN_SPEED;
    // SPIN_360_MS is at TURN_SPEED; a slower turn needs proportionally longer.
    int ms = Math.max(MIN_TURN_MS, Math.round(Math.abs(deg) / 360f * SPIN_360_MS * TURN_SPEED / speed));
    float l = deg < 0 ? -speed : speed;
    robot.drive(l, -l, ms, () -> settleThenLook(me));
  }

  private void settleThenLook(int me) {
    if (!alive(me)) return;
    handler.postDelayed(() -> look(me), SETTLE_MS);
  }

  // ---------------------------------------------------------------- answer parsing

  /** Where the object is in the photo: x 0..100 (left→right), width 0..100 (% of the photo). */
  public static final class Sighting {
    public final float x;
    public final float width;

    Sighting(float x, float width) {
      this.x = x;
      this.width = width;
    }
  }

  private static final Pattern FOUND =
      Pattern.compile(
          "FOUND\\W*x\\s*[=:]\\s*(-?\\d+(?:\\.\\d+)?)\\s*%?\\W*w\\s*[=:]\\s*(\\d+(?:\\.\\d+)?)",
          Pattern.CASE_INSENSITIVE);

  /** Reads "FOUND x=62 w=18" from the vision answer; null for NONE, garbage or no answer. */
  @Nullable
  public static Sighting parse(@Nullable String text) {
    if (text == null) return null;
    Matcher m = FOUND.matcher(text);
    if (!m.find()) return null;
    try {
      float x = Float.parseFloat(m.group(1));
      float w = Float.parseFloat(m.group(2));
      if (x <= 1f && w <= 1f && (x > 0f || w > 0f) && text.contains(".")) {
        // Some models answer in 0..1 instead of 0..100.
        x *= 100f;
        w *= 100f;
      }
      x = Math.max(0f, Math.min(100f, x));
      w = Math.max(0f, Math.min(100f, w));
      return new Sighting(x, w);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
