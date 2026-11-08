package org.openbot.app.robot.voice;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;
import org.openbot.app.robot.R;
import org.openbot.app.robot.common.ControlsFragment;
import org.openbot.app.robot.databinding.FragmentRemoteMicBinding;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;
import org.openbot.app.robot.voice.relay.RelayPinPrompt;
import org.openbot.app.robot.voice.relay.RelayQr;
import org.openbot.app.robot.voice.relay.RemoteMicLink;

/**
 * Remote Mic page: this phone's microphone speaks to the Voice Control page of the robot phone
 * (the phone that holds the robot link and the Xiaozhi session) over the same WiFi.
 *
 * <p>The page owns its WiFi link ({@link RemoteMicLink}): it finds robot phones on this WiFi by
 * broadcast and NSD, connects to the first one on the same WiFi name (or the one the user picks,
 * or a typed IP address), sends the PIN, then streams the microphone — 16 kHz mono opus, 60 ms
 * frames — into the robot phone's Xiaozhi session. Xiaozhi's replies play on the robot phone.
 *
 * <p>Tap the status line to choose the robot phone; tap the orb to start/stop speaking. The
 * microphone is never held while the page is paused.
 */
public class RemoteMicFragment extends ControlsFragment {

  private FragmentRemoteMicBinding binding;
  private RemoteMicLink link;
  private OpusCodec opusCodec;
  private MicStreamer micStreamer;
  private boolean streaming;
  /** The robot phone's Voice Control page confirmed it is hearing the microphone. */
  private boolean micAckLive;
  /** Another phone holding the robot phone's microphone floor (null: this phone, or nobody). */
  private String floorOther;
  /** Phones streaming to the robot phone, this one included. */
  private int floorPhones = 1;
  /** The link was connected at the last refresh (detects reconnects to re-open the stream). */
  private boolean wasConnected;
  private boolean askedLocation;

  private final RemoteMicLink.Listener linkListener =
      new RemoteMicLink.Listener() {
        @Override
        public void onLinkChanged() {
          boolean connected = link.isConnected();
          if (connected && !wasConnected && streaming) {
            // Fresh link: re-open the stream so the robot phone registers this microphone.
            micAckLive = false;
            link.sendMicState(true);
          }
          if (!connected) {
            micAckLive = false;
            floorOther = null;
            floorPhones = 1;
          }
          wasConnected = connected;
          updateStatus();
        }

        @Override
        public void onPinNeeded(String hostName, boolean wrong) {
          if (isAdded() && getView() != null) {
            RelayPinPrompt.show(
                requireContext(), hostName, wrong, pin -> link.setPin(hostName, pin));
          }
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

  /** Location only lets Android tell us the WiFi name (to match phones on the same WiFi). */
  private final ActivityResultLauncher<String[]> locationPermissionLauncher =
      registerForActivityResult(
          new ActivityResultContracts.RequestMultiplePermissions(), result -> updateStatus());

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentRemoteMicBinding.inflate(inflater, container, false);
    return binding.getRoot();
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    link = RemoteMicLink.get();
    link.start(requireContext().getApplicationContext(), linkListener);
    binding.connectionText.setOnClickListener(v -> showRobotPicker());
    binding.wifiText.setOnClickListener(
        v -> {
          askedLocation = false; // an explicit tap may ask again
          askForLocation();
        });
    binding.micButton.setOnClickListener(v -> toggleStreaming());
    binding.scanQrButton.setOnClickListener(v -> scanQr());
    if (!hasLocation()) {
      askForLocation();
    }
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
    if (binding != null) {
      binding.levelBars.setActive(false);
    }
    updateStatus();
  }

  // ---------------------------------------------------------------------- UI

  private void updateStatus() {
    if (binding == null) return;
    MicPageStyle.setLink(binding, statusText(), statusColor());
    MicPageStyle.setLive(binding, streaming);
    MicPageStyle.setScanEmphasis(binding, link.state() != RemoteMicLink.State.CONNECTED);
    String wifi = link.myWifi();
    if (link.state() == RemoteMicLink.State.NO_WIFI) {
      binding.wifiText.setText(R.string.remote_mic_no_wifi);
    } else if (wifi != null) {
      binding.wifiText.setText(getString(R.string.remote_mic_wifi, wifi));
    } else {
      binding.wifiText.setText(R.string.remote_mic_wifi_hidden);
    }
    binding.stateText.setText(
        streaming ? R.string.remote_mic_streaming : R.string.remote_mic_tap_to_start);
  }

  /** The link pill's status light: green linked, amber busy, red trouble. */
  private int statusColor() {
    switch (link.state()) {
      case CONNECTED:
        return streaming && !micAckLive ? MicPageStyle.AMBER : MicPageStyle.GREEN;
      case UNREACHABLE:
      case OTHER_WIFI:
      case NO_WIFI:
        return MicPageStyle.RED;
      default:
        return MicPageStyle.AMBER;
    }
  }

  /** One line saying exactly where the link is — never a bare "searching" when it is stuck. */
  private String statusText() {
    String host = link.stateHost();
    switch (link.state()) {
      case CONNECTED:
        String name = link.hostName();
        if (!streaming) return getString(R.string.remote_mic_host, name);
        if (!micAckLive) return getString(R.string.remote_mic_host_not_ready, name);
        if (floorOther != null) return getString(R.string.remote_mic_floor_other, floorOther);
        if (floorPhones > 1) return getString(R.string.remote_mic_live_shared, name, floorPhones);
        return getString(R.string.remote_mic_live_on, name);
      case CONNECTING:
        return getString(R.string.remote_mic_connecting, host);
      case NEED_PIN:
        return getString(R.string.remote_mic_need_pin, host);
      case UNREACHABLE:
        return getString(R.string.remote_mic_unreachable, host);
      case OTHER_WIFI:
        return getString(R.string.remote_mic_other_wifi, host);
      case NO_WIFI:
        return getString(R.string.remote_mic_no_wifi);
      case SEARCHING:
      default:
        String wifi = link.myWifi();
        return wifi != null
            ? getString(R.string.remote_mic_searching_on, wifi)
            : getString(R.string.voice_relay_searching);
    }
  }

  /** Lists the robot phones found (same WiFi first), plus automatic and "type an IP". */
  private void showRobotPicker() {
    List<RemoteMicLink.Host> hosts = link.hosts();
    List<String> items = new ArrayList<>();
    items.add(getString(R.string.remote_mic_scan_qr));
    items.add(getString(R.string.remote_mic_picker_auto));
    for (RemoteMicLink.Host h : hosts) {
      String item =
          h.wifi != null
              ? getString(R.string.remote_mic_picker_item_wifi, h.name, h.address, h.wifi)
              : getString(R.string.remote_mic_picker_item, h.name, h.address);
      if (!link.sameWifi(h)) {
        item = getString(R.string.remote_mic_picker_other, item);
      }
      items.add(item);
    }
    items.add(getString(R.string.remote_mic_picker_ip));
    AlertDialog.Builder picker =
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.voice_robot_picker_title)
            .setItems(
                items.toArray(new String[0]),
                (dialog, which) -> {
                  if (which == 0) {
                    scanQr();
                  } else if (which == 1) {
                    link.chooseAuto();
                  } else if (which == items.size() - 1) {
                    askForIp();
                  } else {
                    link.choose(hosts.get(which - 2));
                  }
                  updateStatus();
                })
            .setNegativeButton(R.string.voice_cancel, null);
    if (hosts.isEmpty()) {
      picker.setMessage(R.string.remote_mic_picker_none);
    }
    picker.show();
  }

