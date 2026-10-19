package dev.buhanzaz.rwms.logistics.inquiry.service;

import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.ManualBookingDraftHoldsRequest;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.ManualBookingDraftHoldsResponse;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Owns manager-scoped manual booking drafts associated with a client presentation.
 */
@Service
@RequiredArgsConstructor
public class ManualBookingDraftService {
  private final RentalSettingsService settings;
  private final LogisticsDependencyGateway dependencies;
  private final OrderAuthorizer access;

  public ManualBookingDraftHoldsResponse get(
      OrderActor actor, UUID draftId, UUID warehouseId) {
    access.requireWarehouseEdit(actor, warehouseId);
    try {
      return response(
          draftId,
          warehouseId,
          dependencies.readPresentationHolds(
              draftId, actor.subjectId(), actor.role()));
    } catch (LogisticsDependencyException exception) {
      throw dependencyProblem(exception);
    }
  }

  public ManualBookingDraftHoldsResponse replace(
      OrderActor actor,
      UUID idempotencyKey,
      UUID draftId,
      ManualBookingDraftHoldsRequest request) {
    access.requireWarehouseEdit(actor, request.warehouseId());
    List<UUID> requestedIds = uniqueIds(request.rentalItemIds());
    try {
      LogisticsDependencyGateway.PresentationHolds current =
          dependencies.readPresentationHolds(
              draftId, actor.subjectId(), actor.role());
      if (containsExactly(current, draftId, request.warehouseId(), requestedIds)
          && current.expiresAt() != null
          && current.expiresAt().isAfter(now())) {
        return response(draftId, request.warehouseId(), current);
      }

      OffsetDateTime expiresAt =
          now().plusMinutes(settings.manualBookingHoldMinutes(actor));
      LogisticsDependencyGateway.PresentationHolds held =
          dependencies.replacePresentationHolds(
              idempotencyKey,
              draftId,
              request.warehouseId(),
              requestedIds,
              expiresAt,
              actor.subjectId(),
              actor.role());
      if (!containsExactly(held, draftId, request.warehouseId(), requestedIds)) {
        throw conflict(
            "MANUAL_BOOKING_HOLD_MISMATCH",
            "Не удалось удержать все выбранные бытовки");
      }
      return response(draftId, request.warehouseId(), held);
    } catch (LogisticsDependencyException exception) {
      throw dependencyProblem(exception);
    }
  }

  private static ManualBookingDraftHoldsResponse response(
      UUID draftId,
      UUID warehouseId,
      LogisticsDependencyGateway.PresentationHolds source) {
    if (!draftId.equals(source.presentationId())) {
      throw conflict(
          "MANUAL_BOOKING_DRAFT_MISMATCH",
          "Сервис вернул удержание другого черновика");
    }
    if (source.holds().stream()
        .anyMatch(hold -> !warehouseId.equals(hold.warehouseId()))) {
      throw conflict(
          "MANUAL_BOOKING_WAREHOUSE_MISMATCH",
          "Черновик относится к другому складу");
    }
    List<UUID> ids =
        source.holds().stream()
            .map(LogisticsDependencyGateway.PresentationHold::rentalItemId)
            .toList();
    return new ManualBookingDraftHoldsResponse(
        draftId, warehouseId, source.expiresAt(), ids);
  }

  private static boolean containsExactly(
      LogisticsDependencyGateway.PresentationHolds source,
      UUID draftId,
      UUID warehouseId,
      List<UUID> requestedIds) {
    if (!draftId.equals(source.presentationId())
        || source.holds().stream()
            .anyMatch(hold -> !warehouseId.equals(hold.warehouseId()))) {
      return false;
    }
    return Set.copyOf(requestedIds)
        .equals(
            Set.copyOf(
                source.holds().stream()
                    .map(LogisticsDependencyGateway.PresentationHold::rentalItemId)
                    .toList()));
  }

  private static List<UUID> uniqueIds(List<UUID> values) {
    LinkedHashSet<UUID> unique = new LinkedHashSet<>(values);
    if (unique.size() != values.size() || unique.contains(null)) {
      throw new IllegalArgumentException("rentalItemIds must be unique");
    }
    return List.copyOf(unique);
  }

  private static OrderProblemException dependencyProblem(
      LogisticsDependencyException exception) {
    if (exception.kind()
        == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
      return conflict(
          exception.dependencyCode() == null
              ? "MANUAL_BOOKING_HOLD_REJECTED"
              : exception.dependencyCode(),
          "Одна или несколько бытовок уже недоступны");
    }
    return new OrderProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "MANUAL_BOOKING_HOLD_UNAVAILABLE",
        "Сервис удержания бытовок временно недоступен");
  }

  private static OrderProblemException conflict(String code, String message) {
    return new OrderProblemException(HttpStatus.CONFLICT, code, message);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }
}
