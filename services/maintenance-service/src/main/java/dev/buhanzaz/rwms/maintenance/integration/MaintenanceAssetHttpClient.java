package dev.buhanzaz.rwms.maintenance.integration;

import static dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;

/**
 * Owns maintenance's private asset calls and validates the asset-owned versions, leases, and
 * property-disposition fences returned by those calls.
 */
final class MaintenanceAssetHttpClient {
  private static final String ASSET_CLIENT = "maintenance-asset";
  private static final String ASSET_SCOPE = "asset.maintenance";

  private final MaintenanceHttpTransport transport;
  private final String assetBase;

  MaintenanceAssetHttpClient(
      MaintenanceHttpTransport transport, MaintenanceDependencyProperties.Validated properties) {
    this.transport = transport;
    assetBase = MaintenanceHttpTransport.strip(properties.assetBaseUrl().toString())
        + "/api/internal/asset/v1/maintenance";
  }

  AssetSnapshot getRentalItemSnapshot(UUID rentalItemId) {
    try {
      RentalItemSnapshotResponse response = transport.client().get()
          .uri(assetBase + "/rental-items/" + rentalItemId + "/snapshot")
          .header(HttpHeaders.AUTHORIZATION, transport.bearer(ASSET_CLIENT, ASSET_SCOPE))
          .retrieve()
          .body(RentalItemSnapshotResponse.class);
      if (response == null
          || response.id() == null
          || !rentalItemId.equals(response.id())
          || response.version() == null
          || response.version() < 0
          || response.warehouseId() == null
          || response.number() == null
          || response.number().isBlank()
          || response.status() == null
          || response.status().isBlank()) {
        throw MaintenanceHttpTransport.malformed(
            "Asset-service returned malformed rental-item snapshot truth");
      }
      return new AssetSnapshot(
          response.id(), response.version(), response.warehouseId(), response.number(),
          response.status());
    } catch (RuntimeException exception) {
      throw transport.dependencyFailure(exception);
    }
  }

  PropertyAssetSnapshot getPropertyAssetSnapshot(
      PropertyAssetKind assetKind, UUID assetId, UUID warehouseId) {
    if (assetKind == null || assetId == null || warehouseId == null) {
      throw new IllegalArgumentException("Property asset snapshot identity is required");
    }
    try {
      PropertyAssetSnapshotResponse response = transport.client().get()
          .uri(
              assetBase
                  + "/property-assets/"
                  + assetKind.name()
                  + "/"
                  + assetId
                  + "/snapshot?warehouseId="
                  + warehouseId)
          .header(HttpHeaders.AUTHORIZATION, transport.bearer(ASSET_CLIENT, ASSET_SCOPE))
          .retrieve()
          .body(PropertyAssetSnapshotResponse.class);
      return propertyAssetSnapshot(response, assetKind, assetId, warehouseId);
    } catch (RuntimeException exception) {
      throw transport.dependencyFailure(exception);
    }
  }

  PropertyDispositionFence preparePropertyDisposition(
      UUID key, UUID decisionId, PropertyDispositionPreparation request) {
    if (key == null || decisionId == null || request == null) {
      throw new IllegalArgumentException("Property disposition preparation identity is required");
    }
    PropertyDispositionFenceResponse response = transport.post(
        assetBase + "/property-dispositions/" + decisionId + "/prepare",
        key,
        new PreparePropertyDispositionRequest(
            request.warehouseId(),
            request.assetKind().name(),
            request.assetId(),
            request.disposition().name(),
            request.expectedAssetVersion(),
            request.expectedSourceBalanceVersion(),
            request.quantity(),
            request.maintenanceCustodyClaimId(),
            request.maintenanceCustodyVersion(),
            request.contentsMode() == null ? null : request.contentsMode().name(),
            request.contents().stream()
                .map(
                    line ->
                        new PreparePropertyContentRequest(
                            line.equipmentId(),
                            line.expectedBalanceVersion(),
                            line.currentQuantity(),
                            line.moveQuantity()))
                .toList(),
            request.authorizedMaintenanceLease() == null
                ? null
                : new PropertyDispositionLeaseProofRequest(
                    request.authorizedMaintenanceLease().leaseId(),
                    request.authorizedMaintenanceLease().fencingToken(),
                    request.authorizedMaintenanceLease().ownerType(),
                    request.authorizedMaintenanceLease().ownerId())),
        PropertyDispositionFenceResponse.class,
        ASSET_CLIENT,
        ASSET_SCOPE);
    return propertyFence(response, decisionId, request);
  }

