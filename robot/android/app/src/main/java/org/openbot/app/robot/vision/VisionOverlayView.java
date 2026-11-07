package org.openbot.app.robot.vision;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import org.openbot.app.robot.env.ImageUtils;

/**
 * Transparent overlay drawn on top of the camera preview by the vision-mode screens. Boxes are
 * given in analysis-frame coordinates and mapped to the on-screen (fitted, rotated) preview the
 * same way {@link org.openbot.app.robot.tracking.MultiBoxTracker} maps its detections.
 */
public class VisionOverlayView extends View {

  /** One rectangle to draw, in analysis-frame coordinates. */
  public static class Box {
    public final RectF rect;
    public final String label;
    public final int color;
    public final float strokeWidth;

    public Box(RectF rect, String label, int color, float strokeWidth) {
      this.rect = rect;
      this.label = label;
      this.color = color;
      this.strokeWidth = strokeWidth;
    }
  }

  private final Paint boxPaint = new Paint();
  private final Paint fillPaint = new Paint();
  private final Paint textPaint = new Paint();

  private int frameWidth = 1;
  private int frameHeight = 1;
  private int sensorOrientation = 0;
  private Matrix frameToCanvas = new Matrix();
  private Matrix canvasToFrame = new Matrix();

  // Published from the camera thread, read by onDraw on the UI thread.
  private volatile List<Box> boxes = new ArrayList<>();
  private volatile List<RectF> sensorBoxes = new ArrayList<>();
  private float[] guideFractions = null;
  private Integer swatchColor = null;
  private boolean mirrored = false;

  public VisionOverlayView(Context context) {
    this(context, null);
  }

  public VisionOverlayView(Context context, AttributeSet attrs) {
    super(context, attrs);
    boxPaint.setStyle(Paint.Style.STROKE);
    boxPaint.setStrokeWidth(4.0f);
    boxPaint.setAntiAlias(true);
    fillPaint.setStyle(Paint.Style.FILL);
    fillPaint.setAntiAlias(true);
    textPaint.setColor(Color.WHITE);
    textPaint.setTextSize(28f);
    textPaint.setAntiAlias(true);
    textPaint.setShadowLayer(3f, 0f, 1f, Color.BLACK);
  }

  public void setFrameConfiguration(int width, int height, int orientation) {
    frameWidth = Math.max(1, width);
    frameHeight = Math.max(1, height);
    sensorOrientation = orientation;
    updateMatrix(getWidth(), getHeight());
  }

  public void setBoxes(List<Box> boxes) {
    this.boxes = boxes != null ? boxes : new ArrayList<>();
  }

  /** Fixed rectangles (e.g. the line sensors), in analysis-frame coordinates. */
  public void setSensorBoxes(List<RectF> rects) {
    this.sensorBoxes = rects != null ? rects : new ArrayList<>();
  }

  /** Vertical guide lines at these fractions (0..1) of the fitted frame width. */
  public void setGuides(float[] fractions) {
    this.guideFractions = fractions;
  }

  /** Colour chip shown in the top-right corner (the picked target colour), or null to hide. */
  public void setSwatchColor(Integer color) {
    this.swatchColor = color;
  }

  /** Front-camera previews are shown mirrored; flip the mapping so overlay and touch agree. */
  public void setMirrored(boolean mirrored) {
    if (this.mirrored != mirrored) {
      this.mirrored = mirrored;
      updateMatrix(getWidth(), getHeight());
    }
  }

  /** Maps a touch on this view to analysis-frame coordinates. Returns false when unknown. */
  public boolean viewToFrame(float viewX, float viewY, float[] outXY) {
    if (outXY == null || outXY.length < 2) return false;
    if (frameToCanvas.isIdentity() && getWidth() > 0) updateMatrix(getWidth(), getHeight());
    float[] pt = {viewX, viewY};
    canvasToFrame.mapPoints(pt);
    outXY[0] = pt[0];
    outXY[1] = pt[1];
    return true;
  }

