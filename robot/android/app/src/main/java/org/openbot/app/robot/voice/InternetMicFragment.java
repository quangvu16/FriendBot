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
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;
import org.openbot.app.robot.R;
import org.openbot.app.robot.common.ControlsFragment;
import org.openbot.app.robot.databinding.FragmentRemoteMicBinding;
import org.openbot.app.robot.voice.relay.InternetMicLink;
import org.openbot.app.robot.voice.relay.RelayQr;

/**
 * Internet Mic page: this phone's microphone speaks to the robot phone's Voice Control page from
 * anywhere — another WiFi or 4G/5G — through a free public MQTT relay, encrypted end to end with
 * the key in the robot phone's Internet QR ({@link InternetMicLink}).
 *
 * <p>Scan the Internet QR once (the robot phone shows it on its Voice Control page: double-tap
 * the face, then "Internet QR"); the phone remembers it and reconnects by itself. Tap the orb to
 * speak. Same look as the Remote Mic page (it reuses its layout).
 */
public class InternetMicFragment extends ControlsFragment {

  private FragmentRemoteMicBinding binding;
  private InternetMicLink link;
  private OpusCodec opusCodec;
  private MicStreamer micStreamer;
  private boolean streaming;
  private boolean micAckLive;
  private String floorOther;
  private int floorPhones = 1;

  private final InternetMicLink.Listener linkListener =
      new InternetMicLink.Listener() {
        @Override
        public void onLinkChanged() {
          if (!link.isConnected()) {
            micAckLive = false;
            floorOther = null;
            floorPhones = 1;
          }
          updateStatus();
        }

        @Override
        public void onMicAck(boolean live) {
          micAckLive = live;
          updateStatus();
        }

        @Override
        public void onMicFloor(String speaker, boolean you, int phones) {
          floorOther = you ? null : speaker;
          floorPhones = phones;
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
    binding.kickerText.setText(R.string.mic_kicker_net);
    binding.titleText.setText(R.string.tile_internet_mic);
    binding.scanQrButton.setText(R.string.internet_mic_scan_qr);
    binding.wifiText.setText(R.string.internet_mic_any_network);
    binding.networkTag.setText(R.string.mic_tag_encrypted);
    link = InternetMicLink.get();
    link.start(requireContext().getApplicationContext(), linkListener);
    binding.connectionText.setOnClickListener(v -> showOptions());
    binding.scanQrButton.setOnClickListener(v -> scanQr());
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
    link.stop();
    binding = null;
    super.onDestroyView();
  }

  @Override
  protected void processControllerKeyData(String command) {
    // No drive controls on the internet microphone page.
  }

  @Override
  protected void processUSBData(String data) {
    // No USB console on the internet microphone page.
  }

  // ------------------------------------------------------------------ streaming

  private void toggleStreaming() {
    if (streaming) {
      stopStreaming();
      return;
    }
    if (!link.isConnected()) {
      Toast.makeText(requireContext(), statusText(), Toast.LENGTH_LONG).show();
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
    if (streaming || binding == null || !link.isConnected()) return;
    opusCodec = new OpusCodec(OpusCodec.MIC_SAMPLE_RATE);
    micStreamer =
        new MicStreamer(
            opusCodec,
            (opus, rms) -> {
              if (opus != null) {
                link.sendMicFrame(Base64.encodeToString(opus, Base64.NO_WRAP), rms);
              }
              View root = binding == null ? null : binding.getRoot();
              if (root != null) {
                root.post(
                    () -> {
                      if (binding != null) {
                        binding.levelBars.setLevel(Math.min(1f, rms * 3.5f));
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
    link.sendMicState(true);
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
    link.sendMicState(false);
    floorOther = null;
    floorPhones = 1;
    if (binding != null) binding.levelBars.setActive(false);
    updateStatus();
  }

  // ---------------------------------------------------------------------- UI

  private void updateStatus() {
    if (binding == null) return;
    MicPageStyle.setLink(binding, statusText(), statusColor());
    MicPageStyle.setLive(binding, streaming);
    MicPageStyle.setScanEmphasis(binding, link.state() == InternetMicLink.State.NO_CODE);
    binding.stateText.setText(
        streaming ? R.string.remote_mic_streaming : R.string.remote_mic_tap_to_start);
  }

  /** The link pill's status light: green linked, amber busy, red offline, grey no code. */
  private int statusColor() {
    switch (link.state()) {
      case CONNECTED:
        return streaming && !micAckLive ? MicPageStyle.AMBER : MicPageStyle.GREEN;
      case OFFLINE:
        return MicPageStyle.RED;
      case NO_CODE:
        return MicPageStyle.GREY;
      default:
        return MicPageStyle.AMBER;
    }
  }

  private String statusText() {
    String name = link.robotName();
    switch (link.state()) {
      case CONNECTED:
        if (!streaming) return getString(R.string.internet_mic_connected, name);
        if (!micAckLive) return getString(R.string.remote_mic_host_not_ready, name);
        if (floorOther != null) return getString(R.string.remote_mic_floor_other, floorOther);
        if (floorPhones > 1) return getString(R.string.remote_mic_live_shared, name, floorPhones);
        return getString(R.string.remote_mic_live_on, name);
      case CONNECTING:
        return getString(R.string.internet_mic_connecting);
      case WAITING_ROBOT:
        return getString(R.string.internet_mic_waiting, name);
      case OFFLINE:
        return getString(R.string.internet_mic_offline, name);
      case NO_CODE:
      default:
        return getString(R.string.internet_mic_no_code);
    }
  }

  private void showOptions() {
    String[] items = {
      getString(R.string.internet_mic_scan_qr), getString(R.string.internet_mic_forget)
    };
    new AlertDialog.Builder(requireContext())
        .setTitle(R.string.tile_internet_mic)
        .setItems(
            items,
            (d, which) -> {
              if (which == 0) {
                scanQr();
              } else {
                stopStreaming();
                link.forget();
              }
            })
        .setNegativeButton(R.string.voice_cancel, null)
        .show();
  }

  /** Scans the robot phone's Internet QR (Google's code scanner, no camera permission here). */
  private void scanQr() {
    GmsBarcodeScannerOptions options =
        new GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build();
    try {
      GmsBarcodeScanning.getClient(requireContext(), options)
          .startScan()
          .addOnSuccessListener(
              barcode -> {
                if (!isAdded()) return;
                String raw = barcode.getRawValue();
                RelayQr.NetInfo qr = RelayQr.parseNet(raw);
                if (qr == null) {
                  Toast.makeText(
                          requireContext(), R.string.internet_mic_qr_invalid, Toast.LENGTH_LONG)
                      .show();
                  return;
                }
                link.setCode(qr, raw);
                updateStatus();
              })
          .addOnFailureListener(
              e -> {
                if (isAdded()) {
                  Toast.makeText(
                          requireContext(), R.string.remote_mic_qr_unavailable, Toast.LENGTH_LONG)
                      .show();
                }
              });
    } catch (RuntimeException e) {
      Toast.makeText(requireContext(), R.string.remote_mic_qr_unavailable, Toast.LENGTH_LONG)
          .show();
    }
  }
}