  PropertyDispositionEffect applyPropertyDisposition(
      UUID key, UUID decisionId, UUID completedMovementTaskId) {
    if (key == null || decisionId == null) {
      throw new IllegalArgumentException("Property disposition effect identity is required");
    }
    PropertyDispositionEffectResponse response = transport.post(
        assetBase + "/property-dispositions/" + decisionId + "/apply",
        key,
        new ApplyPropertyDispositionRequest(completedMovementTaskId),
        PropertyDispositionEffectResponse.class,
        ASSET_CLIENT,
        ASSET_SCOPE);
    if (response == null
        || response.effectId() == null
        || !decisionId.equals(response.decisionId())
        || response.assetKind() == null
        || response.assetId() == null
        || response.disposition() == null
        || response.appliedAt() == null) {
      throw MaintenanceHttpTransport.malformed(
          "Asset-service returned malformed property disposition effect");
    }
    try {
      return new PropertyDispositionEffect(
          response.effectId(),
          response.decisionId(),
          PropertyAssetKind.valueOf(response.assetKind()),
          response.assetId(),
          PropertyDispositionKind.valueOf(response.disposition()),
          response.assetVersion(),
          response.appliedAt());
    } catch (IllegalArgumentException exception) {
      throw MaintenanceHttpTransport.malformed(
          "Asset-service returned unknown property disposition effect truth");
    }
  }

  List<MaintenanceFurnitureCustodyClaim> unresolvedFurnitureCustody(
      String ownerType, UUID ownerId) {
    if (!("MAINTENANCE_ESTIMATE".equals(ownerType)
            || "MAINTENANCE_REPAIR".equals(ownerType))
        || ownerId == null) {
      throw new IllegalArgumentException("Maintenance furniture custody owner is invalid");
    }
    try {
      MaintenanceFurnitureCustodyClaim[] response = transport.client().get()
          .uri(
              assetBase + "/furniture-custody?ownerType={ownerType}&ownerId={ownerId}",
              ownerType,
              ownerId)
          .header(HttpHeaders.AUTHORIZATION, transport.bearer(ASSET_CLIENT, ASSET_SCOPE))
          .retrieve()
          .body(MaintenanceFurnitureCustodyClaim[].class);
      if (response == null) {
        throw MaintenanceHttpTransport.malformed(
            "Asset-service returned no furniture custody truth");
      }
      List<MaintenanceFurnitureCustodyClaim> result = List.of(response);
      if (result.stream().anyMatch(
              claim -> !ownerType.equals(claim.ownerType()) || !ownerId.equals(claim.ownerId()))
          || result.stream().map(MaintenanceFurnitureCustodyClaim::id).distinct().count()
              != result.size()) {
        throw MaintenanceHttpTransport.malformed(
            "Asset-service returned mismatched furniture custody truth");
      }
      return result;
    } catch (RuntimeException exception) {
      throw transport.dependencyFailure(exception);
    }
  }

