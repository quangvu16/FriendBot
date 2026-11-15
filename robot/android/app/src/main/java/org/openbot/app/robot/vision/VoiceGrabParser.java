package org.openbot.app.robot.vision;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the user said → one robot request, in English or Vietnamese. Pure logic (tested off the
 * phone). The object to recognize always comes from the words, never from the page's object
 * setting. Four fetch commands, each ending with "ask the new command":
 *
 * <pre>
 *   1. "pick up the cup" / "lấy cái cốc"            → PICK cup            → carry to the person
 *      "bring me the bottle" / "mang chai lại đây"  → PICK bottle         → carry to the person
 *   2. "bring the cup to the table" / "mang cốc đến cái bàn"
 *                                                  → BRING cup → table   → carry to the new place
 *   3. "find the ball" / "tìm quả bóng"             → FIND sports ball    → carry to the old place
 *   4. "put the cup next to the bottle" / "đặt cái cốc bên cạnh cái chai"
 *                                                  → NEXT_TO cup→bottle  → carry next to the bottle
 * </pre>
 *
 * Plus "put it down", "follow me", "stop", "hello", "thank you", and the plain drive words (which
 * the page handles with {@link org.openbot.app.robot.voice.VoiceInterpreter}).
 */
public final class VoiceGrabParser {

  public enum Intent {
    PICK,
    BRING,
    NEXT_TO,
    PUT_DOWN,
    FOLLOW,
    FIND,
    /** "go to the cup" / "move toward the bottle but don't touch it": get close, never grip. */
    APPROACH,
    /** "what do you see?": read out what the camera currently sees. */
    SEE,
    /** "did you pick it up?": answer from the claw's own report. */
    CHECK,
    STOP,
    HELLO,
    THANKS,
    UNKNOWN
  }

  /** Where the robot carries the object after the claw has it. */
  public enum Dest {
    /** 1: to the person ("move robot to the people object"). */
    PERSON,
    /** 3: to the old place — where the robot was when you asked (dead reckoning). */
    HOME,
    /** 2: to the new place — a landmark from the words ("the table"). */
    PLACE,
    /** 4: next to another object from the words ("the bottle"). */
    NEXT_TO
  }

  public static final class Request {
    public final Intent intent;
    /** What to pick (detector label, "cup", "sports ball", …) or null (use the last one). */
    public final String object;
    /** Landmark label for {@link Dest#PLACE} / {@link Dest#NEXT_TO} ("dining table", "bottle"). */
    public final String dest;
    /** Where to carry it after gripping (null for non-fetch commands). */
    public final Dest destKind;
    /** Detected colour to insist on ("red", "blue", …) or null for any colour. */
    public final String color;
    /** How many objects to collect — above 1 only when the words ask for a count. */
    public final int count;

    Request(Intent intent, String object, String dest, Dest destKind) {
      this(intent, object, dest, destKind, null, 1);
    }

    Request(Intent intent, String object, String dest, Dest destKind, String color, int count) {
      this.intent = intent;
      this.object = object;
      this.dest = dest;
      this.destKind = destKind;
      this.color = color;
      this.count = count;
    }

    @Override
    public String toString() {
      StringBuilder b = new StringBuilder().append(intent);
      if (object != null) b.append(' ').append(object);
      if (dest != null) b.append(" → ").append(dest);
      return b.toString();
    }
  }

  /** Spoken word (English / Vietnamese, without accents) → detector label. Longest first. */
  static final Map<String, String> OBJECTS = new LinkedHashMap<>();

