package org.openbot.app.robot.voice;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import androidx.annotation.Nullable;

/**
 * The Voice Control page's full-screen robot face: two glowing eyes and one mouth on black,
 * nothing else (design: "FriendBot Face Voice Control"). The page tells it which emotion to show
 * ({@link #setFace}); the view animates by itself — blinks every ~4.6 s, a small motion per
 * emotion, a moving mouth while Xiaozhi speaks, and sleepy after 60 s with nothing happening.
 *
 * <p>{@link #showPhoto} replaces the face with the captured picture for a few seconds (white
 * flash, a thin countdown bar), then the face comes back smiling for 1.5 s.
 *
 * <p>Gestures: long-press anywhere = STOP; double-tap = show the page's controls for a while.
 * Geometry is in design units (the eyes are 56×88 at scale 1) scaled to the view's width.
 */
public class RobotFaceView extends View {

  /** The faces the design draws. */
  public enum Emotion {
    NEUTRAL,
    LISTENING,
    THINKING,
    HAPPY,
    SURPRISED,
    SAD,
    SLEEPY,
    ANGRY,
    CONFUSED
  }

  /** Gestures for the page. */
  public interface Listener {
    void onFaceLongPress();

    void onFaceDoubleTap();
  }

  public static final int ACCENT = 0xFF7FE7FF;
  private static final int GROUND = 0xFF05070B;
  private static final long SLEEPY_AFTER_MS = 60_000;
  private static final long HAPPY_AFTER_PHOTO_MS = 1_500;
  private static final long FLASH_MS = 350;

  private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint photoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path = new Path();
  private final RectF rect = new RectF();
  private final GestureDetector gestures;

  private Emotion emotion = Emotion.NEUTRAL;
  private boolean talking;
  private long changedAtMs = SystemClock.uptimeMillis();
  private long popStartMs;

  private Bitmap photo;
  private long photoStartMs;
  private long photoMs;
  private long happyUntilMs;

  @Nullable private Listener listener;

  public RobotFaceView(Context context) {
    this(context, null);
  }

