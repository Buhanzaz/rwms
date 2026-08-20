package dev.buhanzaz.rwms.logistics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/**
 * JPA persistence model for one logistics document line; document commands own its workflow
 * transitions.
 */
@Entity
@Table(name = "logistics_document_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LogisticsDocumentLine {
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
      foreignKey = @ForeignKey(name = "fk_logistics_document_line_document"))
  private LogisticsDocument document;

  @Column(name = "line_number", nullable = false)
  private int lineNumber;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Column(name = "asset_version", nullable = false)
  private long assetVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private LogisticsLineState state;

  @Column(name = "tenant_snapshot", length = 512)
  private String tenantSnapshot;

  /** Opaque existing rental-order reference used to prove client ownership. */
  @Column(name = "rental_order_id")
  private UUID rentalOrderId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "passport_snapshot", columnDefinition = "jsonb")
  private JsonNode passportSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "expected_contents_snapshot", columnDefinition = "jsonb")
  private JsonNode expectedContentsSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "factual_contents_snapshot", columnDefinition = "jsonb")
  private JsonNode factualContentsSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "source_allocation_snapshot", columnDefinition = "jsonb")
  private JsonNode sourceAllocationSnapshot;

  /**
   * Furniture found in addition to the canonical cabin contents at return acceptance. The logistics
   * completion workflow turns this immutable fact into an asset-service stock receipt.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "return_additional_contents_snapshot", columnDefinition = "jsonb")
  private JsonNode returnAdditionalContentsSnapshot;

  /**
   * Canonical source status captured from maintenance-service before departure. It is deliberately
   * persisted because an in-transit asset no longer exposes whether arrival must restore FREE or
   * REPAIR.
   */
  @Column(name = "transfer_asset_status", length = 16)
  private String transferAssetStatus;

  @Column(name = "active_repair_id")
  private UUID activeRepairId;

  @Column(name = "active_repair_version")
  private Long activeRepairVersion;

  @Column(name = "repair_continuation_priority")
  private Integer repairContinuationPriority;

  @Column(name = "maintenance_prepared_at")
  private OffsetDateTime maintenancePreparedAt;

  @Column(name = "maintenance_arrival_completed_at")
  private OffsetDateTime maintenanceArrivalCompletedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Column(name = "inventory_superseded_by")
  private UUID inventorySupersededBy;

  @Column(name = "inventory_finding_id")
  private UUID inventoryFindingId;

  @Column(name = "inventory_desired_status", length = 24)
  private String inventoryDesiredStatus;

  @Column(name = "inventory_superseded_at")
  private OffsetDateTime inventorySupersededAt;

  @Column(name = "inventory_completed_at")
  private OffsetDateTime inventoryCompletedAt;

  @Column(name = "inventory_final_plan_version")
  private Long inventoryFinalPlanVersion;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "inventory_final_plan_sha256", length = 64)
  private String inventoryFinalPlanSha256;

  public static LogisticsDocumentLine create(
      LogisticsDocument document,
      int lineNumber,
      UUID assetId,
      long assetVersion,
      String tenantSnapshot) {
    return create(document, lineNumber, assetId, assetVersion, tenantSnapshot, null);
  }

  public static LogisticsDocumentLine create(
      LogisticsDocument document,
      int lineNumber,
      UUID assetId,
      long assetVersion,
      String tenantSnapshot,
      UUID rentalOrderId) {
    if (document == null) throw new IllegalArgumentException("document is required");
    if (lineNumber < 1) throw new IllegalArgumentException("lineNumber must be positive");
    if (assetId == null) throw new IllegalArgumentException("assetId is required");
    if (assetVersion < 0) throw new IllegalArgumentException("assetVersion must not be negative");
    LogisticsDocumentLine line = new LogisticsDocumentLine();
    line.document = document;
    line.lineNumber = lineNumber;
    line.assetId = assetId;
    line.assetVersion = assetVersion;
    line.state = LogisticsLineState.PENDING;
    line.tenantSnapshot = optionalSnapshot(tenantSnapshot);
    line.rentalOrderId = rentalOrderId;
    return line;
  }

  public void beginDeparture() {
    transition(LogisticsLineState.PENDING, LogisticsLineState.DEPARTING);
  }

  public void markDeparted() {
    transition(LogisticsLineState.DEPARTING, LogisticsLineState.DEPARTED);
  }

  public void beginArrival() {
    transition(LogisticsLineState.DEPARTED, LogisticsLineState.ARRIVING);
  }

  public void markArrived() {
    transition(LogisticsLineState.ARRIVING, LogisticsLineState.ARRIVED);
  }

  public void conflict() {
    if (state == LogisticsLineState.ARRIVED || state == LogisticsLineState.CANCELLED) {
      throw new IllegalStateException("Terminal line cannot enter conflict");
    }
    state = LogisticsLineState.CONFLICT;
  }

  public void cancel() {
    if (state != LogisticsLineState.PENDING) {
      throw new IllegalStateException("Only a pending line can be cancelled");
    }
    state = LogisticsLineState.CANCELLED;
  }

  /** Marks this historical line as displaced by the exact completed-inventory finding. */
  public void supersedeByCompletedInventory(
      UUID inventoryId,
      UUID findingId,
      String desiredStatus,
      OffsetDateTime completedAt,
      long finalPlanVersion,
      String finalPlanSha256) {
    if (inventoryId == null
        || findingId == null
        || !java.util.Set.of("FREE", "REPAIR", "CAPITAL_REPAIR").contains(desiredStatus)
        || completedAt == null
        || finalPlanVersion < 1
        || finalPlanSha256 == null
        || !finalPlanSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Completed inventory line source is invalid");
    }
    inventorySupersededBy = inventoryId;
    inventoryFindingId = findingId;
    inventoryDesiredStatus = desiredStatus;
    inventorySupersededAt = currentTime();
    inventoryCompletedAt = completedAt;
    inventoryFinalPlanVersion = finalPlanVersion;
    inventoryFinalPlanSha256 = finalPlanSha256;
  }

  /** Refreshes the mutable draft projection after asset-side order reservation. */
  public boolean synchronizeOrderReservation(long nextAssetVersion, String nextTenantSnapshot) {
    if (rentalOrderId == null || state != LogisticsLineState.PENDING) {
      throw new IllegalStateException("Only a pending rental-order line can be synchronized");
    }
    if (nextAssetVersion < assetVersion) {
      throw new IllegalArgumentException("assetVersion must not move backwards");
    }
    String requiredTenantSnapshot = optionalSnapshot(nextTenantSnapshot);
    if (requiredTenantSnapshot == null) {
      throw new IllegalArgumentException("tenantSnapshot is required");
    }
    if (assetVersion == nextAssetVersion
        && java.util.Objects.equals(tenantSnapshot, requiredTenantSnapshot)) {
      return false;
    }
    assetVersion = nextAssetVersion;
    tenantSnapshot = requiredTenantSnapshot;
    return true;
  }

  /** Replaces an unstarted rental-order line after an asset-side atomic cabin swap. */
  public void replaceRentalItem(
      UUID expectedOldRentalItemId, UUID replacementRentalItemId, long replacementAssetVersion) {
    if (rentalOrderId == null
        || state != LogisticsLineState.PENDING
        || !assetId.equals(expectedOldRentalItemId)) {
      throw new IllegalStateException("Only an unstarted matching order line can be replaced");
    }
    if (replacementAssetVersion < 0) {
      throw new IllegalArgumentException("replacementAssetVersion must not be negative");
    }
    assetId = Objects.requireNonNull(replacementRentalItemId, "replacementRentalItemId");
    assetVersion = replacementAssetVersion;
  }

  public void captureExpectedContents(JsonNode snapshot) {
    expectedContentsSnapshot =
        captureImmutable(expectedContentsSnapshot, snapshot, "expectedContentsSnapshot");
  }

  public void captureFactualContents(JsonNode snapshot) {
    factualContentsSnapshot =
        captureImmutable(factualContentsSnapshot, snapshot, "factualContentsSnapshot");
  }

  public void captureSourceAllocations(JsonNode snapshot) {
    sourceAllocationSnapshot =
        captureImmutable(sourceAllocationSnapshot, snapshot, "sourceAllocationSnapshot");
  }

  public void captureReturnAdditionalContents(JsonNode snapshot) {
    returnAdditionalContentsSnapshot =
        captureImmutable(
            returnAdditionalContentsSnapshot, snapshot, "returnAdditionalContentsSnapshot");
  }

  public void captureMaintenanceDeparture(
      UUID repairId, Long repairVersion, String assetStatus, OffsetDateTime preparedAt) {
    String requiredStatus = requiredTransferAssetStatus(assetStatus);
    if (preparedAt == null) throw new IllegalArgumentException("preparedAt is required");
    if ("REPAIR".equals(requiredStatus)) {
      if (repairId == null || repairVersion == null || repairVersion < 0) {
        throw new IllegalArgumentException("Active repair identity and version are required");
      }
    } else if (repairId != null || repairVersion != null) {
      throw new IllegalArgumentException("FREE transfer truth cannot reference an active repair");
    }
    if (maintenancePreparedAt != null
        && (!java.util.Objects.equals(activeRepairId, repairId)
            || !java.util.Objects.equals(activeRepairVersion, repairVersion)
            || !java.util.Objects.equals(transferAssetStatus, requiredStatus))) {
      throw new IllegalStateException("Maintenance departure truth is immutable");
    }
    activeRepairId = repairId;
    activeRepairVersion = repairVersion;
    transferAssetStatus = requiredStatus;
    maintenancePreparedAt = preparedAt;
  }

  public void configureRepairContinuation(Integer priority) {
    if (activeRepairId == null) {
      if (priority != null) {
        throw new IllegalArgumentException(
            "A transfer without an active repair has no continuation");
      }
      repairContinuationPriority = null;
      return;
    }
    if (priority == null || priority < 1 || priority > 5) {
      throw new IllegalArgumentException("Repair priority must be between 1 and 5");
    }
    if (repairContinuationPriority != null && !repairContinuationPriority.equals(priority)) {
      throw new IllegalStateException("Repair continuation settings are immutable");
    }
    repairContinuationPriority = priority;
  }

  public void completeMaintenanceArrival(long repairVersion, OffsetDateTime completedAt) {
    if (activeRepairId == null || repairVersion < 0 || completedAt == null) {
      throw new IllegalArgumentException("Maintenance arrival completion is invalid");
    }
    if (activeRepairVersion != null && repairVersion < activeRepairVersion) {
      throw new IllegalArgumentException("Repair version must not move backwards");
    }
    activeRepairVersion = repairVersion;
    maintenanceArrivalCompletedAt = completedAt;
  }

  public boolean hasActiveRepair() {
    return activeRepairId != null;
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

  private void transition(LogisticsLineState expected, LogisticsLineState next) {
    if (state != expected)
      throw new IllegalStateException("Line lifecycle transition is not allowed");
    state = next;
  }

  private static String optionalSnapshot(String value) {
    if (value == null) return null;
    String normalized = value.trim();
    if (normalized.isEmpty()) return null;
    if (normalized.length() > 512) throw new IllegalArgumentException("tenantSnapshot is too long");
    return normalized;
  }

  private static JsonNode captureImmutable(JsonNode current, JsonNode incoming, String field) {
    if (incoming == null || !incoming.isObject()) {
      throw new IllegalArgumentException(field + " must be a JSON object");
    }
    if (current != null && !current.equals(incoming)) {
      throw new IllegalStateException(field + " is immutable once captured");
    }
    return incoming.deepCopy();
  }

  private static String requiredTransferAssetStatus(String value) {
    if (!"FREE".equals(value) && !"REPAIR".equals(value)) {
      throw new IllegalArgumentException("transferAssetStatus must be FREE or REPAIR");
    }
    return value;
  }

  private static OffsetDateTime currentTime() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }
}
