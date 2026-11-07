package org.openbot.app.robot.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads a single file from a URL in the background, reporting progress and delivering the
 * result on the main thread. Used by Model Management to fetch model files referenced by URL.
 */
public class DownloadFileTask {

  /** Progress in percent, 0..100. Invoked on the main thread. */
  public interface ProgressListener {
    void onProgress(int progress);
  }

  /** The downloaded file, or null when the download failed. Invoked on the main thread. */
  public interface FileListener {
    void onDownloaded(File file);
  }

  private final Context context;
  private final ProgressListener progressListener;
  private final FileListener fileListener;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  public DownloadFileTask(
      Context context, ProgressListener progressListener, FileListener fileListener) {
    this.context = context;
    this.progressListener = progressListener;
    this.fileListener = fileListener;
  }

  /** Starts the download of {@code urlString}; callbacks arrive on the main thread. */
  public void execute(String urlString) {
    new Thread(
            () -> {
              File result = null;
              try {
                result = download(urlString);
              } catch (IOException e) {
                e.printStackTrace();
              }
              File done = result;
              mainHandler.post(
                  () -> {
                    if (fileListener != null) fileListener.onDownloaded(done);
                  });
            },
            "DownloadFileTask")
        .start();
  }

  private File download(String urlString) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
    connection.setConnectTimeout(15000);
    connection.setReadTimeout(30000);
    connection.connect();
    int length = connection.getContentLength();
    try (InputStream input = connection.getInputStream()) {
      File output = File.createTempFile("download", null, context.getCacheDir());
      try (FileOutputStream out = new FileOutputStream(output)) {
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
          out.write(buffer, 0, read);
          total += read;
          if (length > 0) {
            int percent = (int) (total * 100 / length);
            mainHandler.post(
                () -> {
                  if (progressListener != null) progressListener.onProgress(percent);
                });
          }
        }
      }
      return output;
    } finally {
      connection.disconnect();
    }
  }
}
