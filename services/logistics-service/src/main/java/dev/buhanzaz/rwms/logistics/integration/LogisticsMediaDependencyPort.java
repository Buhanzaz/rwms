package dev.buhanzaz.rwms.logistics.integration;

import static dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyFailures.unavailable;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.CabinCoverChange;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.CabinMediaSnapshot;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.ContractorEvidenceMediaReceipt;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.CustomerProfileMediaOwnerProof;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.CustomerProfileMediaValidation;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.LogisticsOwnerType;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.MediaContent;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.MediaOwnerProof;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.MediaReference;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.MediaValidation;
import java.util.List;
import java.util.UUID;

/** Media-service-owned validation, owner-proof and sanitized cabin-media read port. */
interface LogisticsMediaDependencyPort {
  MediaValidation validateMediaReferences(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      List<MediaReference> references);

  /** Validates one READY CustomerApp avatar against its profile, warehouse and subject proof. */
  CustomerProfileMediaValidation validateCustomerProfileMediaReference(
      UUID profileId,
      UUID warehouseId,
      UUID authorizedSubjectId,
      MediaReference reference);

  /** Upserts a non-customer media owner proof without a subject restriction. */
  default MediaOwnerProof upsertMediaOwnerProof(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      UUID proofEventId,
      boolean active) {
    return upsertMediaOwnerProof(
        ownerType,
        documentId,
        lineId,
        warehouseId,
        ownerRevision,
        aggregateVersion,
        proofEventId,
        null,
        active);
  }

  /** Upserts a proof optionally restricted to one CustomerApp subject for shipment evidence. */
  MediaOwnerProof upsertMediaOwnerProof(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      UUID proofEventId,
      UUID authorizedSubjectId,
      boolean active);

  /** Establishes or exactly replays one subject-bound CustomerApp profile-avatar owner proof. */
  CustomerProfileMediaOwnerProof upsertCustomerProfileMediaOwnerProof(
      UUID profileId,
      UUID warehouseId,
      UUID authorizedSubjectId,
      UUID proofEventId);

  default CabinCoverChange setCabinCoverFromTaskEvidence(
      UUID idempotencyKey, UUID cabinId, UUID taskBoardEntryId, UUID evidenceMediaId) {
    throw unavailable("Cabin cover transition is not configured");
  }

  default List<CabinMediaSnapshot> readCabinMediaSnapshots(
      UUID warehouseId, List<UUID> cabinIds) {
    throw unavailable("Presentation media snapshots are not configured");
  }

  default MediaContent readCabinPresentationMedia(
      UUID warehouseId, UUID cabinId, UUID mediaId, long generation, String variant) {
    throw unavailable("Presentation media content is not configured");
  }

  /** Streams one reserved contractor evidence image through the private media owner boundary. */
  default ContractorEvidenceMediaReceipt uploadContractorTaskEvidence(
      UUID warehouseId,
      UUID workerId,
      UUID entryId,
      UUID evidenceId,
      String contentType,
      String sha256,
      byte[] bytes) {
    throw unavailable("Contractor task evidence media upload is not configured");
  }

  /** Reads one exact contractor-owned immutable image variant through media-service. */
  default MediaContent readContractorTaskMedia(
      UUID warehouseId,
      UUID workerId,
      UUID entryId,
      UUID mediaId,
      long generation,
      String variant) {
    throw unavailable("Contractor task media content is not configured");
  }
}
