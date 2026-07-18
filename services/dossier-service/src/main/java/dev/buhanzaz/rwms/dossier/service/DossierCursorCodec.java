package dev.buhanzaz.rwms.dossier.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public final class DossierCursorCodec {
  private final ObjectMapper mapper;
  private final byte[] secret;
  private final Clock clock;

  public DossierCursorCodec(
      ObjectMapper mapper,
      @Value("${rwms.dossier.cursor.secret:dev-only-dossier-cursor-secret-change-me}")
          String secret,
      Clock clock) {
    this.mapper = mapper;
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    this.clock = clock;
  }

  public String encode(UUID cabinId, String filterHash, CursorPosition position) {
    CursorPayload payload =
        new CursorPayload(
            1,
            cabinId,
            filterHash,
            position.occurredAt(),
            position.recordedAt(),
            position.sourceEventId(),
            clock.instant().plus(24, ChronoUnit.HOURS));
    byte[] body = mapper.writeValueAsBytes(payload);
    return url(body) + "." + url(mac(body));
  }

  public CursorPosition decode(String token, UUID cabinId, String filterHash) {
    try {
      if (token == null || token.length() > 4096) throw invalid();
      String[] parts = token == null ? new String[0] : token.split("\\.", -1);
      if (parts.length != 2) throw invalid();
      byte[] body = Base64.getUrlDecoder().decode(parts[0]);
      byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
      if (!java.security.MessageDigest.isEqual(signature, mac(body))) throw invalid();
      CursorPayload payload = mapper.readValue(body, CursorPayload.class);
      if (payload.version() != 1
          || !payload.cabinId().equals(cabinId)
          || !payload.filterHash().equals(filterHash)
          || !payload.expiresAt().isAfter(clock.instant())) {
        throw invalid();
      }
      return new CursorPosition(
          payload.occurredAt(), payload.recordedAt(), payload.sourceEventId());
    } catch (DossierQueryException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw invalid();
    }
  }

  private byte[] mac(byte[] value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return mac.doFinal(value);
    } catch (java.security.GeneralSecurityException exception) {
      throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
    }
  }

  private static String url(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static DossierQueryException invalid() {
    return new DossierQueryException(
        org.springframework.http.HttpStatus.BAD_REQUEST,
        "DOSSIER_INVALID_CURSOR",
        "Dossier cursor is invalid");
  }

  public record CursorPosition(
      Instant occurredAt, Instant recordedAt, UUID sourceEventId) {}

  private record CursorPayload(
      int version,
      UUID cabinId,
      String filterHash,
      Instant occurredAt,
      Instant recordedAt,
      UUID sourceEventId,
      Instant expiresAt) {}
}