  static {
    String[][] words = {
      {"teddy bear", "teddy bear"}, {"gau bong", "teddy bear"}, {"con gau", "teddy bear"},
      {"sports ball", "sports ball"}, {"tennis ball", "sports ball"}, {"qua bong", "sports ball"},
      {"trai banh", "sports ball"}, {"qua banh", "sports ball"}, {"ball", "sports ball"},
      {"bong", "sports ball"}, {"banh", "sports ball"},
      {"wine glass", "wine glass"}, {"ly ruou", "wine glass"},
      {"cell phone", "cell phone"}, {"mobile phone", "cell phone"}, {"dien thoai", "cell phone"},
      {"phone", "cell phone"},
      {"remote", "remote"}, {"dieu khien", "remote"},
      {"toothbrush", "toothbrush"}, {"ban chai", "toothbrush"},
      {"bottle", "bottle"}, {"chai", "bottle"},
      {"cup", "cup"}, {"mug", "cup"}, {"coc", "cup"}, {"ly", "cup"}, {"tach", "cup"},
      {"apple", "apple"}, {"tao", "apple"},
      {"orange", "orange"}, {"qua cam", "orange"},
      {"banana", "banana"}, {"chuoi", "banana"},
      {"book", "book"}, {"sach", "book"},
      {"mouse", "mouse"}, {"con chuot", "mouse"}, {"chuot", "mouse"},
      {"scissors", "scissors"}, {"cai keo", "scissors"},
      {"bowl", "bowl"}, {"cai bat", "bowl"}, {"cai to", "bowl"},
      {"spoon", "spoon"}, {"cai thia", "spoon"}, {"muong", "spoon"}, {"thia", "spoon"},
      {"carrot", "carrot"}, {"ca rot", "carrot"},
      {"donut", "donut"}, {"doughnut", "donut"},
      {"vase", "vase"}, {"lo hoa", "vase"}, {"binh hoa", "vase"},
      {"clock", "clock"}, {"dong ho", "clock"},
      // Places (destinations for "bring the cup to the …"): same detector vocabulary.
      {"dining table", "dining table"}, {"table", "dining table"}, {"ban", "dining table"},
      {"chair", "chair"}, {"ghe", "chair"},
      {"couch", "couch"}, {"sofa", "couch"}, {"giuong", "bed"},
      {"bed", "bed"}, {"tv", "tv"}, {"tivi", "tv"},
      {"laptop", "laptop"}, {"refrigerator", "refrigerator"}, {"tu lanh", "refrigerator"},
      {"sink", "sink"}, {"toilet", "toilet"}, {"bench", "bench"},
    };
    for (String[] w : words) OBJECTS.put(w[0], w[1]);
  }

