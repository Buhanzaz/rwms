package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerOfflineLease;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encodes an authenticated issue timestamp into a UUID-shaped offline lease.
 * The service can validate a delayed command without retaining browser or
 * device state, while a client cannot extend or mint a lease.
 */
@Component
public class WorkerOfflineLeaseCodec {
  private static final Duration LIFETIME = Duration.ofHours(24);
  private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(2);
  private static final long TIMESTAMP_MASK = 0x0000_FFFF_FFFF_FFFFL;
  private static final long SIGNATURE_LOW_MASK = 0x3FFF_FFFF_FFFF_FFFFL;
  private static final long RFC_4122_VARIANT = 0x8000_0000_0000_0000L;

  private final byte[] secret;

  public WorkerOfflineLeaseCodec(
      @Value("${rwms.worker.offline-lease-secret}") String encodedSecret) {
    try {
      secret = Base64.getDecoder().decode(encodedSecret);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Worker offline lease secret must be base64", exception);
    }
    if (secret.length < 32) {
      throw new IllegalStateException("Worker offline lease secret must contain at least 32 bytes");
    }
  }

  public WorkerOfflineLease issue(
      UUID workerId, UUID warehouseId, long revision, OffsetDateTime issuedAt) {
    OffsetDateTime normalized = issuedAt.withOffsetSameInstant(ZoneOffset.UTC);
    UUID id = signedId(workerId, warehouseId, normalized.toInstant().toEpochMilli());
    return new WorkerOfflineLease(id, normalized, normalized.plus(LIFETIME), revision);
  }

  public void requireValid(
      UUID leaseId,
      UUID workerId,
      UUID warehouseId,
      OffsetDateTime occurredAt,
      OffsetDateTime serverNow) {
    Instant issuedAt = requireOwnedLease(leaseId, workerId, warehouseId);
    Instant expiresAt = issuedAt.plus(LIFETIME);
    Instant occurred = occurredAt.toInstant();
    if (occurred.isBefore(issuedAt) || occurred.isAfter(expiresAt)) {
      throw new ConflictException("Действие создано вне срока offline lease");
    }
    requireNotFuture(occurred, serverNow);
  }

  /**
   * Validates the signed lease for a delayed WorkerApp completion or its result evidence.
   *
   * <p>The 24-hour window still fences ordinary offline transitions such as take, join, pause and
   * resume. A worker who remains the current assigned participant may, however, submit the actual
   * result photo and close the already-started task after that window. The caller must still enforce
   * the current assignment, task state, expected version and evidence gate; this method only relaxes
   * the historical occurrence time while retaining cryptographic lease ownership and future-time
   * protection.
   */
  public void requireDeferredCompletionValid(
      UUID leaseId,
      UUID workerId,
      UUID warehouseId,
      OffsetDateTime occurredAt,
      OffsetDateTime serverNow) {
    requireOwnedLease(leaseId, workerId, warehouseId);
    requireNotFuture(occurredAt.toInstant(), serverNow);
  }

  private Instant requireOwnedLease(UUID leaseId, UUID workerId, UUID warehouseId) {
    long issuedAtMillis = leaseId.getMostSignificantBits() >>> 16;
    UUID expected = signedId(workerId, warehouseId, issuedAtMillis);
    if (!MessageDigest.isEqual(bytes(leaseId), bytes(expected))) {
      throw new ConflictException("Offline lease не принадлежит текущему рабочему");
    }
    return Instant.ofEpochMilli(issuedAtMillis);
  }

  private void requireNotFuture(Instant occurred, OffsetDateTime serverNow) {
    if (occurred.isAfter(serverNow.toInstant().plus(FUTURE_TOLERANCE))) {
      throw new ConflictException("Время offline-действия находится в будущем");
    }
  }

  private UUID signedId(UUID workerId, UUID warehouseId, long issuedAtMillis) {
    if ((issuedAtMillis & ~TIMESTAMP_MASK) != 0) {
      throw new IllegalArgumentException("Offline lease timestamp is outside UUID range");
    }
    byte[] signature = hmac(workerId, warehouseId, issuedAtMillis);
    long highSignature =
        ((long) (signature[0] & 0xff) << 4) | ((long) (signature[1] & 0xf0) >>> 4);
    long most = (issuedAtMillis << 16) | 0x7000L | highSignature;
    long lowSignature = ByteBuffer.wrap(signature, 8, 8).getLong() & SIGNATURE_LOW_MASK;
    return new UUID(most, RFC_4122_VARIANT | lowSignature);
  }

  private byte[] hmac(UUID workerId, UUID warehouseId, long issuedAtMillis) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      mac.update(workerId.toString().getBytes(StandardCharsets.US_ASCII));
      mac.update((byte) ':');
      mac.update(warehouseId.toString().getBytes(StandardCharsets.US_ASCII));
      mac.update((byte) ':');
      mac.update(ByteBuffer.allocate(Long.BYTES).putLong(issuedAtMillis).array());
      return mac.doFinal();
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("HmacSHA256 is unavailable", exception);
    }
  }

  private byte[] bytes(UUID value) {
    return ByteBuffer.allocate(16)
        .putLong(value.getMostSignificantBits())
        .putLong(value.getLeastSignificantBits())
        .array();
  }
}
