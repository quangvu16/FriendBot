package org.openbot.app.robot.vision;

import java.util.Locale;

/**
 * The brain of the Grab Object page: on-phone AI boxes + the front sonar → wheel speeds and "grip
 * now". Pure logic (no Android), called once per camera frame, so it can be tested in a
 * simulation.
 *
 * <ul>
 *   <li><b>Search</b>: no object → turn a little, stop, look (short steps: a sharp picture).
 *   <li><b>Turn</b>: object off to one side → turn toward it.
 *   <li><b>Approach</b>: object centred → drive, slower as the <b>sonar</b> distance shrinks.
 *   <li><b>Final</b>: closer than 35 cm → approach mode (the firmware lets the robot reach 4 cm).
 *       The camera often loses the object right in front of the claw; the sonar still sees it,
 *       so the robot creeps on by the sonar alone.
 *   <li><b>Grip</b>: sonar ≤ grab distance with the object centred → stop and close the claw.
 * </ul>
 *
 * <p>Why the sonar? The camera only sees how big the object looks, which depends on the object's
 * size. The sonar measures the real distance in cm, so the claw closes at the right moment for a
 * small ball and a big box alike.
 */
public final class GrabSteering {

  public enum State {
    SEARCH,
    TURN,
    APPROACH,
    FINAL,
    GRIP,
    HOLDING,
    LOST,
    /** "go to the cup": reached the object, never gripped it. */
    ARRIVED,
    /** Too close to crush it, or the claw stayed open — stop and say so. */
    ABORTED
  }

  /** One decision. Wheel speeds are -1..1 (left, right). */
  public static final class Out {
    public float left;
    public float right;
    /** Close the claw now (sent once). */
    public boolean grip;
    /** Keep the firmware's approach mode on (re-sent every 2 s by the page). */
    public boolean approach;
    public State state;
    public String status = "";
  }

  // ---- tuning ---------------------------------------------------------------------------------
  /** Object counts as centred inside this part of the picture (-1 left … +1 right). */
  static final float CENTRED = 0.22f;
  /** Still OK to grip when this close to the centre (the claw is wider than the aim error). */
  static final float GRIP_CENTRED = 0.4f;
  static final float TURN_MIN = 0.3f;
  static final float TURN_GAIN = 0.2f;
  static final float STEER_GAIN = 0.25f;
  static final float SLOWEST = 0.22f;
  static final float FASTEST = 0.55f;
  /** Final approach (firmware approach mode) below this sonar distance. */
  static final float FINAL_CM = 35f;
  /** Sonar readings above this are "nothing in front". */
  static final float SONAR_MAX_CM = 250f;
  /** After the camera loses a centred, near object, creep on by sonar alone this long. */
  static final long BLIND_MS = 1800;
  /** Short detection drop-outs: hold still this long before searching. */
  static final long FLICKER_MS = 600;
  static final long SEARCH_TURN_MS = 330;
  static final long SEARCH_PAUSE_MS = 500;
  static final float SEARCH_SPEED = 0.35f;
  /** Not found in this long → stop and say so (the bottle isn't found in 15 seconds). */
  static final long SEARCH_GIVE_UP_MS = 15000;
  static final long GRIP_WAIT_MS = 1000;
  /** After the grip command: this long with no answer → trust the firmware's blind "done". */
  static final long GRIP_WAIT_MAX_MS = 2500;
  /** Under this sonar distance the object would be crushed → stop, never close. */
  static final float TOO_CLOSE_CM = 2f;
  /** Without a working sonar: grip when the box is this tall (fraction of the picture). */
  static final float NO_SONAR_GRIP_HEIGHT = 0.7f;

  private final String object;
  private final boolean approachOnly;
  private float grabCm;
  private State state = State.SEARCH;
  private long lastSeenMs = Long.MIN_VALUE / 2;
  private float lastOffset;
  private long searchSinceMs = -1;
  private long gripAtMs;
  private int gripTries;

  public GrabSteering(String object, float grabCm) {
    this(object, grabCm, false);
  }

  /** @param approachOnly "go to the cup": stop at the object, never close the claw. */
  public GrabSteering(String object, float grabCm, boolean approachOnly) {
    this.object = object;
    this.grabCm = grabCm;
    this.approachOnly = approachOnly;
  }

  public State state() {
    return state;
  }

  public void setGrabCm(float cm) {
    grabCm = cm;
  }

  /** Start again (e.g. after releasing). */
  public void reset() {
    state = State.SEARCH;
    lastSeenMs = Long.MIN_VALUE / 2;
    searchSinceMs = -1;
    gripTries = 0;
  }

  /**
   * @param now time in ms
   * @param seen the object is in this frame
   * @param offset object centre, -1 (left edge) … +1 (right edge)
   * @param height box height as a fraction of the picture (0…1)
   * @param sonarCm front sonar in cm, ≤ 0 when unknown
   */
  public Out step(long now, boolean seen, float offset, float height, float sonarCm) {
    return step(now, seen, offset, height, sonarCm, -1);
  }