  private static final String[] STOP = {"stop", "halt", "freeze", "dung lai", "dung", "thoi", "ngung"};
  private static final String[] PUT = {
    "put it down", "put down", "drop", "let go", "release", "give it", "give me it", "open the claw",
    "open your hand", "tha ra", "tha xuong", "bo xuong", "dat xuong", "dua cho", "buong", "tha"
  };
  private static final String[] BRING = {
    "bring", "come back", "come here", "come to me", "go back", "return", "mang", "dem", "lai day",
    "quay lai", "ve day", "den day"
  };
  private static final String[] PICK = {
    "pick up", "pick", "grab", "get", "fetch", "take", "hold", "catch", "lay", "collect", "gather",
    "nhat", "cam", "gap", "bat", "giu", "thu thap", "gom"
  };
  private static final String[] FOLLOW = {"follow", "di theo", "theo toi", "theo minh"};
  private static final String[] FIND = {"find", "look for", "where is", "where's", "search", "tim", "o dau"};
  private static final String[] HELLO = {"hello", "hi robot", "hey robot", "xin chao", "chao"};
  private static final String[] THANKS = {"thank", "good job", "well done", "cam on", "gioi lam", "tot lam"};
  /** "put the cup next to the bottle" / "đặt cái cốc bên cạnh cái chai". */
  private static final String[] NEXT_TO = {
    "next to", "beside", "next", "near", "close to", "ben canh", "ke ben", "gan", "sat", "ap"
  };
  /** "bring the cup to the table" / "mang cái cốc đến cái bàn". */
  private static final String[] TO = {"to", "onto", "on", "at", "den", "len", "vao", "sang"};
  /** "bring me the cup", "bring it here": the person is the destination. */
  private static final String[] ME = {"me", "here", "us", "my", "toi", "minh", "day", "cho toi"};
  /** "go to the cup" / "move toward the bottle but don't touch it" — get close, never grip. */
  private static final String[] APPROACH = {
    "approach", "go to", "move toward", "move towards", "move to", "move closer", "come close",
    "don't touch", "do not touch", "den gan", "lai gan", "tien lai gan", "khong cham"
  };
  /** "what do you see?" — read out what the camera currently sees. */
  private static final String[] SEE = {
    "what do you see", "what can you see", "what do you see now", "what's there", "whats there",
    "ban thay gi", "nhin thay gi", "thay gi"
  };
  /** "did you pick it up?" / "check the gripper" — answer from the claw's own report. */
  private static final String[] CHECK = {
    "did you pick", "did you grab", "did you get it", "are you holding", "are you carrying",
    "check if", "check whether", "check the grab", "check the gripper", "check the claw",
    "ban da cam", "kiem tra"
  };
  /**
   * Spoken colour → canonical name; the longest phrases must come first ("xanh duong" before
   * "xanh").
   */
  private static final String[][] COLORS = {
    {"xanh duong", "blue"}, {"xanh lam", "blue"}, {"xanh la", "green"}, {"mau do", "red"},
    {"red", "red"}, {"do", "red"}, {"blue", "blue"}, {"green", "green"},
    {"yellow", "yellow"}, {"vang", "yellow"}, {"xanh", "blue"},
  };
  /** A spoken count ("collect three objects", "hai cái") → how many times to repeat the fetch. */
  private static final String[] COUNT_WORDS = {
    "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
    "mot", "hai", "ba", "bon", "nam", "sau", "bay", "tam", "chin", "muoi",
  };
  private static final int[] COUNT_VALUES = {
    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
  };

  private VoiceGrabParser() {}

  public static Request parse(String said) {
    String plain = plain(said);
    String t = " " + plain + " ";
    // "red cup" / "chai xanh": pull the colour and the count out before the object words.
    String color = extractColor(plain);
    int count = extractCount(plain);
    if (has(t, STOP)) return new Request(Intent.STOP, null, null, null, color, count);
    if (has(t, SEE)) return new Request(Intent.SEE, null, null, null, color, count);
    if (has(t, CHECK)) return new Request(Intent.CHECK, null, null, null, color, count);
    if (has(t, FOLLOW)) return new Request(Intent.FOLLOW, null, null, null, color, count);
    List<String> objs = findObjects(t);
    boolean nextTo = has(t, NEXT_TO);
    boolean to = has(t, TO);
    // Two objects in the words: the first is what to pick, the second names the destination.
    if (objs.size() >= 2 && nextTo) {
      return new Request(Intent.NEXT_TO, objs.get(0), objs.get(1), Dest.NEXT_TO, color, count);
    }
    if (objs.size() >= 2 && to) {
      return new Request(Intent.BRING, objs.get(0), objs.get(1), Dest.PLACE, color, count);
    }
    if (has(t, PUT)) return new Request(Intent.PUT_DOWN, null, null, null, color, count);
    if (has(t, APPROACH)) {
      // "go to the cup" / "move toward the bottle but don't touch it": get close, never grip.
      return new Request(Intent.APPROACH, objs.isEmpty() ? null : objs.get(0), null, null,
          color, count);
    }
    if (objs.size() == 1) {
      String o = objs.get(0);
      if (to && !has(t, ME)) {
        // "bring it to the table": carry what is in the claw to that place.
        return new Request(Intent.BRING, null, o, Dest.PLACE, color, count);
      }
      if (has(t, FIND)) return new Request(Intent.FIND, o, null, Dest.HOME, color, count);
      if (has(t, PICK)) {
        // "pick up the bottle and put it at the box": the place is not a landmark we can see —
        // carry it to the person instead of misreading the bottle as a place.
        return new Request(Intent.PICK, o, null, Dest.PERSON, color, count);
      }
      // "the cup!" alone → pick it up.
      return new Request(Intent.PICK, o, null, Dest.PERSON, color, count);
    }
    // No object named: it is whatever the user last asked for (or is in the claw).
    if (has(t, BRING)) return new Request(Intent.BRING, null, null, Dest.PERSON, color, count);
    if (has(t, FIND)) return new Request(Intent.FIND, null, null, Dest.HOME, color, count);
    if (has(t, PICK)) return new Request(Intent.PICK, null, null, Dest.PERSON, color, count);
    if (has(t, THANKS)) return new Request(Intent.THANKS, null, null, null, color, count);
    if (has(t, HELLO)) return new Request(Intent.HELLO, null, null, null, color, count);
    return new Request(Intent.UNKNOWN, null, null, null, color, count);
  }

