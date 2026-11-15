package org.openbot.app.robot.vision;

import java.util.Locale;

/**
 * The "act like a person" layer of the Voice Grab page. Pure logic (tested in a simulation):
 * called once per camera frame with what the AI saw (the object and/or a person and/or the
 * destination landmark), the front sonar and the time; returns wheel speeds, claw / approach
 * commands, what to say and which face to show.
 *
 * <p>The four fetch commands all share one shape: {@link GrabSteering} finds, reaches and grips
 * the object ("move around to find object, object in the centre of the screen, sonar to stop,
 * gripper to pick"); then the robot carries it to the destination the words named and asks "What
 * should I do next?":
 *
 * <ul>
 *   <li><b>PICK</b> "pick up the cup" / "bring me the bottle": carry to the <b>person</b>
 *       ({@link VoiceGrabParser.Dest#PERSON}).
 *   <li><b>BRING</b> "bring the cup to the table": carry to the <b>new place</b> — the landmark
 *       in the words ({@link VoiceGrabParser.Dest#PLACE}).
 *   <li><b>FIND</b> "find the ball": carry to the <b>old place</b> — where the robot was when you
 *       asked ({@link VoiceGrabParser.Dest#HOME}, dead reckoning).
 *   <li><b>NEXT_TO</b> "put the cup next to the bottle": carry <b>next to the other object</b>
 *       ({@link VoiceGrabParser.Dest#NEXT_TO}).
 * </ul>
 *
 * <p>Also: <b>PUT_DOWN</b> "put it down" (open the claw, step back), <b>FOLLOW</b> "follow me"
 * (keep ~60 cm), <b>STOP</b> (stop at once). The object to recognize always comes from the words,
 * never from the page's object setting.
 */
public final class VoiceGrabBrain {

  public enum Mode {
    IDLE,
    PICK,
    CELEBRATE,
    GOTO,
    PUT_DOWN,
    FOLLOW,
    HELLO
  }

  /** Faces of the robot (same names as the voice page's RobotFaceView emotions). */
  public enum Face {
    NEUTRAL,
    LISTENING,
    THINKING,
    HAPPY,
    SURPRISED,
    SAD,
    CONFUSED
  }

  /** Lines the robot says; the page turns them into English or Vietnamese. */
  public enum Say {
    OK_GET, // "Okay, I'll get the %s."
    SEE_IT, // "I see the %s!"
    GOT_IT, // "Got it!"
    WHERE_TO, // "Where should I take it?"
    COMING, // "On my way!"
    HERE_YOU_ARE, // "Here you are!"
    ARRIVED, // "Here we are."
    ASK_NEXT, // "What should I do next?"
    THERE_YOU_GO, // "There you go."
    CANT_FIND, // "I can't find the %s."
    FOLLOWING, // "I'm following you."
    STOPPED, // "Okay, I stopped."
    NOT_HOLDING, // "I'm not holding anything."
    WHICH_OBJECT, // "Which object should I get?"
    HELLO, // "Hello! What can I get for you?"
    THANKS, // "You're welcome!"
    CANT_GRAB, // "I can't grab it."
    DIDNT_GET // "Sorry, I didn't get that. Try: pick up the cup."
  }

  public static final class Out {
    public float left;
    public float right;
    public boolean grip;
    public boolean release;
    public boolean approach;
    /** Not null: say this now (with {@link #sayObject} for %s). */
    public Say say;
    public String sayObject = "";
    public Face face;
    public String status = "";
  }

  // ---- tuning ---------------------------------------------------------------------------------
  /** Robot speed at wheel command 1.0 (cm/s) and turn rate (deg/s): the FriendBot chassis. */
  public static float CM_PER_S = 60f;
  public static float DEG_PER_S = 184f;
  static final float PERSON_STOP_CM = 35f;
  static final float FOLLOW_CM = 60f;
  static final float HOME_CM = 25f;
  /** Stop this far from the landmark the words named ("to the table"). */
  static final float PLACE_STOP_CM = 20f;
  /** Stop this far from the other object ("next to the bottle"): room to put it down beside. */
  static final float NEXT_TO_STOP_CM = 35f;
  static final long WIGGLE_MS = 220;
  static final int WIGGLES = 4;
  static final long PUT_DOWN_WAIT_MS = 700;
  static final long BACK_OFF_MS = 450;
  static final long BRING_GIVE_UP_MS = 30000;
  /** While the claw holds something, the middle sonar sees that object: ignore readings this close. */
  static final float HELD_OBJECT_CM = 15f;
  /** Without a usable sonar, you are "in front of me" when you fill this much of the picture. */
  static final float PERSON_NEAR_HEIGHT = 0.75f;
  static final long LOOK_AROUND_MS = 14000;
  /** After arriving: pause this long, then ask for the new command. */
  static final long ASK_DELAY_MS = 1200;
  /** "go to the cup": stop this far from the object and never close the claw. */
  static final float APPROACH_STOP_CM = 12f;

