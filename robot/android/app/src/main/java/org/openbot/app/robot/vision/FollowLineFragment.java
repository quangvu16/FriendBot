package org.openbot.app.robot.vision;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import androidx.annotation.Nullable;
import androidx.camera.core.ImageProxy;
import java.util.ArrayList;
import java.util.List;
import org.openbot.app.robot.R;
import org.openbot.app.robot.vehicle.Control;

/**
 * Follow a Black Line — port of stage 3 of Kirstin813/L4-Individual-Project.
 *
 * <p>The original watches three virtual sensors along the bottom of the camera image — the frame is
 * split into thirds, each sensor is a strip along the bottom edge — and treats a sensor as "black"
 * when its mean greyscale brightness is under 90/255. Control follows the priority centre → left →
 * right. Driving follows the AI tab's Object Tracking page: the line position goes through the
 * same steering law as MultiBoxTracker.updateTarget (centre → straight on, side → curve that way)
 * and the command is sent unchanged. No sensor on the line stops the car. The line colour is
 * choosable — black by default (matched by brightness), any preset colour matched per pixel. Use
 * the back camera, phone upright (top up), pointed down at a tape circuit.
 */
public class FollowLineFragment extends VisionModeFragment {

  private static final int DEFAULT_THRESHOLD = 90;
  private static final int SENSOR_COUNT = 3;
  /** Sensor strip height as a fraction of the fitted preview (L4 used 50 px of ~480). */
  private static final float SENSOR_HEIGHT_FRACTION = 0.12f;
  private static final int SCAN_STRIDE = 2;
  /** Per-channel RGB tolerance when matching a coloured line. */
  private static final int COLOR_TOLERANCE = 60;
  /** A sensor counts as on-line when this fraction of its pixels matches the line colour. */
  private static final float MATCH_FRACTION = 0.12f;

  private int blackThreshold = DEFAULT_THRESHOLD;
  /** Line colour to follow: black by default (matched by brightness), any preset otherwise. */
  private volatile int lineColor = Color.BLACK;
  private int[] pixelBuffer = null;

  @Override
  protected String getGuideId() {
    return GuideFragment.GUIDE_LINE;
  }

  @Override
  protected void setupModeControls() {
    addColorRow(
        getString(R.string.follow_line_color),
        new String[] {
          getString(R.string.color_black),
          getString(R.string.color_red),
          getString(R.string.color_green),
          getString(R.string.color_blue),
          getString(R.string.color_yellow)
        },
        new int[] {Color.BLACK, 0xFFE53935, 0xFF43A047, 0xFF1E88E5, 0xFFFDD835},
        v -> lineColor = v);
    addStepper(
        getString(R.string.follow_line_threshold),
        30,
        150,
        10,
        DEFAULT_THRESHOLD,
        v -> blackThreshold = v);
    setStatus(getString(R.string.follow_line_hint));
  }

  @Override
  protected void processVisionFrame(Bitmap frame, ImageProxy proxy) {
    ensureFrameConfiguration();

    RectF fitted = getFittedFrameRect();
    if (fitted == null) {
      sendTrackingControl(new Control(0, 0));
      return;
    }

    int width = frame.getWidth();
    int height = frame.getHeight();
    if (pixelBuffer == null || pixelBuffer.length != width * height) {
      pixelBuffer = new int[width * height];
    }
    frame.getPixels(pixelBuffer, 0, width, 0, 0, width, height);

    // Three sensors along the bottom edge of the preview: map them to frame coordinates so the
    // overlay draws exactly the areas being sampled.
    RectF[] frameSensors = new RectF[SENSOR_COUNT];
    float sensorHeight = fitted.height() * SENSOR_HEIGHT_FRACTION;
    float yTop = fitted.bottom - sensorHeight;
    for (int i = 0; i < SENSOR_COUNT; i++) {
      RectF viewRect =
          new RectF(
              fitted.left + i * fitted.width() / SENSOR_COUNT,
              yTop,
              fitted.left + (i + 1) * fitted.width() / SENSOR_COUNT,
              fitted.bottom);
      frameSensors[i] = viewRectToFrame(viewRect, width, height);
    }
    List<RectF> overlaySensors = new ArrayList<>();
    for (RectF r : frameSensors) overlaySensors.add(new RectF(r));
    binding.overlay.setSensorBoxes(overlaySensors);
    binding.overlay.postInvalidate();

    // Black keeps the original brightness-threshold test; other colours match per pixel.
    boolean blackLine = lineColor == Color.BLACK;
    boolean[] onLine = new boolean[SENSOR_COUNT];
    for (int i = 0; i < SENSOR_COUNT; i++) {
      onLine[i] =
          blackLine
              ? meanBrightness(frameSensors[i], width, height) < blackThreshold
              : matchFraction(frameSensors[i], width, height) >= MATCH_FRACTION;
    }

    // Priority centre → left → right picks where the line is; the robot then drives like the AI
    // tab's Object Tracking page: the line's position goes through MultiBoxTracker's steering
    // law and the result is sent unchanged (sendTrackingControl), as handleDriveCommand does.
    String seeing = getString(R.string.follow_line_seeing);
    if (onLine[1]) {
      setStatus("▲ " + seeing);
      sendTrackingControl(trackingControl(0.5f));
    } else if (onLine[0]) {
      setStatus("◀ " + seeing);
      sendTrackingControl(trackingControl(0.5f / SENSOR_COUNT));
    } else if (onLine[2]) {
      setStatus("▶ " + seeing);
      sendTrackingControl(trackingControl(1f - 0.5f / SENSOR_COUNT));
    } else {
      // No sensor sees the line: stop, as Object Tracking does when nothing is tracked.
      setStatus(getString(R.string.follow_line_lost));
      sendTrackingControl(new Control(0, 0));
    }
    showBoxes(new ArrayList<>());
  }

