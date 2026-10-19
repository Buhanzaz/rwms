package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.domain.InventoryAssetCapture;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetCaptureMember;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetCaptureOperation;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetCaptureState;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient;
import dev.buhanzaz.rwms.asset.repository.InventoryAssetCaptureMemberRepository;
import dev.buhanzaz.rwms.asset.repository.InventoryAssetCaptureOperationRepository;
import dev.buhanzaz.rwms.asset.repository.InventoryAssetCaptureRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

/**
 * Owns durable inventory-capture lifecycle, cursor fencing, and frozen member persistence.
 *
 * <p>It registers immutable operation identity before acquiring the JPA row lock. The projection
 * collaborator supplies material from its independent repeatable-read transaction; this service
 * then persists the exact frozen rows in the caller's capture transaction.
 */
@Service
final class InventoryAssetCaptureService {
  private static final int MAX_PAGE_SIZE = 500;

  private final InventoryAssetCaptureOperationRepository captureOperations;
  private final InventoryAssetCaptureRepository captures;
  private final InventoryAssetCaptureMemberRepository captureMembers;
  private final InventoryAssetBoundaryRegistrar registrar;
  private final WarehouseRegistryClient warehouses;
  private final InventoryAssetProjectionService projections;
  private final InventoryAssetCodec codec;

  InventoryAssetCaptureService(
      InventoryAssetCaptureOperationRepository captureOperations,
      InventoryAssetCaptureRepository captures,
      InventoryAssetCaptureMemberRepository captureMembers,
      InventoryAssetBoundaryRegistrar registrar,
      WarehouseRegistryClient warehouses,
      InventoryAssetProjectionService projections,
      InventoryAssetCodec codec) {
    this.captureOperations = captureOperations;
    this.captures = captures;
    this.captureMembers = captureMembers;
    this.registrar = registrar;
    this.warehouses = warehouses;
    this.projections = projections;
    this.codec = codec;
  }

  InventoryCaptureResponse createCapture(InventoryCaptureRequest request) {
    registerConcurrentSafe(
        () ->
            registrar.registerCaptureOperation(
                request.operationId(), request.warehouseId(), request.requestFingerprint()));
    InventoryAssetCaptureOperation operation =
        captureOperations
            .findByIdForUpdate(request.operationId())
            .orElseThrow(
                () -> new IllegalStateException("Inventory capture operation registration failed"));
    if (!operation.getWarehouseId().equals(request.warehouseId())
        || !operation.getRequestFingerprint().equals(request.requestFingerprint())) {
      throw new AssetConflictException("Inventory capture operation is bound to another request");
    }

    expireCapturesNow();
    InventoryAssetCapture existing =
        captures
            .findByOperationIdAndTechnicalAttempt(request.operationId(), request.technicalAttempt())
            .orElse(null);
    if (existing != null) {
      if (!existing.getRequestFingerprint().equals(request.requestFingerprint())
          || !existing.getWarehouseId().equals(request.warehouseId())) {
        throw new AssetConflictException("Inventory capture attempt is bound to another request");
      }
      if (existing.getState() != InventoryAssetCaptureState.ACTIVE) {
        throw new AssetConflictException("Inventory capture attempt is no longer active");
      }
      return captureResponse(existing);
    }

    InventoryAssetCapture latest =
        captures.findFirstByOperationIdOrderByTechnicalAttemptDesc(request.operationId()).orElse(null);
    if (latest != null
        && (request.technicalAttempt() <= latest.getTechnicalAttempt()
            || latest.getState() != InventoryAssetCaptureState.EXPIRED)) {
      throw new AssetConflictException(
          "A new monotonic inventory capture attempt is allowed only after the previous attempt expires");
    }

    warehouses.requireIncoming(request.warehouseId());
    List<InventoryAssetProjectionService.CaptureMemberRow> members =
        projections.captureMemberSnapshot(request.warehouseId());
    String digest =
        codec.canonicalHash(
            members.stream()
                .map(InventoryAssetProjectionService.CaptureMemberRow::digestValue)
                .toList());
    InventoryAssetCapture capture =
        captures.saveAndFlush(
            InventoryAssetCapture.create(
                request.operationId(),
                request.technicalAttempt(),
                request.warehouseId(),
                request.requestFingerprint(),
                digest,
                members.size(),
                now()));
    captureMembers.saveAllAndFlush(
        members.stream().map(member -> member.toEntity(capture.getCaptureId(), codec)).toList());
    return captureResponse(capture);
  }