  FurnitureEquipmentSnapshot ensureFurnitureEquipment(UUID catalogNodeId, String equipmentName) {
    if (catalogNodeId == null) {
      throw new IllegalArgumentException(
          "Catalog node identity is required for furniture equipment");
    }
    String canonicalName = canonicalEquipmentName(equipmentName);
    try {
      MaintenanceFurnitureEquipmentResponse response = transport.client().post()
          .uri(assetBase + "/equipment-catalog")
          .header("Idempotency-Key", catalogNodeId.toString())
          .header(HttpHeaders.AUTHORIZATION, transport.bearer(ASSET_CLIENT, ASSET_SCOPE))
          .body(new EnsureFurnitureEquipmentRequest(catalogNodeId, canonicalName))
          .retrieve()
          .body(MaintenanceFurnitureEquipmentResponse.class);
      if (response == null
          || !catalogNodeId.equals(response.externalReferenceId())
          || response.equipmentId() == null
          || !canonicalName.equals(response.equipmentName())) {
        throw MaintenanceHttpTransport.malformed(
            "Asset-service returned mismatched furniture equipment truth");
      }
      return new FurnitureEquipmentSnapshot(response.equipmentId(), response.equipmentName());
    } catch (RuntimeException exception) {
      throw transport.furnitureDependencyFailure(exception);
    }
  }

  List<CabinCharacteristicSnapshot> cabinCharacteristics() {
    try {
      CabinCharacteristicResponse[] response = transport.client()
          .get()
          .uri(assetBase + "/cabin-characteristics")
          .header(HttpHeaders.AUTHORIZATION, transport.bearer(ASSET_CLIENT, ASSET_SCOPE))
          .retrieve()
          .body(CabinCharacteristicResponse[].class);
      if (response == null) {
        throw MaintenanceHttpTransport.malformed(
            "Asset-service returned empty cabin characteristic truth");
      }
      List<CabinCharacteristicSnapshot> result = java.util.Arrays.stream(response)
          .map(value -> new CabinCharacteristicSnapshot(value.id(), value.name()))
          .toList();
      if (result.stream().map(CabinCharacteristicSnapshot::characteristicId).distinct().count()
          != result.size()) {
        throw MaintenanceHttpTransport.malformed(
            "Asset-service returned duplicate cabin characteristic identities");
      }
      return result;
    } catch (RuntimeException exception) {
      throw transport.dependencyFailure(exception);
    }
  }

  AppliedCabinCharacteristic applyCabinCharacteristic(
      UUID key, UUID rentalItemId, UUID characteristicId) {
    AppliedCabinCharacteristic response;
    try {
      response = transport.client()
          .put()
          .uri(assetBase + "/rental-items/" + rentalItemId + "/characteristics/" + characteristicId)
          .header("Idempotency-Key", key.toString())
          .header(HttpHeaders.AUTHORIZATION, transport.bearer(ASSET_CLIENT, ASSET_SCOPE))
          .retrieve()
          .body(AppliedCabinCharacteristic.class);
    } catch (RuntimeException exception) {
      throw transport.dependencyFailure(exception);
    }
    if (response == null) {
      throw MaintenanceHttpTransport.malformed(
          "Asset-service returned an empty characteristic result");
    }
    if (!rentalItemId.equals(response.rentalItemId())
        || !characteristicId.equals(response.characteristicId())) {
      throw MaintenanceHttpTransport.malformed(
          "Asset-service returned another applied cabin characteristic");
    }
    return response;
  }

  LeaseSnapshot acquireLease(
      UUID key, UUID rentalItemId, long expectedVersion, String ownerType, String ownerId) {
    LeaseResponse response = transport.post(
        assetBase + "/operation-leases",
        key,
        new AcquireLeaseRequest(rentalItemId, ownerType, UUID.fromString(ownerId), expectedVersion),
        LeaseResponse.class,
        ASSET_CLIENT,
        ASSET_SCOPE);
    return lease(response, rentalItemId, ownerType, ownerId);
  }