  /**
   * MultiBoxTracker.updateTarget's steering law (AI tab, Object Tracking) for a target at
   * {@code screenX} (0 = left edge of the preview, 1 = right edge). With the back camera and the
   * phone upright (top up), the tracker's rotated-frame maths reduces to
   * x = 1 − 2·screenX: line on the left → x &gt; 0 → left wheel slows → curve left.
   */
  private Control trackingControl(float screenX) {
    float xPosScaled = 1.0f - 2.0f * Math.max(0f, Math.min(1f, screenX));
    float leftControl;
    float rightControl;
    if (xPosScaled < 0) {
      leftControl = 1.0f;
      rightControl = 1.0f + xPosScaled;
    } else {
      leftControl = 1.0f - xPosScaled;
      rightControl = 1.0f;
    }
    int sensorOrientation = getSensorOrientation();
    return new Control(
        (0 > sensorOrientation) ? rightControl : leftControl,
        (0 > sensorOrientation) ? leftControl : rightControl);
  }

  /** Maps a preview-space rectangle to an axis-aligned frame-space rectangle. */
  private RectF viewRectToFrame(RectF viewRect, int frameWidth, int frameHeight) {
    float minU = Float.MAX_VALUE;
    float minV = Float.MAX_VALUE;
    float maxU = -Float.MAX_VALUE;
    float maxV = -Float.MAX_VALUE;
    float[] corner = new float[2];
    float[] xs = {viewRect.left, viewRect.right};
    float[] ys = {viewRect.top, viewRect.bottom};
    for (float x : xs) {
      for (float y : ys) {
        if (binding.overlay.viewToFrame(x, y, corner)) {
          minU = Math.min(minU, corner[0]);
          maxU = Math.max(maxU, corner[0]);
          minV = Math.min(minV, corner[1]);
          maxV = Math.max(maxV, corner[1]);
        }
      }
    }
    return new RectF(
        clampF(minU, 0, frameWidth - 1),
        clampF(minV, 0, frameHeight - 1),
        clampF(maxU, 0, frameWidth - 1),
        clampF(maxV, 0, frameHeight - 1));
  }

  /** Mean greyscale brightness (0..255) of a frame rectangle, or 255 when it is empty. */
  private float meanBrightness(RectF rect, int frameWidth, int frameHeight) {
    int x0 = clamp((int) rect.left, 0, frameWidth - 1);
    int x1 = clamp((int) rect.right, 0, frameWidth - 1);
    int y0 = clamp((int) rect.top, 0, frameHeight - 1);
    int y1 = clamp((int) rect.bottom, 0, frameHeight - 1);
    long sum = 0;
    int count = 0;
    for (int y = y0; y <= y1; y += SCAN_STRIDE) {
      int row = y * frameWidth;
      for (int x = x0; x <= x1; x += SCAN_STRIDE) {
        int pixel = pixelBuffer[row + x];
        sum +=
            (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000;
        count++;
      }
    }
    return count == 0 ? 255f : sum / (float) count;
  }

  /** Fraction of pixels in a frame rectangle within COLOR_TOLERANCE of the line colour. */
  private float matchFraction(RectF rect, int frameWidth, int frameHeight) {
    int targetR = Color.red(lineColor);
    int targetG = Color.green(lineColor);
    int targetB = Color.blue(lineColor);
    int x0 = clamp((int) rect.left, 0, frameWidth - 1);
    int x1 = clamp((int) rect.right, 0, frameWidth - 1);
    int y0 = clamp((int) rect.top, 0, frameHeight - 1);
    int y1 = clamp((int) rect.bottom, 0, frameHeight - 1);
    int matches = 0;
    int count = 0;
    for (int y = y0; y <= y1; y += SCAN_STRIDE) {
      int row = y * frameWidth;
      for (int x = x0; x <= x1; x += SCAN_STRIDE) {
        int pixel = pixelBuffer[row + x];
        if (Math.abs(Color.red(pixel) - targetR) <= COLOR_TOLERANCE
            && Math.abs(Color.green(pixel) - targetG) <= COLOR_TOLERANCE
            && Math.abs(Color.blue(pixel) - targetB) <= COLOR_TOLERANCE) {
          matches++;
        }
        count++;
      }
    }
    return count == 0 ? 0f : matches / (float) count;
  }

  private static int clamp(int v, int min, int max) {
    return Math.max(min, Math.min(max, v));
  }

  private static float clampF(float v, float min, float max) {
    return Math.max(min, Math.min(max, v));
  }

  @Nullable
  @Override
  public String remoteScreenName() {
    return "Follow Line";
  }
}
