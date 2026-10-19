package dev.buhanzaz.rwms.logistics.inquiry.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Issues and verifies HMAC-signed client presentation identities, including the presentation revision fence.
 */
@Component
public class ClientPresentationTokenService {
  private final byte[] secret;

  public ClientPresentationTokenService(
      Environment environment,
      @Value("${rwms.logistics.client-presentation.token-secret:}") String configuredSecret) {
    String value = configuredSecret == null ? "" : configuredSecret.trim();
    if (value.length() < 32) {
      throw new IllegalStateException(
          "CLIENT_PRESENTATION_TOKEN_SECRET must contain at least 32 characters");
    }
    if (environment.matchesProfiles("prod", "production")
        && value.equals("rwms-local-client-presentation-secret-change-me")) {
      throw new IllegalStateException(
          "Production requires a dedicated CLIENT_PRESENTATION_TOKEN_SECRET");
    }
    secret = value.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Issues a signed token bound to the exact client-presentation identifier and revision.
   *
   * @param presentationId presentation that the anonymous holder may resolve
   * @param revision immutable presentation revision to fence stale links
   * @return HMAC-signed presentation token
   */
  public String issue(UUID presentationId, long revision) {
    if (presentationId == null || revision < 1) {
      throw new IllegalArgumentException("Presentation token identity is invalid");
    }
    String payload = presentationId + ":" + revision;
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    return encoded + "." + sign(encoded);
  }

  /**
   * Validates token structure, HMAC signature and revision encoding before returning its immutable
   * presentation identity; invalid input is rejected without a fallback identity.
   *
   * @param token untrusted token supplied by the public presentation route
   * @return verified presentation identity and revision
   */
  public TokenIdentity verify(String token) {
    String value = token == null ? "" : token.trim();
    int separator = value.indexOf('.');
    if (separator < 1 || separator != value.lastIndexOf('.')) {
      throw new InvalidPresentationTokenException();
    }
    String encoded = value.substring(0, separator);
    String signature = value.substring(separator + 1);
    if (!MessageDigest.isEqual(
        sign(encoded).getBytes(StandardCharsets.US_ASCII),
        signature.getBytes(StandardCharsets.US_ASCII))) {
      throw new InvalidPresentationTokenException();
    }
    try {
      String payload =
          new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
      String[] parts = payload.split(":", -1);
      if (parts.length != 2) throw new IllegalArgumentException();
      long revision = Long.parseLong(parts[1]);
      if (revision < 1) throw new IllegalArgumentException();
      return new TokenIdentity(UUID.fromString(parts[0]), revision);
    } catch (IllegalArgumentException exception) {
      throw new InvalidPresentationTokenException();
    }
  }

  private String sign(String encodedPayload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return HexFormat.of()
          .formatHex(mac.doFinal(encodedPayload.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("HmacSHA256 is required", exception);
    } catch (java.security.InvalidKeyException exception) {
      throw new IllegalStateException("Presentation token secret is invalid", exception);
    }
  }

  public record TokenIdentity(UUID presentationId, long revision) {}

  public static final class InvalidPresentationTokenException extends RuntimeException {}
}
