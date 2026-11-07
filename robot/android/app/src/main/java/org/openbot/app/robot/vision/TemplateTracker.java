package org.openbot.app.robot.vision;

import android.graphics.Color;
import android.graphics.RectF;

/**
 * Small dependency-free template tracker for the Target Tracking screen: normalized
 * cross-correlation over a fixed-size greyscale patch in a local search window.
 *
 * <p>This stands in for OpenCV's TrackerMedianFlow used by the reference project
 * (longpth/Android-Object-Tracking-OpenCV) without bundling the OpenCV native libraries. Unlike
 * that project — which ignored {@code update()} failures and kept driving — {@link #update} reports
 * loss so the caller can stop the car and ask for a re-lock.
 */
public class TemplateTracker {

  private static final int PATCH = 24;
  private static final int SEARCH_STEP = 4;
  private static final float MIN_SCORE = 0.55f;

  /** Zero-mean, unit-length patch sampled from the target region. */
  private float[] templateNorm = null;
  private final RectF target = new RectF();

  public boolean isTracking() {
    return templateNorm != null;
  }

  public RectF getTarget() {
    return new RectF(target);
  }

  public void reset() {
    templateNorm = null;
    target.setEmpty();
  }

  /** Locks onto the pixels inside {@code roi} (frame coordinates). */
  public boolean init(int[] pixels, int width, int height, RectF roi) {
    RectF clamped = clampRect(roi, width, height);
    if (clamped.width() < 4 || clamped.height() < 4) return false;
    float[] norm = normalize(samplePatch(pixels, width, height, clamped));
    if (norm == null) return false;
    templateNorm = norm;
    target.set(clamped);
    return true;
  }

  /** Follows the target one frame forward. Returns false when it is lost. */
  public boolean update(int[] pixels, int width, int height) {
    if (templateNorm == null) return false;

    // Search in a window of twice the target size around the last position.
    float marginX = target.width() / 2f;
    float marginY = target.height() / 2f;
    RectF window =
        clampRect(
            new RectF(
                target.left - marginX, target.top - marginY, target.right + marginX,
                target.bottom + marginY),
            width,
            height);

    float bestScore = -1f;
    float bestX = target.left;
    float bestY = target.top;
    float[] patch = new float[PATCH * PATCH];

    for (float y = window.top; y + target.height() <= window.bottom; y += SEARCH_STEP) {
      for (float x = window.left; x + target.width() <= window.right; x += SEARCH_STEP) {
        RectF candidate = new RectF(x, y, x + target.width(), y + target.height());
        float[] norm = normalize(samplePatchInto(pixels, width, height, candidate, patch));
        if (norm == null) continue;
        float score = 0f;
        for (int i = 0; i < norm.length; i++) score += norm[i] * templateNorm[i];
        if (score > bestScore) {
          bestScore = score;
          bestX = x;
          bestY = y;
        }
      }
    }

    if (bestScore < MIN_SCORE) {
      reset();
      return false;
    }
    target.offsetTo(bestX, bestY);
    return true;
  }

  /** Samples a rectangle of the frame into a {@code PATCH}×{@code PATCH} greyscale grid. */
  private float[] samplePatch(int[] pixels, int width, int height, RectF rect) {
    return samplePatchInto(pixels, width, height, rect, new float[PATCH * PATCH]);
  }

  private float[] samplePatchInto(int[] pixels, int width, int height, RectF rect, float[] out) {
    float stepX = rect.width() / PATCH;
    float stepY = rect.height() / PATCH;
    for (int j = 0; j < PATCH; j++) {
      int y = clamp((int) (rect.top + (j + 0.5f) * stepY), 0, height - 1);
      int row = y * width;
      for (int i = 0; i < PATCH; i++) {
        int x = clamp((int) (rect.left + (i + 0.5f) * stepX), 0, width - 1);
        int pixel = pixels[row + x];
        out[j * PATCH + i] =
            (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000f;
      }
    }
    return out;
  }

  /** Zero-mean, unit-length version of the patch — its dot product is the NCC score. */
  private static float[] normalize(float[] patch) {
    float mean = 0f;
    for (float v : patch) mean += v;
    mean /= patch.length;
    float norm = 0f;
    for (int i = 0; i < patch.length; i++) {
      patch[i] -= mean;
      norm += patch[i] * patch[i];
    }
    norm = (float) Math.sqrt(norm);
    if (norm < 1e-3f) return null; // flat patch carries no structure to match
    for (int i = 0; i < patch.length; i++) patch[i] /= norm;
    return patch;
  }

  private static RectF clampRect(RectF rect, int width, int height) {
    return new RectF(
        clamp(rect.left, 0, width - 1),
        clamp(rect.top, 0, height - 1),
        clamp(rect.right, 0, width - 1),
        clamp(rect.bottom, 0, height - 1));
  }

  private static float clamp(float v, float min, float max) {
    return Math.max(min, Math.min(max, v));
  }

  private static int clamp(int v, int min, int max) {
    return Math.max(min, Math.min(max, v));
  }
}
