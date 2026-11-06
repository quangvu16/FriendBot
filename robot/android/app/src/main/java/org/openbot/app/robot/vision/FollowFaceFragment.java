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
import com.google.mlkit.vision.face.FaceLandmark;
import java.util.ArrayList;
import java.util.List;
import org.openbot.app.robot.R;
import org.openbot.app.robot.vehicle.Control;

/**
 * Follow a Face — port of stage 2 of Kirstin813/L4-Individual-Project.
 *
 * <p>The original uses MediaPipe Face Detection (short-range model, confidence 0.5) and steers by
 * the nose landmark: left third turns left, right third turns right, and the middle third means
 * STOP — the robot turns to face you but never drives at you. ML Kit Face Detection is the native
 * Android equivalent of the MediaPipe model, so the control law is ported unchanged.
 */
public class FollowFaceFragment extends VisionModeFragment {

  private FaceDetector detector;
  private volatile boolean detectorBusy = false;

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
      sendControl(new Control(0, 0));
      return;
    }

    Face face = faces.get(0);
    // The original steers by the nose landmark; fall back to the box centre without one.
    float centerX = face.getBoundingBox().centerX();
    float centerY = face.getBoundingBox().centerY();
    FaceLandmark nose = face.getLandmark(FaceLandmark.NOSE_BASE);
    if (nose != null) {
      centerX = nose.getPosition().x;
      centerY = nose.getPosition().y;
    }

    List<VisionOverlayView.Box> boxes = new ArrayList<>();
    boxes.add(
        new VisionOverlayView.Box(
            new RectF(face.getBoundingBox()), getString(R.string.tile_follow_face), Color.CYAN, 5f));
    showBoxes(boxes);

    float[] viewXY = new float[2];
    RectF fitted = getFittedFrameRect();
    if (fitted == null || !frameToView(centerX, centerY, viewXY)) {
      sendControl(new Control(0, 0));
      return;
    }

    float third = fitted.width() / 3f;
    if (viewXY[0] < fitted.left + third) {
      setStatus("◀ " + getString(R.string.follow_face_status_found));
      sendControl(new Control(-1f, 1f));
    } else if (viewXY[0] > fitted.right - third) {
      setStatus("▶ " + getString(R.string.follow_face_status_found));
      sendControl(new Control(1f, -1f));
    } else {
      // Middle zone: stop. The robot turns to face you but never drives at you.
      setStatus("■ " + getString(R.string.follow_face_status_found));
      sendControl(new Control(0, 0));
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