  LeaseSnapshot renewLease(
      UUID key,
      UUID leaseId,
      long expectedVersion,
      long fencingToken,
      String ownerType,
      String ownerId) {
    LeaseResponse response = transport.put(
        assetBase + "/operation-leases/" + leaseId + "/renew",
        key,
        new LeaseCommand(expectedVersion, fencingToken, ownerType, UUID.fromString(ownerId)),
        LeaseResponse.class,
        ASSET_CLIENT,
        ASSET_SCOPE);
    validateLease(response, response.rentalItemId(), ownerType, ownerId, "ACTIVE");
    if (!leaseId.equals(response.id())
        || response.version() != Math.addExact(expectedVersion, 1)
        || response.fencingToken() != fencingToken) {
      throw MaintenanceHttpTransport.malformed(
          "Asset-service returned malformed renewed lease truth");
    }
    return snapshot(response);
  }

  AssetSnapshot fencedStatus(
      UUID key,
      UUID rentalItemId,
      UUID warehouseId,
      long expectedVersion,
      UUID leaseId,
      long fencingToken,
      String ownerType,
      String ownerId,
      String transition,
      boolean linkedReturn,
      List<FurniturePendingReturn> furniturePendingReturns) {
    String action = switch (transition) {
      case "EMPTY_ESTIMATE_TO_FREE" -> "COMPLETE_EMPTY_ESTIMATE";
      case "EMPTY_REPAIR_TO_FREE" -> "COMPLETE_EMPTY_REPAIR";
      case "QUEUE_TO_REPAIR" -> "QUEUE_FOR_REPAIR";
      case "QUEUE_TO_CAPITAL_REPAIR" -> "QUEUE_FOR_CAPITAL_REPAIR";
      case "PENDING_ACCEPTANCE" -> "MARK_PENDING_ACCEPTANCE";
      case "ACCEPT_TO_FREE" -> "ACCEPT_REPAIR";
      case "WRITE_OFF" -> "WRITE_OFF";
      default -> throw new IllegalArgumentException("Unsupported asset transition");
    };
    UUID owner = UUID.fromString(ownerId);
    RentalItemResponse response = transport.put(
        assetBase + "/rental-items/" + rentalItemId + "/fenced-status",
        key,
        new FencedStatusRequest(
            expectedVersion,
            action,
            leaseId,
            fencingToken,
            ownerType,
            owner,
            linkedReturn ? owner : null,
            List.copyOf(furniturePendingReturns)),
        RentalItemResponse.class,
        ASSET_CLIENT,
        ASSET_SCOPE);
    String expectedStatus = switch (transition) {
      case "EMPTY_ESTIMATE_TO_FREE", "EMPTY_REPAIR_TO_FREE", "ACCEPT_TO_FREE" -> "FREE";
      case "QUEUE_TO_REPAIR" -> "REPAIR";
      case "QUEUE_TO_CAPITAL_REPAIR" -> "CAPITAL_REPAIR";
      case "PENDING_ACCEPTANCE" -> "WAITING_REPAIR_CHECK";
      case "WRITE_OFF" -> "WRITTEN_OFF";
      default -> throw new IllegalArgumentException("Unsupported asset transition");
    };
    long advancedVersion = Math.addExact(expectedVersion, 1);
    boolean versionMatches = response.version() == advancedVersion
        || (("QUEUE_TO_REPAIR".equals(transition) || "QUEUE_TO_CAPITAL_REPAIR".equals(transition))
            && response.version() == expectedVersion);
    if (!rentalItemId.equals(response.id())
        || !warehouseId.equals(response.warehouseId())
        || !versionMatches
        || !expectedStatus.equals(response.status())) {
      throw MaintenanceHttpTransport.malformed(
          "Asset-service returned malformed fenced rental-item truth");
    }
    return new AssetSnapshot(
        response.id(),
        response.version(),
        response.warehouseId(),
        response.number(),
        response.status());
  }