  private Mode mode = Mode.IDLE;
  private String object = "";
  private VoiceGrabParser.Dest destKind = VoiceGrabParser.Dest.PERSON;
  private String destLabel = "";
  private boolean holding;
  private GrabSteering grab;
  private GrabSteering destSteering;
  private long modeSince;
  private long lastStep = -1;
  private float lastLeft;
  private float lastRight;
  private boolean saidSeeIt;
  private long lookAroundSince = -1;
  private long arrivedAt;
  /** Objects still to fetch of a "collect three …" command (decremented on each arrival). */
  private int collectRemaining = 1;
  /** Colour the current command insists on ("red", …) or null for any colour. */
  private String color;

  // Dead reckoning: where the robot is compared with where it was when you asked (cm, degrees).
  private double x;
  private double y;
  private double heading;
  private double homeX;
  private double homeY;
  private double homeHeading;

  private Out pending; // a reply to a command, delivered with the next step

  public Mode mode() {
    return mode;
  }

  public boolean isHolding() {
    return holding;
  }

  public String object() {
    return object;
  }

  /**
   * Seeds the "it" memory without a spoken command: after the robot asks the user what to do
   * with a seen object, "pick it up" resolves to that object.
   */
  public synchronized void rememberObject(String name) {
    if (name != null && !name.isEmpty()) {
      object = name;
    }
  }

  /** The landmark the words named ("dining table", "bottle"), for {@link #wantsDest()}. */
  public String dest() {
    return destLabel;
  }

  /** Colour the current command insists on ("red", …), or null for any colour. */
  public String color() {
    return color;
  }

  /** Detector labels this frame should look for (the object, a person and/or the destination). */
  public boolean wantsPerson() {
    return (mode == Mode.GOTO && destKind == VoiceGrabParser.Dest.PERSON) || mode == Mode.FOLLOW;
  }

  /** True while carrying to the landmark the words named (PLACE / NEXT_TO). */
  public boolean wantsDest() {
    return mode == Mode.GOTO && destSteering != null;
  }

  /** A voice request (main thread). */
  public void command(VoiceGrabParser.Request r, long now) {
    color = r.color; // every command carries its own colour (clears a stale one)
    Out o = new Out();
    switch (r.intent) {
      case STOP:
        setMode(Mode.IDLE, now);
        arrivedAt = 0;
        o.say = Say.STOPPED;
        o.face = Face.NEUTRAL;
        break;
      case PICK:
      case BRING:
      case FIND:
      case NEXT_TO:
        if (r.intent == VoiceGrabParser.Intent.PICK) collectRemaining = r.count;
        startFetch(r, now, o);
        break;
      case APPROACH:
        startApproach(r, now, o);
        break;
      case SEE:
      case CHECK:
        break; // the page answers these from the camera / claw, never a mission
      case PUT_DOWN:
        if (!holding) {
          o.say = Say.NOT_HOLDING;
          o.face = Face.CONFUSED;
          o.release = true;
          break;
        }
        setMode(Mode.PUT_DOWN, now);
        o.release = true;
        o.face = Face.HAPPY;
        break;
      case FOLLOW:
        setMode(Mode.FOLLOW, now);
        o.say = Say.FOLLOWING;
        o.face = Face.HAPPY;
        break;
      case HELLO:
        setMode(Mode.HELLO, now);
        o.say = Say.HELLO;
        o.face = Face.HAPPY;
        break;
      case THANKS:
        o.say = Say.THANKS;
        o.face = Face.HAPPY;
        break;
      default:
        o.say = Say.DIDNT_GET;
        o.face = Face.CONFUSED;
        break;
    }
    pending = o;
  }

