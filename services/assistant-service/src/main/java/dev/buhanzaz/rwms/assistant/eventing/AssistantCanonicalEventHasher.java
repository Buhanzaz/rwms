package dev.buhanzaz.rwms.assistant.eventing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Computes lowercase SHA-256 evidence for canonical envelopes and rejected source bytes. */
public final class AssistantCanonicalEventHasher {
  private AssistantCanonicalEventHasher() {}

  public static String sha256(String value) {
    if (value == null) return sha256(new byte[0]);
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  public static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
