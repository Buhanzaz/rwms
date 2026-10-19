package dev.buhanzaz.rwms.logistics.photo;

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
 * Issues and verifies non-expiring HMAC tokens for cabin photo presentations. Both the payload and
 * MAC input are domain-separated from rental client-presentation tokens that share the secret.
 */
@Component
public class CabinPhotoPresentationTokenService {
  private static final String DOMAIN = "rwms:cabin-photo-presentation:v1";
  private final byte[] secret;

  public CabinPhotoPresentationTokenService(
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

  /** Issues a deterministic token bound only to one immutable presentation identifier. */
  public String issue(UUID presentationId) {
    if (presentationId == null) {
      throw new IllegalArgumentException("Presentation token identity is invalid");
    }
    String payload = DOMAIN + ":" + presentationId;
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    return encoded + "." + sign(encoded);
  }

  /** Verifies token shape, domain and constant-time HMAC before returning its presentation id. */
  public UUID verify(String token) {
    String value = token == null ? "" : token.trim();
    int separator = value.indexOf('.');
    if (separator < 1 || separator != value.lastIndexOf('.')) {
      throw new InvalidCabinPhotoPresentationTokenException();
    }
    String encoded = value.substring(0, separator);
    String signature = value.substring(separator + 1);
    if (!MessageDigest.isEqual(
        sign(encoded).getBytes(StandardCharsets.US_ASCII),
        signature.getBytes(StandardCharsets.US_ASCII))) {
      throw new InvalidCabinPhotoPresentationTokenException();
    }
    try {
      String payload =
          new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
      String prefix = DOMAIN + ":";
      if (!payload.startsWith(prefix) || payload.length() <= prefix.length()) {
        throw new IllegalArgumentException();
      }
      return UUID.fromString(payload.substring(prefix.length()));
    } catch (IllegalArgumentException exception) {
      throw new InvalidCabinPhotoPresentationTokenException();
    }
  }

  private String sign(String encodedPayload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      String domainSeparated = DOMAIN + "\u0000" + encodedPayload;
      return HexFormat.of()
          .formatHex(mac.doFinal(domainSeparated.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("HmacSHA256 is required", exception);
    } catch (java.security.InvalidKeyException exception) {
      throw new IllegalStateException("Presentation token secret is invalid", exception);
    }
  }

  /** Marker used to collapse all invalid public token forms into the same not-found response. */
  public static final class InvalidCabinPhotoPresentationTokenException extends RuntimeException {}
}
