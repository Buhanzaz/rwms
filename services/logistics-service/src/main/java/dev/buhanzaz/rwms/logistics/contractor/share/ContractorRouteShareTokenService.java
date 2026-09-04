package dev.buhanzaz.rwms.logistics.contractor.share;

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

/** Issues and verifies domain-separated HMAC capabilities for exact contractor route shares. */
@Component
public class ContractorRouteShareTokenService {
  private static final String DOMAIN = "rwms:contractor-route-share:v1";
  private final byte[] secret;

  /** Reuses the validated presentation secret without sharing a MAC domain or payload shape. */
  public ContractorRouteShareTokenService(
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

  /** Issues a deterministic capability bound to one share and its current revocation revision. */
  public String issue(UUID shareId, long tokenRevision) {
    if (shareId == null || tokenRevision < 1) {
      throw new IllegalArgumentException("Contractor route token identity is invalid");
    }
    String payload = DOMAIN + ":" + shareId + ":" + tokenRevision;
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    return encoded + "." + sign(encoded);
  }

  /** Verifies structure, domain and constant-time HMAC before returning the immutable identity. */
  public TokenIdentity verify(String token) {
    String value = token == null ? "" : token.trim();
    int separator = value.indexOf('.');
    if (separator < 1 || separator != value.lastIndexOf('.')) {
      throw new InvalidContractorRouteShareTokenException();
    }
    String encoded = value.substring(0, separator);
    String signature = value.substring(separator + 1);
    if (!MessageDigest.isEqual(
        sign(encoded).getBytes(StandardCharsets.US_ASCII),
        signature.getBytes(StandardCharsets.US_ASCII))) {
      throw new InvalidContractorRouteShareTokenException();
    }
    try {
      String payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
      String prefix = DOMAIN + ":";
      if (!payload.startsWith(prefix)) throw new IllegalArgumentException();
      String[] parts = payload.substring(prefix.length()).split(":", -1);
      if (parts.length != 2) throw new IllegalArgumentException();
      long tokenRevision = Long.parseLong(parts[1]);
      if (tokenRevision < 1) throw new IllegalArgumentException();
      return new TokenIdentity(UUID.fromString(parts[0]), tokenRevision);
    } catch (IllegalArgumentException exception) {
      throw new InvalidContractorRouteShareTokenException();
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

  /** Verified share identity and token-revision fence. */
  public record TokenIdentity(UUID shareId, long tokenRevision) {}

  /** Marker used to collapse every malformed public capability into the same not-found result. */
  public static final class InvalidContractorRouteShareTokenException extends RuntimeException {}
}
