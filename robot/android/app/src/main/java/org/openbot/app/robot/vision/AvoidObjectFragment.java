package org.openbot.app.robot.vision;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.camera.core.ImageProxy;
import java.util.ArrayList;
import java.util.List;
import org.openbot.app.robot.R;
import org.openbot.app.robot.vehicle.Control;

/**
 * Object Avoidance — the fourth stage of Kirstin813/L4-Individual-Project never finished its drive
 * policy (it only drew a MediaPipe Objectron box), so the behaviour here is designed fresh and
 * documented in the in-app guide: tap the obstacle in the camera view to mark its colour. While the
 * obstacle is far away (a small part of the view) the car drives forward; when it fills too much of
 * the view the car stops and pivots toward the clearer side. The vehicle's LiDAR-based object
 * avoidance remains active as a second safety layer.
 */
public class AvoidObjectFragment extends VisionModeFragment {

  private static final int TOLERANCE = 20;
  private static final int MIN_MATCHES = 8;
  private static final int SCAN_STRIDE = 3;
  private static final int DEFAULT_STOP_PERCENT = 10;

  private Integer obstacleColor = null;
  private int stopPercent = DEFAULT_STOP_PERCENT;
  private int[] pixelBuffer = null;

  /** Frame-space position of a pending tap, sampled at the next frame. */
  private float[] pendingSample = null;

  @Override
  protected String getGuideId() {
    return GuideFragment.GUIDE_AVOIDANCE;
  }

  @Override
  protected void setupModeControls() {
    addStepper(
        getString(R.string.avoid_threshold),
        2,
        40,
        2,
        DEFAULT_STOP_PERCENT,
        v -> stopPercent = v);

    binding.overlay.setOnTouchListener(
        (View v, MotionEvent event) -> {
          if (event.getAction() != MotionEvent.ACTION_DOWN) return true;
          float[] frameXY = new float[2];
          if (binding.overlay.viewToFrame(event.getX(), event.getY(), frameXY)) {
            pendingSample = frameXY;
          }
          return true;
        });
    setStatus(getString(R.string.avoid_pick_hint));
  }

  @Override
  protected void processVisionFrame(Bitmap frame, ImageProxy proxy) {
    ensureFrameConfiguration();

    if (pendingSample != null) {
      int px = clamp((int) pendingSample[0], 0, frame.getWidth() - 1);
      int py = clamp((int) pendingSample[1], 0, frame.getHeight() - 1);
      obstacleColor = frame.getPixel(px, py);
      pendingSample = null;
      binding.overlay.setSwatchColor(obstacleColor);
      setStatus(getString(R.string.avoid_pick_hint));
    }

    if (obstacleColor == null) {
      setStatus(getString(R.string.avoid_pick_hint));
      showBoxes(new ArrayList<>());
      sendControl(new Control(0, 0));
      return;
    }

    int targetR = Color.red(obstacleColor);
    int targetG = Color.green(obstacleColor);
    int targetB = Color.blue(obstacleColor);

    int width = frame.getWidth();
    int height = frame.getHeight();
    if (pixelBuffer == null || pixelBuffer.length != width * height) {
      pixelBuffer = new int[width * height];
    }
    frame.getPixels(pixelBuffer, 0, width, 0, 0, width, height);

    int count = 0;
    int leftCount = 0;
    int minX = Integer.MAX_VALUE;
    int minY = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int maxY = Integer.MIN_VALUE;
    int half = width / 2;

    for (int y = 0; y < height; y += SCAN_STRIDE) {
      int row = y * width;
      for (int x = 0; x < width; x += SCAN_STRIDE) {
        int pixel = pixelBuffer[row + x];
        if (Math.abs(Color.red(pixel) - targetR) <= TOLERANCE
            && Math.abs(Color.green(pixel) - targetG) <= TOLERANCE
            && Math.abs(Color.blue(pixel) - targetB) <= TOLERANCE) {
          count++;
          if (x < half) leftCount++;
          if (x < minX) minX = x;
          if (x > maxX) maxX = x;
          if (y < minY) minY = y;
          if (y > maxY) maxY = y;
        }
      }
    }

    int scanned = Math.max(1, (height / SCAN_STRIDE) * (width / SCAN_STRIDE));
    int percent = 100 * count / scanned;

    if (count < MIN_MATCHES || percent < stopPercent) {
      // Obstacle far away (or out of view): drive on.
      setStatus(getString(R.string.avoid_status_clear));
      showBoxes(new ArrayList<>());
      sendControl(new Control(1f, 1f));
      return;
    }

    List<VisionOverlayView.Box> boxes = new ArrayList<>();
    boxes.add(
        new VisionOverlayView.Box(
            new RectF(minX, minY, maxX, maxY),
            getString(R.string.tile_object_avoidance),
            Color.RED,
            5f));
    showBoxes(boxes);

    // Too close: pivot toward the clearer side of the view.
    int rightCount = count - leftCount;
    if (leftCount <= rightCount) {
      setStatus("◀ " + getString(R.string.avoid_status_blocked));
      sendControl(new Control(-1f, 1f));
    } else {
      setStatus("▶ " + getString(R.string.avoid_status_blocked));
      sendControl(new Control(1f, -1f));
    }
  }

  private static int clamp(int v, int min, int max) {
    return Math.max(min, Math.min(max, v));
  }

  @Nullable
  @Override
  public String remoteScreenName() {
    return "Avoid Object";
  }
}
