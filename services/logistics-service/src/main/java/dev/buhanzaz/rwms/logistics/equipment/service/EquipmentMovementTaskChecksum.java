package dev.buhanzaz.rwms.logistics.equipment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

final class EquipmentMovementTaskChecksum {
  private EquipmentMovementTaskChecksum() {}

  static String sha256(String operation, List<String> values) {
    if (operation == null || operation.isBlank() || values == null) {
      throw new IllegalArgumentException("Checksum operation and values are required");
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      append(digest, operation);
      for (String value : values) append(digest, value == null ? "<null>" : value);
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void append(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update((byte) (bytes.length >>> 24));
    digest.update((byte) (bytes.length >>> 16));
    digest.update((byte) (bytes.length >>> 8));
    digest.update((byte) bytes.length);
    digest.update(bytes);
  }
}