  public RobotFaceView(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    fill.setStyle(Paint.Style.FILL);
    fill.setColor(ACCENT);
    stroke.setStyle(Paint.Style.STROKE);
    stroke.setColor(ACCENT);
    stroke.setStrokeCap(Paint.Cap.ROUND);
    stroke.setStrokeJoin(Paint.Join.ROUND);
    float glow = 18 * getResources().getDisplayMetrics().density;
    int glowColor = (ACCENT & 0x00FFFFFF) | 0x66000000;
    fill.setShadowLayer(glow, 0, 0, glowColor);
    stroke.setShadowLayer(glow, 0, 0, glowColor);
    if (Build.VERSION.SDK_INT < 28) {
      setLayerType(LAYER_TYPE_SOFTWARE, null); // shadow layers on shapes need it before Android 9
    }
    barPaint.setStyle(Paint.Style.FILL);
    setBackgroundColor(GROUND);
    setClickable(true);
    setLongClickable(true);
    setContentDescription("FriendBot face. Long-press to stop. Double-tap to show controls.");
    gestures =
        new GestureDetector(
            context,
            new GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onDown(MotionEvent e) {
                return true;
              }

              @Override
              public boolean onDoubleTap(MotionEvent e) {
                if (listener != null) listener.onFaceDoubleTap();
                return true;
              }

              @Override
              public void onLongPress(MotionEvent e) {
                performHapticFeedback(HAPTIC_FEEDBACK_ENABLED);
                if (listener != null) listener.onFaceLongPress();
              }
            });
  }

  public void setListener(@Nullable Listener l) {
    listener = l;
  }

  /** The emotion to show; {@code talking} animates the mouth (Xiaozhi's voice is playing). */
  public void setFace(Emotion e, boolean isTalking) {
    if (e == null) e = Emotion.NEUTRAL;
    long now = SystemClock.uptimeMillis();
    if (e != emotion || isTalking != talking) {
      changedAtMs = now;
      if (e == Emotion.SURPRISED && emotion != Emotion.SURPRISED) popStartMs = now;
    }
    emotion = e;
    talking = isTalking;
    invalidate();
  }

  /** Shows the captured picture full screen for {@code ms}, then the face smiles. */
  public void showPhoto(@Nullable Bitmap bitmap, long ms) {
    if (bitmap == null) return;
    photo = bitmap;
    photoStartMs = SystemClock.uptimeMillis();
    photoMs = Math.max(500, ms);
    changedAtMs = photoStartMs;
    invalidate();
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    gestures.onTouchEvent(event);
    return true;
  }

  // ------------------------------------------------------------------ drawing

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    long now = SystemClock.uptimeMillis();
    if (photo != null) {
      long elapsed = now - photoStartMs;
      if (elapsed < photoMs) {
        drawPhoto(canvas, elapsed);
        postInvalidateOnAnimation();
        return;
      }
      photo = null;
      happyUntilMs = now + HAPPY_AFTER_PHOTO_MS;
      changedAtMs = now;
    }
    Emotion shown = emotion;
    if (now < happyUntilMs) {
      shown = Emotion.HAPPY;
    } else if (!talking
        && (emotion == Emotion.NEUTRAL)
        && now - changedAtMs > SLEEPY_AFTER_MS) {
      shown = Emotion.SLEEPY;
    }
    drawFace(canvas, shown, talking && now >= happyUntilMs, now);
    postInvalidateOnAnimation(); // the face is always gently alive
  }

  /** Design geometry per emotion: eye w/h/radius, tilt, offsets, motion. */
  private static final class Geo {
    float w, h, r, tilt, dy, lx, ly, rightScale = 1f;
    boolean arc, blink, o;
    String anim = "breathe";
  }

  private static Geo geo(Emotion e) {
    Geo g = new Geo();
    switch (e) {
      case LISTENING:
        g.w = 60; g.h = 100; g.r = 30; g.blink = true; g.anim = "listen";
        break;
      case THINKING:
        g.w = 56; g.h = 60; g.r = 26; g.lx = 14; g.ly = -18; g.blink = true; g.anim = "think";
        break;
      case HAPPY:
        g.arc = true; g.w = 70; g.h = 36; g.anim = "bounce";
        break;
      case SURPRISED:
        g.w = 70; g.h = 70; g.r = 35; g.o = true; g.blink = true; g.anim = "pop";
        break;
      case SAD:
        g.w = 56; g.h = 60; g.r = 26; g.tilt = -14; g.dy = 12; g.blink = true; g.anim = "droop";
        break;
      case SLEEPY:
        g.w = 68; g.h = 10; g.r = 5; g.anim = "snooze";
        break;
      case ANGRY:
        g.w = 60; g.h = 60; g.r = 14; g.tilt = 16; g.blink = true; g.anim = "shake";
        break;
      case CONFUSED:
        g.w = 56; g.h = 76; g.r = 28; g.rightScale = 0.6f; g.blink = true; g.anim = "tilt";
        break;
      case NEUTRAL:
      default:
        g.w = 56; g.h = 88; g.r = 28; g.blink = true; g.anim = "breathe";
        break;
    }
    return g;
  }

  private static float wave(long now, float periodMs) {
    return (float) Math.sin(2 * Math.PI * (now % (long) periodMs) / periodMs);
  }

  private void drawFace(Canvas canvas, Emotion e, boolean isTalking, long now) {
    int w = getWidth();
    int h = getHeight();
    if (w == 0 || h == 0) return;
    float s = Math.min(w, h * 0.62f) / 260f; // design: 390 px wide screen at scale 1.5
    Geo g = geo(e);

    // Per-emotion motion of the whole face.
    float tx = 0, ty = 0, scale = 1, rot = 0;
    int alpha = 255;
    switch (g.anim) {
      case "listen":
        scale = 1 + 0.0225f * (1 + wave(now, 1300));
        break;
      case "think":
        tx = 4 * s * (1 + wave(now, 2200));
        break;
      case "bounce":
        ty = -10 * s * Math.abs(wave(now, 1800));
        break;
      case "pop":
        float p = Math.min(1f, (now - popStartMs) / 500f);
        scale = p < 0.6f ? 0.85f + 0.23f * (p / 0.6f) : 1.08f - 0.08f * ((p - 0.6f) / 0.4f);
        break;
      case "droop":
        ty = 3 * s * (1 + wave(now, 3000));
        break;
      case "snooze":
        alpha = (int) (255 * (0.775f + 0.225f * wave(now, 3200)));
        break;
      case "shake":
        tx = 4 * s * wave(now, 450);
        break;
      case "tilt":
        rot = -3 * (1 + wave(now, 2400));
        break;
      case "breathe":
      default:
        ty = -3 * s * (1 + wave(now, 4000));
        break;
    }
    fill.setAlpha(alpha);
    stroke.setAlpha(alpha);

    float eyeBoxH = 100 * s;
    float gap = 36 * s;
    float mouthH = 100 * s;
    float total = eyeBoxH + gap + mouthH;
    float cx = w / 2f;
    float top = h / 2f - total / 2f;
    float eyeCy = top + eyeBoxH / 2f;

    canvas.save();
    canvas.translate(tx, ty);
    canvas.rotate(rot, cx, h / 2f);
    canvas.scale(scale, scale, cx, h / 2f);

    // Blink: 4.6 s cycle, closes around 96 %.
    float blink = 1f;
    if (g.blink) {
      float ph = (now % 4600) / 4600f;
      if (ph > 0.93f) {
        float k = ph < 0.96f ? (ph - 0.93f) / 0.03f : (1f - ph) / 0.04f;
        blink = 1f - 0.92f * Math.min(1f, k);
      }
    }
    float eyeOffset = 32 * s; // half the 64 gap between the eyes
    for (int side = -1; side <= 1; side += 2) {
      float k = side > 0 ? g.rightScale : 1f;
      float ew = (g.arc ? g.w : g.w * k) * s;
      float eh = (g.arc ? g.h : g.h * k) * s;
      float ecx = cx + side * (eyeOffset + g.w * s / 2f) + g.lx * s;
      float ecy = eyeCy + g.ly * s + g.dy * s;
      canvas.save();
      canvas.rotate(side < 0 ? g.tilt : -g.tilt, ecx, ecy);
      if (g.arc) {
        float sw = 11 * s;
        stroke.setStrokeWidth(sw);
        rect.set(ecx - ew / 2 + sw / 2, ecy - eh / 2 + sw / 2, ecx + ew / 2 - sw / 2, ecy + eh * 1.5f);
        canvas.drawArc(rect, 180, 180, false, stroke);
      } else {
        float bh = eh * blink;
        float r = Math.min(g.r * k * s, bh / 2f);
        rect.set(ecx - ew / 2, ecy - bh / 2, ecx + ew / 2, ecy + bh / 2);
        canvas.drawRoundRect(rect, r, r, fill);
      }
      canvas.restore();
    }

    // Mouth, in the design's 200×100 box.
    float mx = cx - 100 * s;
    float my = top + eyeBoxH + gap;
    stroke.setStrokeWidth(10 * s);
    path.reset();
    if (isTalking) {
      float open = 0.3f + 0.35f * (1 + wave(now, 560));
      rect.set(mx + 70 * s, my + (50 - 22 * open) * s, mx + 130 * s, my + (50 + 22 * open) * s);
      canvas.drawOval(rect, fill);
    } else if (g.o) {
      rect.set(mx + 84 * s, my + 30 * s, mx + 116 * s, my + 70 * s);
      canvas.drawOval(rect, stroke);
    } else {
      mouthPath(e, mx, my, s);
      canvas.drawPath(path, stroke);
    }
    canvas.restore();
  }

  private void mouthPath(Emotion e, float mx, float my, float s) {
    switch (e) {
      case LISTENING:
        quad(mx, my, s, 84, 50, 100, 56, 116, 50);
        break;
      case THINKING:
        path.moveTo(mx + 78 * s, my + 56 * s);
        path.lineTo(mx + 126 * s, my + 48 * s);
        break;
      case HAPPY:
        quad(mx, my, s, 46, 30, 100, 100, 154, 30);
        break;
      case SAD:
        quad(mx, my, s, 62, 70, 100, 36, 138, 70);
        break;
      case SLEEPY:
        quad(mx, my, s, 88, 54, 100, 60, 112, 54);
        break;
      case ANGRY:
        path.moveTo(mx + 66 * s, my + 62 * s);
        path.lineTo(mx + 134 * s, my + 62 * s);
        break;
      case CONFUSED:
        quad(mx, my, s, 60, 56, 80, 44, 100, 56);
        path.quadTo(mx + 120 * s, my + 68 * s, mx + 140 * s, my + 56 * s);
        break;
      case NEUTRAL:
      default:
        quad(mx, my, s, 64, 46, 100, 62, 136, 46);
        break;
    }
  }

  private void quad(float mx, float my, float s, float x1, float y1, float qx, float qy, float x2, float y2) {
    path.moveTo(mx + x1 * s, my + y1 * s);
    path.quadTo(mx + qx * s, my + qy * s, mx + x2 * s, my + y2 * s);
  }

  /** The captured picture, a countdown bar on top and the shutter flash. */
  private void drawPhoto(Canvas canvas, long elapsed) {
    float d = getResources().getDisplayMetrics().density;
    int w = getWidth();
    int h = getHeight();
    canvas.drawColor(Color.BLACK);
    float left = 20 * d;
    float right = w - 20 * d;
    float barTop = 56 * d;
    float imgTop = barTop + 4 * d + 16 * d;
    float imgBottom = h - 40 * d;

    // Countdown bar.
    barPaint.setColor(0x22FFFFFF);
    rect.set(left, barTop, right, barTop + 4 * d);
    canvas.drawRoundRect(rect, 2 * d, 2 * d, barPaint);
    float leftFrac = 1f - Math.min(1f, elapsed / (float) photoMs);
    barPaint.setColor(ACCENT);
    rect.set(left, barTop, left + (right - left) * leftFrac, barTop + 4 * d);
    canvas.drawRoundRect(rect, 2 * d, 2 * d, barPaint);

    // The photo, centre-cropped into a rounded frame.
    rect.set(left, imgTop, right, imgBottom);
    float boxRatio = rect.width() / rect.height();
    float bmpRatio = photo.getWidth() / (float) photo.getHeight();
    Rect src;
    if (bmpRatio > boxRatio) {
      int cw = Math.round(photo.getHeight() * boxRatio);
      int x0 = (photo.getWidth() - cw) / 2;
      src = new Rect(x0, 0, x0 + cw, photo.getHeight());
    } else {
      int ch = Math.round(photo.getWidth() / boxRatio);
      int y0 = (photo.getHeight() - ch) / 2;
      src = new Rect(0, y0, photo.getWidth(), y0 + ch);
    }
    canvas.save();
    path.reset();
    path.addRoundRect(rect, 24 * d, 24 * d, Path.Direction.CW);
    canvas.clipPath(path);
    canvas.drawBitmap(photo, src, rect, photoPaint);
    canvas.restore();

    // Shutter flash.
    if (elapsed < FLASH_MS) {
      int a = (int) (0.95f * 255 * (1f - elapsed / (float) FLASH_MS));
      canvas.drawColor((a << 24) | 0x00FFFFFF);
    }
  }
}
