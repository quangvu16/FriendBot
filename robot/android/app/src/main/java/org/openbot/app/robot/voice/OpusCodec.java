package org.openbot.app.robot.voice;

import org.concentus.OpusApplication;
import org.concentus.OpusDecoder;
import org.concentus.OpusEncoder;
import org.concentus.OpusException;

/**
 * Thin wrapper around the vendored Concentus codec (pure-Java port of libopus 1.1.2 FIXED_POINT,
 * see {@code org.concentus} + LICENSE-CONCENTUS.txt) for the Voice Control audio channel.
 *
 * <p>One encoder (16 kHz mono, VOIP application) and one decoder (the server hello's playback
 * sample rate, mono) per session. Microphone frames are exactly {@link #FRAME_SAMPLES} samples
 * (60 ms at 16 kHz), matching the {@code audio_params} sent in the {@code hello}. Decode returns
 * however many samples the packet holds (the server may use a different frame duration).
 */
public final class OpusCodec {

  /** Microphone capture format: 16 kHz mono PCM16. */
  public static final int MIC_SAMPLE_RATE = 16000;

  /** 60 ms of 16 kHz audio per encoded packet ({@code frame_duration: 60}). */
  public static final int FRAME_SAMPLES = 960;

  /** An opus packet is never larger than 1276 bytes. */
  private static final int MAX_PACKET_BYTES = 1276;

  private final OpusEncoder encoder;
  private final OpusDecoder decoder;
  private final int playbackSampleRate;

  /**
   * @param playbackSampleRate the rate the server's TTS opus must be decoded at (from the server
   *     {@code hello}'s {@code audio_params.sample_rate}; 16000 when absent)
   */
  public OpusCodec(int playbackSampleRate) {
    this.playbackSampleRate = playbackSampleRate;
    try {
      encoder = new OpusEncoder(MIC_SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
      decoder = new OpusDecoder(playbackSampleRate, 1);
    } catch (OpusException e) {
      throw new IllegalStateException("Cannot create the Opus codec", e);
    }
  }

  /** The rate {@link #decode} produces, i.e. the server's TTS playback rate. */
  public int getPlaybackSampleRate() {
    return playbackSampleRate;
  }

  /**
   * Encodes one {@link #FRAME_SAMPLES}-sample frame of 16 kHz mono PCM16 into a raw opus packet.
   *
   * @return the packet bytes, or null when encoding failed (drop the frame)
   */
  public byte[] encode(short[] pcm, int offset, int length) {
    byte[] out = new byte[MAX_PACKET_BYTES];
    int written;
    try {
      written = encoder.encode(pcm, offset, length, out, 0, MAX_PACKET_BYTES);
    } catch (OpusException e) {
      // A dropped microphone frame is harmless; the server PLCs over it.
      return null;
    }
    if (written <= 0) {
      return null;
    }
    byte[] packet = new byte[written];
    System.arraycopy(out, 0, packet, 0, written);
    return packet;
  }

  /**
   * Decodes one raw opus packet (TTS downlink) into {@code out}.
   *
   * @return the number of decoded samples per channel (0 when the packet did not decode)
   */
  public int decode(byte[] opus, int offset, int length, short[] out) {
    try {
      return decoder.decode(opus, offset, length, out, 0, out.length, false);
    } catch (Exception e) {
      // A corrupt packet is skipped; the next one resyncs the stream.
      return 0;
    }
  }
}
