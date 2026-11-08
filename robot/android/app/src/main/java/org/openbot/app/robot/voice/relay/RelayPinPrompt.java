package org.openbot.app.robot.voice.relay;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import androidx.appcompat.app.AlertDialog;
import org.openbot.app.robot.R;

/**
 * Asks for the 4-digit PIN that a sharing phone shows on its voice control page, then hands it
 * to {@link VoiceRelayClient#setPin}. Used by the voice control and Remote Mic pages when
 * {@link VoiceRelayClient.Listener#onPinRequired} fires.
 */
public final class RelayPinPrompt {

  private RelayPinPrompt() {}

  /** Receives the 4 digits the user typed. */
  public interface OnPin {
    void onPin(String pin);
  }

  public static void show(Context context, String hostName, boolean wrong) {
    show(context, hostName, wrong, pin -> VoiceRelayClient.get().setPin(hostName, pin));
  }

  /** Same prompt, handing the PIN to {@code onPin} (the Remote Mic page's own link). */
  public static void show(Context context, String hostName, boolean wrong, OnPin onPin) {
    EditText input = new EditText(context);
    input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(4)});
    input.setHint("0000");
    FrameLayout box = new FrameLayout(context);
    int pad = Math.round(20 * context.getResources().getDisplayMetrics().density);
    box.setPadding(pad, 0, pad, 0);
    box.addView(input);

    new AlertDialog.Builder(context)
        .setTitle(R.string.voice_relay_pin_title)
        .setMessage(
            context.getString(
                wrong ? R.string.voice_relay_pin_wrong : R.string.voice_relay_pin_message,
                hostName))
        .setView(box)
        .setPositiveButton(
            R.string.voice_relay_pin_connect,
            (d, which) -> {
              String pin = input.getText().toString().trim();
              if (pin.length() == 4) {
                onPin.onPin(pin);
              } else {
                // Not a PIN: ask again instead of silently giving up.
                input.postDelayed(() -> show(context, hostName, false, onPin), 350);
              }
            })
        .setNegativeButton(R.string.voice_cancel, null)
        .show();
  }
}
