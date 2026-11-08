package org.openbot.app.robot.voice;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.View;
import org.openbot.app.robot.R;
import org.openbot.app.robot.databinding.FragmentRemoteMicBinding;

/**
 * The robotic look shared by the Remote Mic and Internet Mic pages: the link pill's status light,
 * the live / idle mic orb with its pulsing ring and level bars, and the scan button.
 */
final class MicPageStyle {

  static final int CYAN = 0xFF7FE7FF;
  static final int GREEN = 0xFF3DDC97;
  static final int AMBER = 0xFFFFB547;
  static final int RED = 0xFFFF6B6B;
  static final int GREY = 0xFF8FA9B3;
  private static final int INK = 0xFF05070B;
  private static final int TEXT = 0xFFE6F9FF;

  private MicPageStyle() {}

  /** The link pill's text and its status light (green linked, amber busy, red trouble). */
  static void setLink(FragmentRemoteMicBinding b, CharSequence text, int dotColor) {
    b.connectionText.setText(text);
    for (Drawable d : b.connectionText.getCompoundDrawablesRelative()) {
      if (d != null) d.mutate().setTint(dotColor);
    }
  }

  /** Live: solid cyan orb, dark mic, pulsing ring, moving bars. Idle: the reverse. */
  static void setLive(FragmentRemoteMicBinding b, boolean live) {
    b.micButton.setBackgroundResource(live ? R.drawable.mic_orb_live : R.drawable.mic_orb_idle);
    b.micButton.setImageTintList(ColorStateList.valueOf(live ? INK : CYAN));
    b.stateText.setTextColor(live ? CYAN : TEXT);
    b.levelBars.setActive(live);
    boolean wasLive = Boolean.TRUE.equals(b.pulseRing.getTag());
    b.pulseRing.setTag(live);
    if (live && !wasLive) {
      pulse(b.pulseRing);
    } else if (!live) {
      b.pulseRing.animate().cancel();
      b.pulseRing.setAlpha(0f);
    }
  }

  /** Filled scan button when there is nothing linked yet (the next step to take). */
  static void setScanEmphasis(FragmentRemoteMicBinding b, boolean filled) {
    b.scanQrButton.setBackgroundResource(
        filled ? R.drawable.mic_scan_filled : R.drawable.mic_scan_outline);
    int fg = filled ? INK : CYAN;
    b.scanQrButton.setTextColor(fg);
    for (Drawable d : b.scanQrButton.getCompoundDrawablesRelative()) {
      if (d != null) d.mutate().setTint(fg);
    }
  }

  private static void pulse(View ring) {
    ring.setScaleX(1f);
    ring.setScaleY(1f);
    ring.setAlpha(0.55f);
    ring.animate()
        .scaleX(1.31f)
        .scaleY(1.31f)
        .alpha(0f)
        .setDuration(1600)
        .withEndAction(
            () -> {
              if (Boolean.TRUE.equals(ring.getTag())) pulse(ring);
            })
        .start();
  }
}
