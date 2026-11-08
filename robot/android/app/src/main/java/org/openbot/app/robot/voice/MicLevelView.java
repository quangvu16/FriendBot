package org.openbot.app.robot.voice;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;

/** Nine cyan level bars under the mic orb (robotic mic pages): dim at rest, alive with voice. */
public class MicLevelView extends View {

  private static final float[] SHAPE = {0.33f, 0.55f, 0.83f, 0.6f, 1f, 0.66f, 0.83f, 0.5f, 0.28f};
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rect = new RectF();
  private float level;
  private boolean active;

  public MicLevelView(Context context) {
    this(context, null);
  }

  public MicLevelView(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    paint.setColor(MicPageStyle.CYAN);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  /** Voice level 0..1. */
  public void setLevel(float l) {
    level = Math.max(0f, Math.min(1f, l));
    invalidate();
  }

  public void setActive(boolean a) {
    active = a;
    if (!a) level = 0f;
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    int n = SHAPE.length;
    float w = getWidth();
    float h = getHeight();
    float d = getResources().getDisplayMetrics().density;
    float barW = 6 * d;
    float gap = (w - n * barW) / (n - 1);
    long now = SystemClock.uptimeMillis();
    paint.setAlpha(active ? 255 : 64);
    for (int i = 0; i < n; i++) {
      float k;
      if (active) {
        float wobble = 0.75f + 0.25f * (float) Math.sin(now / 90.0 + i * 1.3);
        k = Math.max(0.25f, Math.min(1f, (0.25f + level) * wobble));
      } else {
        k = 0.35f;
      }
      float bh = h * SHAPE[i] * k;
      float x = i * (barW + gap);
      rect.set(x, h - bh, x + barW, h);
      canvas.drawRoundRect(rect, barW / 2, barW / 2, paint);
    }
    if (active) postInvalidateOnAnimation();
  }
}
