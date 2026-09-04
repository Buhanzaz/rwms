package dev.buhanzaz.rwms.logistics.inquiry.service;

import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.CabinSearchRequest;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.CabinSearchResponse;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiry;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquirySearchAttempt;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquirySearchAttemptState;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiryState;
import dev.buhanzaz.rwms.logistics.inquiry.repository.RentalInquiryRepository;
import dev.buhanzaz.rwms.logistics.inquiry.repository.RentalInquirySearchAttemptRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
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
 * Owns the short local transactions that prepare, complete, reject, or expire a cabin-search
 * receipt. No dependency call is made while this component holds an inquiry or receipt lock.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RentalInquiryCabinSearchStore {
  private static final String DOWNSTREAM_KEY_DOMAIN =
      "rwms:logistics:rental-inquiry-cabin-search:v2";

  private final RentalInquiryRepository inquiries;
  private final RentalInquirySearchAttemptRepository attempts;
  private final RentalSettingsService settings;
  private final OrderAuthorizer access;
  private final ObjectMapper json;
  private final Clock clock;

  /**
   * Locks the inquiry, revalidates current authority and either resumes an exact receipt, freezes a
   * new downstream command, or commits a safe expiry transition.
   */
  @Transactional
  public SearchPreparation prepare(
      OrderActor actor,
      UUID inquiryId,
      UUID publicIdempotencyKey,
      CabinSearchRequest request) {
    requireCommand(actor, inquiryId, publicIdempotencyKey, request);
    requireWritableRentalActor(actor);
    OffsetDateTime timestamp = now();
    RentalInquiry inquiry = requiredForUpdate(inquiryId);
    requireOwnedActive(actor, inquiry);

    String requestHash =
        hash(
            write(
                new SearchFingerprint(
                    inquiryId,
                    request.warehouseId(),
                    request.effectiveResultMode(),
                    List.copyOf(request.groups()))));
    RentalInquirySearchAttempt existing =
        attempts
            .findByPublicKeyForUpdate(
                actor.subjectId(),
                RentalInquirySearchAttempt.OPERATION,
                publicIdempotencyKey)
            .orElse(null);
    if (existing != null) {
      if (!existing.matchesRequest(requestHash)) {
        throw conflict(
            "IDEMPOTENCY_KEY_REUSED",
            "Idempotency-Key уже связан с другим поиском бытовок");
      }
      access.requireWarehouseEdit(actor, existing.getWarehouseId());
      requireInquiryWarehouse(inquiry, existing.getWarehouseId());
      return resume(existing, timestamp);
    }

    access.requireWarehouseEdit(actor, request.warehouseId());
    requireInquiryWarehouse(inquiry, request.warehouseId());
    RentalInquirySearchAttempt active =
        attempts
            .findByInquiryAndStateForUpdate(
                inquiryId, RentalInquirySearchAttemptState.PREPARED)
            .orElse(null);
    if (active != null) {
      if (!active.holdExpiredAt(timestamp)) {
        throw conflict(
            "CABIN_SEARCH_IN_PROGRESS",
            "Для диалога уже выполняется другой поиск бытовок");
      }
      active.expire(timestamp);
      attempts.saveAndFlush(active);
    }

    OffsetDateTime expiresAt =
        timestamp.plusMinutes(settings.chatSelectionHoldMinutes(actor));
    LogisticsDependencyGateway.CabinSearchCommand command =
        new LogisticsDependencyGateway.CabinSearchCommand(
            request.warehouseId(),
            inquiryId,
            expiresAt,
            actor.subjectId(),
            actor.role(),
            LogisticsDependencyGateway.CabinSearchResultMode.valueOf(
                request.effectiveResultMode().name()),
            request.groups().stream()
                .map(
                    group ->
                        new LogisticsDependencyGateway.CabinSearchGroup(
                            group.cabinType(),
                            group.finish(),
                            group.dimensions(),
                            group.category(),
                            group.characteristics(),
                            group.linoleum(),
                            group.quantity()))
                .toList());
    String exactRequestBody = write(command);
    UUID downstreamKey = downstreamKey(actor.subjectId(), publicIdempotencyKey);
    RentalInquirySearchAttempt prepared =
        attempts.saveAndFlush(
            RentalInquirySearchAttempt.prepare(
                inquiryId,
                actor.subjectId(),
                publicIdempotencyKey,
                requestHash,
                request.warehouseId(),
                downstreamKey,
                exactRequestBody,
                hash(exactRequestBody),
                actor.role(),
                expiresAt,
                timestamp));
    return SearchPreparation.prepared(prepared, command);
  }

  /**
   * Revalidates current ownership and warehouse-write authority, then atomically selects the
   * inquiry warehouse and freezes a successful asset response.
   */
  @Transactional
  public SearchFinalization complete(
      OrderActor actor, PreparedSearch prepared, CabinSearchResponse response) {
    requireWritableRentalActor(actor);
    Objects.requireNonNull(prepared, "prepared");
    Objects.requireNonNull(response, "response");
    OffsetDateTime timestamp = now();
    RentalInquiry inquiry = requiredForUpdate(prepared.inquiryId());
    requireOwnedActive(actor, inquiry);
    RentalInquirySearchAttempt attempt =
        requiredAttemptForUpdate(prepared.receiptId());
    requirePreparedIdentity(actor, prepared, attempt);
    access.requireWarehouseEdit(actor, attempt.getWarehouseId());
    requireInquiryWarehouse(inquiry, attempt.getWarehouseId());
    if (attempt.getState() == RentalInquirySearchAttemptState.COMPLETED) {
      return SearchFinalization.completed(readResponse(attempt), true);
    }
    if (attempt.getState() != RentalInquirySearchAttemptState.PREPARED) {
      return SearchFinalization.terminal(attempt.getState(), attempt.getRejectionCode());
    }
    if (attempt.holdExpiredAt(timestamp)) {
      attempt.expire(timestamp);
      attempts.saveAndFlush(attempt);
      return SearchFinalization.terminal(RentalInquirySearchAttemptState.EXPIRED, null);
    }
    if (!attempt.getWarehouseId().equals(response.warehouseId())
        || !attempt.getHoldExpiresAt().equals(response.expiresAt())) {
      throw new IllegalArgumentException(
          "Asset cabin-search response does not match its prepared receipt");
    }
    String frozenResponse = write(response);
    inquiry.selectWarehouse(attempt.getWarehouseId(), timestamp);
    attempt.complete(frozenResponse, timestamp);
    inquiries.saveAndFlush(inquiry);
    attempts.saveAndFlush(attempt);
    return SearchFinalization.completed(response, false);
  }

  /**
   * Records only an inactive-warehouse or classified 400/409 domain rejection. Unknown remote
   * outcomes never call this method and therefore stay PREPARED.
   */
  @Transactional
  public SearchFinalization reject(
      OrderActor actor, PreparedSearch prepared, String safeRejectionCode) {
    requireWritableRentalActor(actor);
    Objects.requireNonNull(prepared, "prepared");
    OffsetDateTime timestamp = now();
    RentalInquiry inquiry = requiredForUpdate(prepared.inquiryId());
    requireOwnedActive(actor, inquiry);
    RentalInquirySearchAttempt attempt =
        requiredAttemptForUpdate(prepared.receiptId());
    requirePreparedIdentity(actor, prepared, attempt);
    access.requireWarehouseEdit(actor, attempt.getWarehouseId());
    requireInquiryWarehouse(inquiry, attempt.getWarehouseId());
    if (attempt.getState() == RentalInquirySearchAttemptState.COMPLETED) {
      return SearchFinalization.completed(readResponse(attempt), true);
    }
    if (attempt.getState() != RentalInquirySearchAttemptState.PREPARED) {
      return SearchFinalization.terminal(attempt.getState(), attempt.getRejectionCode());
    }
    if (attempt.holdExpiredAt(timestamp)) {
      attempt.expire(timestamp);
      attempts.saveAndFlush(attempt);
      return SearchFinalization.terminal(RentalInquirySearchAttemptState.EXPIRED, null);
    }
    attempt.reject(safeRejectionCode, timestamp);
    attempts.saveAndFlush(attempt);
    return SearchFinalization.terminal(
        RentalInquirySearchAttemptState.REJECTED, attempt.getRejectionCode());
  }

  private SearchPreparation resume(
      RentalInquirySearchAttempt attempt, OffsetDateTime timestamp) {
    return switch (attempt.getState()) {
      case PREPARED -> {
        if (attempt.holdExpiredAt(timestamp)) {
          attempt.expire(timestamp);
          attempts.saveAndFlush(attempt);
          yield SearchPreparation.terminal(RentalInquirySearchAttemptState.EXPIRED, null);
        }
        LogisticsDependencyGateway.CabinSearchCommand command = readCommand(attempt);
        if (!hash(attempt.getDownstreamRequestBody())
            .equals(attempt.getDownstreamRequestSha256())) {
          throw new IllegalStateException("Stored cabin-search request checksum is corrupt");
        }
        yield SearchPreparation.prepared(attempt, command);
      }
      case COMPLETED -> SearchPreparation.replayed(readResponse(attempt));
      case REJECTED, EXPIRED ->
          SearchPreparation.terminal(attempt.getState(), attempt.getRejectionCode());
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

  private RentalInquirySearchAttempt requiredAttemptForUpdate(UUID receiptId) {
    return attempts
        .findForUpdate(receiptId)
        .orElseThrow(
            () ->
                new IllegalStateException("Prepared cabin-search receipt was not found"));
  }

  private void requireOwnedActive(OrderActor actor, RentalInquiry inquiry) {
    RentalInquiryService.requireOwner(actor, inquiry);
    if (inquiry.getState() != RentalInquiryState.ACTIVE) {
      throw conflict("INQUIRY_ARCHIVED", "Диалог уже завершён");
    }
  }

  private static void requireInquiryWarehouse(RentalInquiry inquiry, UUID warehouseId) {
    if (inquiry.getWarehouseId() != null
        && !inquiry.getWarehouseId().equals(warehouseId)) {
      throw conflict(
          "INQUIRY_WAREHOUSE_LOCKED",
          "Для одного диалога можно использовать только один склад");
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
      OrderActor actor,
      UUID inquiryId,
      UUID publicIdempotencyKey,
      CabinSearchRequest request) {
    if (actor == null
        || inquiryId == null
        || publicIdempotencyKey == null
        || request == null
        || request.warehouseId() == null
        || request.groups() == null) {
      throw new IllegalArgumentException(
          "Cabin search actor, inquiry, Idempotency-Key and request are required");
    }
  }

  private static void requirePreparedIdentity(
      OrderActor actor,
      PreparedSearch prepared,
      RentalInquirySearchAttempt attempt) {
    if (!prepared.receiptId().equals(attempt.getId())
        || !prepared.inquiryId().equals(attempt.getInquiryId())
        || !actor.subjectId().equals(attempt.getSubjectId())
        || !prepared.downstreamIdempotencyKey().equals(attempt.getDownstreamIdempotencyKey())
        || !prepared.exactRequestBody().equals(attempt.getDownstreamRequestBody())) {
      throw new IllegalStateException("Prepared cabin-search identity changed");
    }
  }

  private LogisticsDependencyGateway.CabinSearchCommand readCommand(
      RentalInquirySearchAttempt attempt) {
    try {
      return json.readValue(
          attempt.getDownstreamRequestBody(),
          LogisticsDependencyGateway.CabinSearchCommand.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored cabin-search request is corrupt", exception);
    }
  }

  private CabinSearchResponse readResponse(RentalInquirySearchAttempt attempt) {
    try {
      return json.readValue(attempt.getResponseBody(), CabinSearchResponse.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored cabin-search response is corrupt", exception);
    }
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Cabin search value cannot be serialized", exception);
    }
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static UUID downstreamKey(UUID subjectId, UUID publicIdempotencyKey) {
    String material =
        DOWNSTREAM_KEY_DOMAIN
            + '\u001f'
            + subjectId
            + '\u001f'
            + publicIdempotencyKey;
    return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
  }

  private static OrderProblemException conflict(String code, String message) {
    return new OrderProblemException(HttpStatus.CONFLICT, code, message);
  }

  /** Exact public request material used only to compute the subject receipt hash. */
  private record SearchFingerprint(
      UUID inquiryId,
      UUID warehouseId,
      dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.CabinSearchResultMode
          resultMode,
      List<dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.CabinSearchGroup>
          groups) {}

  /** Result of the PREPARE transaction: call downstream, replay, or return a terminal conflict. */
  public record SearchPreparation(
      PreparationDisposition disposition,
      PreparedSearch prepared,
      CabinSearchResponse response,
      RentalInquirySearchAttemptState terminalState,
      String rejectionCode) {
    private static SearchPreparation prepared(
        RentalInquirySearchAttempt attempt,
        LogisticsDependencyGateway.CabinSearchCommand command) {
      return new SearchPreparation(
          PreparationDisposition.PREPARED,
          new PreparedSearch(
              attempt.getId(),
              attempt.getInquiryId(),
              attempt.getWarehouseId(),
              attempt.getHoldExpiresAt(),
              attempt.getDownstreamIdempotencyKey(),
              attempt.getDownstreamRequestBody(),
              command),
          null,
          null,
          null);
    }

    private static SearchPreparation replayed(CabinSearchResponse response) {
      return new SearchPreparation(
          PreparationDisposition.REPLAYED, null, response, null, null);
    }

    private static SearchPreparation terminal(
        RentalInquirySearchAttemptState state, String rejectionCode) {
      return new SearchPreparation(
          PreparationDisposition.TERMINAL, null, null, state, rejectionCode);
    }
  }

  /** Branch selected by a completed PREPARE transaction. */
  public enum PreparationDisposition {
    /** The application service must perform the exact frozen remote command. */
    PREPARED,
    /** A frozen successful response is ready without any remote call. */
    REPLAYED,
    /** The public key belongs to a rejected or expired terminal receipt. */
    TERMINAL
  }

  /** Immutable resume token containing only values already frozen in the receipt. */
  public record PreparedSearch(
      UUID receiptId,
      UUID inquiryId,
      UUID warehouseId,
      OffsetDateTime holdExpiresAt,
      UUID downstreamIdempotencyKey,
      String exactRequestBody,
      LogisticsDependencyGateway.CabinSearchCommand command) {}

  /** Result of a terminal local transaction, including a concurrent frozen-success replay. */
  public record SearchFinalization(
      CabinSearchResponse response,
      boolean replayed,
      RentalInquirySearchAttemptState terminalState,
      String rejectionCode) {
    private static SearchFinalization completed(
        CabinSearchResponse response, boolean replayed) {
      return new SearchFinalization(response, replayed, null, null);
    }

    private static SearchFinalization terminal(
        RentalInquirySearchAttemptState state, String rejectionCode) {
      return new SearchFinalization(null, false, state, rejectionCode);
    }
  }
}
