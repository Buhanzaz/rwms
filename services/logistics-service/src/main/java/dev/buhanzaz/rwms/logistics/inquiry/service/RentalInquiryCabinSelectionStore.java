package dev.buhanzaz.rwms.logistics.inquiry.service;

import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.AvailableCabinResponse;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.CabinSelectionRequest;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.CabinSelectionResponse;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiry;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquirySelectionCommandType;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquirySelectionReceipt;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquirySelectionReceiptState;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiryState;
import dev.buhanzaz.rwms.logistics.inquiry.repository.RentalInquiryRepository;
import dev.buhanzaz.rwms.logistics.inquiry.repository.RentalInquirySelectionReceiptRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns short PREPARE and terminal transactions for one idempotent cabin-selection effect.
 *
 * <p>It freezes the exact asset request body, non-null command deadline and nullable hold expiry.
 * No dependency call occurs while this store owns an inquiry or receipt lock.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RentalInquiryCabinSelectionStore {
  private final RentalInquiryRepository inquiries;
  private final RentalInquirySelectionReceiptRepository receipts;
  private final RentalSettingsService settings;
  private final OrderAuthorizer access;
  private final ObjectMapper json;
  private final Clock clock;

  /**
   * Revalidates owner and warehouse authority, then resumes or durably freezes one exact command.
   */
  @Transactional
  public SelectionPreparation prepare(
      OrderActor actor, UUID inquiryId, UUID publicIdempotencyKey, CabinSelectionRequest request) {
    requireCommand(actor, inquiryId, publicIdempotencyKey, request);
    requireWritableRentalActor(actor);
    List<UUID> ids = uniqueIds(request.rentalItemIds());
    OffsetDateTime timestamp = now();
    RentalInquiry inquiry = requiredForUpdate(inquiryId);
    requireOwnedActive(actor, inquiry);
    requireInquiryWarehouse(inquiry, request.warehouseId());
    access.requireWarehouseEdit(actor, request.warehouseId());
    String requestHash =
        hash(write(new SelectionFingerprint(inquiryId, request.warehouseId(), ids)));

    RentalInquirySelectionReceipt existing =
        receipts.findByPublicKeyForUpdate(actor.subjectId(), publicIdempotencyKey).orElse(null);
    if (existing != null) {
      if (!existing.matchesRequest(requestHash)) {
        throw conflict(
            "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key уже связан с другой выборкой бытовок");
      }
      requireReceiptAuthority(actor, inquiry, existing);
      return resume(existing, timestamp);
    }

    RentalInquirySelectionReceipt active =
        receipts
            .findByInquiryAndStateForUpdate(inquiryId, RentalInquirySelectionReceiptState.PREPARED)
            .orElse(null);
    if (active != null) {
      if (!active.commandExpiredAt(timestamp)) {
        throw conflict(
            "CABIN_SELECTION_IN_PROGRESS",
            "Для диалога уже выполняется другая команда выборки бытовок");
      }
      active.expire(timestamp);
      receipts.saveAndFlush(active);
    }

    RentalInquirySelectionCommandType commandType =
        ids.isEmpty()
            ? RentalInquirySelectionCommandType.RELEASE
            : RentalInquirySelectionCommandType.REPLACE;
    OffsetDateTime commandExpiresAt =
        timestamp.plusMinutes(settings.chatSelectionHoldMinutes(actor));
    OffsetDateTime expiresAt = ids.isEmpty() ? null : commandExpiresAt;
    String exactBody =
        commandType == RentalInquirySelectionCommandType.RELEASE
            ? write(new ReleaseCommand(actor.subjectId(), actor.role()))
            : write(
                new ReplaceCommand(
                    request.warehouseId(), ids, expiresAt, actor.subjectId(), actor.role(), null));
    RentalInquirySelectionReceipt prepared =
        receipts.saveAndFlush(
            RentalInquirySelectionReceipt.prepare(
                inquiryId,
                actor.subjectId(),
                publicIdempotencyKey,
                requestHash,
                request.warehouseId(),
                commandType,
                exactBody,
                hash(exactBody),
                actor.role(),
                commandExpiresAt,
                expiresAt,
                timestamp));
    return SelectionPreparation.prepared(prepared, ids);
  }

  /** Freezes a validated successful public response after rechecking current authority. */
  @Transactional
  public SelectionFinalization complete(
      OrderActor actor, PreparedSelection prepared, CabinSelectionResponse response) {
    requireWritableRentalActor(actor);
    Objects.requireNonNull(prepared, "prepared");
    Objects.requireNonNull(response, "response");
    OffsetDateTime timestamp = now();
    RentalInquiry inquiry = requiredForUpdate(prepared.inquiryId());
    requireOwnedActive(actor, inquiry);
    RentalInquirySelectionReceipt receipt = requiredReceiptForUpdate(prepared.receiptId());
    requirePreparedIdentity(actor, prepared, receipt);
    requireReceiptAuthority(actor, inquiry, receipt);
    if (receipt.getState() == RentalInquirySelectionReceiptState.COMPLETED) {
      return SelectionFinalization.completed(readResponse(receipt), true);
    }
    if (receipt.getState() != RentalInquirySelectionReceiptState.PREPARED) {
      return SelectionFinalization.terminal(receipt.getState(), receipt.getRejectionCode());
    }
    if (receipt.commandExpiredAt(timestamp)) {
      receipt.expire(timestamp);
      receipts.saveAndFlush(receipt);
      return SelectionFinalization.terminal(RentalInquirySelectionReceiptState.EXPIRED, null);
    }
    validateResponse(prepared, response);
    receipt.complete(write(response), timestamp);
    receipts.saveAndFlush(receipt);
    return SelectionFinalization.completed(response, false);
  }

  /** Records a confirmed sanitized dependency rejection and releases the inquiry command slot. */
  @Transactional
  public SelectionFinalization reject(
      OrderActor actor, PreparedSelection prepared, String safeRejectionCode) {
    requireWritableRentalActor(actor);
    Objects.requireNonNull(prepared, "prepared");
    OffsetDateTime timestamp = now();
    RentalInquiry inquiry = requiredForUpdate(prepared.inquiryId());
    requireOwnedActive(actor, inquiry);
    RentalInquirySelectionReceipt receipt = requiredReceiptForUpdate(prepared.receiptId());
    requirePreparedIdentity(actor, prepared, receipt);
    requireReceiptAuthority(actor, inquiry, receipt);
    if (receipt.getState() == RentalInquirySelectionReceiptState.COMPLETED) {
      return SelectionFinalization.completed(readResponse(receipt), true);
    }
    if (receipt.getState() != RentalInquirySelectionReceiptState.PREPARED) {
      return SelectionFinalization.terminal(receipt.getState(), receipt.getRejectionCode());
    }
    if (receipt.commandExpiredAt(timestamp)) {
      receipt.expire(timestamp);
      receipts.saveAndFlush(receipt);
      return SelectionFinalization.terminal(RentalInquirySelectionReceiptState.EXPIRED, null);
    }
    receipt.reject(safeRejectionCode, timestamp);
    receipts.saveAndFlush(receipt);
    return SelectionFinalization.terminal(
        RentalInquirySelectionReceiptState.REJECTED, receipt.getRejectionCode());
  }

  private SelectionPreparation resume(
      RentalInquirySelectionReceipt receipt, OffsetDateTime timestamp) {
    return switch (receipt.getState()) {
      case PREPARED -> {
        if (receipt.commandExpiredAt(timestamp)) {
          receipt.expire(timestamp);
          receipts.saveAndFlush(receipt);
          yield SelectionPreparation.terminal(RentalInquirySelectionReceiptState.EXPIRED, null);
        }
        if (!hash(receipt.getDownstreamRequestBody())
            .equals(receipt.getDownstreamRequestSha256())) {
          throw new IllegalStateException("Stored cabin-selection request checksum is corrupt");
        }
        yield SelectionPreparation.prepared(receipt, readIds(receipt));
      }
      case COMPLETED -> SelectionPreparation.replayed(readResponse(receipt));
      case REJECTED, EXPIRED ->
          SelectionPreparation.terminal(receipt.getState(), receipt.getRejectionCode());
    };
  }

  private RentalInquiry requiredForUpdate(UUID inquiryId) {
    return inquiries
        .findForUpdate(inquiryId)
        .orElseThrow(
            () ->
                new OrderProblemException(
                    HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND", "Диалог аренды не найден"));
  }

  private RentalInquirySelectionReceipt requiredReceiptForUpdate(UUID receiptId) {
    return receipts
        .findForUpdate(receiptId)
        .orElseThrow(
            () -> new IllegalStateException("Prepared cabin-selection receipt was not found"));
  }

  private void requireReceiptAuthority(
      OrderActor actor, RentalInquiry inquiry, RentalInquirySelectionReceipt receipt) {
    if (!inquiry.getId().equals(receipt.getInquiryId())
        || !actor.subjectId().equals(receipt.getSubjectId())) {
      throw new IllegalStateException("Cabin-selection receipt ownership changed");
    }
    if (!actor.role().equals(receipt.getActorRole())) {
      throw new AccessDeniedException("Cabin-selection actor role changed");
    }
    requireInquiryWarehouse(inquiry, receipt.getWarehouseId());
    access.requireWarehouseEdit(actor, receipt.getWarehouseId());
  }

  private static void requirePreparedIdentity(
      OrderActor actor, PreparedSelection prepared, RentalInquirySelectionReceipt receipt) {
    if (!prepared.receiptId().equals(receipt.getId())
        || !prepared.inquiryId().equals(receipt.getInquiryId())
        || !prepared.warehouseId().equals(receipt.getWarehouseId())
        || !actor.subjectId().equals(receipt.getSubjectId())
        || !prepared.idempotencyKey().equals(receipt.getPublicIdempotencyKey())
        || !prepared.exactRequestBody().equals(receipt.getDownstreamRequestBody())
        || prepared.commandType() != receipt.getCommandType()
        || !prepared.commandExpiresAt().equals(receipt.getCommandExpiresAt())
        || !Objects.equals(prepared.expiresAt(), receipt.getHoldExpiresAt())) {
      throw new IllegalStateException("Prepared cabin-selection identity changed");
    }
  }

  private static void validateResponse(
      PreparedSelection prepared, CabinSelectionResponse response) {
    List<UUID> responseIds = uniqueIds(response.rentalItemIds());
    if (!prepared.inquiryId().equals(response.inquiryId())
        || !prepared.warehouseId().equals(response.warehouseId())
        || !sameIds(prepared.rentalItemIds(), responseIds)
        || !Objects.equals(prepared.expiresAt(), response.expiresAt())
        || response.items() == null
        || response.items().size() != responseIds.size()) {
      throw new IllegalArgumentException(
          "Cabin-selection response does not match its prepared receipt");
    }
    for (int index = 0; index < responseIds.size(); index++) {
      AvailableCabinResponse item = response.items().get(index);
      if (item == null
          || !responseIds.get(index).equals(item.id())
          || item.version() < 0
          || !prepared.warehouseId().equals(item.warehouseId())
          || !"FREE".equals(item.status())
          || item.number() == null
          || item.passport() == null
          || item.tags() == null
          || item.tags().stream().anyMatch(Objects::isNull)
          || item.updatedAt() == null) {
        throw new IllegalArgumentException(
            "Cabin-selection response contains an invalid cabin snapshot");
      }
    }
  }

  private List<UUID> readIds(RentalInquirySelectionReceipt receipt) {
    try {
      if (receipt.getCommandType() == RentalInquirySelectionCommandType.RELEASE) {
        ReleaseCommand command =
            json.readValue(receipt.getDownstreamRequestBody(), ReleaseCommand.class);
        if (!receipt.getSubjectId().equals(command.actorSubjectId())
            || !receipt.getActorRole().equals(command.actorRole())) {
          throw new IllegalStateException("Stored cabin-selection release command is corrupt");
        }
        return List.of();
      }
      ReplaceCommand command =
          json.readValue(receipt.getDownstreamRequestBody(), ReplaceCommand.class);
      if (!receipt.getWarehouseId().equals(command.warehouseId())
          || !receipt.getSubjectId().equals(command.actorSubjectId())
          || !receipt.getActorRole().equals(command.actorRole())
          || !Objects.equals(receipt.getHoldExpiresAt(), command.expiresAt())
          || command.sourceHoldScopeId() != null) {
        throw new IllegalStateException("Stored cabin-selection replace command is corrupt");
      }
      return uniqueIds(command.rentalItemIds());
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored cabin-selection command is corrupt", exception);
    }
  }

  private CabinSelectionResponse readResponse(RentalInquirySelectionReceipt receipt) {
    try {
      if (!hash(receipt.getDownstreamRequestBody()).equals(receipt.getDownstreamRequestSha256())) {
        throw new IllegalStateException("Stored cabin-selection request checksum is corrupt");
      }
      CabinSelectionResponse response =
          json.readValue(receipt.getResponseBody(), CabinSelectionResponse.class);
      validateResponse(
          new PreparedSelection(
              receipt.getId(),
              receipt.getInquiryId(),
              receipt.getWarehouseId(),
              receipt.getCommandType(),
              receipt.getCommandExpiresAt(),
              receipt.getHoldExpiresAt(),
              receipt.getPublicIdempotencyKey(),
              receipt.getDownstreamRequestBody(),
              readIds(receipt)),
          response);
      return response;
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored cabin-selection response is corrupt", exception);
    }
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Cabin-selection value cannot be serialized", exception);
    }
  }

  private static List<UUID> uniqueIds(List<UUID> values) {
    if (values == null || values.size() > 100 || values.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("rentalItemIds are invalid");
    }
    LinkedHashSet<UUID> unique = new LinkedHashSet<>(values);
    if (unique.size() != values.size()) {
      throw new IllegalArgumentException("rentalItemIds must be unique");
    }
    return List.copyOf(unique);
  }

  private static boolean sameIds(List<UUID> left, List<UUID> right) {
    return left.size() == right.size()
        && new LinkedHashSet<>(left).equals(new LinkedHashSet<>(right));
  }

  private static void requireOwnedActive(OrderActor actor, RentalInquiry inquiry) {
    RentalInquiryService.requireOwner(actor, inquiry);
    if (inquiry.getState() != RentalInquiryState.ACTIVE) {
      throw conflict("INQUIRY_ARCHIVED", "Диалог уже завершён");
    }
  }

  private static void requireInquiryWarehouse(RentalInquiry inquiry, UUID warehouseId) {
    if (inquiry.getWarehouseId() == null) {
      throw conflict(
          "INQUIRY_WAREHOUSE_REQUIRED", "Сначала выполните поиск бытовок на выбранном складе");
    }
    if (!inquiry.getWarehouseId().equals(warehouseId)) {
      throw conflict(
          "INQUIRY_WAREHOUSE_LOCKED", "Для одного диалога можно использовать только один склад");
    }
  }

  private static void requireWritableRentalActor(OrderActor actor) {
    if (actor == null
        || !actor.writeScope()
        || !actor.rentalAccess()
        || "VIEWER".equals(actor.role())) {
      throw new AccessDeniedException("Writable rental-manager access is required");
    }
  }

  private static void requireCommand(
      OrderActor actor, UUID inquiryId, UUID publicIdempotencyKey, CabinSelectionRequest request) {
    if (actor == null
        || inquiryId == null
        || publicIdempotencyKey == null
        || request == null
        || request.warehouseId() == null
        || request.rentalItemIds() == null) {
      throw new IllegalArgumentException(
          "Cabin selection actor, inquiry, Idempotency-Key and request are required");
    }
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
  }

  private static OrderProblemException conflict(String code, String message) {
    return new OrderProblemException(HttpStatus.CONFLICT, code, message);
  }

  /** Canonical public request material hashed for key-reuse detection. */
  private record SelectionFingerprint(UUID inquiryId, UUID warehouseId, List<UUID> rentalItemIds) {}

  /** Exact asset replace-holds body frozen before the remote call. */
  private record ReplaceCommand(
      UUID warehouseId,
      List<UUID> rentalItemIds,
      OffsetDateTime expiresAt,
      UUID actorSubjectId,
      String actorRole,
      UUID sourceHoldScopeId) {}

  /** Exact asset release-holds body frozen before the remote call. */
  private record ReleaseCommand(UUID actorSubjectId, String actorRole) {}

  /** Branch selected by the completed PREPARE transaction. */
  public enum PreparationDisposition {
    /** The application service must execute the exact frozen remote command. */
    PREPARED,
    /** A frozen successful response is available with no dependency call. */
    REPLAYED,
    /** The idempotency receipt already ended in a non-success terminal state. */
    TERMINAL
  }

  /** Immutable token for an exact remote retry after the PREPARE transaction commits. */
  public record PreparedSelection(
      UUID receiptId,
      UUID inquiryId,
      UUID warehouseId,
      RentalInquirySelectionCommandType commandType,
      OffsetDateTime commandExpiresAt,
      OffsetDateTime expiresAt,
      UUID idempotencyKey,
      String exactRequestBody,
      List<UUID> rentalItemIds) {}

  /** PREPARE outcome containing exactly one executable, replay, or terminal branch. */
  public record SelectionPreparation(
      PreparationDisposition disposition,
      PreparedSelection prepared,
      CabinSelectionResponse response,
      RentalInquirySelectionReceiptState terminalState,
      String rejectionCode) {
    private static SelectionPreparation prepared(
        RentalInquirySelectionReceipt receipt, List<UUID> ids) {
      return new SelectionPreparation(
          PreparationDisposition.PREPARED,
          new PreparedSelection(
              receipt.getId(),
              receipt.getInquiryId(),
              receipt.getWarehouseId(),
              receipt.getCommandType(),
              receipt.getCommandExpiresAt(),
              receipt.getHoldExpiresAt(),
              receipt.getPublicIdempotencyKey(),
              receipt.getDownstreamRequestBody(),
              ids),
          null,
          null,
          null);
    }

    private static SelectionPreparation replayed(CabinSelectionResponse response) {
      return new SelectionPreparation(PreparationDisposition.REPLAYED, null, response, null, null);
    }

    private static SelectionPreparation terminal(
        RentalInquirySelectionReceiptState state, String rejectionCode) {
      return new SelectionPreparation(
          PreparationDisposition.TERMINAL, null, null, state, rejectionCode);
    }
  }

  /** Terminal transaction result, including a concurrent completed replay. */
  public record SelectionFinalization(
      CabinSelectionResponse response,
      boolean replayed,
      RentalInquirySelectionReceiptState terminalState,
      String rejectionCode) {
    private static SelectionFinalization completed(
        CabinSelectionResponse response, boolean replayed) {
      return new SelectionFinalization(response, replayed, null, null);
    }

    private static SelectionFinalization terminal(
        RentalInquirySelectionReceiptState state, String rejectionCode) {
      return new SelectionFinalization(null, false, state, rejectionCode);
    }
  }
}
