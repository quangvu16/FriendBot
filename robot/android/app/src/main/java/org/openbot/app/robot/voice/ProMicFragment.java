package org.openbot.app.robot.voice;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.MotionEvent;
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
import com.google.gson.JsonObject;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;
import java.util.Locale;
import org.openbot.app.robot.R;
import org.openbot.app.robot.common.ControlsFragment;
import org.openbot.app.robot.databinding.FragmentProMicBinding;
import org.openbot.app.robot.voice.relay.InternetMicLink;
import org.openbot.app.robot.voice.relay.RelayPinPrompt;
import org.openbot.app.robot.voice.relay.RelayQr;
import org.openbot.app.robot.voice.relay.RemoteMicLink;

/**
 * Wi-Fi Mic Pro / Internet Mic Pro: the mic pages plus driving tools. Same links as the Remote
 * Mic ({@link RemoteMicLink}, same WiFi) and Internet Mic ({@link InternetMicLink}, any WiFi or
 * 4G/5G) pages — the old pages are untouched.
 *
 * <ul>
 *   <li><b>STOP</b>: the red bar stops the robot at once. Auto-stop: the robot phone stops by
 *       itself when this page goes silent for 1.5 s (Wi-Fi) / 3 s (internet) while it moves.
 *   <li><b>Heard / answered</b>: what Xiaozhi understood and what it said back.
 *   <li><b>Camera</b>: tap the window to see the robot phone's camera (5 / 2 pictures a second).
 *   <li><b>Hold to talk</b>: the microphone is open only while the orb is held.
 *   <li><b>Drive pad</b>: hold an arrow to drive (short repeated moves), let go to stop.
 *   <li><b>Link delay and battery</b>: ping time in ms, robot phone and robot battery.
 * </ul>
 */
public abstract class ProMicFragment extends ControlsFragment {

  /** Wi-Fi Mic Pro (same WiFi). */
  public static final class Wifi extends ProMicFragment {
    @Override
    protected boolean internet() {
      return false;
    }
  }

  /** Internet Mic Pro (any WiFi or 4G/5G). */
  public static final class Internet extends ProMicFragment {
    @Override
    protected boolean internet() {
      return true;
    }
  }

  protected abstract boolean internet();

  private static final int INK = 0xFF05070B;
  private static final int TEXT = 0xFFE6F9FF;

  /** Drive pad: one move lasts this long and is re-sent every DRIVE_REPEAT_MS while held. */
  static final int DRIVE_MOVE_MS = 600;
  static final long DRIVE_REPEAT_MS = 250;
  static final float DRIVE_SPEED = 0.6f;
  static final float TURN_SPEED = 0.45f;

  private final Handler ui = new Handler(Looper.getMainLooper());
  private FragmentProMicBinding binding;
  private RemoteMicLink wifiLink;
  private InternetMicLink netLink;

  private OpusCodec opusCodec;
  private MicStreamer micStreamer;
  private boolean talking;
  private boolean micAckLive;
  private String floorOther;
  private boolean wasConnected;

  private boolean camOn;
  private long camLastAtMs;
  private long pingId;
  private long pingSentAtMs;
  private long lastPongAtMs;
  private long rttMs = -1;

  /** Held drive pad direction: left/right wheel, or null when no arrow is held. */
  private float[] driving;

  // ------------------------------------------------------------------ link callbacks

  private void linkChanged() {
    boolean connected = isConnected();
    if (connected && !wasConnected) {
      // Fresh link (or a reconnect): tell the robot phone what this Pro page wants.
      sendPro();
      lastPongAtMs = SystemClock.uptimeMillis();
      if (talking) {
        micAckLive = false;
        sendMicState(true);
      }
    }
    if (!connected) {
      micAckLive = false;
      floorOther = null;
      rttMs = -1;
    }
    wasConnected = connected;
    refresh();
  }

  private void onExtra(JsonObject o) {
    if (binding == null) return;
    String t = o.has("t") ? o.get("t").getAsString() : "";
    switch (t) {
      case "pong":
        lastPongAtMs = SystemClock.uptimeMillis();
        if (o.has("id") && o.get("id").getAsLong() == pingId) {
          rttMs = lastPongAtMs - pingSentAtMs;
        }
        refreshPing();
        break;
      case "heard":
        binding.heardText.setText(getString(R.string.pro_heard, str(o, "text")));
        break;
      case "said":
        binding.saidText.setText(getString(R.string.pro_said, str(o, "text")));
        break;
      case "status":
        showBattery(o);
        break;
      case "cam":
        showCamera(str(o, "b"));
        break;
      case "safety":
        Toast.makeText(requireContext(), R.string.pro_safety_stopped, Toast.LENGTH_LONG).show();
        break;
      default:
        break;
    }
  }

