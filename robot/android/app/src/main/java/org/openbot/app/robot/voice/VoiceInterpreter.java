package org.openbot.app.robot.voice;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline keyword understanding for the Voice Control screen.
 *
 * <p>This is the fallback used when no Xiaozhi server is configured (or the server is unreachable,
 * or the phone is offline): the speech-to-text transcript is matched against the same small
 * vocabulary the firmware's {@code t<text>} channel understands, in English, Vietnamese and
 * Chinese — extended with the voice page's trick and accessory commands (lights, sonar, sing,
 * triangle, circle, 360° spin, turn around, turn left and right).
 *
 * <p>Order matters and mirrors the firmware exactly: "stop" wins over everything ("don't go" must
 * stop, not drive), and "back" is checked before "go" so "go back" reverses. The tricks are checked
 * before the plain moves because their phrases contain those words ("move in circle" must not
 * become "move", "sing and turn around" must not become "turn around", "turn left and right" must
 * not become "turn left"). A number in the words stretches the move ("go forward 5 seconds",
 * "tiến 10 giây", "go 3000 ms").
 */
public final class VoiceInterpreter {

  private static final String[] STOP_WORDS = {
    "stop", "stopping", "halt", "dừng", "dừng lại", "dung lai", "đứng lại", "dung", "停", "停止", "别动"
  };
  private static final String[] SING_WORDS = {
    "sing", "singing", "song", "hát", "hat", "唱歌", "唱"
  };
  private static final String[] SONAR_WORDS = {
    "sonar", "distance", "how far", "khoảng cách", "khoang cach", "cảm biến", "距离", "多远", "超声波"
  };
  private static final String[] LIGHTS_ON_WORDS = {
    "lights on", "light on", "turn on the light", "turn on light", "turn on lights",
    "bật đèn", "bat den", "mở đèn", "mo den", "开灯", "打开灯"
  };
  private static final String[] LIGHTS_OFF_WORDS = {
    "lights off", "light off", "turn off the light", "turn off light", "turn off lights",
    "tắt đèn", "tat den", "关灯", "关掉灯"
  };
  private static final String[] INDICATOR_LEFT_WORDS = {
    "signal left", "left signal", "left indicator", "left blinker", "xi nhan trái", "xi nhan trai",
    "左转向灯"
  };
  private static final String[] INDICATOR_RIGHT_WORDS = {
    "signal right", "right signal", "right indicator", "right blinker", "xi nhan phải",
    "xi nhan phai", "右转向灯"
  };
  private static final String[] INDICATOR_HAZARD_WORDS = {
    "hazard", "both signals", "both indicators", "đèn hazard", "den hazard", "xi nhan cả hai",
    "危险灯"
  };
  private static final String[] INDICATOR_OFF_WORDS = {
    "signals off", "indicators off", "turn off the signal", "tắt xi nhan", "tat xi nhan",
    "关转向灯"
  };
  private static final String[] TRIANGLE_WORDS = {
    "triangle", "tam giác", "tam giac", "三角形", "三角"
  };
  private static final String[] CIRCLE_WORDS = {
    "circle", "vòng tròn", "vong tron", "vòng", "圆形", "圆圈", "画圆"
  };
  private static final String[] SPIN_WORDS = {
    "360", "spin", "spinning", "xoay", "một vòng", "mot vong", "旋转", "转一圈", "转圈"
  };
  private static final String[] TURN_AROUND_WORDS = {
    "turn around", "turnaround", "quay lại", "quay lai", "quay đầu", "quay dau", "掉头", "转身"
  };
  private static final String[] WIGGLE_WORDS = {
    "left and right", "right and left", "trái phải", "trai phai", "phải trái", "phai trai",
    "左右", "左右转", "两边"
  };
  private static final String[] BACK_WORDS = {
    "back", "backward", "backwards", "reverse", "reversing", "retreat", "go back", "lùi", "lùi lại", "lui", "倒", "后退", "向后"
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
    "forward", "forwards", "ahead", "straight", "go", "going", "move", "moving", "tiến", "tien", "đi", "前进", "向前", "走"
  };
  /** "go forward for a long time" — stretches a plain move to 5 s. */
  private static final String[] LONG_WORDS = {
    "long", "for a while", "lâu", "lau", "xa", "far"
  };

  /** A spoken duration: number + optional unit ("5 seconds", "3000 ms", "10 giây", "2 phút"). */
  private static final Pattern DURATION =
      Pattern.compile(
          "(\\d+(?:[.,]\\d+)?)\\s*(ms|milliseconds?|mili\\w*|s|secs?|seconds?|giây|giay|phút|phut|minutes?|min)?");

  private static final int LONG_MOVE_MS = 5000;

  private VoiceInterpreter() {}

  /** Matches a transcript; never returns null. Unknown words become {@code UNKNOWN}. */
  public static VoiceCommand interpret(String text) {
    if (text == null) return VoiceCommand.of(VoiceCommand.Action.UNKNOWN, "", "local");
    // NFC: keyboards may send Vietnamese as base letter + combining mark.
    String t = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).trim();
    if (t.isEmpty()) return VoiceCommand.of(VoiceCommand.Action.UNKNOWN, text, "local");

