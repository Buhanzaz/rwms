package dev.buhanzaz.rwms.logistics.inquiry.service;

import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.AvailableCabinResponse;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.CabinSelectionRequest;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.CabinSelectionResponse;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiry;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquirySelectionCommandType;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquirySelectionReceiptState;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiryState;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalInquiryCabinSelectionStore.PreparedSelection;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalInquiryCabinSelectionStore.SelectionFinalization;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalInquiryCabinSelectionStore.SelectionPreparation;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the authoritative chat selection boundary while asset-service remains the only owner of
 * cabin hold state.
 *
 * <p>A durable receipt freezes the exact asset request and expiry before every remote hold call.
 * Unknown outcomes remain retryable with the same bytes; a completed retry returns its frozen
 * response without another asset mutation. Replacing the complete identifier list releases removed
 * cabins immediately, and an empty list releases the whole scope.
 */
@Service
@RequiredArgsConstructor
public class RentalInquiryCabinSelectionService {
  private final RentalInquiryService inquiries;
  private final RentalInquiryCabinSelectionStore store;
  private final LogisticsDependencyGateway dependencies;
  private final OrderAuthorizer access;
  private final Clock clock;

  /** Reads the current owner-scoped active hold set and its authoritative cabin snapshots. */
  @Transactional(propagation = Propagation.NEVER)
  public CabinSelectionResponse get(OrderActor actor, UUID inquiryId) {
    RentalInquiry inquiry = requiredActive(actor, inquiryId);
    if (inquiry.getWarehouseId() == null) {
      return empty(inquiryId, null);
    }
    access.requireWarehouseRead(actor, inquiry.getWarehouseId());
    try {
      return response(
          inquiryId,
          inquiry.getWarehouseId(),
          dependencies.readPresentationHolds(inquiryId, actor.subjectId(), actor.role()));
    } catch (LogisticsDependencyException exception) {
      throw dependencyProblem(exception);
    }
  }