  private final RemoteMicLink.Listener wifiListener =
      new RemoteMicLink.Listener() {
        @Override
        public void onLinkChanged() {
          linkChanged();
        }

        @Override
        public void onPinNeeded(String hostName, boolean wrong) {
          if (isAdded() && getView() != null) {
            RelayPinPrompt.show(
                requireContext(), hostName, wrong, pin -> wifiLink.setPin(hostName, pin));
          }
        }

        @Override
        public void onMicAck(boolean live) {
          micAckLive = live;
          refresh();
        }

        @Override
        public void onMicFloor(String speaker, boolean you, int phones) {
          floorOther = you ? null : speaker;
          refresh();
        }

        @Override
        public void onExtra(JsonObject msg) {
          ProMicFragment.this.onExtra(msg);
        }
      };

  private final InternetMicLink.Listener netListener =
      new InternetMicLink.Listener() {
        @Override
        public void onLinkChanged() {
          linkChanged();
        }

        @Override
        public void onMicAck(boolean live) {
          micAckLive = live;
          refresh();
        }

        @Override
        public void onMicFloor(String speaker, boolean you, int phones) {
          floorOther = you ? null : speaker;
          refresh();
        }

        @Override
        public void onExtra(JsonObject msg) {
          ProMicFragment.this.onExtra(msg);
        }
      };

  private final ActivityResultLauncher<String[]> micPermissionLauncher =
      registerForActivityResult(
          new ActivityResultContracts.RequestMultiplePermissions(),
          result -> {
            Boolean granted = result.get(Manifest.permission.RECORD_AUDIO);
            if (granted == null || !granted) {
              Toast.makeText(
                      requireContext(), R.string.voice_mic_permission_denied, Toast.LENGTH_LONG)
                  .show();
            } else {
              Toast.makeText(requireContext(), R.string.pro_mic_ready, Toast.LENGTH_SHORT).show();
            }
          });