  /**
   * Scans the QR on the robot phone's Voice Control page (Google's code scanner: its own camera
   * screen, no camera permission here) and connects straight to that IP with that PIN.
   */
  private void scanQr() {
    GmsBarcodeScannerOptions options =
        new GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build();
    GmsBarcodeScanner scanner;
    try {
      scanner = GmsBarcodeScanning.getClient(requireContext(), options);
    } catch (RuntimeException e) {
      Toast.makeText(requireContext(), R.string.remote_mic_qr_unavailable, Toast.LENGTH_LONG)
          .show();
      return;
    }
    scanner
        .startScan()
        .addOnSuccessListener(
            barcode -> {
              if (!isAdded()) return;
              RelayQr.Info qr = RelayQr.parse(barcode.getRawValue());
              if (qr == null) {
                Toast.makeText(requireContext(), R.string.remote_mic_qr_invalid, Toast.LENGTH_LONG)
                    .show();
                return;
              }
              String mine = link.myWifi();
              if (mine != null && qr.wifi != null && !mine.equals(qr.wifi)) {
                // Connect anyway (some routers use two names), but say why it may fail.
                Toast.makeText(
                        requireContext(),
                        getString(R.string.remote_mic_qr_other_wifi, qr.name, qr.wifi),
                        Toast.LENGTH_LONG)
                    .show();
              }
              link.connectFromQr(qr);
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
  }

  /** Manual fallback: the robot phone shows its IP under the PIN. */
  private void askForIp() {
    EditText input = new EditText(requireContext());
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    input.setHint("192.168.1.23");
    input.setSingleLine(true);
    FrameLayout box = new FrameLayout(requireContext());
    int pad = Math.round(20 * getResources().getDisplayMetrics().density);
    box.setPadding(pad, 0, pad, 0);
    box.addView(input);
    new AlertDialog.Builder(requireContext())
        .setTitle(R.string.remote_mic_ip_title)
        .setMessage(R.string.remote_mic_ip_message)
        .setView(box)
        .setPositiveButton(
            R.string.voice_relay_pin_connect,
            (d, which) -> {
              String ip = input.getText().toString().trim();
              if (ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                link.connectToAddress(ip);
              } else {
                Toast.makeText(requireContext(), R.string.remote_mic_ip_invalid, Toast.LENGTH_LONG)
                    .show();
              }
            })
        .setNegativeButton(R.string.voice_cancel, null)
        .show();
  }

  private boolean hasLocation() {
    return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
        == PackageManager.PERMISSION_GRANTED;
  }

  private void askForLocation() {
    if (hasLocation() || askedLocation) return;
    askedLocation = true;
    locationPermissionLauncher.launch(
        new String[] {
          Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
        });
  }
}