  /** Same, plus the claw's reported state (0 open, 1 closed, -1 unknown). */
  public Out step(long now, boolean seen, float offset, float height, float sonarCm,
      int gripperState) {
    Out o = new Out();
    boolean sonarOk = sonarCm > 0 && sonarCm < SONAR_MAX_CM;
    if (seen) {
      lastSeenMs = now;
      lastOffset = offset;
      searchSinceMs = -1;
    }

    if (state == State.HOLDING || state == State.LOST
        || state == State.ARRIVED || state == State.ABORTED) {
      String status;
      switch (state) {
        case HOLDING: status = "Holding the " + object; break;
        case ARRIVED: status = "At the " + object; break;
        case ABORTED: status = "Stopped near the " + object; break;
        default: status = "Cannot find the " + object; break;
      }
      return hold(o, status);
    }
    if (state == State.GRIP) {
      long waited = now - gripAtMs;
      if (waited >= GRIP_WAIT_MS) {
        // The claw has had its ~700 ms move: trust the report once it arrives.
        if (gripperState == 0) {
          // Still open: the firmware may have missed the command — one retry, then give up.
          if (gripTries < 1) {
            gripTries++;
            gripAtMs = now;
            o.grip = true;
            return hold(o, "Retrying the grip on the " + object);
          }
          state = State.ABORTED;
          return hold(o, "Missed the " + object);
        }
        if (gripperState >= 0 || waited >= GRIP_WAIT_MAX_MS) {
          state = State.HOLDING; // answered closed, or this firmware never reports: trust it
          return hold(o, "Holding the " + object);
        }
      }
      return hold(o, "Gripping the " + object);
    }

    boolean recentlySeen = now - lastSeenMs < BLIND_MS;
    boolean centredNow = seen ? Math.abs(offset) < GRIP_CENTRED : recentlySeen && Math.abs(lastOffset) < GRIP_CENTRED;

    // ---- under 2 cm: closing would crush it ----
    if (sonarOk && sonarCm < TOO_CLOSE_CM) {
      state = State.ABORTED;
      return hold(o, "Too close to the " + object);
    }

    // ---- grip: the sonar says it is in the claw ----
    if (sonarOk && sonarCm <= grabCm && centredNow) {
      if (approachOnly) {
        state = State.ARRIVED;
        return hold(o, "At the " + object);
      }
      state = State.GRIP;
      gripAtMs = now;
      o.grip = true;
      return hold(o, String.format(Locale.US, "Gripping the %s (%.0f cm)", object, sonarCm));
    }
    if (!sonarOk && seen && height >= NO_SONAR_GRIP_HEIGHT && Math.abs(offset) < GRIP_CENTRED) {
      if (approachOnly) {
        state = State.ARRIVED;
        return hold(o, "At the " + object);
      }
      state = State.GRIP; // no sonar reading: fall back on how big it looks
      gripAtMs = now;
      o.grip = true;
      return hold(o, "Gripping the " + object + " (no sonar)");
    }

    if (seen) {
      if (Math.abs(offset) > CENTRED) {
        state = State.TURN;
        float s = TURN_MIN + TURN_GAIN * Math.min(1f, Math.abs(offset));
        float dir = Math.signum(offset);
        o.left = dir * s;
        o.right = -dir * s;
        o.state = state;
        o.status = (dir < 0 ? "◀ " : "▶ ") + "Turning to the " + object;
        return o;
      }
      float v;
      if (sonarOk) {
        v = clamp(SLOWEST + (sonarCm - grabCm) / 120f, SLOWEST, FASTEST);
      } else {
        v = height > 0.5f ? SLOWEST : 0.45f;
      }
      o.left = v + STEER_GAIN * offset;
      o.right = v - STEER_GAIN * offset;
      boolean fin = sonarOk && sonarCm <= FINAL_CM && !approachOnly;
      o.approach = !approachOnly && fin;
      state = fin ? State.FINAL : State.APPROACH;
      o.state = state;
      o.status =
          sonarOk
              ? String.format(Locale.US, "▲ %s %s · %.0f cm", fin ? "Reaching" : "Driving to", object, sonarCm)
              : "▲ Driving to the " + object;
      return o;
    }

    // ---- not in this frame ----
    if (recentlySeen && Math.abs(lastOffset) < GRIP_CENTRED && sonarOk && sonarCm <= FINAL_CM) {
      // Right in front of the claw: the camera cannot see it any more, the sonar can.
      state = State.FINAL;
      o.left = SLOWEST;
      o.right = SLOWEST;
      o.approach = !approachOnly;
      o.state = state;
      o.status = String.format(Locale.US, "▲ Reaching the %s by sonar · %.0f cm", object, sonarCm);
      return o;
    }
    if (now - lastSeenMs < FLICKER_MS) {
      return hold(o, "Looking for the " + object);
    }
    // ---- search: short turns with pauses (a sharp picture while stopped) ----
    state = State.SEARCH;
    if (searchSinceMs < 0) searchSinceMs = now;
    if (now - searchSinceMs > SEARCH_GIVE_UP_MS) {
      state = State.LOST;
      return hold(o, "Cannot find the " + object);
    }
    long phase = (now - searchSinceMs) % (SEARCH_TURN_MS + SEARCH_PAUSE_MS);
    if (phase < SEARCH_TURN_MS) {
      o.left = SEARCH_SPEED;
      o.right = -SEARCH_SPEED;
    }
    o.state = state;
    o.status = "Searching for the " + object;
    return o;
  }

  private Out hold(Out o, String status) {
    o.left = 0f;
    o.right = 0f;
    o.state = state;
    o.status = status;
    return o;
  }

  private static float clamp(float v, float lo, float hi) {
    return Math.max(lo, Math.min(hi, v));
  }
}