  // ------------------------------------------------------------------ lifecycle

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentProMicBinding.inflate(inflater, container, false);
    return binding.getRoot();
  }

  @SuppressLint("ClickableViewAccessibility")
  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    if (internet()) {
      binding.kickerText.setText(R.string.pro_kicker_net);
      binding.titleText.setText(R.string.tile_internet_mic_pro);
      binding.scanQrButton.setText(R.string.internet_mic_scan_qr);
      netLink = InternetMicLink.get();
      netLink.start(requireContext().getApplicationContext(), netListener);
    } else {
      wifiLink = RemoteMicLink.get();
      wifiLink.start(requireContext().getApplicationContext(), wifiListener);
    }
    binding.connectionText.setOnClickListener(v -> showOptions());
    binding.scanQrButton.setOnClickListener(v -> scanQr());
    binding.camBox.setOnClickListener(v -> toggleCamera());
    binding.stopButton.setOnClickListener(v -> stopRobot());
    binding.micButton.setOnTouchListener(
        (v, e) -> {
          switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              v.setPressed(true);
              startTalking();
              return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
              v.setPressed(false);
              stopTalking();
              return true;
            default:
              return true;
          }
        });
    bindPad(binding.padUp, DRIVE_SPEED, DRIVE_SPEED);
    bindPad(binding.padDown, -DRIVE_SPEED, -DRIVE_SPEED);
    bindPad(binding.padLeft, -TURN_SPEED, TURN_SPEED);
    bindPad(binding.padRight, TURN_SPEED, -TURN_SPEED);
    wasConnected = false;
    linkChanged();
    ui.post(pingTick);
  }

  @Override
  public void onPause() {
    super.onPause();
    stopTalking();
    if (driving != null) releasePad();
  }

  @Override
  public void onDestroyView() {
    ui.removeCallbacksAndMessages(null);
    stopTalking();
    if (isConnected()) sendRaw("{\"t\":\"pro\",\"on\":false}");
    if (netLink != null) netLink.stop();
    if (wifiLink != null) wifiLink.stop();
    binding = null;
    super.onDestroyView();
  }

  @Override
  protected void processControllerKeyData(String command) {
    // The drive pad is on screen; no game controller mapping here.
  }

  @Override
  protected void processUSBData(String data) {
    // No USB console on this page.
  }

  // ------------------------------------------------------------------ link helpers

  private boolean isConnected() {
    return internet()
        ? netLink != null && netLink.isConnected()
        : wifiLink != null && wifiLink.isConnected();
  }

  private void sendRaw(String line) {
    if (internet()) {
      if (netLink != null) netLink.sendRaw(line);
    } else if (wifiLink != null) {
      wifiLink.sendRaw(line);
    }
  }

  private void sendMicState(boolean on) {
    if (internet()) {
      netLink.sendMicState(on);
    } else {
      wifiLink.sendMicState(on);
    }
  }

  private void sendMicFrame(String b64, float rms) {
    if (internet()) {
      netLink.sendMicFrame(b64, rms);
    } else {
      wifiLink.sendMicFrame(b64, rms);
    }
  }

  /** The page's wishes: Pro lines, auto-stop window, camera on/off and its picture rate. */
  private void sendPro() {
    int watch = internet() ? 3000 : 1500;
    int fps = internet() ? 2 : 5;
    sendRaw(
        "{\"t\":\"pro\",\"on\":true,\"watch\":"
            + watch
            + ",\"cam\":"
            + camOn
            + ",\"fps\":"
            + fps
            + "}");
  }

  /** Pings often (the auto-stop heartbeat) and measures the link delay. */
  private final Runnable pingTick =
      new Runnable() {
        @Override
        public void run() {
          if (binding == null) return;
          if (isConnected()) {
            pingId++;
            pingSentAtMs = SystemClock.uptimeMillis();
            sendRaw("{\"t\":\"ping\",\"id\":" + pingId + "}");
          }
          refreshPing();
          if (camOn && SystemClock.uptimeMillis() - camLastAtMs > 4000) {
            binding.camHint.setVisibility(View.VISIBLE);
            binding.camHint.setText(R.string.pro_cam_waiting);
          }
          ui.postDelayed(this, internet() ? 1000 : 500);
        }
      };

  // ------------------------------------------------------------------ talking

  private void startTalking() {
    if (talking || binding == null) return;
    if (!isConnected()) {
      Toast.makeText(requireContext(), statusText(), Toast.LENGTH_SHORT).show();
      return;
    }
    if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      micPermissionLauncher.launch(new String[] {Manifest.permission.RECORD_AUDIO});
      return;
    }
    opusCodec = new OpusCodec(OpusCodec.MIC_SAMPLE_RATE);
    micStreamer =
        new MicStreamer(
            opusCodec,
            (opus, rms) -> {
              if (opus != null) sendMicFrame(Base64.encodeToString(opus, Base64.NO_WRAP), rms);
              ui.post(
                  () -> {
                    if (binding != null) binding.levelBars.setLevel(Math.min(1f, rms * 3.5f));
                  });
            });
    micStreamer.start();
    if (!micStreamer.isRunning()) {
      micStreamer = null;
      opusCodec = null;
      Toast.makeText(requireContext(), R.string.remote_mic_mic_unavailable, Toast.LENGTH_LONG)
          .show();
      return;
    }
    talking = true;
    micAckLive = false;
    sendMicState(true);
    refresh();
  }

  private void stopTalking() {
    if (!talking) return;
    talking = false;
    if (micStreamer != null) {
      micStreamer.stop();
      micStreamer = null;
    }
    opusCodec = null;
    sendMicState(false);
    floorOther = null;
    refresh();
  }

  // ------------------------------------------------------------------ driving

  @SuppressLint("ClickableViewAccessibility")
  private void bindPad(View button, float left, float right) {
    button.setOnTouchListener(
        (v, e) -> {
          switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              v.setPressed(true);
              if (!isConnected()) {
                Toast.makeText(requireContext(), statusText(), Toast.LENGTH_SHORT).show();
                return true;
              }
              driving = new float[] {left, right};
              ui.removeCallbacks(driveTick);
              driveTick.run();
              return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
              v.setPressed(false);
              if (driving != null) releasePad();
              return true;
            default:
              return true;
          }
        });
  }

  private final Runnable driveTick =
      new Runnable() {
        @Override
        public void run() {
          float[] d = driving;
          if (d == null || !isConnected()) return;
          sendRaw(
              String.format(
                  Locale.US,
                  "{\"t\":\"move\",\"l\":%.2f,\"r\":%.2f,\"ms\":%d}",
                  d[0],
                  d[1],
                  DRIVE_MOVE_MS));
          ui.postDelayed(this, DRIVE_REPEAT_MS);
        }
      };

  private void releasePad() {
    driving = null;
    ui.removeCallbacks(driveTick);
    sendRaw("{\"t\":\"stop\"}");
  }

  private void stopRobot() {
    driving = null;
    ui.removeCallbacks(driveTick);
    if (!isConnected()) {
      Toast.makeText(requireContext(), statusText(), Toast.LENGTH_SHORT).show();
      return;
    }
    sendRaw("{\"t\":\"stop\"}");
    // Twice, a moment apart: a single lost packet on 4G must not leave the robot driving.
    ui.postDelayed(() -> sendRaw("{\"t\":\"stop\"}"), 150);
    Toast.makeText(requireContext(), R.string.pro_stop_sent, Toast.LENGTH_SHORT).show();
  }

  // ------------------------------------------------------------------ camera

  private void toggleCamera() {
    camOn = !camOn;
    camLastAtMs = SystemClock.uptimeMillis();
    if (isConnected()) sendPro();
    if (binding == null) return;
    if (camOn) {
      binding.camHint.setText(R.string.pro_cam_waiting);
      binding.camHint.setVisibility(View.VISIBLE);
      binding.camTag.setText(
          getString(R.string.pro_cam_tag_on, internet() ? 2 : 5));
      binding.camTag.setTextColor(MicPageStyle.GREEN);
    } else {
      binding.camImage.setVisibility(View.GONE);
      binding.camHint.setText(R.string.pro_cam_off);
      binding.camHint.setVisibility(View.VISIBLE);
      binding.camTag.setText(R.string.pro_cam_tag_off);
      binding.camTag.setTextColor(MicPageStyle.GREY);
    }
  }

  private void showCamera(String b64) {
    if (!camOn || binding == null || b64.isEmpty()) return;
    byte[] jpeg;
    try {
      jpeg = Base64.decode(b64, Base64.NO_WRAP);
    } catch (IllegalArgumentException e) {
      return;
    }
    Bitmap picture = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
    if (picture == null) return;
    camLastAtMs = SystemClock.uptimeMillis();
    binding.camImage.setImageBitmap(picture);
    binding.camImage.setVisibility(View.VISIBLE);
    binding.camHint.setVisibility(View.GONE);
  }

  // ------------------------------------------------------------------ UI

  private void showBattery(JsonObject o) {
    if (binding == null) return;
    StringBuilder text = new StringBuilder();
    int color = MicPageStyle.GREEN;
    if (o.has("rp")) {
      int rp = o.get("rp").getAsInt();
      text.append(getString(R.string.pro_battery_robot, rp));
      if (rp < 20) color = MicPageStyle.RED;
      else if (rp < 40) color = MicPageStyle.AMBER;
    }
    if (o.has("bat")) {
      int bat = o.get("bat").getAsInt();
      boolean chg = o.has("chg") && o.get("chg").getAsBoolean();
      if (text.length() > 0) text.append(' ');
      text.append(getString(chg ? R.string.pro_battery_phone_chg : R.string.pro_battery_phone, bat));
      if (!o.has("rp")) {
        if (bat < 20 && !chg) color = MicPageStyle.RED;
        else if (bat < 40 && !chg) color = MicPageStyle.AMBER;
      }
    }
    if (text.length() == 0) return;
    binding.batteryText.setText(text);
    binding.batteryText.setTextColor(color);
  }

  private void refreshPing() {
    if (binding == null) return;
    long now = SystemClock.uptimeMillis();
    if (!isConnected()) {
      binding.pingText.setText(R.string.pro_ping_none);
      binding.pingText.setTextColor(MicPageStyle.GREY);
    } else if (now - lastPongAtMs > 3000) {
      binding.pingText.setText(R.string.pro_ping_lost);
      binding.pingText.setTextColor(MicPageStyle.RED);
    } else if (rttMs >= 0) {
      binding.pingText.setText(getString(R.string.pro_ping_ms, rttMs));
      binding.pingText.setTextColor(
          rttMs < 150 ? MicPageStyle.GREEN : rttMs < 400 ? MicPageStyle.AMBER : MicPageStyle.RED);
    }
  }

  private void refresh() {
    if (binding == null) return;
    binding.connectionText.setText(statusText());
    int dot = statusColor();
    for (Drawable d : binding.connectionText.getCompoundDrawablesRelative()) {
      if (d != null) d.mutate().setTint(dot);
    }
    boolean live = talking;
    binding.micButton.setBackgroundResource(live ? R.drawable.mic_orb_live : R.drawable.mic_orb_idle);
    binding.micButton.setImageTintList(ColorStateList.valueOf(live ? INK : MicPageStyle.CYAN));
    binding.stateText.setTextColor(live ? MicPageStyle.CYAN : TEXT);
    binding.levelBars.setActive(live);
    if (!live) {
      binding.stateText.setText(R.string.pro_hold_to_talk);
    } else if (floorOther != null) {
      binding.stateText.setText(getString(R.string.remote_mic_floor_other, floorOther));
    } else if (!micAckLive) {
      binding.stateText.setText(R.string.pro_opening);
    } else {
      binding.stateText.setText(R.string.pro_talking);
    }
    boolean wasLive = Boolean.TRUE.equals(binding.pulseRing.getTag());
    binding.pulseRing.setTag(live);
    if (live && !wasLive) {
      pulse(binding.pulseRing);
    } else if (!live) {
      binding.pulseRing.animate().cancel();
      binding.pulseRing.setAlpha(0f);
    }
    boolean needCode = internet() && netLink.state() == InternetMicLink.State.NO_CODE;
    binding.scanQrButton.setBackgroundResource(
        needCode ? R.drawable.mic_scan_filled : R.drawable.mic_scan_outline);
    int fg = needCode ? INK : MicPageStyle.CYAN;
    binding.scanQrButton.setTextColor(fg);
    for (Drawable d : binding.scanQrButton.getCompoundDrawablesRelative()) {
      if (d != null) d.mutate().setTint(fg);
    }
    refreshPing();
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

  private int statusColor() {
    if (internet()) {
      switch (netLink.state()) {
        case CONNECTED:
          return MicPageStyle.GREEN;
        case OFFLINE:
          return MicPageStyle.RED;
        case NO_CODE:
          return MicPageStyle.GREY;
        default:
          return MicPageStyle.AMBER;
      }
    }
    switch (wifiLink.state()) {
      case CONNECTED:
        return MicPageStyle.GREEN;
      case UNREACHABLE:
      case OTHER_WIFI:
      case NO_WIFI:
        return MicPageStyle.RED;
      default:
        return MicPageStyle.AMBER;
    }
  }

  private String statusText() {
    if (internet()) {
      String name = netLink.robotName();
      switch (netLink.state()) {
        case CONNECTED:
          return getString(R.string.internet_mic_connected, name);
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
    String host = wifiLink.stateHost();
    switch (wifiLink.state()) {
      case CONNECTED:
        return getString(R.string.remote_mic_host, wifiLink.hostName());
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
        String wifi = wifiLink.myWifi();
        return wifi != null
            ? getString(R.string.remote_mic_searching_on, wifi)
            : getString(R.string.voice_relay_searching);
    }
  }

  // ------------------------------------------------------------------ choosing the robot

  private void showOptions() {
    if (internet()) {
      String[] items = {
        getString(R.string.internet_mic_scan_qr), getString(R.string.internet_mic_forget)
      };
      new AlertDialog.Builder(requireContext())
          .setTitle(R.string.tile_internet_mic_pro)
          .setItems(
              items,
              (d, which) -> {
                if (which == 0) {
                  scanQr();
                } else {
                  stopTalking();
                  netLink.forget();
                }
              })
          .setNegativeButton(R.string.voice_cancel, null)
          .show();
      return;
    }
    String[] items = {
      getString(R.string.remote_mic_scan_qr),
      getString(R.string.remote_mic_picker_auto),
      getString(R.string.remote_mic_picker_ip)
    };
    new AlertDialog.Builder(requireContext())
        .setTitle(R.string.tile_wifi_mic_pro)
        .setItems(
            items,
            (d, which) -> {
              if (which == 0) {
                scanQr();
              } else if (which == 1) {
                wifiLink.chooseAuto();
              } else {
                askForIp();
              }
              refresh();
            })
        .setNegativeButton(R.string.voice_cancel, null)
        .show();
  }

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
                wifiLink.connectToAddress(ip);
              } else {
                Toast.makeText(requireContext(), R.string.remote_mic_ip_invalid, Toast.LENGTH_LONG)
                    .show();
              }
            })
        .setNegativeButton(R.string.voice_cancel, null)
        .show();
  }

  /** Scans the robot phone's Wi-Fi QR (this page: Wi-Fi) or Internet QR (Internet page). */
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
                if (internet()) {
                  RelayQr.NetInfo qr = RelayQr.parseNet(raw);
                  if (qr == null) {
                    Toast.makeText(
                            requireContext(), R.string.internet_mic_qr_invalid, Toast.LENGTH_LONG)
                        .show();
                    return;
                  }
                  netLink.setCode(qr, raw);
                } else {
                  RelayQr.Info qr = RelayQr.parse(raw);
                  if (qr == null) {
                    Toast.makeText(
                            requireContext(), R.string.remote_mic_qr_invalid, Toast.LENGTH_LONG)
                        .show();
                    return;
                  }
                  wifiLink.connectFromQr(qr);
                }
                refresh();
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

  private static String str(JsonObject o, String key) {
    return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
  }
}