  void releaseLease(
      UUID key,
      UUID leaseId,
      long expectedVersion,
      long fencingToken,
      String ownerType,
      String ownerId) {
    LeaseResponse response = transport.put(
        assetBase + "/operation-leases/" + leaseId + "/release",
        key,
        new LeaseCommand(expectedVersion, fencingToken, ownerType, UUID.fromString(ownerId)),
        LeaseResponse.class,
        ASSET_CLIENT,
        ASSET_SCOPE);
    validateLease(response, response.rentalItemId(), ownerType, ownerId, "RELEASED");
    if (!leaseId.equals(response.id())
        || response.version() != Math.addExact(expectedVersion, 1)
        || response.fencingToken() != fencingToken) {
      throw MaintenanceHttpTransport.malformed("Asset-service did not confirm lease release");
    }
  }

  private static LeaseSnapshot lease(
      LeaseResponse response, UUID rentalItemId, String ownerType, String ownerId) {
    validateLease(response, rentalItemId, ownerType, ownerId, "ACTIVE");
    return snapshot(response);
  }

  private static void validateLease(
      LeaseResponse response,
      UUID rentalItemId,
      String ownerType,
      String ownerId,
      String state) {
    if (response == null
        || response.id() == null
        || !rentalItemId.equals(response.rentalItemId())
        || !ownerType.equals(response.ownerType())
        || !ownerId.equals(response.ownerId())
        || response.version() < 0
        || response.fencingToken() < 1
        || response.expiresAt() == null
        || !state.equals(response.state())) {
      throw MaintenanceHttpTransport.malformed("Asset-service returned malformed lease truth");
    }
  }

  private static LeaseSnapshot snapshot(LeaseResponse response) {
    return new LeaseSnapshot(
        response.id(),
        response.version(),
        response.rentalItemId(),
        response.ownerType(),
        UUID.fromString(response.ownerId()),
        response.fencingToken(),
        response.expiresAt());
  }

  private static PropertyAssetSnapshot propertyAssetSnapshot(
      PropertyAssetSnapshotResponse response,
      PropertyAssetKind expectedKind,
      UUID expectedAssetId,
      UUID expectedWarehouseId) {
    if (response == null
        || response.assetKind() == null
        || response.assetId() == null
        || response.assetDisplayName() == null
        || response.warehouseId() == null
        || response.version() == null
        || response.contents() == null
        || !expectedAssetId.equals(response.assetId())
        || !expectedWarehouseId.equals(response.warehouseId())) {
      throw MaintenanceHttpTransport.malformed(
          "Asset-service returned malformed property asset snapshot");
    }
    try {
      PropertyAssetKind actualKind = PropertyAssetKind.valueOf(response.assetKind());
      if (actualKind != expectedKind) {
        throw MaintenanceHttpTransport.malformed(
            "Asset-service returned another property asset kind");
      }
      List<PropertyAssetContentSnapshot> contents = response.contents().stream()
          .map(
              line ->
                  new PropertyAssetContentSnapshot(
                      line.equipmentId(),
                      line.equipmentName(),
                      line.equipmentFormat(),
                      requiredNonNegative(
                          line.balanceVersion(), "Property content balance version"),
                      requiredPositive(line.quantity(), "Property content quantity")))
          .toList();
      return new PropertyAssetSnapshot(
          actualKind,
          response.assetId(),
          response.assetDisplayName(),
          response.warehouseId(),
          requiredNonNegative(response.version(), "Property asset version"),
          response.status(),
          response.quantity(),
          response.sourceBalanceVersion(),
          contents,
          response.activeReservation(),
          response.activeHold(),
          response.activeLease(),
          response.dispositionAllowed());
    } catch (IllegalArgumentException exception) {
      throw MaintenanceHttpTransport.malformed(
          "Asset-service returned invalid property asset snapshot truth");
    }
  }

