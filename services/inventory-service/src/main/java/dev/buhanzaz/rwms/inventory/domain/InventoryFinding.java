package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * JPA entity that persists inventory finding in the inventory-owned database.
 */
@Entity
@Table(name = "inventory_finding")
public class InventoryFinding implements Persistable<UUID> {
  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Column(name = "expected_item_id")
  private UUID expectedItemId;

  @Version
  @Column(name = "finding_revision", nullable = false)
  private long revision;

  @Column(name = "owner_proof_revision", nullable = false)
  private long ownerProofRevision;

  @Column(name = "owner_proof_active", nullable = false)
  private boolean ownerProofActive;

  @Column(name = "membership_active", nullable = false)
  private boolean membershipActive;

  @Enumerated(EnumType.STRING)
  @Column(name = "origin", nullable = false, length = 32)
  private FindingOrigin origin;

  @Enumerated(EnumType.STRING)
  @Column(name = "inspection", nullable = false, length = 24)
  private InspectionState inspection;

  @Enumerated(EnumType.STRING)
  @Column(name = "inspection_source", length = 24)
  private InspectionSource inspectionSource;

  @Column(name = "external_inspection_asset_version")
  private Long externalInspectionAssetVersion;

  @Column(name = "inspection_superseded_by_departure", nullable = false)
  private boolean inspectionSupersededByDeparture;

  @Column(name = "membership_event_asset_version")
  private Long membershipEventAssetVersion;

  @Column(name = "membership_event_warehouse_id")
  private UUID membershipEventWarehouseId;

