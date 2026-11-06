package org.openbot.app.robot.voice;

import java.util.Locale;

/**
 * Offline keyword understanding for the Voice Control screen.
 *
 * <p>This is the fallback used when no Xiaozhi server is configured (or the server is unreachable,
 * or the phone is offline): the speech-to-text transcript is matched against the same small
 * vocabulary the firmware's {@code t<text>} channel understands, in English, Vietnamese and
 * Chinese. Order matters and mirrors the firmware exactly: "stop" wins over everything ("don't go"
 * must stop, not drive), and "back" is checked before "go" so "go back" reverses.
 *
 * <p>Turning commands spin in place, matching the firmware's text channel. The Xiaozhi LLM path
 * can produce richer moves (arc turns, chosen durations); this path keeps them short and predictable.
 */
public final class VoiceInterpreter {

  private static final String[] STOP_WORDS = {
    "stop", "halt", "dừng", "dừng lại", "dung lai", "đứng lại", "dung", "停", "停止", "别动"
  };
  private static final String[] BACK_WORDS = {
    "back", "reverse", "retreat", "go back", "lùi", "lùi lại", "lui", "倒", "后退", "向后"
  };
  private static final String[] LEFT_WORDS = {
    "left", "turn left", "trái", "rẽ trái", "re trai", "左", "左转"
  };
  private static final String[] RIGHT_WORDS = {
    "right", "turn right", "phải", "rẽ phải", "re phai", "右", "右转"
  };
  private static final String[] FASTER_WORDS = {
    "faster", "speed up", "speedup", "nhanh", "nhanh lên", "tăng tốc", "快", "快点", "加速"
  };
  private static final String[] SLOWER_WORDS = {
    "slower", "slow down", "slowdown", "chậm", "chậm lại", "cham", "giảm tốc", "慢", "慢点", "减速"
  };
  private static final String[] FWD_WORDS = {
    "forward", "ahead", "straight", "go", "move", "tiến", "tien", "đi", "前进", "向前", "走"
  };

  private VoiceInterpreter() {}

  /** Matches a transcript; never returns null. Unknown words become {@code UNKNOWN}. */
  public static VoiceCommand interpret(String text) {
    if (text == null) return VoiceCommand.of(VoiceCommand.Action.UNKNOWN, "", "local");
    String t = text.toLowerCase(Locale.ROOT).trim();
    if (t.isEmpty()) return VoiceCommand.of(VoiceCommand.Action.UNKNOWN, text, "local");

    VoiceCommand.Action action;
    if (containsAny(t, STOP_WORDS)) action = VoiceCommand.Action.STOP;
    else if (containsAny(t, BACK_WORDS)) action = VoiceCommand.Action.BACKWARD;
    else if (containsAny(t, LEFT_WORDS)) action = VoiceCommand.Action.LEFT;
    else if (containsAny(t, RIGHT_WORDS)) action = VoiceCommand.Action.RIGHT;
    else if (containsAny(t, FASTER_WORDS)) action = VoiceCommand.Action.FASTER;
    else if (containsAny(t, SLOWER_WORDS)) action = VoiceCommand.Action.SLOWER;
    else if (containsAny(t, FWD_WORDS)) action = VoiceCommand.Action.FORWARD;
    else action = VoiceCommand.Action.UNKNOWN;

    return VoiceCommand.of(action, text, "local");
  }

  private static boolean containsAny(String text, String[] needles) {
    for (String n : needles) {
      if (text.contains(n)) return true;
    }
    return false;
  }
}
