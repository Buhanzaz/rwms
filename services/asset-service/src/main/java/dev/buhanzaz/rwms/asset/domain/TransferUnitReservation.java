package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/**
 * Asset-owned historical reservation of one physical cabin by one transfer line.
 *
 * <p>Identity and the post-confirmation rental-item revision never change. A terminal row is kept
 * as evidence that release or departure was applied once.
 */
@Entity
@Table(name = "transfer_unit_reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransferUnitReservation {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "transfer_id", nullable = false)
  private UUID transferId;

  @Column(name = "line_id", nullable = false)
  private UUID lineId;

  @Column(name = "rental_item_id", nullable = false)
  private UUID rentalItemId;

  @Column(name = "source_warehouse_id", nullable = false)
  private UUID sourceWarehouseId;

  @Column(name = "reserved_rental_item_version", nullable = false)
  private long reservedRentalItemVersion;

  @Column(name = "terminal_rental_item_version")
  private Long terminalRentalItemVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private TransferUnitReservationState state;

  @Column(name = "confirmed_by_subject_id", nullable = false)
  private UUID confirmedBySubjectId;

  @Column(name = "confirmed_idempotency_key", nullable = false)
  private UUID confirmedIdempotencyKey;

  @Column(name = "terminal_by_subject_id")
  private UUID terminalBySubjectId;

  @Column(name = "terminal_idempotency_key")
  private UUID terminalIdempotencyKey;

  @Column(name = "confirmed_at", nullable = false)
  private OffsetDateTime confirmedAt;

  @Column(name = "terminal_at")
  private OffsetDateTime terminalAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  /** Creates immutable ownership evidence after the cabin status has become RESERVED. */
  public static TransferUnitReservation confirm(
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      long reservedRentalItemVersion,
      UUID subjectId,
      UUID idempotencyKey) {
    if (reservedRentalItemVersion < 0) {
      throw new IllegalArgumentException("reservedRentalItemVersion must not be negative");
    }
    OffsetDateTime timestamp = now();
    TransferUnitReservation reservation = new TransferUnitReservation();
    reservation.transferId = Objects.requireNonNull(transferId, "transferId");
    reservation.lineId = Objects.requireNonNull(lineId, "lineId");
    reservation.rentalItemId = Objects.requireNonNull(rentalItemId, "rentalItemId");
    reservation.sourceWarehouseId =
        Objects.requireNonNull(sourceWarehouseId, "sourceWarehouseId");
    reservation.reservedRentalItemVersion = reservedRentalItemVersion;
    reservation.state = TransferUnitReservationState.ACTIVE;
    reservation.confirmedBySubjectId = Objects.requireNonNull(subjectId, "subjectId");
    reservation.confirmedIdempotencyKey =
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    reservation.confirmedAt = timestamp;
    reservation.updatedAt = timestamp;
    return reservation;
  }

  /** Releases this exact active owner and records the FREE rental-item revision produced. */
  public void release(
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      long terminalRentalItemVersion,
      UUID subjectId,
      UUID idempotencyKey) {
    requireActiveOwner(transferId, lineId, rentalItemId);
    finish(
        TransferUnitReservationState.RELEASED,
        terminalRentalItemVersion,
        subjectId,
        idempotencyKey);
  }

  /** Consumes this exact active owner when the fenced transfer departure starts. */
  public void consume(
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      long terminalRentalItemVersion,
      UUID subjectId,
      UUID idempotencyKey) {
    requireActiveOwner(transferId, lineId, rentalItemId);
    finish(
        TransferUnitReservationState.CONSUMED,
        terminalRentalItemVersion,
        subjectId,
        idempotencyKey);
  }

  /** Returns whether this historical row belongs to the exact transfer document line and cabin. */
  public boolean isOwnedBy(UUID transferId, UUID lineId, UUID rentalItemId) {
    return Objects.equals(this.transferId, transferId)
        && Objects.equals(this.lineId, lineId)
        && Objects.equals(this.rentalItemId, rentalItemId);
  }

  private void requireActiveOwner(UUID transferId, UUID lineId, UUID rentalItemId) {
    if (!isOwnedBy(transferId, lineId, rentalItemId)) {
      throw new IllegalStateException("Transfer unit reservation belongs to another transfer line");
    }
    if (state != TransferUnitReservationState.ACTIVE) {
      throw new IllegalStateException("Transfer unit reservation is no longer active");
    }
  }

  private void finish(
      TransferUnitReservationState terminalState,
      long terminalRentalItemVersion,
      UUID subjectId,
      UUID idempotencyKey) {
    if (terminalRentalItemVersion < 0) {
      throw new IllegalArgumentException("terminalRentalItemVersion must not be negative");
    }
    OffsetDateTime timestamp = now();
    state = Objects.requireNonNull(terminalState, "terminalState");
    this.terminalRentalItemVersion = terminalRentalItemVersion;
    terminalBySubjectId = Objects.requireNonNull(subjectId, "subjectId");
    terminalIdempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    terminalAt = timestamp;
    updatedAt = timestamp;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
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
        && Objects.equals(id, ((TransferUnitReservation) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