  /** The shared pick-then-carry shape of the four fetch commands. */
  private void startFetch(VoiceGrabParser.Request r, long now, Out o) {
    VoiceGrabParser.Dest kind =
        r.destKind != null ? r.destKind : VoiceGrabParser.Dest.PERSON;
    String wanted = (r.object != null && !r.object.isEmpty()) ? r.object : object;

    // Already in the claw: skip the pick phase and just carry it ("bring it to me",
    // "put the cup next to the bottle" while holding the cup).
    if (holding && (r.object == null || r.object.isEmpty() || r.object.equals(object))) {
      if ((kind == VoiceGrabParser.Dest.PLACE || kind == VoiceGrabParser.Dest.NEXT_TO)
          && (r.dest == null || r.dest.isEmpty())) {
        o.say = Say.WHERE_TO;
        o.face = Face.CONFUSED;
        return;
      }
      beginCarry(kind, r.dest, now);
      o.say = Say.COMING;
      o.face = Face.HAPPY;
      return;
    }
    if (holding) {
      o.say = Say.WHERE_TO; // hands full of something else: ask first
      o.face = Face.CONFUSED;
      return;
    }
    if (wanted.isEmpty()) {
      o.say = Say.WHICH_OBJECT;
      o.face = Face.CONFUSED;
      return;
    }
    if ((kind == VoiceGrabParser.Dest.PLACE || kind == VoiceGrabParser.Dest.NEXT_TO)
        && (r.dest == null || r.dest.isEmpty())) {
      o.say = Say.WHERE_TO;
      o.face = Face.CONFUSED;
      return;
    }
    object = wanted;
    destKind = kind;
    destLabel = r.dest != null ? r.dest : "";
    rememberHome();
    grab = new GrabSteering(object, 5f); // the 5 cm rule
    destSteering =
        kind == VoiceGrabParser.Dest.PLACE
            ? new GrabSteering(destLabel, PLACE_STOP_CM)
            : kind == VoiceGrabParser.Dest.NEXT_TO
                ? new GrabSteering(destLabel, NEXT_TO_STOP_CM)
                : null;
    saidSeeIt = false;
    arrivedAt = 0;
    setMode(Mode.PICK, now);
    o.say = Say.OK_GET;
    o.sayObject = object;
    o.face = Face.THINKING;
    o.release = true; // open the claw, ready
  }

  /**
   * "go to the cup" / "move toward the bottle but don't touch it": get close to the object and
   * stop — no grip, no carry, then ask for the new command.
   */
  private void startApproach(VoiceGrabParser.Request r, long now, Out o) {
    String wanted = (r.object != null && !r.object.isEmpty()) ? r.object : object;
    if (wanted.isEmpty()) {
      o.say = Say.WHICH_OBJECT;
      o.face = Face.CONFUSED;
      return;
    }
    object = wanted;
    destKind = VoiceGrabParser.Dest.PERSON;
    destLabel = "";
    destSteering = null;
    rememberHome();
    grab = new GrabSteering(object, APPROACH_STOP_CM, true);
    saidSeeIt = false;
    arrivedAt = 0;
    setMode(Mode.PICK, now);
    o.say = Say.OK_GET;
    o.sayObject = object;
    o.face = Face.THINKING;
    o.release = false; // "don't touch it": the claw stays as it is
  }

  /** Hands full: go straight to the carrying leg. */
  private void beginCarry(VoiceGrabParser.Dest kind, String dest, long now) {
    destKind = kind;
    destLabel = dest != null ? dest : "";
    destSteering =
        kind == VoiceGrabParser.Dest.PLACE
            ? new GrabSteering(destLabel, PLACE_STOP_CM)
            : kind == VoiceGrabParser.Dest.NEXT_TO
                ? new GrabSteering(destLabel, NEXT_TO_STOP_CM)
                : null;
    rememberHome();
    lookAroundSince = -1;
    arrivedAt = 0;
    setMode(Mode.GOTO, now);
  }

  /** Remember "here" as where the speaker is (the old place to come back to). */
  private void rememberHome() {
    homeX = x;
    homeY = y;
    homeHeading = heading;
  }

  /** Grip/release the page did by button: keep the brain's idea of the claw right. */
  public void setHolding(boolean h) {
    holding = h;
  }

  private void setMode(Mode m, long now) {
    mode = m;
    modeSince = now;
  }