    VoiceCommand.Action action;
    // Tricks before plain moves: their phrases contain the plain move words.
    if (containsAny(t, STOP_WORDS)) action = VoiceCommand.Action.STOP;
    else if (containsAny(t, SING_WORDS)) action = VoiceCommand.Action.SING;
    else if (containsAny(t, SONAR_WORDS)) action = VoiceCommand.Action.SONAR;
    else if (containsAny(t, LIGHTS_ON_WORDS)) action = VoiceCommand.Action.LIGHTS_ON;
    else if (containsAny(t, LIGHTS_OFF_WORDS)) action = VoiceCommand.Action.LIGHTS_OFF;
    else if (containsAny(t, TRIANGLE_WORDS)) action = VoiceCommand.Action.TRIANGLE;
    else if (containsAny(t, CIRCLE_WORDS)) action = VoiceCommand.Action.CIRCLE;
    else if (containsAny(t, SPIN_WORDS)) action = VoiceCommand.Action.SPIN360;
    else if (containsAny(t, TURN_AROUND_WORDS)) action = VoiceCommand.Action.TURN_AROUND;
    else if (containsAny(t, WIGGLE_WORDS)) action = VoiceCommand.Action.WIGGLE;
    else if (containsAny(t, INDICATOR_LEFT_WORDS)) action = VoiceCommand.Action.INDICATOR_LEFT;
    else if (containsAny(t, INDICATOR_RIGHT_WORDS)) action = VoiceCommand.Action.INDICATOR_RIGHT;
    else if (containsAny(t, INDICATOR_HAZARD_WORDS)) action = VoiceCommand.Action.INDICATOR_HAZARD;
    else if (containsAny(t, INDICATOR_OFF_WORDS)) action = VoiceCommand.Action.INDICATOR_OFF;
    else if (containsAny(t, BACK_WORDS)) action = VoiceCommand.Action.BACKWARD;
    else if (containsAny(t, LEFT_WORDS)) action = VoiceCommand.Action.LEFT;
    else if (containsAny(t, RIGHT_WORDS)) action = VoiceCommand.Action.RIGHT;
    else if (containsAny(t, FASTER_WORDS)) action = VoiceCommand.Action.FASTER;
    else if (containsAny(t, SLOWER_WORDS)) action = VoiceCommand.Action.SLOWER;
    else if (containsAny(t, FWD_WORDS)) action = VoiceCommand.Action.FORWARD;
    else action = VoiceCommand.Action.UNKNOWN;

    int durationMs = parseDurationMs(t);
    if (durationMs <= 0 && containsAny(t, LONG_WORDS)) durationMs = LONG_MOVE_MS;
    if (durationMs <= 0) {
      return VoiceCommand.of(action, text, "local");
    }
    return VoiceCommand.of(
        action, VoiceCommand.DEFAULT_SPEED, durationMs, text, "local");
  }

  /**
   * The duration the words asked for in ms ("go forward 5 seconds" → 5000), or -1 when the words
   * carry no duration. A bare number is seconds when small (≤ 60) and milliseconds when large
   * ("go 3000" runs 3000 ms). Clamped by {@link VoiceCommand#MAX_DURATION_MS}.
   */
  static int parseDurationMs(String text) {
    Matcher m = DURATION.matcher(text);
    if (!m.find()) return -1;
    double value;
    try {
      value = Double.parseDouble(m.group(1).replace(',', '.'));
    } catch (NumberFormatException e) {
      return -1;
    }
    String unit = m.group(2) == null ? "" : m.group(2);
    double ms;
    if (unit.startsWith("ms") || unit.startsWith("mili")) ms = value;
    else if (unit.startsWith("ph") || unit.startsWith("min") || unit.startsWith("mi")) ms = value * 60000;
    else if (unit.isEmpty()) ms = value <= 60 ? value * 1000 : value;
    else ms = value * 1000; // s, sec, second, giây…
    long rounded = Math.round(ms);
    return (int) Math.max(1, Math.min(VoiceCommand.MAX_DURATION_MS, rounded));
  }

  private static boolean containsAny(String text, String[] needles) {
    for (String n : needles) {
      if (containsWord(text, n)) return true;
    }
    return false;
  }

  /**
   * True when {@code needle} appears in {@code text} as a whole word, so "hat" does not match
   * "what" and "đi" does not match "điện". Chinese keywords are matched as plain substrings,
   * because Chinese writes words without spaces.
   */
  static boolean containsWord(String text, String needle) {
    if (needle.isEmpty()) return false;
    if (Character.UnicodeScript.of(needle.codePointAt(0)) == Character.UnicodeScript.HAN) {
      return text.contains(needle);
    }
    int from = 0;
    while (true) {
      int i = text.indexOf(needle, from);
      if (i < 0) return false;
      int end = i + needle.length();
      boolean startOk = i == 0 || !Character.isLetterOrDigit(text.codePointBefore(i));
      boolean endOk = end >= text.length() || !Character.isLetterOrDigit(text.codePointAt(end));
      if (startOk && endOk) return true;
      from = i + 1;
    }
  }
}
