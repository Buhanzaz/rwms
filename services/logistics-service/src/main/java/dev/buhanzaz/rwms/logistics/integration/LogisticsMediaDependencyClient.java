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

  MediaOwnerProof upsertMediaOwnerProof(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      UUID proofEventId,
      boolean active) {
    if (ownerType == null
        || ownerType == LogisticsOwnerType.LOGISTICS_SHIPMENT
        || documentId == null
        || lineId == null
        || warehouseId == null
        || ownerRevision < 0
        || aggregateVersion < 0
        || proofEventId == null) {
      throw malformed("Logistics media owner proof is invalid");
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
        active);
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
}
