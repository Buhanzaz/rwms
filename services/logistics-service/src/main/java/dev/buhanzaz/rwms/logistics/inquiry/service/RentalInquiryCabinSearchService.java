package dev.buhanzaz.rwms.logistics.inquiry.service;

import static dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.*;

import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquirySearchAttemptState;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalInquiryCabinSearchStore.PreparedSearch;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalInquiryCabinSearchStore.SearchFinalization;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalInquiryCabinSearchStore.SearchPreparation;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Non-transactional application workflow for rental-inquiry cabin search.
 *
 * <p>It performs warehouse and asset HTTP calls only after PREPARE committed, then delegates the
 * local COMPLETE or sanitized terminal transition to {@link RentalInquiryCabinSearchStore}.
 */
@Service
@RequiredArgsConstructor
public class RentalInquiryCabinSearchService {
  private final RentalInquiryCabinSearchStore store;
  private final LogisticsDependencyGateway dependencies;

  /**
   * Executes or resumes one subject-scoped search. A completed replay never calls either remote
   * owner and is marked for the HTTP {@code Idempotency-Replayed} header.
   */
  @Transactional(propagation = Propagation.NEVER)
  public CabinSearchOutcome search(
      OrderActor actor, UUID inquiryId, UUID publicIdempotencyKey, CabinSearchRequest request) {
    SearchPreparation preparation = store.prepare(actor, inquiryId, publicIdempotencyKey, request);
    return switch (preparation.disposition()) {
      case REPLAYED -> new CabinSearchOutcome(preparation.response(), true);
      case TERMINAL ->
          throw terminalProblem(preparation.terminalState(), preparation.rejectionCode());
      case PREPARED -> executePrepared(actor, preparation.prepared());
    };
  }

  private CabinSearchOutcome executePrepared(OrderActor actor, PreparedSearch prepared) {
    LogisticsDependencyGateway.WarehouseIdentity warehouse;
    try {
      warehouse = dependencies.readWarehouseIdentity(prepared.warehouseId());
    } catch (LogisticsDependencyException exception) {
      throw unavailable();
    }
    if (warehouse == null || !prepared.warehouseId().equals(warehouse.id())) {
      throw unavailable();
    }
    if (!warehouse.active()) {
      return finalized(store.reject(actor, prepared, "WAREHOUSE_UNAVAILABLE"));
    }

    LogisticsDependencyGateway.CabinSearchResult result;
    try {
      result =
          dependencies.searchAvailableCabins(
              prepared.downstreamIdempotencyKey(), prepared.exactRequestBody());
      validateResult(prepared, result);
    } catch (LogisticsDependencyException exception) {
      if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
        String safeCode =
            exception.dependencyCode() == null
                ? "CABIN_SEARCH_REJECTED"
                : exception.dependencyCode();
        return finalized(store.reject(actor, prepared, safeCode));
      }
      throw unavailable();
    }

    CabinSearchResponse response =
        new CabinSearchResponse(
            result.warehouseId(),
            result.expiresAt(),
            result.groups().stream()
                .map(
                    group ->
                        new CabinSearchGroupResult(
                            apiGroup(group.group()),
                            group.cabins().stream()
                                .map(RentalInquiryCabinSearchService::cabin)
                                .toList()))
                .toList());
    return finalized(store.complete(actor, prepared, response));
  }

  private static void validateResult(
      PreparedSearch prepared, LogisticsDependencyGateway.CabinSearchResult result) {
    if (result == null
        || !prepared.warehouseId().equals(result.warehouseId())
        || !prepared.holdExpiresAt().equals(result.expiresAt())
        || result.groups() == null
        || result.groups().size() != prepared.command().groups().size()) {
      throw malformedResult();
    }
    for (int index = 0; index < result.groups().size(); index++) {
      LogisticsDependencyGateway.CabinSearchGroupResult group = result.groups().get(index);
      if (group == null
          || !prepared.command().groups().get(index).equals(group.group())
          || group.cabins() == null
          || group.cabins().stream()
              .anyMatch(
                  cabin -> cabin == null || !prepared.warehouseId().equals(cabin.warehouseId()))) {
        throw malformedResult();
      }
    }
  }

  private static CabinSearchOutcome finalized(SearchFinalization finalization) {
    if (finalization.response() != null) {
      return new CabinSearchOutcome(finalization.response(), finalization.replayed());
    }
    throw terminalProblem(finalization.terminalState(), finalization.rejectionCode());
  }

  private static CabinSearchGroup apiGroup(LogisticsDependencyGateway.CabinSearchGroup source) {
    return new CabinSearchGroup(
        source.cabinType(),
        source.finish(),
        source.dimensions(),
        source.category(),
        source.characteristics(),
        source.linoleum(),
        source.quantity());
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
        source.contents().stream()
            .map(
                content ->
                    new dev.buhanzaz.rwms.logistics.order.api.OrderApiModels
                        .OrderEquipmentContentResponse(
                        content.equipmentId(),
                        content.equipmentName(),
                        content.quantity(),
                        content.locationKind()))
            .toList(),
        source.updatedAt());
  }

  private static LogisticsDependencyException malformedResult() {
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.CONFIGURATION,
        "Asset-service returned a cabin search result that does not match the prepared command");
  }

  private static OrderProblemException terminalProblem(
      RentalInquirySearchAttemptState state, String rejectionCode) {
    if (state == RentalInquirySearchAttemptState.EXPIRED) {
      return new OrderProblemException(
          HttpStatus.CONFLICT,
          "CABIN_SEARCH_EXPIRED",
          "Срок действия поиска бытовок истёк; используйте новый Idempotency-Key");
    }
    return new OrderProblemException(
        HttpStatus.CONFLICT,
        rejectionCode == null ? "CABIN_SEARCH_REJECTED" : rejectionCode,
        "Поиск свободных бытовок отклонён");
  }

  private static OrderProblemException unavailable() {
    return new OrderProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CABIN_SEARCH_UNAVAILABLE",
        "Сервис свободных бытовок временно недоступен");
  }

  /** HTTP response plus whether it came from the durable frozen-response receipt. */
  public record CabinSearchOutcome(CabinSearchResponse response, boolean replayed) {}
}
