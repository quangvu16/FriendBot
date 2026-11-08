package org.openbot.app.robot.voice.relay;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Internet Mic encryption: AES-256-GCM with the 32-byte key from the robot phone's Internet QR.
 * A message is nonce (12 bytes) + ciphertext + tag (16 bytes) — the same layout as iOS CryptoKit's
 * {@code AES.GCM.SealedBox.combined}, so both apps read each other's messages. Anyone else on
 * the public broker sees only noise, and anything that does not decrypt is dropped.
 */
final class NetCrypto {

  private static final SecureRandom RNG = new SecureRandom();
  private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";

  private NetCrypto() {}

  static String randomId(int length) {
    StringBuilder b = new StringBuilder(length);
    for (int i = 0; i < length; i++) b.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
    return b.toString();
  }

  static byte[] randomKey() {
    byte[] key = new byte[32];
    RNG.nextBytes(key);
    return key;
  }

  static String b64(byte[] data) {
    return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
  }

  static byte[] unb64(String text) {
    try {
      return Base64.decode(text, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  static byte[] seal(byte[] key, String text) {
    try {
      byte[] nonce = new byte[12];
      RNG.nextBytes(nonce);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      byte[] ct = c.doFinal(text.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[12 + ct.length];
      System.arraycopy(nonce, 0, out, 0, 12);
      System.arraycopy(ct, 0, out, 12, ct.length);
      return out;
    } catch (Exception e) {
      return null;
    }
  }

  /** The decrypted text, or null when it is not ours (wrong key, tampered, garbage). */
  static String open(byte[] key, byte[] data) {
    if (data == null || data.length < 12 + 16) return null;
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(128, data, 0, 12));
      return new String(c.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
    } catch (Exception e) {
      return null;
    }
  }
}
