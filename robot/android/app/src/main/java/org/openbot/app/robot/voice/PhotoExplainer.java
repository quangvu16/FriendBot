package org.openbot.app.robot.voice;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import timber.log.Timber;

/**
 * The phone camera of the Voice Control screen: the live corner preview (its "eye"), the
 * front/back lens choice, and the vision tool (modeled on the Keyestudio KS5028 S3CAM firmware's
 * {@code self.camera.take_photo}) — capture one JPEG and upload it to the Xiaozhi vision explain
 * service. The service's response body is the tool result and is spoken by the server like any
 * other reply — the phone never speaks it.
 *
 * <p>The upload mirrors the firmware's {@code Esp32Camera::Explain} exactly: {@code POST} of
 * {@code multipart/form-data} with the {@code question} and {@code file} fields (boundary
 * {@code ----ESP32_CAMERA_BOUNDARY}, {@code Device-Id}/{@code Client-Id}/{@code Authorization}
 * headers) to the explain URL + token the server pushes in the MCP {@code initialize}.
 *
 * <p>The camera binds to the screen's lifecycle (preview + capture); after every capture the
 * shutter still goes back to the UI so the user sees the picture being taken.
 */
public final class PhotoExplainer {

  /** Main-thread callbacks of one capture. */
  public interface Callback {
    /** The shutter fired; {@code jpeg} is the raw capture (for the corner still flash). */
    void onCaptured(@Nullable byte[] jpeg);

    /** {@code photoJson} non-null on success; {@code error} then holds a short reason. */
    void onExplained(@Nullable String photoJson, @NonNull String error);
  }

  /** Longest edge kept when shrinking the capture before upload. */
  private static final int MAX_EDGE = 1024;

  private static final int JPEG_QUALITY = 80;

  /** Longest edge of the shutter still shown in the corner (a thumbnail, not a wallpaper). */
  private static final int STILL_EDGE = 512;

  /** Same fixed multipart boundary as the firmware's camera upload. */
  private static final String UPLOAD_BOUNDARY = "----ESP32_CAMERA_BOUNDARY";

  private static final MediaType JPEG = MediaType.get("image/jpeg");

  private final Handler main = new Handler(Looper.getMainLooper());

  private final OkHttpClient http =
      new OkHttpClient.Builder()
          .connectTimeout(15, TimeUnit.SECONDS)
          .readTimeout(30, TimeUnit.SECONDS)
          .writeTimeout(30, TimeUnit.SECONDS)
          .build();

  private ImageCapture imageCapture;
  private boolean running;
  private boolean front;

  /** Explain endpoint of the current capture (one photo job runs at a time). */
  private String explainUrl = "";

  private String explainToken = "";

  public boolean isFront() {
    return front;
  }

