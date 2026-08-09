package dev.buhanzaz.rwms.logistics.inquiry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;

/**
 * Durable subject-scoped receipt that freezes one exact asset hold replace or release command.
 *
 * <p>The receipt contains command and response evidence, never a second copy of authoritative hold
 * state. An unknown remote outcome remains PREPARED so a retry uses the same body, command deadline
 * and (for replace) hold expiry.
 */
@Entity
@Table(
    name = "rental_inquiry_selection_receipt",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_rental_inquiry_selection_receipt_subject_key",
            columnNames = {"subject_id", "public_idempotency_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RentalInquirySelectionReceipt {
  private static final Set<String> ACTOR_ROLES =
      Set.of("SYSTEM_ADMIN", "WMS_ADMIN", "WAREHOUSE_MANAGER", "RENTAL_MANAGER", "VIEWER");

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "inquiry_id", nullable = false)
  private UUID inquiryId;

  @Column(name = "subject_id", nullable = false)
  private UUID subjectId;

  @Column(name = "public_idempotency_key", nullable = false)
  private UUID publicIdempotencyKey;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Enumerated(EnumType.STRING)
  @Column(name = "command_type", nullable = false, length = 16)
  private RentalInquirySelectionCommandType commandType;

  @Column(name = "downstream_request_body", nullable = false, columnDefinition = "text")
  private String downstreamRequestBody;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "downstream_request_sha256", nullable = false, length = 64)
  private String downstreamRequestSha256;

  @Column(name = "actor_role", nullable = false, length = 32)
  private String actorRole;

  @Column(name = "command_expires_at", nullable = false)
  private OffsetDateTime commandExpiresAt;

  @Column(name = "hold_expires_at")
  private OffsetDateTime holdExpiresAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private RentalInquirySelectionReceiptState state;

  @Column(name = "response_body", columnDefinition = "text")
  private String responseBody;

  @Column(name = "rejection_code", length = 64)
  private String rejectionCode;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Column(name = "terminal_at")
  private OffsetDateTime terminalAt;

  /** Creates an immutable PREPARED command receipt. */
  public static RentalInquirySelectionReceipt prepare(
      UUID inquiryId,
      UUID subjectId,
      UUID publicIdempotencyKey,
      String requestSha256,
      UUID warehouseId,
      RentalInquirySelectionCommandType commandType,
      String downstreamRequestBody,
      String downstreamRequestSha256,
      String actorRole,
      OffsetDateTime commandExpiresAt,
      OffsetDateTime holdExpiresAt,
      OffsetDateTime now) {
    RentalInquirySelectionCommandType requiredType =
        Objects.requireNonNull(commandType, "commandType");
    OffsetDateTime timestamp = Objects.requireNonNull(now, "now");
    OffsetDateTime commandExpiry = Objects.requireNonNull(commandExpiresAt, "commandExpiresAt");
    if (!commandExpiry.isAfter(timestamp)
        || (requiredType == RentalInquirySelectionCommandType.REPLACE
            && !commandExpiry.equals(holdExpiresAt))
        || (requiredType == RentalInquirySelectionCommandType.RELEASE && holdExpiresAt != null)) {
      throw new IllegalArgumentException("Selection expiry does not match commandType");
    }
    RentalInquirySelectionReceipt receipt = new RentalInquirySelectionReceipt();
    receipt.inquiryId = Objects.requireNonNull(inquiryId, "inquiryId");
    receipt.subjectId = Objects.requireNonNull(subjectId, "subjectId");
    receipt.publicIdempotencyKey =
        Objects.requireNonNull(publicIdempotencyKey, "publicIdempotencyKey");
    receipt.requestSha256 = requireHash(requestSha256, "requestSha256");
    receipt.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    receipt.commandType = requiredType;
    receipt.downstreamRequestBody = requireRequestBody(downstreamRequestBody);
    receipt.downstreamRequestSha256 =
        requireHash(downstreamRequestSha256, "downstreamRequestSha256");
    receipt.actorRole = requireActorRole(actorRole);
    receipt.commandExpiresAt = commandExpiry;
    receipt.holdExpiresAt = holdExpiresAt;
    receipt.state = RentalInquirySelectionReceiptState.PREPARED;
    receipt.createdAt = timestamp;
    receipt.updatedAt = receipt.createdAt;
    return receipt;
  }

  /** Returns whether this key was prepared from the same canonical public command. */
  public boolean matchesRequest(String hash) {
    return requestSha256.equals(hash);
  }

  /** Returns whether this prepared replace or release command can no longer be retried. */
  public boolean commandExpiredAt(OffsetDateTime now) {
    return !commandExpiresAt.isAfter(Objects.requireNonNull(now, "now"));
  }

  /** Freezes the validated public response after a successful remote effect. */
  public void complete(String frozenResponseBody, OffsetDateTime now) {
    requirePrepared();
    OffsetDateTime timestamp = Objects.requireNonNull(now, "now");
    if (commandExpiredAt(timestamp)) {
      throw new IllegalStateException("Expired cabin selection cannot be completed");
    }
    responseBody = requireResponseBody(frozenResponseBody);
    state = RentalInquirySelectionReceiptState.COMPLETED;
    updatedAt = timestamp;
    terminalAt = updatedAt;
  }

  /** Records one sanitized confirmed remote rejection. */
  public void reject(String safeCode, OffsetDateTime now) {
    requirePrepared();
    OffsetDateTime timestamp = Objects.requireNonNull(now, "now");
    if (commandExpiredAt(timestamp)) {
      throw new IllegalStateException("Expired cabin selection cannot be rejected");
    }
    rejectionCode = requireCode(safeCode);
    state = RentalInquirySelectionReceiptState.REJECTED;
    updatedAt = timestamp;
    terminalAt = updatedAt;
  }

  /** Releases the one-PREPARED-per-inquiry slot after its frozen command lifetime. */
  public void expire(OffsetDateTime now) {
    requirePrepared();
    OffsetDateTime timestamp = Objects.requireNonNull(now, "now");
    if (!commandExpiredAt(timestamp)) {
      throw new IllegalStateException("A live selection receipt cannot be expired");
    }
    state = RentalInquirySelectionReceiptState.EXPIRED;
    updatedAt = timestamp;
    terminalAt = timestamp;
  }

  private void requirePrepared() {
    if (state != RentalInquirySelectionReceiptState.PREPARED) {
      throw new IllegalStateException("Selection receipt is already terminal");
    }
  }

  private static String requireHash(String value, String name) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be a SHA-256 digest");
    }
    return value;
  }

  private static String requireRequestBody(String value) {
    if (value == null
        || value.length() < 2
        || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65_535) {
      throw new IllegalArgumentException("Stored selection request JSON is invalid");
    }
    return value;
  }

  private static String requireResponseBody(String value) {
    if (value == null || value.length() < 2) {
      throw new IllegalArgumentException("Stored selection response JSON is invalid");
    }
    return value;
  }

  private static String requireActorRole(String value) {
    if (!ACTOR_ROLES.contains(value)) {
      throw new IllegalArgumentException("actorRole is invalid");
    }
    return value;
  }

  private static String requireCode(String value) {
    if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,63}")) {
      throw new IllegalArgumentException("rejectionCode is invalid");
    }
    return value;
  }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    Class<?> otherClass =
        other instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : other.getClass();
    Class<?> thisClass =
        this instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : getClass();
    return thisClass == otherClass
        && id != null
        && Objects.equals(id, ((RentalInquirySelectionReceipt) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
