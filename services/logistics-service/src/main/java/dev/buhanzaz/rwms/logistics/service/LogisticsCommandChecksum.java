package dev.buhanzaz.rwms.logistics.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

final class LogisticsCommandChecksum {
  private LogisticsCommandChecksum() {}

  static String sha256(String operation, List<String> values) {
    StringBuilder canonical = new StringBuilder(operation.length() + values.size() * 40);
    append(canonical, operation);
    for (String value : values) {
      append(canonical, value == null ? "<null>" : value);
    }
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void append(StringBuilder target, String value) {
    target.append(value.length()).append(':').append(value);
  }
}
