package dev.buhanzaz.rwms.logistics.inquiry.service;

import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.AvailableCabinResponse;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.CabinCatalogResponse;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiry;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiryState;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads bounded asset-owned cabin facts for an inquiry without creating, renewing, or releasing
 * selection holds.
 */
@Service
@RequiredArgsConstructor
public class RentalInquiryCabinCatalogService {
  private final RentalInquiryService inquiries;
  private final LogisticsDependencyGateway dependencies;
  private final OrderAuthorizer access;

  /** Performs one warehouse-authorized facts-only cabin lookup outside a local transaction. */
  @Transactional(propagation = Propagation.NEVER)
  public CabinCatalogResponse search(
      OrderActor actor, UUID inquiryId, UUID warehouseId, String query, int page, int size) {
    if (actor == null || inquiryId == null || warehouseId == null) {
      throw new IllegalArgumentException("Cabin catalog actor, inquiry and warehouse are required");
    }
    String normalizedQuery = query == null ? "" : query.trim();
    if (normalizedQuery.length() > 255) {
      throw new IllegalArgumentException("Cabin catalog query is invalid");
    }
    if (page < 0 || size < 1 || size > 100) {
      throw new IllegalArgumentException("Cabin catalog page is invalid");
    }
    RentalInquiry inquiry = inquiries.requiredOwned(actor, inquiryId);
    if (inquiry.getState() != RentalInquiryState.ACTIVE) {
      throw conflict("INQUIRY_ARCHIVED", "Диалог уже завершён");
    }
    if (inquiry.getWarehouseId() != null && !inquiry.getWarehouseId().equals(warehouseId)) {
      throw conflict(
          "INQUIRY_WAREHOUSE_LOCKED", "Для одного диалога можно использовать только один склад");
    }
    access.requireWarehouseRead(actor, warehouseId);
    LogisticsDependencyGateway.CabinCatalogPage result;
    try {
      result = dependencies.readCabinCatalog(warehouseId, normalizedQuery, page, size);
    } catch (LogisticsDependencyException exception) {
      throw dependencyProblem(exception);
    }
    validate(result, warehouseId, page, size);
    return new CabinCatalogResponse(
        result.warehouseId(),
        result.content().stream().map(RentalInquiryCabinCatalogService::cabin).toList(),
        result.page(),
        result.size(),
        result.totalElements(),
        result.totalPages());
  }

  private static void validate(
      LogisticsDependencyGateway.CabinCatalogPage page,
      UUID warehouseId,
      int requestedPage,
      int requestedSize) {
    long expectedTotalPages =
        page == null || page.totalElements() <= 0
            ? 0
            : page.totalElements() / requestedSize
                + (page.totalElements() % requestedSize == 0 ? 0 : 1);
    if (page == null
        || !warehouseId.equals(page.warehouseId())
        || page.content() == null
        || page.page() != requestedPage
        || page.size() != requestedSize
        || page.totalElements() < 0
        || page.totalPages() < 0
        || page.totalPages() != expectedTotalPages
        || page.content().size() > requestedSize
        || page.content().size()
            != expectedContentSize(page.totalElements(), requestedPage, requestedSize)) {
      throw malformedCatalog();
    }
    Set<UUID> ids = new HashSet<>();
    for (LogisticsDependencyGateway.AvailableCabin cabin : page.content()) {
      if (cabin == null
          || cabin.id() == null
          || !warehouseId.equals(cabin.warehouseId())
          || !ids.add(cabin.id())) {
        throw malformedCatalog();
      }
    }
  }

  private static int expectedContentSize(long totalElements, int page, int size) {
    long offset = (long) page * size;
    if (offset >= totalElements) return 0;
    return (int) Math.min(size, totalElements - offset);
  }

  private static OrderProblemException malformedCatalog() {
    return new OrderProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CABIN_CATALOG_INVALID_RESPONSE",
        "Каталог бытовок вернул несогласованные данные");
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

  private static OrderProblemException dependencyProblem(LogisticsDependencyException exception) {
    if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
      return conflict(
          exception.dependencyCode() == null
              ? "CABIN_CATALOG_REJECTED"
              : exception.dependencyCode(),
          "Поиск бытовок отклонён");
    }
    return new OrderProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CABIN_CATALOG_UNAVAILABLE",
        "Каталог бытовок временно недоступен");
  }

  private static OrderProblemException conflict(String code, String message) {
    return new OrderProblemException(HttpStatus.CONFLICT, code, message);
  }
}
