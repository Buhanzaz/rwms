package dev.buhanzaz.rwms.dossier.eventing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Computes stable hashes used to compare source identity and build sanitized DLT metadata without storing unsafe failures. */
public final class DossierEventHash {
  private DossierEventHash() {}

  public static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public static String sha256(String value) {
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }
}
