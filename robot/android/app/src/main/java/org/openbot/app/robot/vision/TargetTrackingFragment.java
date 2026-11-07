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
 * Target Tracking — port of the interaction of longpth/Android-Object-Tracking-OpenCV: drag a box
 * around the object on the camera preview, lock it, and the car follows the box's centre with the
 * same three-zone steering as the follow modes. That project used OpenCV's TrackerMedianFlow and —
 * importantly — kept driving when {@code tracker.update()} failed; here tracking is a pure-Java
 * template matcher ({@link TemplateTracker}) and a lost target stops the car until you re-lock.
 */
public class TargetTrackingFragment extends VisionModeFragment {

  private static final float MIN_DRAG_SIZE_DP = 24f;

  private final TemplateTracker tracker = new TemplateTracker();
  private final RectF dragRect = new RectF();
  private boolean dragging = false;
  // Written by the touch handler on the UI thread and by the camera thread after a lock.
  private volatile boolean hasDragRect = false;

  /** Frame-space rect waiting to be locked on the next camera frame. */
  private RectF pendingLock = null;

  private android.widget.Button lockButton;
  private int[] pixelBuffer = null;

  @Override
  protected String getGuideId() {
    // The L4 project ships no tutorial for this mode; the help button shows the usage hint.
    return null;
  }

  @Override
  protected void setupModeControls() {
    binding.overlay.setGuides(new float[] {1f / 3f, 2f / 3f});
    lockButton = addActionButton(getString(R.string.track_lock), v -> onLockButton());
    setStatus(getString(R.string.track_status_idle));
    binding.helpButton.setOnClickListener(
        v ->
            android.widget.Toast.makeText(requireContext(), R.string.track_hint,
                    android.widget.Toast.LENGTH_LONG)
                .show());

    binding.overlay.setOnTouchListener(
        (View v, MotionEvent event) -> {
          ensureFrameConfiguration();
          float[] frameXY = new float[2];
          switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              if (binding.overlay.viewToFrame(event.getX(), event.getY(), frameXY)) {
                dragRect.set(frameXY[0], frameXY[1], frameXY[0], frameXY[1]);
                dragging = true;
                hasDragRect = false;
              }
              return true;
            case MotionEvent.ACTION_MOVE:
              if (dragging && binding.overlay.viewToFrame(event.getX(), event.getY(), frameXY)) {
                dragRect.set(
                    Math.min(dragRect.left, frameXY[0]),
                    Math.min(dragRect.top, frameXY[1]),
                    Math.max(dragRect.left, frameXY[0]),
                    Math.max(dragRect.top, frameXY[1]));
                hasDragRect = true;
              }
              return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
              dragging = false;
              if (hasDragRect && !tracker.isTracking()) {
                showDragRect();
                setStatus(getString(R.string.track_hint));
              }
              return true;
            default:
              return true;
          }
        });
  }

  private void onLockButton() {
    if (tracker.isTracking() || pendingLock != null) {
      tracker.reset();
      pendingLock = null;
      lockButton.setText(getString(R.string.track_lock));
      showBoxes(new ArrayList<>());
      setStatus(getString(R.string.track_status_idle));
      sendControl(new Control(0, 0));
      return;
    }
    if (!hasDragRect) {
      setStatus(getString(R.string.track_status_idle));
      return;
    }
    pendingLock = new RectF(dragRect);
    setStatus(getString(R.string.track_status_tracking));
  }

  @Override
  protected void processVisionFrame(Bitmap frame, ImageProxy proxy) {
    ensureFrameConfiguration();

    int width = frame.getWidth();
    int height = frame.getHeight();
    if (pixelBuffer == null || pixelBuffer.length != width * height) {
      pixelBuffer = new int[width * height];
    }
    frame.getPixels(pixelBuffer, 0, width, 0, 0, width, height);

    if (pendingLock != null) {
      RectF roi = pendingLock;
      pendingLock = null;
      if (tracker.init(pixelBuffer, width, height, roi)) {
        lockButton.post(() -> lockButton.setText(getString(R.string.track_unlock)));
        hasDragRect = false;
      } else {
        setStatus(getString(R.string.track_status_idle));
        sendControl(new Control(0, 0));
      }
    }

    if (!tracker.isTracking()) return;

    if (!tracker.update(pixelBuffer, width, height)) {
      // The reference project kept driving on tracker loss; stopping is the safe behaviour.
      setStatus(getString(R.string.track_status_lost));
      showBoxes(new ArrayList<>());
      sendControl(new Control(0, 0));
      lockButton.post(() -> lockButton.setText(getString(R.string.track_lock)));
      return;
    }

    RectF target = tracker.getTarget();
    List<VisionOverlayView.Box> boxes = new ArrayList<>();
    boxes.add(new VisionOverlayView.Box(target, getString(R.string.tile_target_tracking), Color.GREEN, 5f));
    showBoxes(boxes);

    float[] viewXY = new float[2];
    RectF fitted = getFittedFrameRect();
    if (fitted == null || !frameToView(target.centerX(), target.centerY(), viewXY)) {
      sendControl(new Control(0, 0));
      return;
    }

    float third = fitted.width() / 3f;
    if (viewXY[0] < fitted.left + third) {
      sendTurn(true, getString(R.string.track_status_tracking));
    } else if (viewXY[0] > fitted.right - third) {
      sendTurn(false, getString(R.string.track_status_tracking));
    } else {
      setStatus("▲ " + getString(R.string.track_status_tracking));
      sendControl(new Control(1f, 1f));
    }
  }

  private void showDragRect() {
    List<VisionOverlayView.Box> boxes = new ArrayList<>();
    boxes.add(
        new VisionOverlayView.Box(
            new RectF(dragRect), getString(R.string.track_lock), Color.YELLOW, 4f));
    showBoxes(boxes);
  }

  @Nullable
  @Override
  public String remoteScreenName() {
    return "Target Tracking";
  }
}
