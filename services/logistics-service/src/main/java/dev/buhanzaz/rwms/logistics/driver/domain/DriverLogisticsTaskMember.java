package dev.buhanzaz.rwms.logistics.driver.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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

/**
 * Immutable cabin membership snapshot for one grouped document driver task, including the per-cabin
 * completion-cover checkpoint.
 *
 * <p>Asset ownership remains external. This row only retains the cabin and document-line identities
 * needed to make one completion evidence item safely cover every cabin in the group.
 */
@Entity
@Table(
    name = "driver_logistics_task_member",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_driver_logistics_task_member_line",
          columnNames = {"document_line_id"}),
      @UniqueConstraint(
          name = "uk_driver_logistics_task_member_cabin",
          columnNames = {"driver_task_id", "cabin_id"})
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DriverLogisticsTaskMember {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "driver_task_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_driver_logistics_task_member_task"))
  private DriverLogisticsTask task;

  @Column(name = "document_line_id", nullable = false)
  private UUID documentLineId;

  @Column(name = "cabin_id", nullable = false)
  private UUID cabinId;

  @Column(name = "unit_number", nullable = false, length = 64)
  private String unitNumber;

  @Column(name = "position", nullable = false)
  private int position;

  @Column(name = "cover_applied", nullable = false)
  private boolean coverApplied;

  @Column(name = "cover_media_id")
  private UUID coverMediaId;

  @Column(name = "cover_entry_id")
  private UUID coverEntryId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  /** Creates one immutable cabin member before its owning task is persisted. */
  static DriverLogisticsTaskMember create(
      DriverLogisticsTask task,
      UUID documentLineId,
      UUID cabinId,
      String unitNumber,
      int position) {
    if (task == null || documentLineId == null || cabinId == null || position < 1) {
      throw new IllegalArgumentException("Grouped document member identity is invalid");
    }
    DriverLogisticsTaskMember member = new DriverLogisticsTaskMember();
    member.task = task;
    member.documentLineId = documentLineId;
    member.cabinId = cabinId;
    member.unitNumber = requiredUnitNumber(unitNumber);
    member.position = position;
    return member;
  }

  /** Marks one cabin's cover effect exactly once for the immutable completion evidence item. */
  void markCoverApplied(UUID mediaId, UUID entryId) {
    if (mediaId == null || entryId == null) {
      throw new IllegalArgumentException("Grouped shipment member cover evidence is required");
    }
    if (coverApplied) {
      if (!mediaId.equals(coverMediaId) || !entryId.equals(coverEntryId)) {
        throw new IllegalStateException("Grouped shipment member cover cannot be replaced");
      }
      return;
    }
    coverApplied = true;
    coverMediaId = mediaId;
    coverEntryId = entryId;
  }

  /** Refreshes an unstarted grouped-trip member after a same-document cabin replacement. */
  public void replaceCabin(
      UUID expectedOldCabinId, UUID replacementCabinId, String replacementUnitNumber) {
    if (coverApplied || !cabinId.equals(expectedOldCabinId)) {
      throw new IllegalStateException("Only an unstarted matching trip member can be replaced");
    }
    cabinId = Objects.requireNonNull(replacementCabinId, "replacementCabinId");
    unitNumber = requiredUnitNumber(replacementUnitNumber);
  }

  @PrePersist
  void beforeInsert() {
    OffsetDateTime now = currentTime();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void beforeUpdate() {
    updatedAt = currentTime();
  }

  private static String requiredUnitNumber(String value) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > 64) {
      throw new IllegalArgumentException("unitNumber is invalid");
    }
    return normalized;
  }

  private static OffsetDateTime currentTime() {
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
        && Objects.equals(id, ((DriverLogisticsTaskMember) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
