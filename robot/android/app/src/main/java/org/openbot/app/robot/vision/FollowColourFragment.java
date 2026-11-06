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
 * Follow a Coloured Object — port of stage 1 of Kirstin813/L4-Individual-Project.
 *
 * <p>The original scans the camera image for the first pixel within an RGB tolerance of the target
 * colour and drives with three zones on its x position. Here the match is a centroid over all
 * matching pixels (less noisy than the first match) but the control law is kept as the original:
 * left third turns left, middle third drives forward, right third turns right. Tap the preview to
 * sample the colour to follow.
 */
public class FollowColourFragment extends VisionModeFragment {

  private static final int DEFAULT_TOLERANCE = 15;
  private static final int MIN_MATCHES = 8;
  private static final int SCAN_STRIDE = 3;
  private static final int STOP = 0;
  private static final int LEFT = 1;
  private static final int FORWARD = 2;
  private static final int RIGHT = 3;

  private int tolerance = DEFAULT_TOLERANCE;
  private Integer targetColor = null;
  private int[] pixelBuffer = null;

  /** Frame-space position of a pending tap, sampled at the next frame. */
  private float[] pendingSample = null;

  @Override
  protected String getGuideId() {
    return GuideFragment.GUIDE_COLOUR;
  }

  @Override
  protected void setupModeControls() {
    binding.overlay.setGuides(new float[] {1f / 3f, 2f / 3f});
    addStepper(
        getString(R.string.follow_colour_tolerance),
        5,
        60,
        5,
        DEFAULT_TOLERANCE,
        v -> tolerance = v);

    binding.overlay.setOnTouchListener(
        (View v, MotionEvent event) -> {
          if (event.getAction() != MotionEvent.ACTION_DOWN) return true;
          float[] frameXY = new float[2];
          if (binding.overlay.viewToFrame(event.getX(), event.getY(), frameXY)) {
            pendingSample = frameXY;
          }
          return true;
        });
  }

  @Override
  protected void processVisionFrame(Bitmap frame, ImageProxy proxy) {
    ensureFrameConfiguration();

    if (pendingSample != null) {
      int px = clamp((int) pendingSample[0], 0, frame.getWidth() - 1);
      int py = clamp((int) pendingSample[1], 0, frame.getHeight() - 1);
      targetColor = frame.getPixel(px, py);
      pendingSample = null;
      binding.overlay.setSwatchColor(targetColor);
      setStatus(getString(R.string.follow_colour_pick_hint));
    }

    if (targetColor == null) {
      setStatus(getString(R.string.follow_colour_none));
      showBoxes(new ArrayList<>());
      sendControl(new Control(0, 0));
      return;
    }

    int targetR = Color.red(targetColor);
    int targetG = Color.green(targetColor);
    int targetB = Color.blue(targetColor);

    int width = frame.getWidth();
    int height = frame.getHeight();
    if (pixelBuffer == null || pixelBuffer.length != width * height) {
      pixelBuffer = new int[width * height];
    }
    frame.getPixels(pixelBuffer, 0, width, 0, 0, width, height);

    int count = 0;
    long sumX = 0;
    long sumY = 0;
    int minX = Integer.MAX_VALUE;
    int minY = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int maxY = Integer.MIN_VALUE;

    for (int y = 0; y < height; y += SCAN_STRIDE) {
      int row = y * width;
      for (int x = 0; x < width; x += SCAN_STRIDE) {
        int pixel = pixelBuffer[row + x];
        if (Math.abs(Color.red(pixel) - targetR) <= tolerance
            && Math.abs(Color.green(pixel) - targetG) <= tolerance
            && Math.abs(Color.blue(pixel) - targetB) <= tolerance) {
          count++;
          sumX += x;
          sumY += y;
          if (x < minX) minX = x;
          if (x > maxX) maxX = x;
          if (y < minY) minY = y;
          if (y > maxY) maxY = y;
        }
      }
    }

    if (count < MIN_MATCHES) {
      // The original keeps driving the last action when the object disappears; a real car is
      // safer stopping, so the guide tells the user the car stops when the object is lost.
      setStatus(getString(R.string.follow_colour_none));
      showBoxes(new ArrayList<>());
      sendControl(new Control(0, 0));
      return;
    }

    float centroidX = sumX / (float) count;
    float centroidY = sumY / (float) count;

    List<VisionOverlayView.Box> boxes = new ArrayList<>();
    boxes.add(
        new VisionOverlayView.Box(
            new RectF(minX, minY, maxX, maxY),
            getString(R.string.tile_follow_colour),
            Color.GREEN,
            5f));
    showBoxes(boxes);

    int action = zoneAction(centroidX, centroidY);
    switch (action) {
      case LEFT:
        setStatus("◀ " + getString(R.string.vision_running));
        sendControl(new Control(-1f, 1f));
        break;
      case RIGHT:
        setStatus("▶ " + getString(R.string.vision_running));
        sendControl(new Control(1f, -1f));
        break;
      case FORWARD:
        setStatus("▲ " + getString(R.string.vision_running));
        sendControl(new Control(1f, 1f));
        break;
      default:
        setStatus(getString(R.string.vision_stopped));
        sendControl(new Control(0, 0));
        break;
    }
  }

  /** Three-zone control on the target's displayed x position, like the original sketch. */
  private int zoneAction(float frameX, float frameY) {
    float[] viewXY = new float[2];
    RectF fitted = getFittedFrameRect();
    if (fitted == null || !frameToView(frameX, frameY, viewXY)) return STOP;
    float third = fitted.width() / 3f;
    if (viewXY[0] < fitted.left + third) return LEFT;
    if (viewXY[0] > fitted.right - third) return RIGHT;
    return FORWARD;
  }

  private static int clamp(int v, int min, int max) {
    return Math.max(min, Math.min(max, v));
  }

  @Nullable
  @Override
  public String remoteScreenName() {
    return "Follow Colour";
  }
}
