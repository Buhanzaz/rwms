package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** JPA aggregate for a versioned maintenance estimate and repair plan for one rental item. */
@Entity
@Table(name = "maintenance_estimate")
public class MaintenanceEstimate {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "rental_item_id", nullable = false)
  private UUID rentalItemId;

  @Column(name = "rental_item_version_snapshot", nullable = false)
  private long rentalItemVersionSnapshot;

  @Column(name = "catalog_version_id", nullable = false)
  private UUID catalogVersionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private EstimateState state;

  @Column(name = "revision", nullable = false)
  private int revision;

  @Column(name = "dispatch_date")
  private LocalDate dispatchDate;

  /** Manager-selected inventory publication priority, retained until this DRAFT becomes a repair. */
  @Column(name = "priority", nullable = false)
  private int priority = 3;

  /** Whether the eventual repair needs an inbound driver movement. */
  @Column(name = "movement_to_repair", nullable = false)
  private boolean movementToRepair;

  /** Manager-owned override that classifies the eventual repair as capital. */
  @Column(name = "force_capital_repair", nullable = false)
  private boolean forceCapitalRepair;

  @Column(name = "movement_scheduled_date")
  private LocalDate movementScheduledDate;

  @Column(name = "source_party", length = 512)
  private String sourceParty;

  @Column(name = "comment", length = 4000)
  private String comment;

  @Column(name = "repair_id")
  private UUID repairId;

  @Column(name = "cover_media_id")
  private UUID coverMediaId;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  /** Audit-only terminal marker for a manager-replaced inventory DRAFT; it is never deleted. */
  @Column(name = "inventory_superseded_at")
  private OffsetDateTime inventorySupersededAt;

  @Column(name = "actor_ref", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String actorRef;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected MaintenanceEstimate() {}

  public static MaintenanceEstimate create(
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersionSnapshot,
      UUID catalogVersionId,
      LocalDate dispatchDate,
      String sourceParty,
      String comment,
      String actorRef) {
    if (warehouseId == null || rentalItemId == null || catalogVersionId == null
        || rentalItemVersionSnapshot < 0) {
      throw new IllegalArgumentException("Estimate ownership references are required");
    }
    MaintenanceEstimate value = new MaintenanceEstimate();
    value.warehouseId = warehouseId;
    value.rentalItemId = rentalItemId;
    value.rentalItemVersionSnapshot = rentalItemVersionSnapshot;
    value.catalogVersionId = catalogVersionId;
    value.state = EstimateState.DRAFT;
    value.revision = 1;
    value.priority = 3;
    value.movementToRepair = false;
    value.actorRef = actorRef == null ? "{}" : actorRef;
    value.replaceMetadata(dispatchDate, sourceParty, comment);
    return value;
  }

  public boolean replaceMetadata(LocalDate dispatchDate, String sourceParty, String comment) {
    requireDraft();
    String normalizedParty = optional(sourceParty, 512);
    String normalizedComment = optional(comment, 4000);
    boolean changed =
        !Objects.equals(this.dispatchDate, dispatchDate)
            || !Objects.equals(this.sourceParty, normalizedParty)
            || !Objects.equals(this.comment, normalizedComment);
    this.dispatchDate = dispatchDate;
    this.sourceParty = normalizedParty;
    this.comment = normalizedComment;
    return changed;
  }

  public void touchDraft() {
    requireDraft();
    updatedAt = MaintenanceTime.now();
  }

  public void replaceCoverMediaId(UUID coverMediaId) {
    requireDraft();
    this.coverMediaId = coverMediaId;
    updatedAt = MaintenanceTime.now();
  }

  /** Replaces the explicit capital-repair choice while this estimate is an active draft. */
  public void selectForceCapitalRepair(boolean forceCapitalRepair) {
    requireDraft();
    this.forceCapitalRepair = forceCapitalRepair;
    updatedAt = MaintenanceTime.now();
  }

  /** Stores the final manager choices captured with immutable inventory evidence. */
  public void selectInventoryPublication(
      int priority, boolean movementToRepair, LocalDate movementScheduledDate) {
    selectInventoryPublication(priority, movementToRepair, movementScheduledDate, false);
  }

  /** Stores inventory publication choices, including the explicit capital-repair override. */
  public void selectInventoryPublication(
      int priority,
      boolean movementToRepair,
      LocalDate movementScheduledDate,
      boolean forceCapitalRepair) {
    requireDraft();
    if (priority < 1 || priority > 5) {
      throw new IllegalArgumentException("Estimate priority must be between 1 and 5");
    }
    if (!movementToRepair && movementScheduledDate != null) {
      throw new IllegalArgumentException(
          "Inbound movement date must be absent when movement to repair is disabled");
    }
    this.priority = priority;
    this.movementToRepair = movementToRepair;
    this.movementScheduledDate = movementScheduledDate;
    this.forceCapitalRepair = forceCapitalRepair;
    updatedAt = MaintenanceTime.now();
  }

  /**
   * A selected DRAFT has been replaced/merged by a later completed inventory plan.  Retaining
   * the row protects the historical publication audit while keeping it out of active candidates.
   */
  public void supersedeForInventoryPublication() {
    requireDraft();
    if (repairId != null) {
      throw new IllegalStateException("A repair-linked estimate cannot be superseded");
    }
    inventorySupersededAt = MaintenanceTime.now();
    updatedAt = inventorySupersededAt;
  }

  public void complete(UUID repairId) {
    requireDraft();
    state = EstimateState.COMPLETED;
    this.repairId = repairId;
    completedAt = MaintenanceTime.now();
  }

  public void amend(UUID repairId) {
    if (state != EstimateState.COMPLETED) {
      throw new IllegalStateException("Only a completed estimate can be amended");
    }
    revision = Math.addExact(revision, 1);
    this.repairId = repairId;
  }

  public void replaceCompletedMetadata(
      LocalDate dispatchDate, String sourceParty, String comment, UUID repairId) {
    replaceCompletedMetadata(
        dispatchDate, sourceParty, comment, repairId, forceCapitalRepair);
  }

  /** Appends an immutable completed revision with its explicit capital-repair choice. */
  public void replaceCompletedMetadata(
      LocalDate dispatchDate,
      String sourceParty,
      String comment,
      UUID repairId,
      boolean forceCapitalRepair) {
    if (state != EstimateState.COMPLETED) {
      throw new IllegalStateException("Only a completed estimate can be amended");
    }
    this.dispatchDate = dispatchDate;
    this.sourceParty = optional(sourceParty, 512);
    this.comment = optional(comment, 4000);
    this.forceCapitalRepair = forceCapitalRepair;
    amend(repairId);
  }

  public void linkCompletedRepair(UUID repairId) {
    if (state != EstimateState.COMPLETED || this.repairId != null || repairId == null) {
      throw new IllegalStateException("Only an unlinked completed estimate can receive its first repair");
    }
    this.repairId = repairId;
  }

  private void requireDraft() {
    if (state != EstimateState.DRAFT || inventorySupersededAt != null) {
      throw new IllegalStateException(
          "Only an active draft estimate can change; completed or superseded estimates are immutable");
    }
  }

  private static String optional(String value, int maximumLength) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim();
    if (normalized.length() > maximumLength) throw new IllegalArgumentException("Text is too long");
    return normalized;
  }

  @PrePersist
  void beforeInsert() {
    OffsetDateTime now = MaintenanceTime.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void beforeUpdate() { updatedAt = MaintenanceTime.now(); }

  public UUID getId() { return id; }
  public long getVersion() { return version; }
  public UUID getWarehouseId() { return warehouseId; }
  public UUID getRentalItemId() { return rentalItemId; }
  public long getRentalItemVersionSnapshot() { return rentalItemVersionSnapshot; }
  public UUID getCatalogVersionId() { return catalogVersionId; }
  public EstimateState getState() { return state; }
  public int getRevision() { return revision; }
  public LocalDate getDispatchDate() { return dispatchDate; }
  public int getPriority() { return priority; }
  public boolean isMovementToRepair() { return movementToRepair; }
  public boolean isForceCapitalRepair() { return forceCapitalRepair; }
  public LocalDate getMovementScheduledDate() { return movementScheduledDate; }
  public String getSourceParty() { return sourceParty; }
  public String getComment() { return comment; }
  public UUID getRepairId() { return repairId; }
  public UUID getCoverMediaId() { return coverMediaId; }
  public OffsetDateTime getCompletedAt() { return completedAt; }
  public OffsetDateTime getInventorySupersededAt() { return inventorySupersededAt; }
  public String getActorRef() { return actorRef; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
