package dev.buhanzaz.rwms.logistics.driver.settings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/**
 * Warehouse-scoped logistics policy that caps how many cabins a newly created shipment task may
 * contain.
 *
 * <p>The row is created lazily with the compatibility default of one cabin. Existing documents
 * are deliberately not regrouped when a warehouse changes this policy.</p>
 */
@Entity
@Table(name = "shipment_task_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShipmentTaskSettings {
  /** Default retained for warehouses that have not yet configured grouped shipment work. */
  public static final int DEFAULT_MAX_CABINS_PER_SHIPMENT_TASK = 1;

  /** Largest valid warehouse-local shipment task group. */
  public static final int MAXIMUM_MAX_CABINS_PER_SHIPMENT_TASK = 100;

  @Id
  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "max_cabins_per_shipment_task", nullable = false)
  private int maxCabinsPerShipmentTask;

  @Column(name = "updated_by_subject_id", nullable = false)
  private UUID updatedBySubjectId;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  /** Applies one version-fenced administrator-selected shipment-group cap. */
  public void update(int maximum, UUID actorSubjectId, OffsetDateTime now) {
    requireMaximum(maximum);
    maxCabinsPerShipmentTask = maximum;
    updatedBySubjectId = Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    updatedAt = Objects.requireNonNull(now, "now");
  }

  /** Validates a configured maximum independently of the HTTP transport. */
  public static void requireMaximum(int value) {
    if (value < DEFAULT_MAX_CABINS_PER_SHIPMENT_TASK
        || value > MAXIMUM_MAX_CABINS_PER_SHIPMENT_TASK) {
      throw new IllegalArgumentException(
          "maxCabinsPerShipmentTask must be between "
              + DEFAULT_MAX_CABINS_PER_SHIPMENT_TASK
              + " and "
              + MAXIMUM_MAX_CABINS_PER_SHIPMENT_TASK);
    }
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
        && warehouseId != null
        && Objects.equals(warehouseId, ((ShipmentTaskSettings) other).warehouseId);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
