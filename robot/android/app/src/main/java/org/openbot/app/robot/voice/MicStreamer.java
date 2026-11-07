package org.openbot.app.robot.voice;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import timber.log.Timber;

/**
 * Push-to-talk microphone uplink for the Voice Control screen: captures 16 kHz mono PCM16 on a
 * background thread, opus-encodes each 60 ms frame ({@link OpusCodec#FRAME_SAMPLES} samples) and
 * hands the raw packet to a callback, which sends it as a binary WebSocket message. Started and
 * stopped from the main thread; the callback runs on the capture thread.
 */
final class MicStreamer {

  interface Callback {
    /**
     * One captured frame (60 ms of 16 kHz mono audio), produced on the capture thread.
     *
     * @param opus the raw opus packet, or null when encoding failed (the level is still reported so
     *     the UI waveform keeps moving)
     * @param rms the frame's RMS level normalized to 0..1 (full scale = 32768)
     */
    void onOpusFrame(byte[] opus, float rms);
  }

  private final OpusCodec codec;
  private final Callback callback;

  private volatile boolean running;
  private Thread thread;
  private AudioRecord recorder;

  MicStreamer(OpusCodec codec, Callback callback) {
    this.codec = codec;
    this.callback = callback;
  }

  synchronized boolean isRunning() {
    return running;
  }

  /** Opens the microphone and starts streaming frames. No-op when already running. */
  synchronized void start() {
    if (running) {
      return;
    }
    int minBuffer =
        AudioRecord.getMinBufferSize(
            OpusCodec.MIC_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    if (minBuffer <= 0) {
      Timber.e("AudioRecord not available at 16 kHz mono");
      return;
    }
    AudioRecord record;
    try {
      record =
          new AudioRecord(
              MediaRecorder.AudioSource.MIC,
              OpusCodec.MIC_SAMPLE_RATE,
              AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT,
              Math.max(minBuffer, OpusCodec.FRAME_SAMPLES * 2 * 4));
    } catch (Exception e) {
      Timber.e(e, "Cannot open the microphone");
      return;
    }
    if (record.getState() != AudioRecord.STATE_INITIALIZED) {
      Timber.e("AudioRecord failed to initialize");
      record.release();
      return;
    }
    recorder = record;
    running = true;
    thread = new Thread(this::captureLoop, "xiaozhi-mic");
    thread.start();
  }

  /** Stops capture and releases the microphone. Safe to call repeatedly. */
  synchronized void stop() {
    running = false;
    AudioRecord record = recorder;
    recorder = null;
    if (record != null) {
      try {
        record.stop(); // unblocks the capture thread's read()
      } catch (Exception ignored) {
        // Already stopped.
      }
    }
    Thread capture = thread;
    thread = null;
    if (capture != null) {
      capture.interrupt();
      try {
        capture.join(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (record != null) {
      record.release();
    }
  }

  private void captureLoop() {
    AudioRecord record = recorder;
    if (record == null) {
      return;
    }
    try {
      record.startRecording();
    } catch (Exception e) {
      Timber.e(e, "AudioRecord failed to start");
      return;
    }
    short[] pcm = new short[OpusCodec.FRAME_SAMPLES];
    while (running) {
      int filled = 0;
      while (filled < pcm.length && running) {
        int read = record.read(pcm, filled, pcm.length - filled);
        if (read <= 0) {
          if (running) {
            Timber.w("AudioRecord read failed (%d), stopping the microphone uplink", read);
          }
          return;
        }
        filled += read;
      }
      if (!running) {
        return;
      }
      float rms = frameRms(pcm);
      byte[] opus = codec.encode(pcm, 0, pcm.length);
      callback.onOpusFrame(opus != null && opus.length > 0 ? opus : null, rms);
    }
  }

  /** RMS of one PCM16 frame normalized to 0..1 (full scale = 32768). */
  private static float frameRms(short[] pcm) {
    double sum = 0;
    for (short sample : pcm) {
      sum += (double) sample * sample;
    }
    return (float) Math.sqrt(sum / pcm.length) / 32768f;
  }
}
