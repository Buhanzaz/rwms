package dev.buhanzaz.rwms.logistics.inquiry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;

/** JPA persistence model for Client Presentation in the logistics-owned database. */
@Entity
@Table(name = "client_presentation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClientPresentation {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "inquiry_id", nullable = false)
  private UUID inquiryId;

  @Column(name = "revision", nullable = false)
  private long revision;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Enumerated(EnumType.STRING)
  @Column(name = "mode", nullable = false, length = 16)
  private ClientPresentationMode mode;

  @JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  @Column(name = "replacement_unit_ids_json", nullable = false, columnDefinition = "jsonb")
  private String replacementUnitIdsJson;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 24)
  private ClientPresentationState state;

  @Column(name = "expires_at", nullable = false)
  private OffsetDateTime expiresAt;

  @Column(name = "view_until", nullable = false)
  private OffsetDateTime viewUntil;

  @Column(name = "booked_order_id")
  private UUID bookedOrderId;

  @Column(name = "last_publish_idempotency_key", nullable = false)
  private UUID lastPublishIdempotencyKey;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "last_publish_request_sha256", nullable = false, length = 64)
  private String lastPublishRequestSha256;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Column(name = "booked_at")
  private OffsetDateTime bookedAt;

  public static ClientPresentation create(
      UUID inquiryId,
      UUID warehouseId,
      ClientPresentationMode mode,
      String replacementUnitIdsJson,
      OffsetDateTime expiresAt,
      OffsetDateTime viewUntil,
      UUID idempotencyKey,
      String requestSha256,
      OffsetDateTime now) {
    ClientPresentation presentation = new ClientPresentation();
    presentation.inquiryId = Objects.requireNonNull(inquiryId, "inquiryId");
    presentation.revision = 1;
    presentation.activate(
        warehouseId,
        mode,
        replacementUnitIdsJson,
        expiresAt,
        viewUntil,
        idempotencyKey,
        requestSha256,
        now);
    presentation.createdAt = now;
    return presentation;
  }

  public void replace(
      UUID nextWarehouseId,
      ClientPresentationMode nextMode,
      String nextReplacementUnitIdsJson,
      OffsetDateTime nextExpiresAt,
      OffsetDateTime nextViewUntil,
      UUID idempotencyKey,
      String requestSha256,
      OffsetDateTime now) {
    if (state == ClientPresentationState.BOOKING_PENDING
        || state == ClientPresentationState.BOOKED) {
      throw new IllegalStateException("A presentation being booked cannot be replaced");
    }
    revision = Math.addExact(revision, 1);
    activate(
        nextWarehouseId,
        nextMode,
        nextReplacementUnitIdsJson,
        nextExpiresAt,
        nextViewUntil,
        idempotencyKey,
        requestSha256,
        now);
    bookedOrderId = null;
    bookedAt = null;
  }

  public void markBookingPending(UUID orderId, OffsetDateTime now) {
    if (!canConfirm(now)) {
      throw new IllegalStateException("Presentation can no longer be booked");
    }
    state = ClientPresentationState.BOOKING_PENDING;
    bookedOrderId = Objects.requireNonNull(orderId, "orderId");
    updatedAt = now;
  }

  public void resumeAfterTransientFailure(OffsetDateTime now) {
    if (state == ClientPresentationState.BOOKING_PENDING) {
      updatedAt = now;
    }
  }

  public void markBooked(UUID orderId, OffsetDateTime now) {
    if (state == ClientPresentationState.BOOKED && Objects.equals(bookedOrderId, orderId)) return;
    if (state != ClientPresentationState.BOOKING_PENDING) {
      throw new IllegalStateException("Presentation booking is not pending");
    }
    state = ClientPresentationState.BOOKED;
    bookedOrderId = Objects.requireNonNull(orderId, "orderId");
    bookedAt = now;
    updatedAt = now;
  }

  public void revoke(OffsetDateTime now) {
    if (state == ClientPresentationState.BOOKED) return;
    state = ClientPresentationState.REVOKED;
    updatedAt = now;
  }

  public boolean canConfirm(OffsetDateTime now) {
    return state == ClientPresentationState.ACTIVE && expiresAt.isAfter(now);
  }

  public boolean isViewable(OffsetDateTime now) {
    return state != ClientPresentationState.REVOKED && viewUntil.isAfter(now);
  }

  public boolean matchesPublish(UUID idempotencyKey, String requestSha256) {
    return lastPublishIdempotencyKey.equals(idempotencyKey)
        && lastPublishRequestSha256.equals(requestSha256);
  }

  public boolean hasPublishKey(UUID idempotencyKey) {
    return lastPublishIdempotencyKey.equals(idempotencyKey);
  }

  private void activate(
      UUID nextWarehouseId,
      ClientPresentationMode nextMode,
      String nextReplacementUnitIdsJson,
      OffsetDateTime nextExpiresAt,
      OffsetDateTime nextViewUntil,
      UUID idempotencyKey,
      String requestSha256,
      OffsetDateTime now) {
    if (!Objects.requireNonNull(nextExpiresAt, "expiresAt").isAfter(now)
        || !Objects.requireNonNull(nextViewUntil, "viewUntil").isAfter(nextExpiresAt)) {
      throw new IllegalArgumentException("Presentation lifetime is invalid");
    }
    warehouseId = Objects.requireNonNull(nextWarehouseId, "warehouseId");
    mode = Objects.requireNonNull(nextMode, "mode");
    replacementUnitIdsJson = requireReplacementJson(nextReplacementUnitIdsJson);
    state = ClientPresentationState.ACTIVE;
    expiresAt = nextExpiresAt;
    viewUntil = nextViewUntil;
    lastPublishIdempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    lastPublishRequestSha256 = requireHash(requestSha256);
    updatedAt = now;
  }

  private static String requireHash(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("requestSha256 is invalid");
    }
    return value;
  }

  private static String requireReplacementJson(String value) {
    String normalized = value == null ? "" : value.trim();
    if (!normalized.startsWith("[") || !normalized.endsWith("]") || normalized.length() > 4_000) {
      throw new IllegalArgumentException("replacementUnitIdsJson is invalid");
    }
    return normalized;
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
        && Objects.equals(id, ((ClientPresentation) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