  /**
   * One camera frame.
   *
   * @param objSeen/objOffset/objHeight the requested object (-1 left … +1 right; height 0…1)
   * @param personSeen/personOffset/personHeight a person, when {@link #wantsPerson()}
   * @param destSeen/destOffset/destHeight the destination landmark, when {@link #wantsDest()}
   * @param sonarCm front sonar, ≤ 0 unknown
   */
  public Out step(
      long now,
      boolean objSeen,
      float objOffset,
      float objHeight,
      boolean personSeen,
      float personOffset,
      float personHeight,
      boolean destSeen,
      float destOffset,
      float destHeight,
      float sonarCm) {
    return step(now, objSeen, objOffset, objHeight, personSeen, personOffset, personHeight,
        destSeen, destOffset, destHeight, sonarCm, -1);
  }

  /** Same, plus the claw's reported state (0 open, 1 closed, -1 unknown). */
  public Out step(
      long now,
      boolean objSeen,
      float objOffset,
      float objHeight,
      boolean personSeen,
      float personOffset,
      float personHeight,
      boolean destSeen,
      float destOffset,
      float destHeight,
      float sonarCm,
      int gripperState) {
    integrate(now);
    Out o = pending != null ? pending : new Out();
    pending = null;
    boolean sonarOk = sonarCm > 0 && sonarCm < 250f;
    if (holding && sonarOk && sonarCm < HELD_OBJECT_CM) {
      sonarOk = false; // that is the object in the claw, not the way ahead
    }
    switch (mode) {
      case IDLE:
        break;
      case PICK:
        stepPick(o, now, objSeen, objOffset, objHeight, sonarCm, sonarOk, gripperState);
        break;
      case CELEBRATE:
        {
          long t = now - modeSince;
          int n = (int) (t / WIGGLE_MS);
          if (n < WIGGLES) {
            float s = (n % 2 == 0) ? 0.45f : -0.45f;
            o.left = s;
            o.right = -s;
          } else {
            // Got it: now carry it where the words said.
            lookAroundSince = -1;
            arrivedAt = 0;
            setMode(Mode.GOTO, now);
            o.say = Say.COMING;
          }
          o.face = Face.HAPPY;
          o.status = "Got the " + object + "!";
          break;
        }
      case GOTO:
        {
          if (arrivedAt > 0) {
            // Stopped at the destination: a beat, then fetch the next one ("collect three …")
            // or ask for the new command.
            if (now - arrivedAt >= ASK_DELAY_MS) {
              arrivedAt = 0;
              if (collectRemaining > 1 && destKind == VoiceGrabParser.Dest.PERSON) {
                collectRemaining--;
                holding = false;
                startFetch(new VoiceGrabParser.Request(
                    VoiceGrabParser.Intent.PICK, object, null, VoiceGrabParser.Dest.PERSON),
                    now, o);
              } else {
                setMode(Mode.IDLE, now);
                o.say = Say.ASK_NEXT;
                o.face = Face.HAPPY;
              }
            }
            break;
          }
          switch (destKind) {
            case PERSON:
              stepBring(o, now, personSeen, personOffset, personHeight, sonarCm, sonarOk);
              break;
            case HOME:
              stepHome(o, now, sonarCm, sonarOk);
              break;
            case PLACE:
            case NEXT_TO:
              stepDest(o, now, destSeen, destOffset, destHeight, sonarCm, sonarOk);
              break;
          }
          break;
        }
      case PUT_DOWN:
        {
          long t = now - modeSince;
          if (t < PUT_DOWN_WAIT_MS) {
            o.status = "Putting it down";
          } else if (t < PUT_DOWN_WAIT_MS + BACK_OFF_MS) {
            o.left = -0.35f;
            o.right = -0.35f;
            o.status = "Stepping back";
          } else {
            holding = false;
            setMode(Mode.IDLE, now);
            o.say = Say.THERE_YOU_GO;
            o.face = Face.HAPPY;
          }
          break;
        }
      case FOLLOW:
        stepFollow(o, personSeen, personOffset, personHeight, sonarCm, sonarOk);
        break;
      case HELLO:
        {
          long t = now - modeSince;
          if (t < 2 * WIGGLE_MS) {
            float s = t < WIGGLE_MS ? 0.4f : -0.4f;
            o.left = s;
            o.right = -s;
          } else {
            setMode(Mode.IDLE, now);
          }
          break;
        }
    }
    lastLeft = o.left;
    lastRight = o.right;
    if (o.status.isEmpty()) o.status = idleStatus();
    return o;
  }

  private String idleStatus() {
    return holding ? "Holding the " + object + " ✊" : "Listening…";
  }