  /**
   * Replaces the full inquiry selection with a settings-derived TTL; an empty selection releases
   * all holds and returns a null expiry.
   */
  @Transactional(propagation = Propagation.NEVER)
  public CabinSelectionResponse replace(
      OrderActor actor, UUID inquiryId, UUID idempotencyKey, CabinSelectionRequest request) {
    SelectionPreparation preparation = store.prepare(actor, inquiryId, idempotencyKey, request);
    if (preparation.response() != null) return preparation.response();
    if (preparation.prepared() == null) {
      throw terminalProblem(preparation.terminalState(), preparation.rejectionCode());
    }
    PreparedSelection prepared = preparation.prepared();
    try {
      LogisticsDependencyGateway.PresentationHolds holds;
      if (prepared.commandType() == RentalInquirySelectionCommandType.RELEASE) {
        holds =
            dependencies.releasePresentationHoldsExact(
                prepared.idempotencyKey(), inquiryId, prepared.exactRequestBody());
        if (!activeIds(holds, inquiryId, prepared.warehouseId()).isEmpty()) {
          throw malformedSelection();
        }
      } else {
        holds =
            dependencies.replacePresentationHoldsExact(
                prepared.idempotencyKey(), inquiryId, prepared.exactRequestBody());
        if (!sameIds(activeIds(holds, inquiryId, prepared.warehouseId()), prepared.rentalItemIds())
            || !prepared.expiresAt().equals(holds.expiresAt())) {
          throw malformedSelection();
        }
      }
      CabinSelectionResponse response =
          prepared.commandType() == RentalInquirySelectionCommandType.RELEASE
              ? empty(inquiryId, prepared.warehouseId())
              : response(inquiryId, prepared.warehouseId(), holds);
      return finalized(store.complete(actor, prepared, response));
    } catch (LogisticsDependencyException exception) {
      if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
        String safeCode =
            exception.dependencyCode() == null
                ? "CABIN_SELECTION_REJECTED"
                : exception.dependencyCode();
        SelectionFinalization finalization = store.reject(actor, prepared, safeCode);
        if (finalization.response() != null) return finalization.response();
      }
      throw dependencyProblem(exception);
    }
  }

  private static CabinSelectionResponse finalized(SelectionFinalization finalization) {
    if (finalization.response() != null) return finalization.response();
    throw terminalProblem(finalization.terminalState(), finalization.rejectionCode());
  }

  private static OrderProblemException terminalProblem(
      RentalInquirySelectionReceiptState state, String rejectionCode) {
    if (state == RentalInquirySelectionReceiptState.REJECTED) {
      return conflict(
          rejectionCode == null ? "CABIN_SELECTION_REJECTED" : rejectionCode,
          "Не удалось изменить выборку бытовок");
    }
    if (state == RentalInquirySelectionReceiptState.EXPIRED) {
      return conflict(
          "CABIN_SELECTION_RECEIPT_EXPIRED", "Срок повторения команды выборки бытовок истёк");
    }
    return new OrderProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CABIN_SELECTION_RECEIPT_INVALID",
        "Состояние команды выборки бытовок недоступно");
  }

  private RentalInquiry requiredActive(OrderActor actor, UUID inquiryId) {
    if (actor == null || inquiryId == null) {
      throw new IllegalArgumentException("Cabin selection actor and inquiry are required");
    }
    RentalInquiry inquiry = inquiries.requiredOwned(actor, inquiryId);
    if (inquiry.getState() != RentalInquiryState.ACTIVE) {
      throw conflict("INQUIRY_ARCHIVED", "Диалог уже завершён");
    }
    return inquiry;
  }

  private CabinSelectionResponse response(
      UUID inquiryId, UUID warehouseId, LogisticsDependencyGateway.PresentationHolds holds) {
    List<UUID> ids = activeIds(holds, inquiryId, warehouseId);
    if (ids.isEmpty()) return empty(inquiryId, warehouseId);
    if (holds.expiresAt() == null || !holds.expiresAt().isAfter(now())) {
      return empty(inquiryId, warehouseId);
    }
    List<LogisticsDependencyGateway.AvailableCabin> snapshots =
        dependencies.readCabinSnapshots(warehouseId, ids);
    Map<UUID, LogisticsDependencyGateway.AvailableCabin> byId = new LinkedHashMap<>();
    if (snapshots == null) throw malformedSelection();
    for (LogisticsDependencyGateway.AvailableCabin snapshot : snapshots) {
      if (snapshot == null
          || snapshot.id() == null
          || snapshot.version() < 0
          || !warehouseId.equals(snapshot.warehouseId())
          || !"FREE".equals(snapshot.status())
          || snapshot.number() == null
          || snapshot.passport() == null
          || snapshot.tags() == null
          || snapshot.tags().stream().anyMatch(java.util.Objects::isNull)
          || snapshot.updatedAt() == null
          || byId.put(snapshot.id(), snapshot) != null) {
        throw malformedSelection();
      }
    }
    if (!byId.keySet().equals(new LinkedHashSet<>(ids))) throw malformedSelection();
    return new CabinSelectionResponse(
        inquiryId,
        warehouseId,
        holds.expiresAt(),
        ids,
        ids.stream().map(byId::get).map(RentalInquiryCabinSelectionService::cabin).toList());
  }

  private static List<UUID> activeIds(
      LogisticsDependencyGateway.PresentationHolds holds, UUID inquiryId, UUID warehouseId) {
    if (holds == null
        || holds.presentationId() == null
        || !inquiryId.equals(holds.presentationId())
        || holds.holds() == null) {
      throw malformedSelection();
    }
    LinkedHashSet<UUID> ids = new LinkedHashSet<>();
    for (LogisticsDependencyGateway.PresentationHold hold : holds.holds()) {
      if (hold == null
          || !inquiryId.equals(hold.presentationId())
          || (warehouseId != null && !warehouseId.equals(hold.warehouseId()))) {
        throw malformedSelection();
      }
      if ("ACTIVE".equals(hold.state())) {
        if (hold.rentalItemId() == null || !ids.add(hold.rentalItemId())) {
          throw malformedSelection();
        }
      }
    }
    return List.copyOf(ids);
  }

  private static boolean sameIds(List<UUID> left, List<UUID> right) {
    return left.size() == right.size()
        && new LinkedHashSet<>(left).equals(new LinkedHashSet<>(right));
  }

  private static AvailableCabinResponse cabin(LogisticsDependencyGateway.AvailableCabin source) {
    return new AvailableCabinResponse(
        source.id(),
        source.version(),
        source.warehouseId(),
        source.status(),
        source.number(),
        source.rentalType(),
        source.dimensions(),
        source.finishing(),
        source.category(),
        source.characteristics(),
        source.linoleum(),
        source.passport(),
        source.tags(),
        source.updatedAt());
  }

  private static CabinSelectionResponse empty(UUID inquiryId, UUID warehouseId) {
    return new CabinSelectionResponse(inquiryId, warehouseId, null, List.of(), List.of());
  }

  private static OrderProblemException malformedSelection() {
    return new OrderProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CABIN_SELECTION_INVALID_RESPONSE",
        "Сервис блокировок вернул несогласованную выборку");
  }

  private static OrderProblemException dependencyProblem(LogisticsDependencyException exception) {
    if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
      return conflict(
          exception.dependencyCode() == null
              ? "CABIN_SELECTION_REJECTED"
              : exception.dependencyCode(),
          "Не удалось изменить выборку бытовок");
    }
    return new OrderProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CABIN_SELECTION_UNAVAILABLE",
        "Сервис блокировок бытовок временно недоступен");
  }

  private static OrderProblemException conflict(String code, String message) {
    return new OrderProblemException(HttpStatus.CONFLICT, code, message);
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
  }
}