  /** Leftmost spoken colour in the sentence ("the red cup" → "red"), or null. */
  static String extractColor(String plain) {
    String bestWord = null;
    int bestAt = Integer.MAX_VALUE;
    for (String[] c : COLORS) {
      int at = plain.indexOf(c[0]);
      if (at >= 0 && at < bestAt) {
        bestAt = at;
        bestWord = c[1];
      }
    }
    return bestWord;
  }

  /**
   * How many objects to collect: a spoken count word, else a bare digit ("2", "3") in 1–20.
   * Digits override the word scan ("collect 2 red cups" → 2).
   */
  static int extractCount(String plain) {
    int best = 1;
    for (int i = 0; i < COUNT_WORDS.length; i++) {
      if ((" " + plain + " ").contains(" " + COUNT_WORDS[i] + " ")) {
        best = COUNT_VALUES[i];
        break;
      }
    }
    for (String tok : plain.split(" ")) {
      if (tok.matches("\\d+")) {
        int n = Integer.parseInt(tok);
        if (n >= 1 && n <= 20) return n;
      }
    }
    return best;
  }

  /** Lower case, Vietnamese accents removed (đ → d), punctuation → spaces. */
  static String plain(String s) {
    if (s == null) return "";
    String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .replace('đ', 'd');
    return n.replaceAll("[^a-z0-9' ]+", " ").replaceAll("\\s+", " ").trim();
  }

  private static boolean has(String text, String[] words) {
    for (String w : words) {
      if (text.contains(" " + w + " ") || text.contains(" " + w + "s ")) return true;
    }
    return false;
  }

  /** Every object mentioned, in the order the words say them ("cup … bottle"). */
  private static List<String> findObjects(String text) {
    List<String> found = new ArrayList<>();
    String rest = text;
    while (true) {
      int bestAt = Integer.MAX_VALUE;
      String bestWord = null;
      String bestLabel = null;
      for (Map.Entry<String, String> e : OBJECTS.entrySet()) {
        String w = e.getKey();
        int at = indexOf(" " + rest + " ", w);
        if (at >= 0 && at < bestAt) {
          bestAt = at;
          bestWord = w;
          bestLabel = e.getValue();
        }
      }
      if (bestWord == null) return found;
      found.add(bestLabel);
      // Cut the text after this mention so "cup … cup" lists both.
      int cut = rest.indexOf(bestWord);
      rest = rest.substring(cut + bestWord.length());
    }
  }

  private static int indexOf(String text, String word) {
    int a = text.indexOf(" " + word + " ");
    int b = text.indexOf(" " + word + "s ");
    int c = text.indexOf(" " + word + "es ");
    int at = Integer.MAX_VALUE;
    if (a >= 0) at = Math.min(at, a);
    if (b >= 0) at = Math.min(at, b);
    if (c >= 0) at = Math.min(at, c);
    return at == Integer.MAX_VALUE ? -1 : at;
  }
}
