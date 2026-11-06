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
 * right: centre drives forward, a side sensor turns that way. No sensor black stops the car (the
 * original keeps the last action; a real car is safer stopping). Point the camera down at a black
 * tape circuit.
 */
public class FollowLineFragment extends VisionModeFragment {

  private static final int DEFAULT_THRESHOLD = 90;
  private static final int SENSOR_COUNT = 3;
  /** Sensor strip height as a fraction of the fitted preview (L4 used 50 px of ~480). */
  private static final float SENSOR_HEIGHT_FRACTION = 0.12f;
  private static final int SCAN_STRIDE = 2;

  private int blackThreshold = DEFAULT_THRESHOLD;
  private int[] pixelBuffer = null;

  @Override
  protected String getGuideId() {
    return GuideFragment.GUIDE_LINE;
  }

  @Override
  protected void setupModeControls() {
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
      sendControl(new Control(0, 0));
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

    boolean[] black = new boolean[SENSOR_COUNT];
    for (int i = 0; i < SENSOR_COUNT; i++) {
      black[i] = meanBrightness(frameSensors[i], width, height) < blackThreshold;
    }

    // Priority: centre → left → right, like the original sketch.
    if (black[1]) {
      setStatus("▲ " + getString(R.string.follow_line_seeing));
      sendControl(new Control(1f, 1f));
    } else if (black[0]) {
      setStatus("◀ " + getString(R.string.follow_line_seeing));
      sendControl(new Control(-1f, 1f));
    } else if (black[2]) {
      setStatus("▶ " + getString(R.string.follow_line_seeing));
      sendControl(new Control(1f, -1f));
    } else {
      // No sensor sees the line: stop rather than blindly keep the last turn.
      setStatus(getString(R.string.follow_line_lost));
      sendControl(new Control(0, 0));
    }
    showBoxes(new ArrayList<>());
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