  @Column(name = "membership_event_status", length = 48)
  private String membershipEventStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "reconciliation", nullable = false, length = 24)
  private ReconciliationState reconciliation;

  @Column(name = "asset_id")
  private UUID assetId;

  @Column(name = "asset_version_snapshot")
  private Long assetVersion;

  @Column(name = "current_warehouse_id")
  private UUID currentWarehouseId;

  @Column(name = "current_status", length = 48)
  private String currentStatus;

  @Column(name = "current_tenant_snapshot", length = 512)
  private String currentTenantSnapshot;

  @Column(name = "current_display_canonical_number", length = 128)
  private String currentDisplayCanonicalNumber;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "current_passport_snapshot", columnDefinition = "jsonb")
  private String currentPassportSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "current_contents_snapshot", columnDefinition = "jsonb")
  private String currentContentsSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "current_repairs_snapshot", columnDefinition = "jsonb")
  private String currentRepairsSnapshot;

  @Column(name = "inspection_asset_version")
  private Long inspectionAssetVersion;

  @Column(name = "inspection_warehouse_id")
  private UUID inspectionWarehouseId;

  @Column(name = "inspection_status", length = 48)
  private String inspectionStatus;

  @Column(name = "inspection_display_canonical_number", length = 128)
  private String inspectionDisplayCanonicalNumber;

  @Column(name = "inspection_tenant_snapshot", length = 512)
  private String inspectionTenantSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "inspection_passport_snapshot", columnDefinition = "jsonb")
  private String inspectionPassportSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "inspection_contents_snapshot", columnDefinition = "jsonb")
  private String inspectionContentsSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "inspection_repairs_snapshot", columnDefinition = "jsonb")
  private String inspectionRepairsSnapshot;

  @Enumerated(EnumType.STRING)
  @Column(name = "conflict_resolution_strategy", length = 32)
  private ConflictResolutionStrategy conflictResolutionStrategy;

  @Column(name = "conflict_resolution_current_sha256", length = 64)
  private String conflictResolutionCurrentSha256;

  @Column(name = "conflict_resolution_reason", length = 2000)
  private String conflictResolutionReason;

  @Column(name = "conflict_resolved_at")
  private OffsetDateTime conflictResolvedAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "conflict_resolved_by_actor_ref", columnDefinition = "jsonb")
  private String conflictResolvedByActorRef;

  @Column(name = "display_canonical_number", nullable = false, length = 128)
  private String displayCanonicalNumber;

  @Column(name = "identity_match_key", nullable = false, length = 128)
  private String identityMatchKey;

  @Enumerated(EnumType.STRING)
  @Column(name = "passport_observation_state", nullable = false, length = 24)
  private ObservationPresence passportObservationState;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "passport_observation", columnDefinition = "jsonb")
  private String passportObservation;

  @Enumerated(EnumType.STRING)
  @Column(name = "equipment_observation_state", nullable = false, length = 24)
  private ObservationPresence equipmentObservationState;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "equipment_observation", columnDefinition = "jsonb")
  private String equipmentObservation;

  @Enumerated(EnumType.STRING)
  @Column(name = "mutation_state", nullable = false, length = 32)
  private MutationState mutationState;

  @Column(name = "maintenance_plan_fingerprint_sha256", length = 64)
  private String maintenancePlanFingerprintSha256;

  @Column(name = "cover_media_id")
  private UUID coverMediaId;

  @Column(name = "inspection_comment", nullable = false, length = 2000)
  private String inspectionComment;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "actor_ref", nullable = false, columnDefinition = "jsonb")
  private String actorRef;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected InventoryFinding() {}

  public static InventoryFinding expected(
      UUID inventoryId,
      UUID expectedItemId,
      UUID assetId,
      long assetVersion,
      UUID warehouseId,
      String status,
      String tenantSnapshot,
      String displayNumber,
      String matchKey,
      String actorRef) {
    if (expectedItemId == null) throw new IllegalArgumentException("Expected item is required");
    return create(
        inventoryId,
        expectedItemId,
        FindingOrigin.EXPECTED,
        assetId,
        assetVersion,
        warehouseId,
        status,
        tenantSnapshot,
        displayNumber,
        matchKey,
        ReconciliationState.MISSING,
        actorRef);
  }

  public static InventoryFinding expected(
      UUID inventoryId,
      UUID expectedItemId,
      UUID assetId,
      long assetVersion,
      String displayNumber,
      String matchKey,
      String actorRef) {
    return expected(
        inventoryId,
        expectedItemId,
        assetId,
        assetVersion,
        null,
        null,
        null,
        displayNumber,
        matchKey,
        actorRef);
  }

  public static InventoryFinding unexpected(
      UUID inventoryId,
      FindingOrigin origin,
      UUID assetId,
      Long assetVersion,
      UUID warehouseId,
      String status,
      String tenantSnapshot,
      String displayNumber,
      String matchKey,
      ReconciliationState reconciliation,
      String actorRef) {
    if (origin == null || origin == FindingOrigin.EXPECTED) {
      throw new IllegalArgumentException("Unexpected finding origin is required");
    }
    return create(
        inventoryId,
        null,
        origin,
        assetId,
        assetVersion,
        warehouseId,
        status,
        tenantSnapshot,
        displayNumber,
        matchKey,
        reconciliation,
        actorRef);
  }

  public static InventoryFinding unexpected(
      UUID inventoryId,
      FindingOrigin origin,
      UUID assetId,
      Long assetVersion,
      String displayNumber,
      String matchKey,
      ReconciliationState reconciliation,
      String actorRef) {
    return unexpected(
        inventoryId,
        origin,
        assetId,
        assetVersion,
        null,
        null,
        null,
        displayNumber,
        matchKey,
        reconciliation,
        actorRef);
  }

  public static InventoryFinding unexpectedWithId(
      UUID id,
      UUID inventoryId,
      FindingOrigin origin,
      String displayNumber,
      String matchKey,
      String actorRef) {
    if (id == null) throw new IllegalArgumentException("Finding ID is required");
    InventoryFinding value =
        unexpected(
            inventoryId,
            origin,
            null,
            null,
            null,
            null,
            null,
            displayNumber,
            matchKey,
            ReconciliationState.CONFLICT,
            actorRef);
    value.id = id;
    value.beginSourceCreate();
    return value;
  }

  private static InventoryFinding create(
      UUID inventoryId,
      UUID expectedItemId,
      FindingOrigin origin,
      UUID assetId,
      Long assetVersion,
      UUID currentWarehouseId,
      String currentStatus,
      String currentTenantSnapshot,
      String displayNumber,
      String matchKey,
      ReconciliationState reconciliation,
      String actorRef) {
    boolean emptyCurrentSnapshot =
        currentWarehouseId == null && currentStatus == null && currentTenantSnapshot == null;
    boolean completeCurrentSnapshot =
        assetId != null && currentWarehouseId != null && currentStatus != null;
    if (inventoryId == null
        || origin == null
        || reconciliation == null
        || (assetId == null) != (assetVersion == null)
        || (!emptyCurrentSnapshot && !completeCurrentSnapshot)
        || (assetVersion != null && assetVersion < 0)) {
      throw new IllegalArgumentException("Finding ownership and asset snapshot are invalid");
    }
    InventoryFinding value = new InventoryFinding();
    value.id = UUID.randomUUID();
    value.inventoryId = inventoryId;
    value.expectedItemId = expectedItemId;
    value.origin = origin;
    value.inspection = InspectionState.NOT_INSPECTED;
    value.reconciliation = reconciliation;
    value.assetId = assetId;
    value.assetVersion = assetVersion;
    value.currentWarehouseId = currentWarehouseId;
    value.membershipEventAssetVersion = completeCurrentSnapshot ? assetVersion : null;
    value.membershipEventWarehouseId = currentWarehouseId;
    value.membershipEventStatus = currentStatus;
    value.currentStatus = nullableRequired(currentStatus, 48, "current status");
    value.currentTenantSnapshot = nullable(currentTenantSnapshot, 512, "current tenant");
    value.currentDisplayCanonicalNumber =
        assetId == null ? null : required(displayNumber, 128, "current display number");
    value.currentPassportSnapshot = assetId == null ? null : "{}";
    value.currentContentsSnapshot = assetId == null ? null : "[]";
    value.currentRepairsSnapshot = assetId == null ? null : "[]";
    value.displayCanonicalNumber = required(displayNumber, 128, "display number");
    value.identityMatchKey = required(matchKey, 128, "identity match key");
    value.passportObservationState = ObservationPresence.ABSENT;
    value.equipmentObservationState = ObservationPresence.ABSENT;
    value.mutationState = MutationState.IDLE;
    value.inspectionComment = "";
    value.ownerProofActive = true;
    value.membershipActive = true;
    value.actorRef = required(actorRef, 2000, "actor reference");
    return value;
  }

  public boolean transitionOwnerProof(boolean active) {
    if (ownerProofActive == active) return false;
    ownerProofRevision = Math.addExact(ownerProofRevision, 1);
    ownerProofActive = active;
    return true;
  }

  public boolean changeMembership(boolean active) {
    if (membershipActive == active) return false;
    membershipActive = active;
    transitionOwnerProof(active);
    return true;
  }

  /**
   * Returns whether this finding was created from an explicit warehouse observation rather than
   * from the automatic asset population captured at session start.
   */
  public boolean isExplicitObservation() {
    return origin != FindingOrigin.EXPECTED;
  }

  /** Historical and return-owned inspections never overwrite live operational state. */
  public boolean preservesOperationalState(UUID inventoryWarehouseId) {
    return preservesOperationalState(inventoryWarehouseId, currentWarehouseId, currentStatus);
  }

  /** Uses fresh owner truth when it is newer than this finding's event-driven projection. */
  public boolean preservesOperationalState(UUID inventoryWarehouseId, UUID liveWarehouseId, String liveStatus) {
    if (inspection == InspectionState.NOT_INSPECTED) return false;
    return inspectionSource == InspectionSource.LOGISTICS_RETURN
        || inspectionSupersededByDeparture
        || (liveWarehouseId != null && !liveWarehouseId.equals(inventoryWarehouseId))
        || ("RENTED".equals(liveStatus) && !"RENTED".equals(inspectionStatus))
        || "IN_TRANSFER".equals(liveStatus);
  }

  /** Advances only from ordered event facts, never from a newer remote read snapshot. */
  public boolean advanceMembershipEvent(long version, UUID warehouseId, String status) {
    if (version < 0 || warehouseId == null || status == null) {
      throw new IllegalArgumentException("Membership event facts are incomplete");
    }
    if (membershipEventAssetVersion != null && version <= membershipEventAssetVersion) return false;
    membershipEventAssetVersion = version;
    membershipEventWarehouseId = warehouseId;
    membershipEventStatus = status;
    return true;
  }

  /** Keeps pre-departure evidence without reactivating its obsolete operational plan. */
  public void retainInspectionBeforeDeparture() {
    if (inspection != InspectionState.NOT_INSPECTED) inspectionSupersededByDeparture = true;
  }

  /** Records an external inspection proof without inventing an inventory passport baseline. */
  public void importReturnInspection(long inspectedAssetVersion, String nextActorRef) {
    requireIdleOrCreated();
    if (inspectedAssetVersion < 0 || assetId == null) {
      throw new IllegalArgumentException("Return inspection asset proof is invalid");
    }
    inspection = InspectionState.READY;
    inspectionSource = InspectionSource.LOGISTICS_RETURN;
    externalInspectionAssetVersion = inspectedAssetVersion;
    inspectionSupersededByDeparture = false;
    inspectionAssetVersion = null;
    inspectionWarehouseId = null;
    inspectionStatus = null;
    inspectionDisplayCanonicalNumber = null;
    inspectionTenantSnapshot = null;
    inspectionPassportSnapshot = null;
    inspectionContentsSnapshot = null;
    inspectionRepairsSnapshot = null;
    passportObservationState = ObservationPresence.ABSENT;
    passportObservation = null;
    equipmentObservationState = ObservationPresence.ABSENT;
    equipmentObservation = null;
    maintenancePlanFingerprintSha256 = null;
    inspectionComment = "";
    coverMediaId = null;
    reconciliation = ReconciliationState.MATCHED;
    actorRef = required(nextActorRef, 2000, "actor reference");
    clearConflictResolution();
  }

  /**
   * Restores an inspected explicit observation that an obsolete automatic-membership projection
   * removed from a completed session.
   *
   * <p>The inspection baseline is the last human-confirmed warehouse evidence, so it becomes the
   * current snapshot again. Media owner authorization deliberately remains closed because the
   * session is already completed; downstream outcome publication uses the service-owned completed
   * finding evidence instead of reopening worker uploads.
   */
  public boolean restoreCompletedExplicitObservation() {
    if (membershipActive) return false;
    if (!isExplicitObservation()
        || inspection == InspectionState.NOT_INSPECTED
        || assetId == null
        || inspectionAssetVersion == null
        || inspectionWarehouseId == null
        || inspectionStatus == null
        || inspectionDisplayCanonicalNumber == null
        || inspectionPassportSnapshot == null
        || inspectionContentsSnapshot == null
        || inspectionRepairsSnapshot == null) {
      throw new IllegalStateException(
          "Only a complete inspected explicit observation may be restored");
    }
    assetVersion = inspectionAssetVersion;
    currentWarehouseId = inspectionWarehouseId;
    currentStatus = inspectionStatus;
    currentTenantSnapshot = inspectionTenantSnapshot;
    currentDisplayCanonicalNumber = inspectionDisplayCanonicalNumber;
    currentPassportSnapshot = inspectionPassportSnapshot;
    currentContentsSnapshot = inspectionContentsSnapshot;
    currentRepairsSnapshot = inspectionRepairsSnapshot;
    reconciliation = ReconciliationState.MATCHED;
    membershipActive = true;
    return true;
  }

  public void beginSourceCreate() {
    requireIdle();
    if (assetId != null
        || (origin != FindingOrigin.ADDED_NEW && origin != FindingOrigin.ADDED_USED)) {
      throw new IllegalStateException("Only an unresolved added finding may create an asset");
    }
    mutationState = MutationState.SOURCE_CREATE_PENDING;
  }

  public void attachCreatedAsset(
      UUID createdAssetId,
      long createdAssetVersion,
      UUID warehouseId,
      String status,
      String tenantSnapshot) {
    if (mutationState != MutationState.SOURCE_CREATE_PENDING
        || createdAssetId == null
        || createdAssetVersion < 0) {
      throw new IllegalStateException("Source asset completion is not valid in the current state");
    }
    assetId = createdAssetId;
    assetVersion = createdAssetVersion;
    currentWarehouseId = java.util.Objects.requireNonNull(warehouseId, "warehouseId");
    currentStatus = required(status, 48, "current status");
    currentTenantSnapshot = nullable(tenantSnapshot, 512, "current tenant");
    reconciliation = ReconciliationState.MATCHED;
    mutationState = MutationState.SOURCE_CREATED;
  }

  public void refreshCurrentAsset(
      Long currentAssetVersion,
      UUID warehouseId,
      String status,
      String tenantSnapshot,
      String currentDisplayNumber,
      String passportSnapshot,
      String contentsSnapshot,
      String repairsSnapshot,
      ReconciliationState nextReconciliation) {
    requireIdleOrCreated();
    boolean missing =
        currentAssetVersion == null
            && warehouseId == null
            && status == null
            && tenantSnapshot == null;
    boolean present =
        assetId != null
            && currentAssetVersion != null
            && currentAssetVersion >= 0
            && warehouseId != null
            && status != null;
    if ((!missing && !present) || nextReconciliation == null) {
      throw new IllegalArgumentException("Current asset snapshot is invalid");
    }
    if (present) assetVersion = currentAssetVersion;
    currentWarehouseId = warehouseId;
    currentStatus = nullableRequired(status, 48, "current status");
    currentTenantSnapshot = nullable(tenantSnapshot, 512, "current tenant");
    currentDisplayCanonicalNumber =
        present ? required(currentDisplayNumber, 128, "current display number") : null;
    currentPassportSnapshot =
        present ? jsonObject(passportSnapshot, "current passport snapshot") : null;
    currentContentsSnapshot =
        present ? jsonArray(contentsSnapshot, "current contents snapshot") : null;
    currentRepairsSnapshot =
        present ? jsonArray(repairsSnapshot, "current repairs snapshot") : null;
    reconciliation = nextReconciliation;
  }

  public void refreshCurrentAsset(
      Long currentAssetVersion,
      UUID warehouseId,
      String status,
      String tenantSnapshot,
      ReconciliationState nextReconciliation) {
    refreshCurrentAsset(
        currentAssetVersion,
        warehouseId,
        status,
        tenantSnapshot,
        currentAssetVersion == null ? null : displayCanonicalNumber,
        currentAssetVersion == null ? null : "{}",
        currentAssetVersion == null ? null : "[]",
        currentAssetVersion == null ? null : "[]",
        nextReconciliation);
  }

  public void saveInspection(
      InspectionState nextInspection,
      ReconciliationState nextReconciliation,
      ObservationPresence passportPresence,
      String passportJson,
      ObservationPresence equipmentPresence,
      String equipmentJson,
      String planFingerprint,
      String comment,
      UUID nextCoverMediaId,
      String nextActorRef) {
    if (nextInspection == null || nextInspection == InspectionState.NOT_INSPECTED) {
      throw new IllegalArgumentException("Inspection must be READY or WORK_STAGED");
    }
    requireIdleOrCreated();
    inspection = nextInspection;
    reconciliation = nextReconciliation;
    passportObservationState = passportPresence;
    passportObservation = observation(passportPresence, passportJson, false);
    equipmentObservationState = equipmentPresence;
    equipmentObservation = observation(equipmentPresence, equipmentJson, true);
    maintenancePlanFingerprintSha256 =
        nextInspection == InspectionState.WORK_STAGED ? sha256(planFingerprint) : null;
    coverMediaId = nextCoverMediaId;
    inspectionComment = normalizedComment(comment);
    if (inspectionComment == null) inspectionComment = "";
    actorRef = required(nextActorRef, 2000, "actor reference");
    captureInspectionBaseline();
    clearConflictResolution();
  }

  public void saveInspection(
      InspectionState nextInspection,
      ReconciliationState nextReconciliation,
      ObservationPresence passportPresence,
      String passportJson,
      ObservationPresence equipmentPresence,
      String equipmentJson,
      String planFingerprint,
      String comment,
      String nextActorRef) {
    saveInspection(
        nextInspection,
        nextReconciliation,
        passportPresence,
        passportJson,
        equipmentPresence,
        equipmentJson,
        planFingerprint,
        comment,
        null,
        nextActorRef);
  }

  public void saveInspection(
      InspectionState nextInspection,
      ReconciliationState nextReconciliation,
      ObservationPresence passportPresence,
      String passportJson,
      ObservationPresence equipmentPresence,
      String equipmentJson,
      String planFingerprint,
      String nextActorRef) {
    saveInspection(
        nextInspection,
        nextReconciliation,
        passportPresence,
        passportJson,
        equipmentPresence,
        equipmentJson,
        planFingerprint,
        "",
        null,
        nextActorRef);
  }

  public void markMissing(String nextActorRef) {
    reconciliation = ReconciliationState.MISSING;
    actorRef = required(nextActorRef, 2000, "actor reference");
  }

  /** Updates only the equipment observation after the cabin review has been frozen. */
  public void saveFurnitureObservation(
      ObservationPresence equipmentPresence, String equipmentJson, String nextActorRef) {
    requireIdleOrCreated();
    equipmentObservationState = equipmentPresence;
    equipmentObservation = observation(equipmentPresence, equipmentJson, true);
    actorRef = required(nextActorRef, 2000, "actor reference");
  }

  public void resolveConflict(
      ConflictResolutionStrategy strategy,
      String currentFingerprint,
      String reason,
      String nextActorRef) {
    if (inspection == InspectionState.NOT_INSPECTED) {
      throw new IllegalStateException("An uninspected finding has no registry conflict to resolve");
    }
    requireIdleOrCreated();
    if (strategy == null) {
      throw new IllegalArgumentException("Conflict resolution strategy is required");
    }
    String normalizedReason = nullable(reason, 2000, "conflict resolution reason");
    if (strategy == ConflictResolutionStrategy.KEEP_INSPECTION && normalizedReason == null) {
      throw new IllegalArgumentException(
          "Keeping inspection data requires a conflict resolution reason");
    }
    if (strategy == ConflictResolutionStrategy.ACCEPT_REGISTRY) {
      captureInspectionBaseline();
      inspection = InspectionState.READY;
      maintenancePlanFingerprintSha256 = null;
    }
    conflictResolutionStrategy = strategy;
    conflictResolutionCurrentSha256 = sha256(currentFingerprint);
    conflictResolutionReason = normalizedReason;
    conflictResolvedAt = OffsetDateTime.now(ZoneOffset.UTC);
    conflictResolvedByActorRef = required(nextActorRef, 2000, "actor reference");
    actorRef = conflictResolvedByActorRef;
    reconciliation =
        currentWarehouseId == null || currentStatus == null
            ? ReconciliationState.MISSING
            : ReconciliationState.MATCHED;
  }

  private void captureInspectionBaseline() {
    if (assetId == null
        || assetVersion == null
        || currentWarehouseId == null
        || currentStatus == null
        || currentDisplayCanonicalNumber == null
        || currentPassportSnapshot == null
        || currentContentsSnapshot == null
        || currentRepairsSnapshot == null) {
      throw new IllegalStateException("Inspection requires a complete current asset snapshot");
    }
    inspectionSource = InspectionSource.INVENTORY;
    externalInspectionAssetVersion = null;
    inspectionSupersededByDeparture = false;
    inspectionAssetVersion = assetVersion;
    inspectionWarehouseId = currentWarehouseId;
    inspectionStatus = currentStatus;
    inspectionDisplayCanonicalNumber = currentDisplayCanonicalNumber;
    inspectionTenantSnapshot = currentTenantSnapshot;
    inspectionPassportSnapshot = currentPassportSnapshot;
    inspectionContentsSnapshot = currentContentsSnapshot;
    inspectionRepairsSnapshot = currentRepairsSnapshot;
  }

  private void clearConflictResolution() {
    conflictResolutionStrategy = null;
    conflictResolutionCurrentSha256 = null;
    conflictResolutionReason = null;
    conflictResolvedAt = null;
    conflictResolvedByActorRef = null;
  }

  private void requireIdle() {
    if (mutationState != MutationState.IDLE) {
      throw new IllegalStateException("Another finding mutation is in flight");
    }
  }

  private void requireIdleOrCreated() {
    if (mutationState != MutationState.IDLE && mutationState != MutationState.SOURCE_CREATED) {
      throw new IllegalStateException("Another finding mutation is in flight");
    }
  }

  private static String observation(ObservationPresence presence, String value, boolean array) {
    if (presence == null) throw new IllegalArgumentException("Observation presence is required");
    return switch (presence) {
      case ABSENT -> {
        if (value != null) throw new IllegalArgumentException("ABSENT observation has no value");
        yield null;
      }
      case EXPLICIT_EMPTY -> {
        String expected = array ? "[]" : "{}";
        if (!expected.equals(value)) {
          throw new IllegalArgumentException("EXPLICIT_EMPTY observation must be canonical empty JSON");
        }
        yield expected;
      }
      case PRESENT -> {
        String normalized = required(value, 16000, "observation");
        String empty = array ? "[]" : "{}";
        if (empty.equals(normalized)
            || (array ? !normalized.startsWith("[") : !normalized.startsWith("{"))) {
          throw new IllegalArgumentException("PRESENT observation must be non-empty typed JSON");
        }
        yield normalized;
      }
    };
  }

  private static String sha256(String value) {
    if (value == null || !value.matches("^[0-9a-f]{64}$")) {
      throw new IllegalArgumentException("Plan fingerprint is required");
    }
    return value;
  }

  private static String required(String value, int maximum, String field) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(field + " is required");
    String normalized = value.trim();
    if (normalized.length() > maximum) throw new IllegalArgumentException(field + " is too long");
    return normalized;
  }

  private static String nullableRequired(String value, int maximum, String field) {
    return value == null ? null : required(value, maximum, field);
  }

  private static String nullable(String value, int maximum, String field) {
    if (value == null) return null;
    String normalized = value.trim();
    if (normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is too long");
    }
    return normalized.isEmpty() ? null : normalized;
  }

  private static String normalizedComment(String value) {
    if (value == null) throw new IllegalArgumentException("inspection comment is required");
    String normalized = value.trim();
    if (normalized.length() > 2000) {
      throw new IllegalArgumentException("inspection comment is too long");
    }
    return normalized;
  }

  private static String jsonObject(String value, String field) {
    String normalized = required(value, 65_536, field);
    if (!normalized.startsWith("{")) {
      throw new IllegalArgumentException(field + " must be a JSON object");
    }
    return normalized;
  }

  private static String jsonArray(String value, String field) {
    String normalized = required(value, 262_144, field);
    if (!normalized.startsWith("[")) {
      throw new IllegalArgumentException(field + " must be a JSON array");
    }
    return normalized;
  }

  @PrePersist
  void beforeInsert() {
    OffsetDateTime current = OffsetDateTime.now(ZoneOffset.UTC);
    createdAt = current;
    updatedAt = current;
  }

  @PreUpdate
  void beforeUpdate() {
    updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  @Override
  public UUID getId() {
    return id;
  }

  @Override
  public boolean isNew() {
    return createdAt == null;
  }

  public UUID getInventoryId() {
    return inventoryId;
  }

  public InspectionSource getInspectionSource() { return inspectionSource; }

  public Long getExternalInspectionAssetVersion() { return externalInspectionAssetVersion; }

  public Long getMembershipEventAssetVersion() { return membershipEventAssetVersion; }

  public UUID getMembershipEventWarehouseId() { return membershipEventWarehouseId; }

  public String getMembershipEventStatus() { return membershipEventStatus; }

  public long getRevision() {
    return revision;
  }

  public long getOwnerProofRevision() {
    return ownerProofRevision;
  }

  public boolean isOwnerProofActive() {
    return ownerProofActive;
  }

  public boolean isMembershipActive() {
    return membershipActive;
  }

  public FindingOrigin getOrigin() {
    return origin;
  }

  public InspectionState getInspection() {
    return inspection;
  }

  public ReconciliationState getReconciliation() {
    return reconciliation;
  }

  public UUID getAssetId() {
    return assetId;
  }

  public Long getAssetVersion() {
    return assetVersion;
  }

  public UUID getCurrentWarehouseId() {
    return currentWarehouseId;
  }

  public String getCurrentStatus() {
    return currentStatus;
  }

  public String getCurrentTenantSnapshot() {
    return currentTenantSnapshot;
  }

  public String getCurrentDisplayCanonicalNumber() {
    return currentDisplayCanonicalNumber;
  }

  public String getCurrentPassportSnapshot() {
    return currentPassportSnapshot;
  }

  public String getCurrentContentsSnapshot() {
    return currentContentsSnapshot;
  }

  public String getCurrentRepairsSnapshot() {
    return currentRepairsSnapshot;
  }

  public Long getInspectionAssetVersion() {
    return inspectionAssetVersion;
  }

  public UUID getInspectionWarehouseId() {
    return inspectionWarehouseId;
  }

  public String getInspectionStatus() {
    return inspectionStatus;
  }

  public String getInspectionDisplayCanonicalNumber() {
    return inspectionDisplayCanonicalNumber;
  }

  public String getInspectionTenantSnapshot() {
    return inspectionTenantSnapshot;
  }

  public String getInspectionPassportSnapshot() {
    return inspectionPassportSnapshot;
  }

  public String getInspectionContentsSnapshot() {
    return inspectionContentsSnapshot;
  }

  public String getInspectionRepairsSnapshot() {
    return inspectionRepairsSnapshot;
  }

  public ConflictResolutionStrategy getConflictResolutionStrategy() {
    return conflictResolutionStrategy;
  }

  public String getConflictResolutionCurrentSha256() {
    return conflictResolutionCurrentSha256;
  }

  public String getConflictResolutionReason() {
    return conflictResolutionReason;
  }

  public OffsetDateTime getConflictResolvedAt() {
    return conflictResolvedAt;
  }

  public String getDisplayCanonicalNumber() {
    return displayCanonicalNumber;
  }

  public String getIdentityMatchKey() {
    return identityMatchKey;
  }

  public MutationState getMutationState() {
    return mutationState;
  }

  public String getMaintenancePlanFingerprintSha256() {
    return maintenancePlanFingerprintSha256;
  }

  public UUID getCoverMediaId() {
    return coverMediaId;
  }

  public String getInspectionComment() {
    return inspectionComment;
  }

  public ObservationPresence getPassportObservationState() {
    return passportObservationState;
  }

  public String getPassportObservation() {
    return passportObservation;
  }

  public ObservationPresence getEquipmentObservationState() {
    return equipmentObservationState;
  }

  public String getEquipmentObservation() {
    return equipmentObservation;
  }
}
