package org.openbot.app.robot.voice;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import timber.log.Timber;

/**
 * Server TTS downlink for the Voice Control screen: raw opus packets arrive from the WebSocket,
 * get decoded and written to a streaming {@link AudioTrack} in arrival order on a background
 * thread. This is the screen's only voice output (no local TTS).
 *
 * <p>{@code tts stop} simply lets the queued audio finish naturally; barge-in calls {@link
 * #flushAndReset()} which drops the queue and restarts the track instantly. Created at the server
 * hello's playback sample rate and released with the screen.
 */
final class OpusPlayer {

  /** Longest possible opus frame: 120 ms. */
  private static final int MAX_FRAME_MS = 120;

  /** Poll interval of the playback loop; also the resolution of the drain detection. */
  private static final long DRAIN_POLL_MS = 120;

  /** The player counts as drained once the queue has been empty this long (last audio played out). */
  private static final long DRAIN_QUIET_MS = 350;

  /** Playback feedback for the Voice Control UI (level waveform + post-TTS re-arm). */
  interface Listener {
    /** One decoded frame's RMS level, normalized 0..1. Called on the playback thread. */
    void onPlaybackLevel(float rms);

    /** Everything queued has been played. Called on the playback thread. */
    void onPlaybackDrained();
  }

  private final OpusCodec codec;
  private final int sampleRate;
  private final Listener listener;
  private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();

  private volatile boolean released;
  /** True while frames are pending or playing; reset by {@link #flushAndReset()} (barge-in). */
  private volatile boolean busy;
  private AudioTrack track;
  private Thread thread;

  OpusPlayer(OpusCodec codec, int sampleRate, Listener listener) {
    this.codec = codec;
    this.sampleRate = sampleRate;
    this.listener = listener;
    int minBuffer =
        AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
    if (minBuffer <= 0) {
      Timber.e("AudioTrack not available at %d Hz mono", sampleRate);
      return;
    }
    try {
      track =
          new AudioTrack.Builder()
              .setAudioAttributes(
                  new AudioAttributes.Builder()
                      .setUsage(AudioAttributes.USAGE_MEDIA)
                      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                      .build())
              .setAudioFormat(
                  new AudioFormat.Builder()
                      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                      .setSampleRate(sampleRate)
                      .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                      .build())
              .setTransferMode(AudioTrack.MODE_STREAM)
              // ~240 ms of slack keeps playback smooth without much latency.
              .setBufferSizeInBytes(Math.max(minBuffer, sampleRate / 1000 * 240 * 2))
              .build();
      track.play();
    } catch (Exception e) {
      Timber.e(e, "Cannot open the speaker at %d Hz", sampleRate);
      track = null;
      return;
    }
    thread = new Thread(this::playbackLoop, "xiaozhi-speaker");
    thread.start();
  }

  int getSampleRate() {
    return sampleRate;
  }

  /** Queues one raw opus packet for playback, in order. */
  void play(byte[] opus) {
    if (released || opus == null || opus.length == 0) {
      return;
    }
    busy = true;
    queue.offer(opus);
  }

  /**
   * True while audio is queued or still playing out. False right after {@link #flushAndReset()} and
   * after {@link Listener#onPlaybackDrained()} — the UI uses it to tell "tts with no audio" from
   * "wait for the drain callback".
   */
  boolean hasPendingAudio() {
    return busy;
  }

  /** Barge-in: drop everything queued and restart the track immediately. */
  void flushAndReset() {
    queue.clear();
    busy = false; // the cancelled segment never drains; no drain callback for it
    AudioTrack current = track;
    if (current != null) {
      try {
        current.pause();
        current.flush();
        current.play();
      } catch (Exception e) {
        Timber.w(e, "AudioTrack reset failed");
      }
    }
  }

  /** Stops playback and frees the speaker. Safe to call repeatedly. */
  void release() {
    released = true;
    queue.clear();
    busy = false;
    Thread player = thread;
    thread = null;
    if (player != null) {
      player.interrupt();
      try {
        player.join(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    AudioTrack current = track;
    track = null;
    if (current != null) {
      try {
        current.stop();
      } catch (Exception ignored) {
        // Already stopped.
      }
      current.release();
    }
  }

  private void playbackLoop() {
    short[] pcm = new short[sampleRate * MAX_FRAME_MS / 1000 + 16];
    long lastActivityMs = SystemClock.uptimeMillis();
    while (!released) {
      byte[] opus;
      try {
        opus = queue.poll(DRAIN_POLL_MS, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        return;
      }
      if (opus == null) {
        maybeNotifyDrained(lastActivityMs);
        continue;
      }
      lastActivityMs = SystemClock.uptimeMillis();
      int samples = codec.decode(opus, 0, opus.length, pcm);
      if (samples <= 0) {
        continue;
      }
      if (listener != null) {
        listener.onPlaybackLevel(frameRms(pcm, samples));
      }
      AudioTrack current = track;
      if (current == null) {
        continue; // no speaker: drop the frame but keep draining the queue
      }
      try {
        current.write(pcm, 0, samples);
        lastActivityMs = SystemClock.uptimeMillis();
      } catch (Exception e) {
        Timber.w(e, "AudioTrack write failed");
        return;
      }
    }
  }

  /**
   * Fires {@link Listener#onPlaybackDrained()} once per busy segment: the queue has been empty for
   * {@link #DRAIN_QUIET_MS} (longer than the AudioTrack buffer slack), so the last audio has played
   * out. Barge-in resets {@link #busy} instead, so a cancelled segment never drains.
   */
  private void maybeNotifyDrained(long lastActivityMs) {
    if (!busy || !queue.isEmpty()) {
      return;
    }
    if (SystemClock.uptimeMillis() - lastActivityMs < DRAIN_QUIET_MS) {
      return;
    }
    busy = false;
    if (listener != null) {
      listener.onPlaybackDrained();
    }
  }

  /** RMS of the first {@code samples} samples, normalized 0..1 (full scale = 32768). */
  private static float frameRms(short[] pcm, int samples) {
    double sum = 0;
    for (int i = 0; i < samples; i++) {
      sum += (double) pcm[i] * pcm[i];
    }
    return (float) Math.sqrt(sum / samples) / 32768f;
  }
}
