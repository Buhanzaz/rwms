package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.RentalItemCreationIntentApiModels.CreateRentalItemWithPhotoIntentRequest;
import static dev.buhanzaz.rwms.asset.api.RentalItemCreationIntentApiModels.CreateRentalItemWithPhotoIntentResponse;
import static dev.buhanzaz.rwms.asset.api.RentalItemCreationIntentApiModels.RentalItemCreationIntentCommand;
import static dev.buhanzaz.rwms.asset.api.RentalItemCreationIntentApiModels.RentalItemCreationIntentPage;
import static dev.buhanzaz.rwms.asset.api.RentalItemCreationIntentApiModels.RentalItemCreationPhotoManifestInput;
import static dev.buhanzaz.rwms.asset.api.RentalItemCreationIntentApiModels.RentalItemCreationIntentResponse;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.OperationLeaseResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.domain.OperationLease;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemCreationIntent;
import dev.buhanzaz.rwms.asset.domain.RentalItemCreationPhotoManifestEntry;
import dev.buhanzaz.rwms.asset.domain.RentalItemCreationIntentState;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.integration.media.MediaCabinCreationSnapshotClient;
import dev.buhanzaz.rwms.asset.integration.media.MediaCabinCreationSnapshotClient.CabinCreationSnapshot;
import dev.buhanzaz.rwms.asset.integration.media.MediaCabinCreationSnapshotClient.ReadyPhoto;
import dev.buhanzaz.rwms.asset.repository.RentalItemCreationIntentRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Owns the durable mandatory-photo lifecycle for interactive cabin creation.
 *
 * <p>Candidate media reads happen outside asset database transactions. Completion then rechecks
 * the intent version, state, exact cabin and exact immutable media identities under local locks
 * before releasing the availability hold.
 */
@Service
public class RentalItemCreationIntentService {
  private static final String CREATE_SCOPE = "rental-item-creation-intent.create";
  private static final String COMPLETE_SCOPE = "rental-item-creation-intent.complete";
  private static final String ABANDON_SCOPE = "rental-item-creation-intent.abandon";

  private final RentalItemCreationIntentRepository intents;
  private final AssetRentalItemService rentals;
  private final AssetLeaseService leases;
  private final MediaCabinCreationSnapshotClient media;
  private final AssetIdempotencyStore idempotency;
  private final AssetJsonCodec json;
  private final RentalAvailabilityInvalidationPublisher availabilityInvalidations;
  private final TransactionTemplate transactions;

  public RentalItemCreationIntentService(
      RentalItemCreationIntentRepository intents,
      AssetRentalItemService rentals,
      AssetLeaseService leases,
      MediaCabinCreationSnapshotClient media,
      AssetIdempotencyStore idempotency,
      AssetJsonCodec json,
      RentalAvailabilityInvalidationPublisher availabilityInvalidations,
      TransactionTemplate transactions) {
    this.intents = intents;
    this.rentals = rentals;
    this.leases = leases;
    this.media = media;
    this.idempotency = idempotency;
    this.json = json;
    this.availabilityInvalidations = availabilityInvalidations;
    this.transactions = transactions;
  }

