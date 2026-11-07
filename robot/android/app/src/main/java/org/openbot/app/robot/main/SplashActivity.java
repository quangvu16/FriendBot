package org.openbot.app.robot.main;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.app.AppCompatActivity;
import org.openbot.app.robot.R;

/**
 * Startup screen: shows the FriendBot logo for one second, then opens {@link MainActivity}.
 *
 * <p>It is the launcher activity (see AndroidManifest); re-opening the app from Recents goes
 * straight to {@link MainActivity} because this screen finishes itself.
 */
public class SplashActivity extends AppCompatActivity {

  /** How long the logo stays on screen before the main app opens. */
  private static final long SPLASH_DURATION_MS = 1000L;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_splash);
    new Handler(Looper.getMainLooper())
        .postDelayed(
            () -> {
              startActivity(new Intent(SplashActivity.this, MainActivity.class));
              finish();
            },
            SPLASH_DURATION_MS);
  }
}