  private static PropertyDispositionFence propertyFence(
      PropertyDispositionFenceResponse response,
      UUID expectedDecisionId,
      PropertyDispositionPreparation request) {
    if (response == null
        || !expectedDecisionId.equals(response.decisionId())
        || !request.warehouseId().equals(response.warehouseId())
        || !request.assetId().equals(response.assetId())
        || response.assetKind() == null
        || response.disposition() == null
        || response.contents() == null
        || response.preparedAt() == null) {
      throw MaintenanceHttpTransport.malformed(
          "Asset-service returned malformed property disposition fence");
    }
    try {
      PropertyAssetKind assetKind = PropertyAssetKind.valueOf(response.assetKind());
      PropertyDispositionKind disposition = PropertyDispositionKind.valueOf(response.disposition());
      if (assetKind != request.assetKind()
          || disposition != request.disposition()
          || !java.util.Objects.equals(
              response.maintenanceCustodyClaimId(), request.maintenanceCustodyClaimId())
          || !java.util.Objects.equals(
              response.maintenanceCustodyVersion(), request.maintenanceCustodyVersion())) {
        throw MaintenanceHttpTransport.malformed(
            "Asset-service returned another property disposition fence");
      }
      List<PropertyDispositionContent> contents = response.contents().stream()
          .map(
              line ->
                  new PropertyDispositionContent(
                      line.equipmentId(),
                      requiredNonNegative(
                          line.expectedBalanceVersion(), "Prepared content balance version"),
                      requiredPositive(line.currentQuantity(), "Prepared content quantity"),
                      requiredNonNegative(
                          line.moveQuantity(), "Prepared content movement quantity")))
          .toList();
      return new PropertyDispositionFence(
          response.decisionId(),
          response.state(),
          response.requestSha256(),
          response.warehouseId(),
          assetKind,
          response.assetId(),
          disposition,
          response.maintenanceCustodyClaimId(),
          response.maintenanceCustodyVersion(),
          contents,
          response.preparedAt(),
          response.appliedAt());
    } catch (IllegalArgumentException exception) {
      throw MaintenanceHttpTransport.malformed(
          "Asset-service returned invalid property disposition fence truth");
    }
  }

  private static long requiredNonNegative(Long value, String field) {
    if (value == null || value < 0) {
      throw MaintenanceHttpTransport.malformed(field + " is invalid");
    }
    return value;
  }

  private static long requiredPositive(Long value, String field) {
    if (value == null || value < 1) {
      throw MaintenanceHttpTransport.malformed(field + " is invalid");
    }
    return value;
  }

  private static String canonicalEquipmentName(String value) {
    String canonical = value == null ? "" : value.trim();
    if (canonical.isEmpty() || canonical.length() > 255) {
      throw new IllegalArgumentException("Furniture equipment name is invalid");
    }
    return canonical;
  }

  /**
   * Carries the observed rental-item version and maintenance owner identity used to acquire a new
   * asset-owned operation lease.
   */
  private record AcquireLeaseRequest(
      UUID rentalItemId, String ownerType, UUID ownerId, long expectedRentalItemVersion) {}

  /** Identifies the catalog-derived furniture equipment that asset-service must materialize. */
  private record EnsureFurnitureEquipmentRequest(UUID externalReferenceId, String equipmentName) {}

  /** Returns the asset-owned equipment identity resolved for a maintenance catalog position. */
  private record MaintenanceFurnitureEquipmentResponse(
      UUID externalReferenceId, UUID equipmentId, String equipmentName) {}

  /**
   * Supplies the version, fencing token and owner proof required to renew or release an existing
   * asset lease.
   */
  private record LeaseCommand(
      long expectedVersion, long fencingToken, String ownerType, UUID ownerId) {}

  /**
   * Describes an asset status transition fenced by the current maintenance lease and, for return
   * processing, the linked estimate and pending furniture evidence.
   */
  private record FencedStatusRequest(
      long expectedVersion,
      String action,
      UUID leaseId,
      long fencingToken,
      String ownerType,
      UUID ownerId,
      UUID linkedReturnEstimateId,
      List<FurniturePendingReturn> furniturePendingReturns) {}