  /** Atomically creates the strict cabin aggregate, creation lease and resumable intent. */
  @Transactional
  public AssetService.CreateResult<CreateRentalItemWithPhotoIntentResponse> create(
      UUID subjectId,
      UUID idempotencyKey,
      CreateRentalItemWithPhotoIntentRequest request) {
    requireCommandIdentity(subjectId, idempotencyKey);
    String requestHash = json.hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, CREATE_SCOPE, idempotencyKey, requestHash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          json.read(replay.get(), CreateRentalItemWithPhotoIntentResponse.class), true);
    }

    UUID intentId = UUID.randomUUID();
    UUID mediaFolderId = UUID.randomUUID();
    UUID mediaCommandId = UUID.randomUUID();
    List<RentalItemCreationPhotoManifestEntry> photoManifest =
        createPhotoManifest(request.photoManifest());
    String photoManifestSha256 = hashManifest(photoManifest);
    RentalItemResponse rentalItem =
        rentals.createForPhotoIntent(request.rentalItem());
    OperationLeaseResponse creationHold =
        leases.acquireCreationHold(rentalItem.id(), intentId, idempotencyKey);
    RentalItemCreationIntent persisted =
        intents.saveAndFlush(
            RentalItemCreationIntent.create(
                intentId,
                rentalItem.id(),
                rentalItem.warehouseId(),
                photoManifest.size(),
                mediaFolderId,
                mediaCommandId,
                photoManifestSha256,
                photoManifest,
                creationHold.id(),
                creationHold.fencingToken(),
                subjectId,
                now()));
    CreateRentalItemWithPhotoIntentResponse response =
        new CreateRentalItemWithPhotoIntentResponse(rentalItem, response(persisted));
    idempotency.store(subjectId, CREATE_SCOPE, idempotencyKey, requestHash, 201, response);
    return new AssetService.CreateResult<>(response, false);
  }

  /** Returns only pending intents for one authorized warehouse. */
  @Transactional(readOnly = true)
  public RentalItemCreationIntentPage listPending(UUID warehouseId, int page, int size) {
    if (warehouseId == null) throw new IllegalArgumentException("warehouseId is required");
    Page<RentalItemCreationIntent> result =
        intents.findAllByWarehouseIdAndStateOrderByCreatedAtAscIdAsc(
            warehouseId,
            RentalItemCreationIntentState.PENDING,
            PageRequest.of(page, size));
    return new RentalItemCreationIntentPage(
        result.getContent().stream().map(RentalItemCreationIntentService::response).toList(),
        result.getNumber(),
        result.getSize(),
        result.getTotalElements(),
        result.getTotalPages());
  }

  /** Reads one intent without exposing the internal creation lease credentials. */
  @Transactional(readOnly = true)
  public RentalItemCreationIntentResponse get(UUID intentId) {
    return response(require(intentId));
  }

  /**
   * Verifies the exact current media gallery outside local locks, then atomically completes the
   * pending intent and releases its creation hold.
   */
  public AssetService.CreateResult<RentalItemCreationIntentResponse> complete(
      UUID subjectId,
      UUID idempotencyKey,
      UUID intentId,
      RentalItemCreationIntentCommand request) {
    requireCommandIdentity(subjectId, idempotencyKey);
    String requestHash = json.hash(new IntentCommandEnvelope(intentId, request));
    CompletionPreparation preparation =
        requireTransactionResult(
            transactions.execute(
                ignored ->
                    prepareCompletion(
                        subjectId,
                        idempotencyKey,
                        intentId,
                        request,
                        requestHash)));
    if (preparation.replay() != null) {
      return new AssetService.CreateResult<>(preparation.replay(), true);
    }
    CabinCreationSnapshot snapshot =
        media
            .read(preparation.warehouseId(), preparation.rentalItemId())
            .orElseThrow(
                () ->
                    incompleteProof(
                        "Cabin photos are not yet available for verification"));
    return requireTransactionResult(
        transactions.execute(
            ignored ->
                finishCompletion(
                    subjectId,
                    idempotencyKey,
                    request,
                    requestHash,
                    preparation,
                    snapshot)));
  }

  /** Quarantines an incomplete cabin in WAREHOUSE and releases only its exact creation hold. */
  @Transactional
  public AssetService.CreateResult<RentalItemCreationIntentResponse> abandon(
      UUID subjectId,
      UUID idempotencyKey,
      UUID intentId,
      RentalItemCreationIntentCommand request) {
    requireCommandIdentity(subjectId, idempotencyKey);
    String requestHash = json.hash(new IntentCommandEnvelope(intentId, request));
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, ABANDON_SCOPE, idempotencyKey, requestHash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          json.read(replay.get(), RentalItemCreationIntentResponse.class), true);
    }
    RentalItemCreationIntent intent = requireForUpdate(intentId);
    assertPendingVersion(intent, request.expectedVersion());
    OperationLease creationHold =
        leases.validate(
            intent.getRentalItemId(),
            intent.getCreationLeaseId(),
            intent.getCreationLeaseFencingToken());
    assertCreationHold(intent, creationHold);
    RentalItem rentalItem = rentals.requireForUpdate(intent.getRentalItemId());
    assertCreationRentalItem(intent, rentalItem);
    rentals.changeStatusLocked(
        rentalItem.getId(),
        rentalItem.getVersion(),
        RentalItemStatus.WAREHOUSE,
        true);
    leases.releaseCreationHold(
        intent.getRentalItemId(),
        intent.getCreationLeaseId(),
        intent.getCreationLeaseFencingToken(),
        intent.getId());
    intent.abandon(now());
    RentalItemCreationIntentResponse response = response(intents.saveAndFlush(intent));
    availabilityInvalidations.publishAfterCommit(
        intent.getWarehouseId(), List.of(intent.getRentalItemId()));
    idempotency.store(subjectId, ABANDON_SCOPE, idempotencyKey, requestHash, 200, response);
    return new AssetService.CreateResult<>(response, false);
  }

  private CompletionPreparation prepareCompletion(
      UUID subjectId,
      UUID idempotencyKey,
      UUID intentId,
      RentalItemCreationIntentCommand request,
      String requestHash) {
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, COMPLETE_SCOPE, idempotencyKey, requestHash);
    if (replay.isPresent()) {
      return CompletionPreparation.replay(
          json.read(replay.get(), RentalItemCreationIntentResponse.class));
    }
    RentalItemCreationIntent intent = require(intentId);
    assertPendingVersion(intent, request.expectedVersion());
    return CompletionPreparation.pending(intent);
  }

  private AssetService.CreateResult<RentalItemCreationIntentResponse> finishCompletion(
      UUID subjectId,
      UUID idempotencyKey,
      RentalItemCreationIntentCommand request,
      String requestHash,
      CompletionPreparation preparation,
      CabinCreationSnapshot snapshot) {
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, COMPLETE_SCOPE, idempotencyKey, requestHash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          json.read(replay.get(), RentalItemCreationIntentResponse.class), true);
    }
    RentalItemCreationIntent intent = requireForUpdate(preparation.intentId());
    assertPendingVersion(intent, request.expectedVersion());
    preparation.assertSame(intent);
    RentalItem rentalItem = rentals.requireForUpdate(intent.getRentalItemId());
    assertCreationRentalItem(intent, rentalItem);
    String proofSha256 = verifyProof(intent, snapshot);
    leases.releaseCreationHold(
        intent.getRentalItemId(),
        intent.getCreationLeaseId(),
        intent.getCreationLeaseFencingToken(),
        intent.getId());
    intent.complete(snapshot.coverMediaId(), proofSha256, now());
    RentalItemCreationIntentResponse response = response(intents.saveAndFlush(intent));
    availabilityInvalidations.publishAfterCommit(
        intent.getWarehouseId(), List.of(intent.getRentalItemId()));
    idempotency.store(subjectId, COMPLETE_SCOPE, idempotencyKey, requestHash, 200, response);
    return new AssetService.CreateResult<>(response, false);
  }

  private String verifyProof(
      RentalItemCreationIntent intent, CabinCreationSnapshot snapshot) {
    if (!intent.getRentalItemId().equals(snapshot.cabinId())
        || !intent.getWarehouseId().equals(snapshot.warehouseId())
        || !intent.getMediaFolderId().equals(snapshot.activeFolderId())) {
      throw incompleteProof(
          "Cabin photos do not belong to the expected cabin, warehouse and gallery");
    }
    if (snapshot.photoCount() != intent.getExpectedPhotoCount()
        || snapshot.readyPhotos().size() != intent.getExpectedPhotoCount()) {
      throw incompleteProof(
          "Cabin photo gallery is incomplete or still processing");
    }
    if (snapshot.coverMediaId() == null || snapshot.readyPhotos().isEmpty()) {
      throw incompleteProof("Cabin photo gallery has no READY cover");
    }
    Set<UUID> uniqueMedia = new HashSet<>();
    Map<Long, ReadyPhoto> readyByIndex = new HashMap<>();
    for (ReadyPhoto photo : snapshot.readyPhotos()) {
      if (photo == null
          || photo.mediaId() == null
          || photo.generation() < 1
          || photo.photoIndex() < 0
          || photo.photoIndex() >= intent.getExpectedPhotoCount()
          || !uniqueMedia.add(photo.mediaId())
          || readyByIndex.put(photo.photoIndex(), photo) != null) {
        throw incompleteProof("Cabin photo proof is inconsistent");
      }
    }
    if (!snapshot.coverMediaId().equals(snapshot.readyPhotos().getFirst().mediaId())
        || snapshot.readyPhotos().getFirst().photoIndex() != 0
        || !uniqueMedia.contains(snapshot.coverMediaId())) {
      throw incompleteProof("Cabin photo gallery cover is not a current READY image");
    }
    List<RentalItemCreationPhotoManifestEntry> expected = intent.getPhotoManifest();
    if (!intent.getPhotoManifestSha256().equals(hashManifest(expected))) {
      throw new AssetConflictException(
          "Rental-item creation photo manifest integrity check failed");
    }
    for (int photoIndex = 0; photoIndex < expected.size(); photoIndex++) {
      RentalItemCreationPhotoManifestEntry manifest = expected.get(photoIndex);
      ReadyPhoto ready = readyByIndex.get((long) photoIndex);
      if (ready == null
          || !manifest.getChecksumSha256().equals(ready.checksumSha256())
          || !manifest.getContentType().equals(ready.contentType())
          || manifest.getContentLength() != ready.contentLength()) {
        throw incompleteProof(
            "Cabin photo gallery does not match the planned source manifest");
      }
    }
    return json.hash(
        new MediaProof(
            snapshot.cabinId(),
            snapshot.warehouseId(),
            snapshot.activeFolderId(),
            snapshot.coverMediaId(),
            snapshot.photoCount(),
            snapshot.readyPhotos()));
  }

  private RentalItemCreationIntent require(UUID intentId) {
    if (intentId == null) throw new IllegalArgumentException("intentId is required");
    return intents
        .findById(intentId)
        .orElseThrow(() -> new AssetNotFoundException("Rental-item creation intent was not found"));
  }

  private RentalItemCreationIntent requireForUpdate(UUID intentId) {
    if (intentId == null) throw new IllegalArgumentException("intentId is required");
    return intents
        .findByIdForUpdate(intentId)
        .orElseThrow(() -> new AssetNotFoundException("Rental-item creation intent was not found"));
  }

  private static void assertPendingVersion(
      RentalItemCreationIntent intent, Long expectedVersion) {
    AssetLeaseService.assertVersion(intent.getVersion(), expectedVersion);
    if (intent.getState() != RentalItemCreationIntentState.PENDING) {
      throw new AssetConflictException("Rental-item creation intent is already terminal");
    }
  }

  private static void assertCreationRentalItem(
      RentalItemCreationIntent intent, RentalItem rentalItem) {
    if (!intent.getWarehouseId().equals(rentalItem.getWarehouseId())) {
      throw new AssetConflictException("Creation intent cabin belongs to another warehouse");
    }
    if (rentalItem.getStatus() != RentalItemStatus.FREE) {
      throw new AssetConflictException(
          "Creation intent cabin is not in the expected FREE state");
    }
  }

  private static void assertCreationHold(
      RentalItemCreationIntent intent, OperationLease creationHold) {
    if (!creationHold.isOwnedBy(
        AssetLeaseService.CABIN_CREATION_OWNER_TYPE, intent.getId().toString())) {
      throw new AssetConflictException("Creation hold identity does not match the intent");
    }
  }

  private static RentalItemCreationIntentResponse response(
      RentalItemCreationIntent intent) {
    return new RentalItemCreationIntentResponse(
        intent.getId(),
        intent.getVersion(),
        intent.getRentalItemId(),
        intent.getWarehouseId(),
        intent.getState(),
        intent.getExpectedPhotoCount(),
        intent.getMediaFolderId(),
        intent.getMediaCommandId(),
        intent.getPhotoManifestSha256(),
        publicManifest(intent),
        intent.getCoverMediaId(),
        intent.getMediaProofSha256(),
        intent.getCreatedAt(),
        intent.getCompletedAt(),
        intent.getAbandonedAt());
  }

  private static void requireCommandIdentity(UUID subjectId, UUID idempotencyKey) {
    if (subjectId == null || idempotencyKey == null) {
      throw new IllegalArgumentException(
          "subjectId and idempotencyKey are required");
    }
  }

  private static AssetDependencyException incompleteProof(String message) {
    return new AssetDependencyException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  private static <T> T requireTransactionResult(T result) {
    if (result == null) throw new IllegalStateException("Asset transaction returned no result");
    return result;
  }

  private List<RentalItemCreationPhotoManifestEntry> createPhotoManifest(
      List<RentalItemCreationPhotoManifestInput> requested) {
    if (requested == null || requested.isEmpty() || requested.size() > 20) {
      throw new IllegalArgumentException("photoManifest must contain between 1 and 20 photos");
    }
    List<RentalItemCreationPhotoManifestEntry> manifest =
        new ArrayList<>(requested.size());
    for (int index = 0; index < requested.size(); index++) {
      RentalItemCreationPhotoManifestInput entry = requested.get(index);
      if (entry == null || entry.photoIndex() == null || entry.photoIndex() != index) {
        throw new IllegalArgumentException(
            "photoManifest indices must be contiguous and match array order");
      }
      manifest.add(
          RentalItemCreationPhotoManifestEntry.create(
              UUID.randomUUID(),
              entry.checksumSha256(),
              entry.contentType(),
              entry.contentLength() == null ? 0 : entry.contentLength()));
    }
    return List.copyOf(manifest);
  }

  private String hashManifest(List<RentalItemCreationPhotoManifestEntry> manifest) {
    List<ManifestHashEntry> canonical = new ArrayList<>(manifest.size());
    for (int index = 0; index < manifest.size(); index++) {
      RentalItemCreationPhotoManifestEntry entry = manifest.get(index);
      canonical.add(
          new ManifestHashEntry(
              index,
              entry.getUploadCommandId(),
              entry.getChecksumSha256(),
              entry.getContentType(),
              entry.getContentLength()));
    }
    return json.hash(canonical);
  }

  private static List<dev.buhanzaz.rwms.asset.api.RentalItemCreationIntentApiModels.RentalItemCreationPhotoManifestEntry>
      publicManifest(RentalItemCreationIntent intent) {
    List<RentalItemCreationPhotoManifestEntry> stored = intent.getPhotoManifest();
    List<dev.buhanzaz.rwms.asset.api.RentalItemCreationIntentApiModels.RentalItemCreationPhotoManifestEntry>
        response = new ArrayList<>(stored.size());
    for (int index = 0; index < stored.size(); index++) {
      RentalItemCreationPhotoManifestEntry entry = stored.get(index);
      response.add(
          new dev.buhanzaz.rwms.asset.api.RentalItemCreationIntentApiModels.RentalItemCreationPhotoManifestEntry(
              index,
              entry.getUploadCommandId(),
              entry.getChecksumSha256(),
              entry.getContentType(),
              entry.getContentLength()));
    }
    return List.copyOf(response);
  }

  /** Stable resource-and-payload envelope for terminal command idempotency. */
  private record IntentCommandEnvelope(
      UUID intentId, RentalItemCreationIntentCommand request) {}


  /** Immutable local state captured before the remote media proof read. */
  private record CompletionPreparation(
      UUID intentId,
      UUID rentalItemId,
      UUID warehouseId,
      int expectedPhotoCount,
      UUID mediaFolderId,
      UUID mediaCommandId,
      String photoManifestSha256,
      RentalItemCreationIntentResponse replay) {
    static CompletionPreparation pending(RentalItemCreationIntent intent) {
      return new CompletionPreparation(
          intent.getId(),
          intent.getRentalItemId(),
          intent.getWarehouseId(),
          intent.getExpectedPhotoCount(),
          intent.getMediaFolderId(),
          intent.getMediaCommandId(),
          intent.getPhotoManifestSha256(),
          null);
    }

    static CompletionPreparation replay(RentalItemCreationIntentResponse response) {
      return new CompletionPreparation(null, null, null, 0, null, null, null, response);
    }

    void assertSame(RentalItemCreationIntent intent) {
      if (!intentId.equals(intent.getId())
          || !rentalItemId.equals(intent.getRentalItemId())
          || !warehouseId.equals(intent.getWarehouseId())
          || expectedPhotoCount != intent.getExpectedPhotoCount()
          || !mediaFolderId.equals(intent.getMediaFolderId())
          || !mediaCommandId.equals(intent.getMediaCommandId())
          || !photoManifestSha256.equals(intent.getPhotoManifestSha256())) {
        throw new AssetConflictException(
            "Rental-item creation intent changed during media verification");
      }
    }
  }

  /** Canonical accepted media proof persisted only as its SHA-256 digest. */
  private record MediaProof(
      UUID cabinId,
      UUID warehouseId,
      UUID activeFolderId,
      UUID coverMediaId,
      long photoCount,
      List<ReadyPhoto> readyPhotos) {}

  /** Canonical durable manifest value including one generated upload command identity. */
  private record ManifestHashEntry(
      int photoIndex,
      UUID uploadCommandId,
      String checksumSha256,
      String contentType,
      long contentLength) {}
}
