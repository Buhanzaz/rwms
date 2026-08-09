package dev.buhanzaz.rwms.warehouse.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Computes lowercase SHA-256 checksums used to fence durable warehouse records and envelopes. */
public final class WarehouseChecksum {
  private WarehouseChecksum() {}

  public static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
    }
  }
}
