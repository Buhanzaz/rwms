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
 * Durable logistics-owned receipt for one subject-scoped rental-inquiry cabin search.
 *
 * <p>The receipt freezes the exact asset-service request text and downstream idempotency key while
 * it is {@link RentalInquirySearchAttemptState#PREPARED}. Only a frozen successful response or a
 * sanitized terminal classification may replace that prepared state.
 */
@Entity
@Table(
    name = "rental_inquiry_search_attempt",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_rental_inquiry_search_attempt_subject_operation_key",
          columnNames = {"subject_id", "operation_name", "public_idempotency_key"}),
      @UniqueConstraint(
          name = "uk_rental_inquiry_search_attempt_downstream_key",
          columnNames = "downstream_idempotency_key")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RentalInquirySearchAttempt {
  /** Stable operation discriminator used in the subject-scoped receipt key. */
  public static final String OPERATION = "RENTAL_INQUIRY_CABIN_SEARCH";

  private static final Set<String> ACTOR_ROLES =
      Set.of(
          "SYSTEM_ADMIN",
          "WMS_ADMIN",
          "WAREHOUSE_MANAGER",
          "RENTAL_MANAGER",
          "VIEWER");

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

  @Column(name = "operation_name", nullable = false, length = 64)
  private String operationName;

  @Column(name = "public_idempotency_key", nullable = false)
  private UUID publicIdempotencyKey;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "downstream_idempotency_key", nullable = false)
  private UUID downstreamIdempotencyKey;

  @Column(name = "downstream_request_body", nullable = false, columnDefinition = "text")
  private String downstreamRequestBody;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "downstream_request_sha256", nullable = false, length = 64)
  private String downstreamRequestSha256;

  @Column(name = "actor_role", nullable = false, length = 32)
  private String actorRole;

  @Column(name = "hold_expires_at", nullable = false)
  private OffsetDateTime holdExpiresAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private RentalInquirySearchAttemptState state;

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

  /** Creates a prepared receipt whose downstream identity and request cannot be changed later. */
  public static RentalInquirySearchAttempt prepare(
      UUID inquiryId,
      UUID subjectId,
      UUID publicIdempotencyKey,
      String requestSha256,
      UUID warehouseId,
      UUID downstreamIdempotencyKey,
      String downstreamRequestBody,
      String downstreamRequestSha256,
      String actorRole,
      OffsetDateTime holdExpiresAt,
      OffsetDateTime now) {
    OffsetDateTime timestamp = Objects.requireNonNull(now, "now");
    OffsetDateTime expiry = Objects.requireNonNull(holdExpiresAt, "holdExpiresAt");
    if (!expiry.isAfter(timestamp)) {
      throw new IllegalArgumentException("holdExpiresAt must be after creation");
    }
    String requestBody = requireBody(downstreamRequestBody);
    RentalInquirySearchAttempt attempt = new RentalInquirySearchAttempt();
    attempt.inquiryId = Objects.requireNonNull(inquiryId, "inquiryId");
    attempt.subjectId = Objects.requireNonNull(subjectId, "subjectId");
    attempt.operationName = OPERATION;
    attempt.publicIdempotencyKey =
        Objects.requireNonNull(publicIdempotencyKey, "publicIdempotencyKey");
    attempt.requestSha256 = requireHash(requestSha256, "requestSha256");
    attempt.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    attempt.downstreamIdempotencyKey =
        Objects.requireNonNull(downstreamIdempotencyKey, "downstreamIdempotencyKey");
    attempt.downstreamRequestBody = requestBody;
    attempt.downstreamRequestSha256 =
        requireHash(downstreamRequestSha256, "downstreamRequestSha256");
    attempt.actorRole = requireActorRole(actorRole);
    attempt.holdExpiresAt = expiry;
    attempt.state = RentalInquirySearchAttemptState.PREPARED;
    attempt.createdAt = timestamp;
    attempt.updatedAt = timestamp;
    return attempt;
  }

  /** Returns whether this public key was originally prepared for the same canonical request. */
  public boolean matchesRequest(String hash) {
    return requestSha256.equals(hash);
  }

  /** Returns whether the prepared command is no longer allowed to create a live remote hold. */
  public boolean holdExpiredAt(OffsetDateTime now) {
    return !holdExpiresAt.isAfter(Objects.requireNonNull(now, "now"));
  }

  /** Freezes the successful response after validating that the receipt is still resumable. */
  public void complete(String frozenResponseBody, OffsetDateTime now) {
    requirePrepared();
    OffsetDateTime timestamp = Objects.requireNonNull(now, "now");
    if (holdExpiredAt(timestamp)) {
      throw new IllegalStateException("Expired cabin search cannot be completed");
    }
    responseBody = requireResponseBody(frozenResponseBody);
    state = RentalInquirySearchAttemptState.COMPLETED;
    updatedAt = timestamp;
    terminalAt = timestamp;
  }

  /** Ends the attempt with one sanitized, proven domain-semantic rejection code. */
  public void reject(String safeCode, OffsetDateTime now) {
    requirePrepared();
    rejectionCode = requireCode(safeCode);
    state = RentalInquirySearchAttemptState.REJECTED;
    updatedAt = Objects.requireNonNull(now, "now");
    terminalAt = updatedAt;
  }

  /** Releases the prepared slot only after the stored hold lifetime has elapsed. */
  public void expire(OffsetDateTime now) {
    if (state == RentalInquirySearchAttemptState.EXPIRED) return;
    requirePrepared();
    OffsetDateTime timestamp = Objects.requireNonNull(now, "now");
    if (!holdExpiredAt(timestamp)) {
      throw new IllegalStateException("A live cabin search attempt cannot be expired");
    }
    state = RentalInquirySearchAttemptState.EXPIRED;
    updatedAt = timestamp;
    terminalAt = timestamp;
  }

  private void requirePrepared() {
    if (state != RentalInquirySearchAttemptState.PREPARED) {
      throw new IllegalStateException("Cabin search receipt is already terminal");
    }
  }

  private static String requireHash(String value, String name) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be a SHA-256 digest");
    }
    return value;
  }

  private static String requireBody(String value) {
    if (value == null
        || value.length() < 2
        || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65_535) {
      throw new IllegalArgumentException("Stored cabin search JSON is invalid");
    }
    return value;
  }

  private static String requireResponseBody(String value) {
    if (value == null || value.length() < 2) {
      throw new IllegalArgumentException("Stored cabin search response JSON is invalid");
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
        && Objects.equals(id, ((RentalInquirySearchAttempt) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