  InventoryCapturePage capturePage(UUID captureId, String cursor, int size) {
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("Inventory capture page size must be between 1 and 500");
    }
    InventoryAssetCapture capture = requireCapture(captureId);
    if (capture.getState() != InventoryAssetCaptureState.ACTIVE
        || !capture.getExpiresAt().isAfter(now())) {
      throw new AssetConflictException("Inventory capture is released or expired");
    }
    long after = decodeCursor(cursor, capture);
    List<InventoryAssetCaptureMember> fetched =
        captureMembers.pageAfter(captureId, after, PageRequest.of(0, size + 1));
    boolean hasMore = fetched.size() > size;
    List<InventoryAssetCaptureMember> page =
        hasMore ? List.copyOf(fetched.subList(0, size)) : fetched;
    List<InventoryCaptureMember> content = page.stream().map(this::memberResponse).toList();
    String nextCursor = hasMore ? encodeCursor(capture, content.getLast().sequence()) : null;
    return new InventoryCapturePage(
        capture.getCaptureId(),
        capture.getOperationId(),
        capture.getTechnicalAttempt(),
        capture.getWarehouseId(),
        capture.getTotalCount(),
        capture.getMembershipDigest(),
        nextCursor,
        content);
  }

  void releaseCapture(UUID captureId) {
    InventoryAssetCapture capture =
        captures
            .findByIdForUpdate(captureId)
            .orElseThrow(() -> new AssetNotFoundException("Inventory capture was not found"));
    capture.release(now());
    captures.saveAndFlush(capture);
  }

  void expireCapturesNow() {
    captures.expireActiveBefore(
        InventoryAssetCaptureState.ACTIVE, InventoryAssetCaptureState.EXPIRED, now());
  }

  private InventoryCaptureMember memberResponse(InventoryAssetCaptureMember member) {
    return new InventoryCaptureMember(
        member.getId().getSequenceNo(),
        member.getAssetId(),
        member.getAssetVersion(),
        member.getWarehouseId(),
        member.getStatus(),
        member.getDisplayCanonicalNumber(),
        member.getIdentityMatchKey(),
        codec.read(member.getPassportSnapshot(), new TypeReference<java.util.Map<String, Object>>() {}),
        codec.read(
            member.getContentsSnapshot(), new TypeReference<List<EquipmentContentResponse>>() {}));
  }

  private InventoryAssetCapture requireCapture(UUID captureId) {
    return captures
        .findById(captureId)
        .orElseThrow(() -> new AssetNotFoundException("Inventory capture was not found"));
  }

  private static InventoryCaptureResponse captureResponse(InventoryAssetCapture capture) {
    return new InventoryCaptureResponse(
        capture.getCaptureId(),
        capture.getOperationId(),
        capture.getTechnicalAttempt(),
        capture.getWarehouseId(),
        capture.getTotalCount(),
        capture.getMembershipDigest(),
        capture.getCreatedAt(),
        capture.getExpiresAt());
  }

  private static void registerConcurrentSafe(Runnable registration) {
    try {
      registration.run();
    } catch (DataIntegrityViolationException ignored) {
      // Another transaction registered the same immutable key; the caller locks and validates it.
    }
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  private static String encodeCursor(InventoryAssetCapture capture, long sequence) {
    String payload =
        capture.getCaptureId()
            + "."
            + capture.getMembershipDigest()
            + "."
            + sequence
            + "."
            + cursorSignature(capture, sequence);
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(payload.getBytes(StandardCharsets.US_ASCII));
  }

  private static long decodeCursor(String cursor, InventoryAssetCapture capture) {
    if (cursor == null || cursor.isBlank()) {
      return -1;
    }
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
      String[] parts = decoded.split("\\.", -1);
      if (parts.length != 4) {
        throw new IllegalArgumentException("Malformed capture cursor");
      }
      UUID cursorCaptureId = UUID.fromString(parts[0]);
      long sequence = Long.parseLong(parts[2]);
      if (sequence < 0) {
        throw new IllegalArgumentException("Negative capture cursor");
      }
      if (!capture.getCaptureId().equals(cursorCaptureId)
          || !capture.getMembershipDigest().equals(parts[1])
          || !cursorSignature(capture, sequence).equals(parts[3])) {
        throw new AssetConflictException("Inventory capture cursor does not belong to this capture");
      }
      return sequence;
    } catch (AssetConflictException exception) {
      throw exception;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Inventory capture cursor is invalid", exception);
    }
  }

  private static String cursorSignature(InventoryAssetCapture capture, long sequence) {
    String value =
        capture.getCaptureId()
            + ":"
            + capture.getMembershipDigest()
            + ":"
            + sequence
            + ":"
            + capture.getRequestFingerprint();
    return AssetChecksum.sha256(value.getBytes(StandardCharsets.US_ASCII));
  }
}