  /**
   * Raw asset-service lease snapshot whose aggregate identity, owner and state are validated before
   * maintenance accepts it as a fencing authority.
   */
  private record LeaseResponse(
      UUID id,
      long version,
      UUID rentalItemId,
      String ownerType,
      String ownerId,
      long fencingToken,
      String state,
      OffsetDateTime expiresAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** Read-side rental-item snapshot retaining nullable transport fields for explicit validation. */
  private record RentalItemSnapshotResponse(
      UUID id, Long version, UUID warehouseId, String number, String status) {}

  /** Mutation response for a rental item after an asset-owned fenced status transition. */
  private record RentalItemResponse(
      UUID id, long version, UUID warehouseId, String number, String status) {}

  /** Captures one equipment balance and its observed version inside a property asset snapshot. */
  private record PropertyAssetContentSnapshotResponse(
      UUID equipmentId,
      String equipmentName,
      String equipmentFormat,
      Long balanceVersion,
      Long quantity) {}

  /**
   * Asset-owned disposition preflight snapshot, including aggregate versions, contents and active
   * reservation, hold and lease blockers.
   */
  private record PropertyAssetSnapshotResponse(
      String assetKind,
      UUID assetId,
      String assetDisplayName,
      UUID warehouseId,
      Long version,
      String status,
      Long quantity,
      Long sourceBalanceVersion,
      List<PropertyAssetContentSnapshotResponse> contents,
      boolean activeReservation,
      boolean activeHold,
      boolean activeLease,
      boolean dispositionAllowed) {}

  /**
   * Freezes one proposed content movement against the balance version and quantity observed during
   * maintenance decision preparation.
   */
  private record PreparePropertyContentRequest(
      UUID equipmentId, long expectedBalanceVersion, long currentQuantity, long moveQuantity) {}

  /** Proves that a cabin disposition is authorized by the current maintenance-owned asset lease. */
  private record PropertyDispositionLeaseProofRequest(
      UUID leaseId, long fencingToken, String ownerType, UUID ownerId) {}

  /**
   * Sends the complete immutable maintenance disposition decision to asset-service for fenced
   * preparation before any physical movement effect is applied.
   */
  private record PreparePropertyDispositionRequest(
      UUID warehouseId,
      String assetKind,
      UUID assetId,
      String disposition,
      Long expectedAssetVersion,
      Long expectedSourceBalanceVersion,
      Long quantity,
      UUID maintenanceCustodyClaimId,
      Long maintenanceCustodyVersion,
      String contentsMode,
      List<PreparePropertyContentRequest> contents,
      PropertyDispositionLeaseProofRequest authorizedMaintenanceLease) {}

  /**
   * Asset-owned content fence returned for one prepared equipment movement, preserving the exact
   * source balance and quantities that may later be applied.
   */
  private record PropertyDispositionPreparedContentResponse(
      UUID equipmentId,
      UUID sourceBalanceId,
      Long expectedBalanceVersion,
      Long currentQuantity,
      Long moveQuantity,
      Long dispositionQuantity) {}

  /**
   * Durable asset-side prepare/apply receipt used to reconcile a disposition by decision identity
   * and canonical request hash.
   */
  private record PropertyDispositionFenceResponse(
      UUID decisionId,
      String state,
      String requestSha256,
      UUID warehouseId,
      String assetKind,
      UUID assetId,
      String disposition,
      UUID maintenanceCustodyClaimId,
      Long maintenanceCustodyVersion,
      List<PropertyDispositionPreparedContentResponse> contents,
      Instant preparedAt,
      Instant appliedAt) {}

  /** Supplies the completed logistics movement task that authorizes the prepared asset effect. */
  private record ApplyPropertyDispositionRequest(UUID completedMovementTaskId) {}

  /**
   * Asset-owned terminal effect receipt used to make retries converge on the same disposition and
   * resulting aggregate version.
   */
  private record PropertyDispositionEffectResponse(
      UUID effectId,
      UUID decisionId,
      String assetKind,
      UUID assetId,
      String disposition,
      Long assetVersion,
      Instant appliedAt) {}

  /** Represents one asset-owned cabin characteristic exposed to maintenance catalog workflows. */
  private record CabinCharacteristicResponse(UUID id, String name) {}
}
