package dev.buhanzaz.rwms.logistics.domain;

import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;

/** Links one shipment cabin to the worker task that reconciles its furniture. */
@Entity
@Table(
    name = "shipment_furniture_movement_task",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_shipment_furniture_movement_task_unit",
          columnNames = {"document_id", "rental_item_id"}),
      @UniqueConstraint(
          name = "uk_shipment_furniture_movement_task_task",
          columnNames = "equipment_movement_task_id"),
      @UniqueConstraint(
          name = "uk_shipment_furniture_movement_task_replacement_key",
          columnNames = {"order_id", "replacement_idempotency_key"}),
      @UniqueConstraint(
          name = "uk_shipment_furniture_movement_task_replacement_batch_pair",
          columnNames = {"order_id", "replacement_batch_idempotency_key", "replacement_pair_index"})
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShipmentFurnitureMovementTask {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "document_id",
      foreignKey = @ForeignKey(name = "fk_shipment_furniture_movement_task_document"))
  private LogisticsDocument document;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "order_id",
      foreignKey = @ForeignKey(name = "fk_shipment_furniture_movement_task_order"))
  private RentalOrder order;

  @Column(name = "old_rental_item_id")
  private UUID oldRentalItemId;

  @Column(name = "rental_item_id", nullable = false)
  private UUID rentalItemId;

  @Column(name = "unit_number", nullable = false, length = 64)
  private String unitNumber;

  @Column(name = "equipment_movement_task_id")
  private UUID equipmentMovementTaskId;

  @Column(name = "line_count", nullable = false)
  private int lineCount;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "replacement_reason", length = 2000)
  private String replacementReason;

  @Column(name = "replacement_actor_subject_id")
  private UUID replacementActorSubjectId;

  @Column(name = "replacement_actor_role", length = 32)
  private String replacementActorRole;

  @Column(name = "replacement_idempotency_key")
  private UUID replacementIdempotencyKey;

  @Column(name = "replacement_batch_idempotency_key")
  private UUID replacementBatchIdempotencyKey;

  @Column(name = "replacement_pair_index")
  private Integer replacementPairIndex;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "replacement_request_sha256", length = 64)
  private String replacementRequestSha256;

  @Column(name = "replacement_presentation_id")
  private UUID replacementPresentationId;

  /** Physical warehouse whose furniture and replacement cabin are prepared. */
  @Column(name = "replacement_inventory_source_warehouse_id")
  private UUID replacementInventorySourceWarehouseId;

  @Column(name = "replacement_source_reservation_id")
  private UUID replacementSourceReservationId;

  @Column(name = "replacement_completed_at")
  private OffsetDateTime replacementCompletedAt;

  @Column(name = "replacement_rejected_at")
  private OffsetDateTime replacementRejectedAt;

  public static ShipmentFurnitureMovementTask create(
      LogisticsDocument document,
      UUID rentalItemId,
      String unitNumber,
      UUID equipmentMovementTaskId,
      int lineCount) {
    if (lineCount < 1) {
      throw new IllegalArgumentException("lineCount is invalid");
    }
    ShipmentFurnitureMovementTask link = new ShipmentFurnitureMovementTask();
    link.document = Objects.requireNonNull(document, "document");
    link.rentalItemId = Objects.requireNonNull(rentalItemId, "rentalItemId");
    link.unitNumber = requireText(unitNumber, 64, "unitNumber");
    link.equipmentMovementTaskId =
        Objects.requireNonNull(equipmentMovementTaskId, "equipmentMovementTaskId");
    link.lineCount = lineCount;
    link.createdAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    return link;
  }

  /**
   * Creates a durable replacement checkpoint before the asset-side atomic swap. The linked movement
   * task is absent only when asset-service proved that no physical furniture move is required.
   */
  public static ShipmentFurnitureMovementTask createReplacement(
      RentalOrder order,
      LogisticsDocument document,
      UUID oldRentalItemId,
      UUID replacementRentalItemId,
      String replacementUnitNumber,
      UUID equipmentMovementTaskId,
      int lineCount,
      String reason,
      UUID actorSubjectId,
      String actorRole,
      UUID idempotencyKey,
      UUID batchIdempotencyKey,
      int pairIndex,
      String requestSha256,
      UUID presentationId,
      OffsetDateTime now) {
    return createReplacement(
        order,
        document,
        oldRentalItemId,
        replacementRentalItemId,
        replacementUnitNumber,
        equipmentMovementTaskId,
        lineCount,
        reason,
        actorSubjectId,
        actorRole,
        idempotencyKey,
        batchIdempotencyKey,
        pairIndex,
        requestSha256,
        presentationId,
        order == null ? null : order.getWarehouseId(),
        now);
  }

  /** Creates a replacement checkpoint with its physical inventory source frozen for recovery. */
  public static ShipmentFurnitureMovementTask createReplacement(
      RentalOrder order,
      LogisticsDocument document,
      UUID oldRentalItemId,
      UUID replacementRentalItemId,
      String replacementUnitNumber,
      UUID equipmentMovementTaskId,
      int lineCount,
      String reason,
      UUID actorSubjectId,
      String actorRole,
      UUID idempotencyKey,
      UUID batchIdempotencyKey,
      int pairIndex,
      String requestSha256,
      UUID presentationId,
      UUID inventorySourceWarehouseId,
      OffsetDateTime now) {
    if (lineCount < 0 || (lineCount == 0) != (equipmentMovementTaskId == null)) {
      throw new IllegalArgumentException("Replacement movement identity is invalid");
    }
    ShipmentFurnitureMovementTask link = new ShipmentFurnitureMovementTask();
    link.order = Objects.requireNonNull(order, "order");
    link.document = document;
    link.oldRentalItemId = Objects.requireNonNull(oldRentalItemId, "oldRentalItemId");
    link.rentalItemId = Objects.requireNonNull(replacementRentalItemId, "replacementRentalItemId");
    if (link.oldRentalItemId.equals(link.rentalItemId)) {
      throw new IllegalArgumentException("Replacement cabin must differ from the old cabin");
    }
    link.unitNumber = requireText(replacementUnitNumber, 64, "unitNumber");
    link.equipmentMovementTaskId = equipmentMovementTaskId;
    link.lineCount = lineCount;
    link.replacementReason = optionalText(reason, 2000, "reason");
    link.replacementActorSubjectId = Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    link.replacementActorRole = requireText(actorRole, 32, "actorRole");
    link.replacementIdempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    link.replacementBatchIdempotencyKey =
        Objects.requireNonNull(batchIdempotencyKey, "batchIdempotencyKey");
    if (pairIndex < 0) {
      throw new IllegalArgumentException("pairIndex is invalid");
    }
    link.replacementPairIndex = pairIndex;
    link.replacementRequestSha256 = requireHash(requestSha256);
    link.replacementPresentationId = presentationId;
    link.replacementInventorySourceWarehouseId =
        Objects.requireNonNull(inventorySourceWarehouseId, "inventorySourceWarehouseId");
    link.createdAt = Objects.requireNonNull(now, "now");
    return link;
  }

  public boolean isReplacement() {
    return replacementIdempotencyKey != null;
  }

  public boolean matchesReplacementRequest(String requestSha256) {
    return Objects.equals(replacementRequestSha256, requestSha256);
  }

  public boolean isReplacementPending() {
    return isReplacement() && replacementCompletedAt == null && replacementRejectedAt == null;
  }

  public void completeReplacement(UUID releasedSourceReservationId, OffsetDateTime now) {
    if (!isReplacement() || replacementRejectedAt != null) {
      throw new IllegalStateException("Replacement checkpoint cannot be completed");
    }
    if ((equipmentMovementTaskId == null) != (releasedSourceReservationId == null)) {
      throw new IllegalArgumentException("Replacement source reservation identity is invalid");
    }
    if (replacementSourceReservationId != null
        && !replacementSourceReservationId.equals(releasedSourceReservationId)) {
      throw new IllegalStateException("Replacement source reservation identity changed");
    }
    replacementSourceReservationId = releasedSourceReservationId;
    if (replacementCompletedAt == null) replacementCompletedAt = Objects.requireNonNull(now, "now");
    if (equipmentMovementTaskId == null) document = null;
  }

  public void rejectReplacement(OffsetDateTime now) {
    if (!isReplacement() || replacementCompletedAt != null) {
      throw new IllegalStateException("Replacement checkpoint cannot be rejected");
    }
    if (replacementRejectedAt == null) replacementRejectedAt = Objects.requireNonNull(now, "now");
    document = null;
  }

  /**
   * Attaches a completed order-level replacement movement to the later shipment that contains its
   * replacement cabin. The movement task remains the same durable task and is never duplicated.
   */
  public void attachToShipment(LogisticsDocument shipment) {
    LogisticsDocument required = Objects.requireNonNull(shipment, "shipment");
    if (!isReplacement()
        || replacementCompletedAt == null
        || replacementRejectedAt != null
        || equipmentMovementTaskId == null
        || order == null
        || required.getDocumentType() != LogisticsDocumentType.SHIPMENT
        || !Objects.equals(order.getId(), required.getRentalOrderId())) {
      throw new IllegalStateException("Replacement movement cannot be attached to shipment");
    }
    if (document != null && !Objects.equals(document.getId(), required.getId())) {
      throw new IllegalStateException("Replacement movement already belongs to another shipment");
    }
    document = required;
  }

  private static String requireText(String value, int maximum, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private static String optionalText(String value, int maximum, String field) {
    if (value == null) return null;
    String normalized = value.trim();
    if (normalized.isEmpty()) return null;
    if (normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private static String requireHash(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("requestSha256 is invalid");
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
        && Objects.equals(id, ((ShipmentFurnitureMovementTask) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