  /** Find, reach and grip the object (all four commands start here). */
  private void stepPick(Out o, long now, boolean seen, float off, float h, float sonar,
      boolean sonarOk, int gripperState) {
    if (seen && !saidSeeIt) {
      saidSeeIt = true;
      if (o.say == null) {
        o.say = Say.SEE_IT;
        o.sayObject = object;
      }
      if (o.face == null) o.face = Face.SURPRISED;
    }
    GrabSteering.Out g = grab.step(now, seen, off, h, sonar, gripperState);
    o.left = g.left;
    o.right = g.right;
    o.approach = g.approach;
    o.status = g.status;
    if (g.grip) {
      o.grip = true;
    }
    if (g.state == GrabSteering.State.HOLDING) {
      holding = true;
      setMode(Mode.CELEBRATE, now);
      o.say = Say.GOT_IT;
      o.face = Face.HAPPY;
    } else if (g.state == GrabSteering.State.ARRIVED) {
      // "go to the cup": there, without gripping — ask for the new command.
      setMode(Mode.IDLE, now);
      o.say = Say.ARRIVED;
      o.face = Face.HAPPY;
    } else if (g.state == GrabSteering.State.ABORTED
        || g.state == GrabSteering.State.LOST) {
      // Too close to grab, the claw missed it, or 15 s without finding it.
      setMode(Mode.IDLE, now);
      o.say = Say.CANT_GRAB;
      o.sayObject = object;
      o.face = Face.SAD;
    }
  }

  /** Carry to the person (camera + dead reckoning): command 1. */
  private void stepBring(
      Out o, long now, boolean personSeen, float personOff, float personH, float sonar, boolean sonarOk) {
    if (now - modeSince > BRING_GIVE_UP_MS) {
      arrive(o, now); // as close as it got
      return;
    }
    if (personSeen) {
      // You are in view: steer to you, stop in front of you (sonar, or how tall you look).
      lookAroundSince = -1;
      boolean near = sonarOk ? sonar <= PERSON_STOP_CM : personH >= PERSON_NEAR_HEIGHT;
      if (near && Math.abs(personOff) < 0.4f) {
        arrive(o, now);
        return;
      }
      if (Math.abs(personOff) > 0.25f) {
        float s = 0.3f + 0.2f * Math.min(1f, Math.abs(personOff));
        o.left = Math.signum(personOff) * s;
        o.right = -o.left;
      } else {
        float v =
            sonarOk
                ? clamp(0.25f + (sonar - PERSON_STOP_CM) / 150f, 0.25f, 0.5f)
                : (personH > 0.5f ? 0.25f : 0.4f);
        o.left = v + 0.25f * personOff;
        o.right = v - 0.25f * personOff;
      }
      o.status = "Coming to you" + (sonarOk ? String.format(Locale.US, " · %.0f cm", sonar) : "");
      return;
    }
    // Not in view: head back to where you were (dead reckoning).
    double dx = homeX - x;
    double dy = homeY - y;
    double dist = Math.hypot(dx, dy);
    if (dist < HOME_CM || lookAroundSince >= 0) {
      // Back where you asked from, but you are not in view: look around for you, like a person.
      if (lookAroundSince < 0) lookAroundSince = now;
      long t = now - lookAroundSince;
      if (t > LOOK_AROUND_MS) {
        arrive(o, now);
        return;
      }
      if (t % 830 < 330) {
        o.left = 0.35f;
        o.right = -0.35f;
      }
      o.face = Face.THINKING;
      o.status = "Looking for you";
      return;
    }
    if (sonarOk && sonar <= PERSON_STOP_CM) {
      arrive(o, now); // something right in front on the way back: stop and offer it
      return;
    }
    driveToHome(o, dist, dx, dy, "Coming to you");
  }

  /** Carry to the old place (dead reckoning to where the robot was when you asked): command 3. */
  private void stepHome(Out o, long now, float sonar, boolean sonarOk) {
    if (now - modeSince > BRING_GIVE_UP_MS) {
      arrive(o, now); // as close as it got
      return;
    }
    double dx = homeX - x;
    double dy = homeY - y;
    double dist = Math.hypot(dx, dy);
    if (dist < HOME_CM) {
      arrive(o, now);
      return;
    }
    if (sonarOk && sonar <= PERSON_STOP_CM) {
      arrive(o, now); // something right in front: stop here
      return;
    }
    driveToHome(o, dist, dx, dy, "Bringing it back");
  }

