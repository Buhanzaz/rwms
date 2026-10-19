package dev.buhanzaz.rwms.logistics.order.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Produces stable order-command fingerprints and scoped derivative idempotency keys. */
final class OrderCommandChecksum {
  private OrderCommandChecksum() {}

  static String sha256(String operation, List<String> values) {
    if (operation == null || operation.isBlank() || values == null) {
      throw new IllegalArgumentException("Checksum operation and values are required");
    }
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
    update(digest, operation);
    for (String value : values) update(digest, value == null ? "<null>" : value);
    return HexFormat.of().formatHex(digest.digest());
  }

  static UUID scopedKey(UUID idempotencyKey, String scope) {
    if (idempotencyKey == null || scope == null || scope.isBlank()) {
      throw new IllegalArgumentException("Idempotency key and scope are required");
    }
    return UUID.nameUUIDFromBytes(
        (scope + "\u001f" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
  }

  private static void update(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update((byte) (bytes.length >>> 24));
    digest.update((byte) (bytes.length >>> 16));
    digest.update((byte) (bytes.length >>> 8));
    digest.update((byte) bytes.length);
    digest.update(bytes);
  }
}