  /** Opens the live corner preview and the capture path on the chosen lens. */
  public void start(
      Context context, LifecycleOwner lifecycle, PreviewView previewView, boolean useFront) {
    Context app = context.getApplicationContext();
    front = useFront;
    ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(app);
    future.addListener(
        () -> {
          try {
            ProcessCameraProvider provider = future.get();
            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());
            ImageCapture capture =
                new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build();
            provider.unbindAll();
            provider.bindToLifecycle(lifecycle, selector(), preview, capture);
            imageCapture = capture;
            running = true;
          } catch (Exception e) {
            imageCapture = null;
            running = false;
          }
        },
        ContextCompat.getMainExecutor(app));
  }

  /** Flips between the phone's back and front cameras (the preview follows). */
  public void switchCamera(Context context, LifecycleOwner lifecycle, PreviewView previewView) {
    start(context, lifecycle, previewView, !front);
  }

  /** The camera stopped with the screen; the next capture binds again. */
  public void markStopped() {
    running = false;
    imageCapture = null;
  }

  private CameraSelector selector() {
    return front ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
  }

  /**
   * Capture → explain → callbacks. Uses the live preview's camera when it is running and falls
   * back to a one-shot bind otherwise, so the tool works even before the corner preview shows.
   *
   * @param explainUrl vision explain endpoint pushed by the server (MCP initialize)
   * @param explainToken bearer token for it (may be empty)
   */
  public void captureAndExplain(
      Context context,
      LifecycleOwner lifecycle,
      @Nullable String question,
      @NonNull String explainUrl,
      @NonNull String explainToken,
      @NonNull Callback callback) {
    Context app = context.getApplicationContext();
    this.explainUrl = explainUrl;
    this.explainToken = explainToken;
    if (running && imageCapture != null) {
      takePicture(app, imageCapture, question, callback);
      return;
    }
    ImageCapture capture =
        new ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build();
    ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(app);
    future.addListener(
        () -> {
          try {
            ProcessCameraProvider provider = future.get();
            provider.unbindAll();
            provider.bindToLifecycle(lifecycle, selector(), capture);
            imageCapture = capture;
            running = true;
          } catch (Exception e) {
            callback.onCaptured(null);
            callback.onExplained(null, "Camera unavailable");
            return;
          }
          takePicture(app, capture, question, callback);
        },
        ContextCompat.getMainExecutor(app));
  }

  private void takePicture(
      Context app, ImageCapture capture, String question, Callback callback) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageCapture.OutputFileOptions options =
        new ImageCapture.OutputFileOptions.Builder(out).build();
    capture.takePicture(
        options,
        ContextCompat.getMainExecutor(app),
        new ImageCapture.OnImageSavedCallback() {
          @Override
          public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
            byte[] raw = out.toByteArray();
            callback.onCaptured(raw);
            byte[] jpeg = shrink(raw);
            if (jpeg == null) {
              callback.onExplained(null, "Failed to capture photo");
              return;
            }
            explain(app, jpeg, question, callback);
          }

          @Override
          public void onError(@NonNull ImageCaptureException e) {
            callback.onCaptured(null);
            callback.onExplained(null, "Failed to capture photo");
          }
        });
  }

  /**
   * Uploads the photo to the vision explain endpoint — the exact wire format of the S3CAM
   * firmware's {@code Esp32Camera::Explain}: {@code multipart/form-data} with the {@code
   * question} and {@code file} fields, bearer token plus {@code Device-Id}/{@code Client-Id}.
   * The service's response body is the answer.
   */
  private void explain(Context app, byte[] jpeg, String question, Callback callback) {
    if (explainUrl.isEmpty()) {
      callback.onExplained(null, "Image explain URL or token is not set");
      return;
    }
    MultipartBody body =
        new MultipartBody.Builder(UPLOAD_BOUNDARY)
            .setType(MultipartBody.FORM)
            .addFormDataPart("question", question == null ? "" : question)
            .addFormDataPart("file", "camera.jpg", RequestBody.create(jpeg, JPEG))
            .build();
    Request.Builder request = new Request.Builder().url(explainUrl).post(body);
    if (!explainToken.isEmpty()) {
      request.header("Authorization", "Bearer " + explainToken);
    }
    request.header("Device-Id", XiaozhiActivator.deviceId(app));
    request.header("Client-Id", XiaozhiActivator.clientId(app));
    http.newCall(request.build())
        .enqueue(
            new okhttp3.Callback() {
              @Override
              public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Timber.e(e, "Photo upload failed");
                main.post(() -> callback.onExplained(null, "Failed to upload photo"));
              }

              @Override
              public void onResponse(@NonNull Call call, @NonNull Response response)
                  throws IOException {
                try (ResponseBody responseBody = response.body()) {
                  String payload = responseBody == null ? "" : responseBody.string();
                  if (!response.isSuccessful() || payload.isEmpty()) {
                    Timber.w("Photo explain failed (HTTP %d)", response.code());
                    main.post(() -> callback.onExplained(null, "Failed to upload photo"));
                    return;
                  }
                  main.post(() -> callback.onExplained(payload, ""));
                }
              }
            });
  }

  /** Downscales the capture to {@link #MAX_EDGE} and re-encodes it; null when not decodable. */
  @Nullable
  private static byte[] shrink(byte[] jpeg) {
    if (jpeg == null || jpeg.length == 0) {
      return null;
    }
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
      return jpeg; // not a decodable image — let the vision service deal with the bytes as-is
    }
    int sample = 1;
    while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) {
      sample *= 2;
    }
    BitmapFactory.Options opts = new BitmapFactory.Options();
    opts.inSampleSize = sample;
    Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
    if (bitmap == null) {
      return jpeg;
    }
    float scale = (float) MAX_EDGE / Math.max(bitmap.getWidth(), bitmap.getHeight());
    if (scale < 1f) {
      bitmap =
          Bitmap.createScaledBitmap(
              bitmap,
              Math.round(bitmap.getWidth() * scale),
              Math.round(bitmap.getHeight() * scale),
              true);
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
    return out.toByteArray();
  }

  /** Decodes the shutter still at thumbnail size for the corner flash. */
  @Nullable
  public static Bitmap decodeStill(byte[] jpeg) {
    if (jpeg == null || jpeg.length == 0) {
      return null;
    }
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
      return null;
    }
    int sample = 1;
    while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= STILL_EDGE) {
      sample *= 2;
    }
    BitmapFactory.Options opts = new BitmapFactory.Options();
    opts.inSampleSize = sample;
    return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
  }
}
