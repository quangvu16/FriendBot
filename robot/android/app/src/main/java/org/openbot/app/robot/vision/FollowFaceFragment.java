package org.openbot.app.robot.vision;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.camera.core.ImageProxy;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import java.util.ArrayList;
import java.util.List;
import org.openbot.app.robot.R;
import org.openbot.app.robot.vehicle.Control;

/**
 * Follow a Face — port of stage 2 of Kirstin813/L4-Individual-Project, driven like the AI tab's
 * Object Tracking page: the face box goes through the same steering law as
 * MultiBoxTracker.updateTarget and the result is sent to the vehicle unchanged.
 *
 * <p>Detection uses ML Kit Face Detection, the native Android equivalent of the original's
 * MediaPipe model. Steering replaced the original's pivot-in-place thirds, which drove the wrong
 * way on this robot: the car now drives toward the face, steering by its position, slowing as it
 * gets bigger (Dynamic speed) and stopping when it fills most of the view.
 */
public class FollowFaceFragment extends VisionModeFragment {

  private FaceDetector detector;
  private volatile boolean detectorBusy = false;
  // Raw camera frame geometry of the frame being detected (for the Object Tracking maths).
  private volatile int lastFrameWidth = 1;
  private volatile int lastFrameHeight = 1;
  private volatile int lastRotation = 0;

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // ML Kit has no detection-confidence knob like the original's MediaPipe minDetectionConfidence
    // 0.5; its short-range-ish behaviour is matched with a 0.15 minimum face size instead.
    FaceDetectorOptions options =
        new FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build();
    detector = FaceDetection.getClient(options);
  }

  @Override
  protected String getGuideId() {
    return GuideFragment.GUIDE_FACE;
  }

  @Override
  protected void setupModeControls() {
    binding.overlay.setGuides(new float[] {1f / 3f, 2f / 3f});
    setStatus(getString(R.string.follow_face_status_scanning));
  }

  @Override
  protected void processVisionFrame(Bitmap frame, ImageProxy proxy) {
    ensureFrameConfiguration();
    if (detectorBusy) return;
    detectorBusy = true;

    // ML Kit keeps a reference to the bitmap until the async call finishes, and the camera
    // reuses its buffer, so hand over a copy.
    Bitmap copy = frame.copy(Bitmap.Config.ARGB_8888, false);
    int rotationDegrees = getRotationDegrees();
    lastFrameWidth = frame.getWidth();
    lastFrameHeight = frame.getHeight();
    lastRotation = rotationDegrees;
    InputImage image = InputImage.fromBitmap(copy, rotationDegrees);

    detector
        .process(image)
        .addOnSuccessListener(this::onFaces)
        .addOnFailureListener(
            e -> {
              detectorBusy = false;
              setStatus(getString(R.string.follow_face_status_scanning));
            })
        .addOnCompleteListener(t -> detectorBusy = false);
  }

  private void onFaces(List<Face> faces) {
    if (!autoMode || binding == null) return;
    if (faces.isEmpty()) {
      setStatus(getString(R.string.follow_face_status_scanning));
      showBoxes(new ArrayList<>());
      sendTrackingControl(new Control(0, 0));
      return;
    }

    Face face = faces.get(0);
    List<VisionOverlayView.Box> boxes = new ArrayList<>();
    boxes.add(
        new VisionOverlayView.Box(
            new RectF(face.getBoundingBox()), getString(R.string.tile_follow_face), Color.CYAN, 5f));
    showBoxes(boxes);

    sendTrackingControl(trackingControl(face.getBoundingBox(), lastFrameWidth, lastFrameHeight, lastRotation));
  }

  /**
   * The Object Tracking page's steering law (MultiBoxTracker.updateTarget in the AI tab), applied
   * to the face box. ML Kit returns the box in the upright image, so it is first mapped back to
   * the raw camera frame that MultiBoxTracker works in; then the maths is the same line for line:
   * steer by the horizontal position of the box centre, slow down as the box grows (dynamic speed)
   * and stop when the face fills most of the view.
   */
  private Control trackingControl(android.graphics.Rect upright, int frameWidth, int frameHeight, int rotation) {
    RectF trackedPos = uprightToFrame(new RectF(upright), frameWidth, frameHeight, rotation);
    int sensorOrientation = getSensorOrientation();
    boolean rotated = sensorOrientation % 180 == 90;
    float imgWidth = (float) (rotated ? frameHeight : frameWidth);
    float boxArea = trackedPos.height() * trackedPos.width();
    float centerX = rotated ? trackedPos.centerY() : trackedPos.centerX();
    centerX = Math.max(0.0f, Math.min(centerX, imgWidth));
    float xPosNorm = 1.0f - 2.0f * centerX / imgWidth;
    float xPosScaled = rotated ? -xPosNorm : xPosNorm;

    float leftControl;
    float rightControl;
    if (xPosScaled < 0) {
      leftControl = 1.0f;
      rightControl = 1.0f + xPosScaled;
    } else {
      leftControl = 1.0f - xPosScaled;
      rightControl = 1.0f;
    }

    if (preferencesManager.getDynamicSpeed()) {
      float scaleFactor = 1.0f - boxArea / (frameWidth * frameHeight);
      scaleFactor = scaleFactor > 0.75f ? 1.0f : scaleFactor;
      if (scaleFactor > 0.25f) {
        leftControl *= scaleFactor;
        rightControl *= scaleFactor;
      } else {
        leftControl = 0.0f;
        rightControl = 0.0f;
      }
    }

    // Status arrow from the steering, like the other vision modes.
    String found = getString(R.string.follow_face_status_found);
    if (leftControl == 0f && rightControl == 0f) setStatus("■ " + found);
    else if (leftControl < rightControl) setStatus("◀ " + found);
    else if (rightControl < leftControl) setStatus("▶ " + found);
    else setStatus("▲ " + found);

    return new Control(
        (0 > sensorOrientation) ? rightControl : leftControl,
        (0 > sensorOrientation) ? leftControl : rightControl);
  }

  /** Maps a box from ML Kit's upright image back to the raw (unrotated) camera frame. */
  private static RectF uprightToFrame(RectF r, int w, int h, int rotation) {
    switch (((rotation % 360) + 360) % 360) {
      case 90: // upright x = h - rawY, upright y = rawX
        return new RectF(r.top, h - r.right, r.bottom, h - r.left);
      case 180:
        return new RectF(w - r.right, h - r.bottom, w - r.left, h - r.top);
      case 270: // upright x = rawY, upright y = w - rawX
        return new RectF(w - r.bottom, r.left, w - r.top, r.right);
      default:
        return new RectF(r);
    }
  }

  @Override
  public void onDestroyView() {
    sendControl(new Control(0, 0));
    super.onDestroyView();
  }

  @Override
  public void onDestroy() {
    if (detector != null) detector.close();
    super.onDestroy();
  }

  @Nullable
  @Override
  public String remoteScreenName() {
    return "Follow Face";
  }
}