  /** Maps a point in analysis-frame coordinates to this view's coordinates. */
  public boolean frameToView(float frameX, float frameY, float[] outXY) {
    if (outXY == null || outXY.length < 2) return false;
    if (frameToCanvas.isIdentity() && getWidth() > 0) updateMatrix(getWidth(), getHeight());
    float[] pt = {frameX, frameY};
    frameToCanvas.mapPoints(pt);
    outXY[0] = pt[0];
    outXY[1] = pt[1];
    return true;
  }

  /** The fitted preview rectangle (rotated, letterboxed) in this view's coordinates. */
  public RectF getFittedFrameRect() {
    if (frameToCanvas.isIdentity() && getWidth() > 0) updateMatrix(getWidth(), getHeight());
    RectF fitted = new RectF(0, 0, frameWidth, frameHeight);
    frameToCanvas.mapRect(fitted);
    return fitted;
  }

  private void updateMatrix(int canvasWidth, int canvasHeight) {
    if (canvasWidth <= 0 || canvasHeight <= 0) return;
    final boolean rotated = sensorOrientation % 180 == 90;
    final float multiplier =
        Math.min(
            canvasHeight / (float) (rotated ? frameWidth : frameHeight),
            canvasWidth / (float) (rotated ? frameHeight : frameWidth));
    frameToCanvas =
        ImageUtils.getTransformationMatrix(
            frameWidth,
            frameHeight,
            (int) (multiplier * (rotated ? frameHeight : frameWidth)),
            (int) (multiplier * (rotated ? frameWidth : frameHeight)),
            sensorOrientation,
            new RectF(0, 0, 0, 0),
            false);
    frameToCanvas.invert(canvasToFrame);
    if (mirrored) {
      RectF fitted = new RectF(0, 0, frameWidth, frameHeight);
      frameToCanvas.mapRect(fitted);
      Matrix flip = new Matrix();
      flip.setScale(-1, 1, fitted.centerX(), 0);
      frameToCanvas.postConcat(flip);
      frameToCanvas.invert(canvasToFrame);
    }
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    updateMatrix(canvas.getWidth(), canvas.getHeight());

    // Vertical guides (thirds of the fitted frame).
    if (guideFractions != null) {
      RectF fitted = new RectF(0, 0, frameWidth, frameHeight);
      frameToCanvas.mapRect(fitted);
      boxPaint.setColor(Color.WHITE);
      boxPaint.setStrokeWidth(3.0f);
      boxPaint.setAlpha(140);
      for (float f : guideFractions) {
        float x = fitted.left + f * fitted.width();
        canvas.drawLine(x, fitted.top, x, fitted.bottom, boxPaint);
      }
      boxPaint.setAlpha(255);
    }

    // Fixed sensor boxes.
    boxPaint.setStrokeWidth(3.0f);
    for (RectF r : sensorBoxes) {
      RectF mapped = new RectF(r);
      frameToCanvas.mapRect(mapped);
      boxPaint.setColor(Color.WHITE);
      canvas.drawRect(mapped, boxPaint);
    }

    // Tracked/detected boxes.
    for (Box box : boxes) {
      RectF mapped = new RectF(box.rect);
      frameToCanvas.mapRect(mapped);
      boxPaint.setColor(box.color);
      boxPaint.setStrokeWidth(box.strokeWidth);
      canvas.drawRect(mapped, boxPaint);
      if (box.label != null && !box.label.isEmpty()) {
        textPaint.setColor(box.color);
        float corner = Math.min(mapped.width(), mapped.height()) / 8f;
        canvas.drawText(box.label, mapped.left + corner, mapped.top + 34f, textPaint);
      }
    }

    // Target colour swatch.
    if (swatchColor != null) {
      float size = 72f;
      fillPaint.setColor(swatchColor);
      canvas.drawRoundRect(
          new RectF(24f, 24f, 24f + size, 24f + size), 12f, 12f, fillPaint);
      boxPaint.setColor(Color.WHITE);
      boxPaint.setStrokeWidth(3.0f);
      canvas.drawRoundRect(
          new RectF(24f, 24f, 24f + size, 24f + size), 12f, 12f, boxPaint);
    }
  }
}
