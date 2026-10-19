package dev.buhanzaz.rwms.maintenance.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Computes the canonical checksum that binds a durable maintenance command or event payload. */
public final class MaintenanceChecksum {
  private MaintenanceChecksum() {}

  public static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
