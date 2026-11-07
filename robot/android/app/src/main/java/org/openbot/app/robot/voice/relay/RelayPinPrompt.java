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

  public static void show(Context context, String hostName, boolean wrong) {
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
                VoiceRelayClient.get().setPin(hostName, pin);
              }
            })
        .setNegativeButton(R.string.voice_cancel, null)
        .show();
  }
}
