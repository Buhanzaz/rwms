package dev.buhanzaz.rwms.inventory.domain;

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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "inventory_finding")
public class InventoryFinding {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
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

  @Enumerated(EnumType.STRING)
  @Column(name = "origin", nullable = false, length = 32)
  private FindingOrigin origin;

  @Enumerated(EnumType.STRING)
  @Column(name = "inspection", nullable = false, length = 24)
  private InspectionState inspection;

  @Enumerated(EnumType.STRING)
  @Column(name = "reconciliation", nullable = false, length = 24)
  private ReconciliationState reconciliation;

  @Column(name = "asset_id")
  private UUID assetId;

  @Column(name = "asset_version_snapshot")
  private Long assetVersion;

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
        displayNumber,
        matchKey,
        ReconciliationState.MISSING,
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
    if (origin == null || origin == FindingOrigin.EXPECTED) {
      throw new IllegalArgumentException("Unexpected finding origin is required");
    }
    return create(
        inventoryId,
        null,
        origin,
        assetId,
        assetVersion,
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
      String displayNumber,
      String matchKey,
      ReconciliationState reconciliation,
      String actorRef) {
    if (inventoryId == null
        || origin == null
        || reconciliation == null
        || (assetId == null) != (assetVersion == null)
        || (assetVersion != null && assetVersion < 0)) {
      throw new IllegalArgumentException("Finding ownership and asset snapshot are invalid");
    }
    InventoryFinding value = new InventoryFinding();
    value.inventoryId = inventoryId;
    value.expectedItemId = expectedItemId;
    value.origin = origin;
    value.inspection = InspectionState.NOT_INSPECTED;
    value.reconciliation = reconciliation;
    value.assetId = assetId;
    value.assetVersion = assetVersion;
    value.displayCanonicalNumber = required(displayNumber, 128, "display number");
    value.identityMatchKey = required(matchKey, 128, "identity match key");
    value.passportObservationState = ObservationPresence.ABSENT;
    value.equipmentObservationState = ObservationPresence.ABSENT;
    value.mutationState = MutationState.IDLE;
    value.ownerProofActive = true;
    value.actorRef = required(actorRef, 2000, "actor reference");
    return value;
  }

  public boolean transitionOwnerProof(boolean active) {
    if (ownerProofActive == active) return false;
    ownerProofRevision = Math.addExact(ownerProofRevision, 1);
    ownerProofActive = active;
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

  public void attachCreatedAsset(UUID createdAssetId, long createdAssetVersion) {
    if (mutationState != MutationState.SOURCE_CREATE_PENDING
        || createdAssetId == null
        || createdAssetVersion < 0) {
      throw new IllegalStateException("Source asset completion is not valid in the current state");
    }
    assetId = createdAssetId;
    assetVersion = createdAssetVersion;
    reconciliation = ReconciliationState.MATCHED;
    mutationState = MutationState.SOURCE_CREATED;
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
    actorRef = required(nextActorRef, 2000, "actor reference");
  }

  public void markMissing(String nextActorRef) {
    reconciliation = ReconciliationState.MISSING;
    actorRef = required(nextActorRef, 2000, "actor reference");
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

  public UUID getId() {
    return id;
  }

  public UUID getInventoryId() {
    return inventoryId;
  }

  public long getRevision() {
    return revision;
  }

  public long getOwnerProofRevision() {
    return ownerProofRevision;
  }

  public boolean isOwnerProofActive() {
    return ownerProofActive;
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