  private void driveToHome(Out o, double dist, double dx, double dy, String what) {
    double want = Math.toDegrees(Math.atan2(dx, dy));
    double err = wrap(want - heading);
    if (Math.abs(err) > 20) {
      float s = 0.4f * (float) Math.signum(err);
      o.left = s;
      o.right = -s;
    } else {
      float v = dist > 60 ? 0.5f : 0.3f;
      float steer = (float) (err / 60.0);
      o.left = v + 0.2f * steer;
      o.right = v - 0.2f * steer;
    }
    o.status = String.format(Locale.US, "%s · %.0f cm to go", what, dist);
  }

  /**
   * Carry to the landmark the words named — "to the new place" (command 2) or "next to the other
   * object" (command 4). {@link GrabSteering} finds and centres it; its "grip" distance is the
   * stop distance here (the claw keeps holding).
   */
  private void stepDest(Out o, long now, boolean seen, float off, float h, float sonar, boolean sonarOk) {
    if (now - modeSince > BRING_GIVE_UP_MS) {
      arrive(o, now); // as close as it got
      return;
    }
    GrabSteering.Out g = destSteering.step(now, seen, off, h, sonar);
    if (g.state == GrabSteering.State.LOST) {
      setMode(Mode.IDLE, now);
      o.say = Say.CANT_FIND;
      o.sayObject = destLabel;
      o.face = Face.SAD;
      return;
    }
    if (g.state == GrabSteering.State.GRIP || g.state == GrabSteering.State.HOLDING
        || g.state == GrabSteering.State.ABORTED) {
      arrive(o, now); // at the stop distance from the destination
      return;
    }
    o.left = g.left;
    o.right = g.right;
    // Never forward g.approach / g.grip: the claw keeps holding, no firmware reach-mode.
    o.status = g.status;
  }

  private void arrive(Out o, long now) {
    lookAroundSince = -1;
    arrivedAt = now;
    if (destKind == VoiceGrabParser.Dest.PLACE || destKind == VoiceGrabParser.Dest.NEXT_TO) {
      o.release = true; // "put it at the table" — open the claw on arrival
      holding = false;
    }
    o.say = destKind == VoiceGrabParser.Dest.PERSON ? Say.HERE_YOU_ARE : Say.ARRIVED;
    o.sayObject = destLabel;
    o.face = Face.HAPPY;
    o.status =
        destKind == VoiceGrabParser.Dest.PERSON
            ? "Here you are!"
            : destKind == VoiceGrabParser.Dest.HOME
                ? "Back where we started!"
                : destKind == VoiceGrabParser.Dest.NEXT_TO
                    ? "Here, next to the " + destLabel + "!"
                    : "Here at the " + destLabel + "!";
  }

  private void stepFollow(Out o, boolean seen, float off, float personH, float sonar, boolean sonarOk) {
    if (!seen) {
      o.face = Face.THINKING;
      o.status = "Where are you?";
      return; // wait: never drive blind after a person
    }
    float turn = Math.abs(off) > 0.2f ? 0.25f * off : 0f;
    float v = 0f;
    if (sonarOk) {
      if (sonar > FOLLOW_CM + 15) v = clamp((sonar - FOLLOW_CM) / 120f, 0.25f, 0.55f);
      else if (sonar < FOLLOW_CM - 20) v = -0.25f; // too close: step back
    } else {
      v = personH > 0.7f ? 0f : personH > 0.45f ? 0.25f : 0.4f; // no sonar: how tall you look
    }
    o.left = v + turn + (v == 0f ? 0.3f * Math.signum(turn) : 0f);
    o.right = v - turn - (v == 0f ? 0.3f * Math.signum(turn) : 0f);
    o.status = "Following you" + (sonarOk ? String.format(Locale.US, " · %.0f cm", sonar) : "");
  }

  /** Dead reckoning from the wheel commands of the last frame. */
  private void integrate(long now) {
    if (lastStep >= 0) {
      double dt = Math.min(0.5, (now - lastStep) / 1000.0);
      double v = (lastLeft + lastRight) / 2.0 * CM_PER_S;
      double w = (lastLeft - lastRight) / 2.0 * DEG_PER_S;
      heading = wrap(heading + w * dt);
      x += v * dt * Math.sin(Math.toRadians(heading));
      y += v * dt * Math.cos(Math.toRadians(heading));
    }
    lastStep = now;
  }

  private static double wrap(double a) {
    while (a > 180) a -= 360;
    while (a < -180) a += 360;
    return a;
  }

  private static float clamp(float v, float lo, float hi) {
    return Math.max(lo, Math.min(hi, v));
  }
}
