package org.openbot.app.robot.voice;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import java.util.List;
import org.openbot.app.robot.R;
import org.openbot.app.robot.common.ControlsFragment;
import org.openbot.app.robot.databinding.FragmentRemoteMicBinding;
import org.openbot.app.robot.voice.relay.RelayPinPrompt;
import org.openbot.app.robot.voice.relay.VoiceRelayClient;

/**
 * Remote Mic page: this phone's microphone feeds the Voice Control page on the phone that holds
 * the robot link. The mic is captured here, opus-encoded (16 kHz mono, 60 ms frames) and streamed
 * over the WiFi voice relay ({@link VoiceRelayClient}) into that page's Xiaozhi session — so the
 * second phone needs no Xiaozhi pairing. The conversation and Xiaozhi's replies stay on the host
 * phone; only the microphone travels.
 *
 * <p>Tap the orb to open/close the stream ({@code {"t":"mic","on":...}} + frame lines). The host
 * answers {@code micack} saying whether its voice control page is actually listening. Stop on
 * pause so the microphone is never held while the app is in the background.
 */
public class RemoteMicFragment extends ControlsFragment {

  private FragmentRemoteMicBinding binding;
  private VoiceRelayClient relayClient;
  private OpusCodec opusCodec;
  private MicStreamer micStreamer;
  private boolean streaming;
  /** The host's voice control page confirmed it is hearing the microphone. */
  private boolean micAckLive;

  private final VoiceRelayClient.Listener relayListener =
      new VoiceRelayClient.Listener() {
        @Override
        public void onHostConnected(String hostName) {
          // Fresh link: re-announce the stream so the host re-registers it.
          if (streaming) {
            micAckLive = false;
            relayClient.sendMicState(true);
          }
          updateStatus();
        }

        @Override
        public void onHostLost() {
          micAckLive = false;
          updateStatus();
        }

        @Override
        public void onHostsChanged() {
          updateStatus();
        }

        @Override
        public void onPinRequired(String hostName, boolean wrong) {
          if (isAdded() && getView() != null) {
            RelayPinPrompt.show(requireContext(), hostName, wrong);
          }
        }

        @Override
        public void onMicAck(boolean live) {
          micAckLive = live;
          updateStatus();
        }
      };

  private final ActivityResultLauncher<String[]> micPermissionLauncher =
      registerForActivityResult(
          new ActivityResultContracts.RequestMultiplePermissions(),
          result -> {
            Boolean granted = result.get(Manifest.permission.RECORD_AUDIO);
            if (granted != null && granted) {
              startStreaming();
            } else {
              Toast.makeText(
                      requireContext(), R.string.voice_mic_permission_denied, Toast.LENGTH_LONG)
                  .show();
            }
          });

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentRemoteMicBinding.inflate(inflater, container, false);
    return binding.getRoot();
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    relayClient = VoiceRelayClient.get();
    relayClient.start(requireContext().getApplicationContext(), relayListener);

    binding.connectionText.setOnClickListener(v -> showRobotPicker());
    binding.micButton.setOnClickListener(v -> toggleStreaming());
    updateStatus();
  }

  @Override
  public void onPause() {
    super.onPause();
    stopStreaming();
  }

  @Override
  public void onDestroyView() {
    stopStreaming();
    relayClient.stop();
    binding = null;
    super.onDestroyView();
  }

  @Override
  protected void processControllerKeyData(String command) {
    // No drive controls on the remote microphone page.
  }

  @Override
  protected void processUSBData(String data) {
    // No USB console on the remote microphone page.
  }

  // ------------------------------------------------------------------ streaming

  private void toggleStreaming() {
    if (streaming) {
      stopStreaming();
      return;
    }
    if (!relayClient.isConnected()) {
      Toast.makeText(requireContext(), R.string.voice_relay_searching, Toast.LENGTH_SHORT).show();
      return;
    }
    if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      micPermissionLauncher.launch(new String[] {Manifest.permission.RECORD_AUDIO});
      return;
    }
    startStreaming();
  }

  private void startStreaming() {
    if (streaming || binding == null || !relayClient.isConnected()) return;
    opusCodec = new OpusCodec(OpusCodec.MIC_SAMPLE_RATE);
    micStreamer =
        new MicStreamer(
            opusCodec,
            (opus, rms) -> {
              if (opus != null) {
                relayClient.sendMicFrame(Base64.encodeToString(opus, Base64.NO_WRAP), rms);
              }
              View root = binding == null ? null : binding.getRoot();
              if (root != null) {
                root.post(
                    () -> {
                      if (binding != null) {
                        binding.levelBar.setScaleX(Math.min(1f, rms * 3.5f));
                      }
                    });
              }
            });
    micStreamer.start();
    if (!micStreamer.isRunning()) {
      micStreamer = null;
      opusCodec = null;
      Toast.makeText(requireContext(), R.string.remote_mic_mic_unavailable, Toast.LENGTH_LONG)
          .show();
      return;
    }
    streaming = true;
    micAckLive = false;
    relayClient.sendMicState(true);
    updateStatus();
  }

  private void stopStreaming() {
    if (!streaming) return;
    streaming = false;
    if (micStreamer != null) {
      micStreamer.stop();
      micStreamer = null;
    }
    opusCodec = null;
    relayClient.sendMicState(false);
    if (binding != null) {
      binding.levelBar.setScaleX(0f);
    }
    updateStatus();
  }

  // ---------------------------------------------------------------------- UI

  private void updateStatus() {
    if (binding == null) return;
    if (relayClient.isConnected()) {
      String host = relayClient.hostName();
      if (streaming) {
        binding.connectionText.setText(
            getString(
                micAckLive ? R.string.remote_mic_live_on : R.string.remote_mic_host_not_ready,
                host));
      } else {
        binding.connectionText.setText(getString(R.string.remote_mic_host, host));
      }
    } else {
      binding.connectionText.setText(R.string.voice_relay_searching);
    }
    binding.stateText.setText(
        streaming ? R.string.remote_mic_streaming : R.string.remote_mic_tap_to_start);
  }

  /** Pops the shared-robot picker (only WiFi hosts — this page always speaks over the relay). */
  private void showRobotPicker() {
    List<String> hosts = relayClient.hostNames();
    if (hosts.isEmpty()) {
      Toast.makeText(requireContext(), R.string.voice_robot_none_found, Toast.LENGTH_LONG).show();
      return;
    }
    String[] items = new String[hosts.size()];
    for (int i = 0; i < hosts.size(); i++) {
      items[i] = getString(R.string.voice_robot_wifi_item, hosts.get(i));
    }
    int checked = hosts.indexOf(relayClient.hostName());
    new AlertDialog.Builder(requireContext())
        .setTitle(R.string.voice_robot_picker_title)
        .setSingleChoiceItems(
            items,
            checked,
            (dialog, which) -> {
              relayClient.chooseHost(hosts.get(which));
              dialog.dismiss();
              updateStatus();
            })
        .setNegativeButton(R.string.voice_cancel, null)
        .show();
  }
}
