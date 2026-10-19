package dev.buhanzaz.rwms.logistics.integration;

import static dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.*;
import static dev.buhanzaz.rwms.logistics.integration.LogisticsOAuthHttpTransport.FailurePolicy.DEFAULT;
import static dev.buhanzaz.rwms.logistics.integration.LogisticsOAuthHttpTransport.defaultFailure;
import static dev.buhanzaz.rwms.logistics.integration.LogisticsOAuthHttpTransport.malformed;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Private media-service client for logistics ownership proof and cabin presentation media.
 *
 * <p>It retains the source's response validation at the media boundary, including binary content
 * type fallback, while the shared transport owns token and error policy.
 */
final class LogisticsMediaDependencyClient {
  private static final String MEDIA_CLIENT = "logistics-media";
  private static final String MEDIA_SCOPE = "media.logistics";
  private static final String CUSTOMER_PROFILE_OWNER_TYPE = "LOGISTICS_CUSTOMER_PROFILE";
  private static final String CUSTOMER_PROFILE_CONTEXT = "PROFILE_AVATAR";

  private final LogisticsOAuthHttpTransport transport;
  private final String mediaBase;

  LogisticsMediaDependencyClient(LogisticsOAuthHttpTransport transport, String mediaBase) {
    this.transport = transport;
    this.mediaBase = mediaBase;
  }

