package dev.buhanzaz.rwms.maintenance.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Small, representation-scoped conditional-GET helper for manager read projections.
 *
 * <p>The payload is still the authoritative service projection. The tag only lets a client
 * retain an already verified local copy when that exact projection did not change.</p>
 */
final class ConditionalGet {
  private ConditionalGet() {}

  static <T> ResponseEntity<T> response(String scope, T body, String ifNoneMatch) {
    String etag = eTag(scope, body);
    if (matches(ifNoneMatch, etag)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
    }
    return ResponseEntity.ok().eTag(etag).body(body);
  }

  private static String eTag(String scope, Object body) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(scope.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(String.valueOf(body).getBytes(StandardCharsets.UTF_8));
      return "W/\"" + HexFormat.of().formatHex(digest.digest()) + "\"";
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the JVM", exception);
    }
  }

  private static boolean matches(String ifNoneMatch, String etag) {
    if (ifNoneMatch == null || ifNoneMatch.isBlank()) return false;
    String normalizedEtag = normalize(etag);
    for (String candidate : ifNoneMatch.split(",")) {
      String normalizedCandidate = normalize(candidate);
      if ("*".equals(normalizedCandidate) || normalizedEtag.equals(normalizedCandidate)) {
        return true;
      }
    }
    return false;
  }

  private static String normalize(String value) {
    String normalized = value.trim();
    return normalized.startsWith("W/") ? normalized.substring(2) : normalized;
  }
}
