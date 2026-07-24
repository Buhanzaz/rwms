package dev.buhanzaz.rwms.logistics.domain;

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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
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
          columnNames = "equipment_movement_task_id")
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

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "document_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_shipment_furniture_movement_task_document"))
  private LogisticsDocument document;

  @Column(name = "rental_item_id", nullable = false)
  private UUID rentalItemId;

  @Column(name = "unit_number", nullable = false, length = 64)
  private String unitNumber;

  @Column(name = "equipment_movement_task_id", nullable = false)
  private UUID equipmentMovementTaskId;

  @Column(name = "line_count", nullable = false)
  private int lineCount;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

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

  private static String requireText(String value, int maximum, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is invalid");
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
        && Objects.equals(id, ((ShipmentFurnitureMovementTask) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