  MediaValidation validateMediaReferences(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      List<MediaReference> references) {
    MediaValidationResponse response =
        transport.postWithoutIdempotency(
            mediaBase + "/logistics/references/validate",
            new MediaValidationRequest(
                ownerType.name(),
                documentId,
                lineId,
                warehouseId,
                references.stream()
                    .map(
                        reference ->
                            new MediaReferenceRequest(reference.mediaId(), reference.generation()))
                    .toList()),
            MediaValidationResponse.class,
            MEDIA_CLIENT,
            MEDIA_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    if (response == null
        || ownerType.name() == null
        || !ownerType.name().equals(response.ownerType())
        || !documentId.equals(response.documentId())
        || !lineId.equals(response.lineId())
        || !warehouseId.equals(response.warehouseId())
        || response.references() == null) {
      throw malformed("Media-service returned an invalid logistics validation");
    }
    List<MediaReference> validated =
        response.references().stream()
            .map(reference -> new MediaReference(reference.mediaId(), reference.generation()))
            .toList();
    if (!Set.copyOf(validated).equals(Set.copyOf(references))) {
      throw malformed("Media-service returned mismatched logistics references");
    }
    return new MediaValidation(ownerType, documentId, lineId, warehouseId, validated);
  }

  CustomerProfileMediaValidation validateCustomerProfileMediaReference(
      UUID profileId,
      UUID warehouseId,
      UUID authorizedSubjectId,
      MediaReference reference) {
    if (profileId == null
        || warehouseId == null
        || authorizedSubjectId == null
        || reference == null
        || reference.mediaId() == null
        || reference.generation() < 1) {
      throw malformed("Customer profile avatar reference is invalid");
    }
    CustomerProfileMediaValidationResponse response =
        transport.postWithoutIdempotency(
            mediaBase + "/logistics/references/validate",
            new CustomerProfileMediaValidationRequest(
                CUSTOMER_PROFILE_OWNER_TYPE,
                profileId,
                warehouseId,
                authorizedSubjectId,
                CUSTOMER_PROFILE_CONTEXT,
                List.of(new MediaReferenceRequest(reference.mediaId(), reference.generation()))),
            CustomerProfileMediaValidationResponse.class,
            MEDIA_CLIENT,
            MEDIA_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    if (response == null
        || !CUSTOMER_PROFILE_OWNER_TYPE.equals(response.ownerType())
        || !profileId.equals(response.ownerId())
        || !warehouseId.equals(response.warehouseId())
        || !authorizedSubjectId.equals(response.authorizedSubjectId())
        || !CUSTOMER_PROFILE_CONTEXT.equals(response.context())
        || response.references() == null
        || response.references().size() != 1
        || !reference.mediaId().equals(response.references().getFirst().mediaId())
        || reference.generation() != response.references().getFirst().generation()) {
      throw malformed("Media-service returned a mismatched customer profile avatar");
    }
    return new CustomerProfileMediaValidation(
        profileId, warehouseId, authorizedSubjectId, reference);
  }

  MediaOwnerProof upsertMediaOwnerProof(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      UUID proofEventId,
      UUID authorizedSubjectId,
      boolean active) {
    if (ownerType == null
        || documentId == null
        || lineId == null
        || warehouseId == null
        || ownerRevision < 0
        || aggregateVersion < 0
        || proofEventId == null) {
      throw malformed("Logistics media owner proof is invalid");
    }
    if ((ownerType == LogisticsOwnerType.LOGISTICS_SHIPMENT) != (authorizedSubjectId != null)) {
      throw malformed("Shipment media owner proof subject is invalid");
    }
    MediaOwnerProofResponse response =
        transport.postWithoutIdempotency(
            mediaBase + "/owner-proofs",
            new MediaOwnerProofRequest(
                ownerType.name(),
                documentId,
                lineId,
                warehouseId,
                ownerRevision,
                aggregateVersion,
                proofEventId,
                authorizedSubjectId,
                active),
            MediaOwnerProofResponse.class,
            MEDIA_CLIENT,
            MEDIA_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    if (!ownerType.name().equals(response.ownerType())
        || !documentId.equals(response.documentId())
        || !lineId.equals(response.lineId())
        || !warehouseId.equals(response.warehouseId())
        || response.ownerRevision() == null
        || response.aggregateVersion() == null
        || response.active() == null
        || ownerRevision != response.ownerRevision()
        || aggregateVersion != response.aggregateVersion()
        || !proofEventId.equals(response.proofEventId())
        || !Objects.equals(authorizedSubjectId, response.authorizedSubjectId())
        || active != response.active()) {
      throw malformed("Media-service returned a mismatched logistics owner proof");
    }
    return new MediaOwnerProof(
        ownerType,
        documentId,
        lineId,
        warehouseId,
        ownerRevision,
        aggregateVersion,
        proofEventId,
        authorizedSubjectId,
        active);
  }

  CustomerProfileMediaOwnerProof upsertCustomerProfileMediaOwnerProof(
      UUID profileId,
      UUID warehouseId,
      UUID authorizedSubjectId,
      UUID proofEventId) {
    if (profileId == null
        || warehouseId == null
        || authorizedSubjectId == null
        || proofEventId == null) {
      throw malformed("Customer profile media owner proof is invalid");
    }
    CustomerProfileMediaOwnerProofResponse response =
        transport.postWithoutIdempotency(
            mediaBase + "/owner-proofs",
            new CustomerProfileMediaOwnerProofRequest(
                CUSTOMER_PROFILE_OWNER_TYPE,
                profileId,
                warehouseId,
                0,
                0,
                proofEventId,
                authorizedSubjectId,
                true),
            CustomerProfileMediaOwnerProofResponse.class,
            MEDIA_CLIENT,
            MEDIA_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    if (response == null
        || !CUSTOMER_PROFILE_OWNER_TYPE.equals(response.ownerType())
        || !profileId.equals(response.ownerId())
        || !warehouseId.equals(response.warehouseId())
        || response.ownerRevision() == null
        || response.ownerRevision() != 0
        || response.aggregateVersion() == null
        || response.aggregateVersion() != 0
        || !proofEventId.equals(response.proofEventId())
        || !authorizedSubjectId.equals(response.authorizedSubjectId())
        || !Boolean.TRUE.equals(response.active())) {
      throw malformed("Media-service returned a mismatched customer profile owner proof");
    }
    return new CustomerProfileMediaOwnerProof(
        profileId, warehouseId, authorizedSubjectId, 0, 0, proofEventId, true);
  }

  CabinCoverChange setCabinCoverFromTaskEvidence(
      UUID idempotencyKey, UUID cabinId, UUID taskBoardEntryId, UUID evidenceMediaId) {
    CabinCoverChangeResponse response =
        transport.post(
            mediaBase + "/logistics/cabins/" + cabinId + "/cover-from-task-evidence",
            idempotencyKey,
            new SetCabinCoverFromTaskEvidenceRequest(taskBoardEntryId, evidenceMediaId),
            CabinCoverChangeResponse.class,
            MEDIA_CLIENT,
            MEDIA_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    if (response.cabinId() == null
        || !cabinId.equals(response.cabinId())
        || response.warehouseId() == null
        || response.coverMediaId() == null
        || !evidenceMediaId.equals(response.coverMediaId())
        || response.generation() < 1
        || response.taskBoardEntryId() == null
        || !taskBoardEntryId.equals(response.taskBoardEntryId())
        || response.version() < 0
        || response.changedAt() == null) {
      throw malformed("Media-service returned a mismatched cabin cover");
    }
    return new CabinCoverChange(
        response.cabinId(),
        response.warehouseId(),
        response.coverMediaId(),
        response.generation(),
        response.taskBoardEntryId(),
        response.version(),
        response.changedAt());
  }

  List<CabinMediaSnapshot> readCabinMediaSnapshots(UUID warehouseId, List<UUID> cabinIds) {
    CabinMediaSnapshotsResponse response =
        transport.postWithoutIdempotency(
            mediaBase + "/logistics/cabin-presentations/snapshots",
            new CabinMediaSnapshotsRequest(warehouseId, cabinIds),
            CabinMediaSnapshotsResponse.class,
            MEDIA_CLIENT,
            MEDIA_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    if (response == null || response.items() == null) {
      throw malformed("Media-service returned an invalid cabin media snapshot batch");
    }
    return response.items().stream()
        .map(LogisticsMediaDependencyClient::mapCabinMediaSnapshot)
        .toList();
  }

  /** Converts one complete dependency item without allowing partial JSON to escape as an NPE. */
  private static CabinMediaSnapshot mapCabinMediaSnapshot(CabinMediaSnapshotResponse item) {
    if (item == null
        || item.cabinId() == null
        || item.photoCount() < 0
        || item.photos() == null) {
      throw malformed("Media-service returned an invalid cabin media snapshot");
    }
    return new CabinMediaSnapshot(
        item.cabinId(),
        item.photoCount(),
        item.photos().stream().map(LogisticsMediaDependencyClient::mapCabinMediaPhoto).toList());
  }

  /** Copies one READY photo only after validating every collection needed by the service layer. */
  private static CabinMediaPhoto mapCabinMediaPhoto(CabinMediaPhotoResponse photo) {
    if (photo == null
        || photo.mediaId() == null
        || photo.generation() < 1
        || photo.sortOrder() < 0
        || photo.availableVariants() == null
        || photo.availableVariants().stream().anyMatch(Objects::isNull)) {
      throw malformed("Media-service returned invalid cabin media photo metadata");
    }
    return new CabinMediaPhoto(
        photo.mediaId(),
        photo.generation(),
        photo.sortOrder(),
        List.copyOf(photo.availableVariants()));
  }

  MediaContent readCabinPresentationMedia(
      UUID warehouseId, UUID cabinId, UUID mediaId, long generation, String variant) {
    String uri =
        UriComponentsBuilder.fromUriString(
                mediaBase
                    + "/logistics/cabin-presentations/assets/"
                    + mediaId
                    + "/variants/"
                    + variant
                    + "/content")
            .queryParam("warehouseId", warehouseId)
            .queryParam("cabinId", cabinId)
            .queryParam("generation", generation)
            .build()
            .encode()
            .toUriString();
    try {
      ResponseEntity<byte[]> response = transport.getBytes(uri, MEDIA_CLIENT, MEDIA_SCOPE, DEFAULT);
      if (response.getBody() == null) {
        throw malformed("Media-service returned empty presentation content");
      }
      String contentType =
          response.getHeaders().getContentType() == null
              ? "application/octet-stream"
              : response.getHeaders().getContentType().toString();
      return new MediaContent(response.getBody(), contentType);
    } catch (RuntimeException exception) {
      throw defaultFailure(exception);
    }
  }

  ContractorEvidenceMediaReceipt uploadContractorTaskEvidence(
      UUID warehouseId,
      UUID workerId,
      UUID entryId,
      UUID evidenceId,
      String contentType,
      String sha256,
      byte[] bytes) {
    if (warehouseId == null
        || workerId == null
        || entryId == null
        || evidenceId == null
        || !("image/jpeg".equals(contentType) || "image/webp".equals(contentType))
        || bytes == null
        || bytes.length == 0
        || bytes.length > ("image/jpeg".equals(contentType) ? 15_728_640 : 1_048_576)
        || sha256 == null
        || !sha256.matches("^[0-9a-f]{64}$")) {
      throw new IllegalArgumentException("Contractor evidence media upload is invalid");
    }
    String uri =
        UriComponentsBuilder.fromUriString(
                mediaBase
                    + "/logistics/contractor-task-executions/"
                    + entryId
                    + "/workers/"
                    + workerId
                    + "/evidence/"
                    + evidenceId)
            .queryParam("warehouseId", warehouseId)
            .build()
            .encode()
            .toUriString();
    ContractorTaskEvidenceReceipt response =
        transport.postBytes(
            uri,
            evidenceId,
            sha256,
            contentType,
            bytes,
            ContractorTaskEvidenceReceipt.class,
            MEDIA_CLIENT,
            MEDIA_SCOPE,
            "Media-service returned an empty contractor evidence receipt",
            DEFAULT);
    if (response == null
        || response.mediaId() == null
        || response.generation() < 0
        || !Set.of("PROCESSING", "READY", "FAILED").contains(response.status())
        || ("PROCESSING".equals(response.status()) && response.generation() != 0)
        || ("READY".equals(response.status()) && response.generation() < 1)) {
      throw malformed("Media-service returned an invalid contractor evidence receipt");
    }
    return new ContractorEvidenceMediaReceipt(
        response.mediaId(), response.generation(), response.status());
  }

  MediaContent readContractorTaskMedia(
      UUID warehouseId,
      UUID workerId,
      UUID entryId,
      UUID mediaId,
      long generation,
      String variant) {
    if (warehouseId == null
        || workerId == null
        || entryId == null
        || mediaId == null
        || generation < 1
        || !Set.of("SMALL", "MEDIUM", "LARGE").contains(variant)) {
      throw new IllegalArgumentException("Contractor task media identity is invalid");
    }
    String uri =
        UriComponentsBuilder.fromUriString(
                mediaBase
                    + "/logistics/contractor-task-executions/"
                    + entryId
                    + "/workers/"
                    + workerId
                    + "/assets/"
                    + mediaId
                    + "/generations/"
                    + generation
                    + "/variants/"
                    + variant
                    + "/content")
            .queryParam("warehouseId", warehouseId)
            .build()
            .encode()
            .toUriString();
    try {
      ResponseEntity<byte[]> response = transport.getBytes(uri, MEDIA_CLIENT, MEDIA_SCOPE, DEFAULT);
      if (response.getBody() == null
          || response.getBody().length == 0
          || response.getHeaders().getContentType() == null
          || !"image/webp".equals(response.getHeaders().getContentType().toString())) {
        throw malformed("Media-service returned invalid contractor task media content");
      }
      return new MediaContent(response.getBody(), "image/webp");
    } catch (RuntimeException exception) {
      throw defaultFailure(exception);
    }
  }

  /** Media identifier pinned to the exact generation that logistics intends to consume. */
  private record MediaReferenceRequest(UUID mediaId, long generation) {}

  /**
   * Validation request binding immutable media generations to their logistics owner line and
   * warehouse.
   */
  private record MediaValidationRequest(
      String ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      List<MediaReferenceRequest> references) {}

  /**
   * Media-service validation echo used to reject mismatched owner correlation or substituted
   * media generations.
   */
  private record MediaValidationResponse(
      String ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      List<MediaReferenceRequest> references) {}

  /** Subject-bound non-structured avatar reference validation request. */
  private record CustomerProfileMediaValidationRequest(
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      UUID authorizedSubjectId,
      String context,
      List<MediaReferenceRequest> references) {}

  /** Exact profile avatar reference echoed by media-service after READY validation. */
  private record CustomerProfileMediaValidationResponse(
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      UUID authorizedSubjectId,
      String context,
      List<MediaReferenceRequest> references) {}

  /**
   * Owner-proof upsert carrying monotonic owner and aggregate revisions plus the originating event
   * identity for idempotent recovery.
   */
  private record MediaOwnerProofRequest(
      String ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      UUID proofEventId,
      UUID authorizedSubjectId,
      boolean active) {}

  /**
   * Authoritative owner-proof result echoed for strict revision, event, owner, and active-state
   * validation.
   */
  private record MediaOwnerProofResponse(
      String ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      Long ownerRevision,
      Long aggregateVersion,
      UUID proofEventId,
      UUID authorizedSubjectId,
      Boolean active) {}

  /** Initial active CustomerApp profile-avatar owner proof. */
  private record CustomerProfileMediaOwnerProofRequest(
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      UUID proofEventId,
      UUID authorizedSubjectId,
      boolean active) {}

  /** Strict echo of the active CustomerApp profile-avatar owner proof. */
  private record CustomerProfileMediaOwnerProofResponse(
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      Long ownerRevision,
      Long aggregateVersion,
      UUID proofEventId,
      UUID authorizedSubjectId,
      Boolean active) {}

  /** Command selecting verified task evidence as the new cabin cover image. */
  private record SetCabinCoverFromTaskEvidenceRequest(
      UUID taskBoardEntryId, UUID evidenceMediaId) {}

  /**
   * Media-service cover change result containing the new generation and task evidence correlation.
   */
  private record CabinCoverChangeResponse(
      UUID cabinId,
      UUID warehouseId,
      UUID coverMediaId,
      long generation,
      UUID taskBoardEntryId,
      long version,
      OffsetDateTime changedAt) {}

  /** Warehouse-scoped batch request for presentation-safe cabin media snapshots. */
  private record CabinMediaSnapshotsRequest(UUID warehouseId, List<UUID> cabinIds) {}

  /** Ordered presentation photo metadata with its immutable generation and available variants. */
  private record CabinMediaPhotoResponse(
      UUID mediaId, long generation, int sortOrder, List<String> availableVariants) {}

  /** Presentation media snapshot for one cabin. */
  private record CabinMediaSnapshotResponse(
      UUID cabinId, long photoCount, List<CabinMediaPhotoResponse> photos) {}

  /** Batch wrapper for cabin media snapshots returned by media-service. */
  private record CabinMediaSnapshotsResponse(List<CabinMediaSnapshotResponse> items) {}

  /** Opaque media-service outcome for one exact contractor evidence byte stream. */
  private record ContractorTaskEvidenceReceipt(
      UUID mediaId, long generation, String status) {}
}
