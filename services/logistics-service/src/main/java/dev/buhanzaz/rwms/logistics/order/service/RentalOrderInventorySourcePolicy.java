package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Authorizes a physical order source independently from the service warehouse and checks the
 * warehouse-owned directed support edge. The shipment contract is date-only, so calendar
 * eligibility is checked at local noon; exact service-interval routing remains a route-planner
 * responsibility before physical departure.
 */
@Service
@RequiredArgsConstructor
class RentalOrderInventorySourcePolicy {
  private final LogisticsDependencyGateway dependencies;

  /** Authorizes a source candidate for the current instant without mutating order reservations. */
  UUID requireReadableSource(OrderActor actor, RentalOrder order, UUID requestedSource) {
    UUID source = source(order, requestedSource);
    if (!canRead(actor, order.getWarehouseId()) || !canRead(actor, source)) {
      throw new AccessDeniedException("Insufficient warehouse access");
    }
    if (!source.equals(order.getWarehouseId())) {
      requireSupportLink(order.getWarehouseId(), source, OffsetDateTime.now(ZoneOffset.UTC));
    }
    return source;
  }

  /** Rechecks both warehouse access and date-filtered direct-fulfillment support before admission. */
  UUID requireWritableShipmentSource(
      OrderActor actor, RentalOrder order, UUID requestedSource, LocalDate scheduledDate) {
    if (scheduledDate == null) throw new IllegalArgumentException("Shipment date is required");
    UUID source = source(order, requestedSource);
    if (!actor.writeScope()
        || !canEdit(actor, order.getWarehouseId())
        || !canEdit(actor, source)) {
      throw new AccessDeniedException("Insufficient warehouse access");
    }
    if (!source.equals(order.getWarehouseId())) {
      LogisticsDependencyGateway.WarehouseIdentity served;
      try {
        served = dependencies.readWarehouseIdentity(order.getWarehouseId());
      } catch (LogisticsDependencyException exception) {
        throw RentalOrderProblems.dependencyProblem(exception);
      }
      if (served == null
          || !order.getWarehouseId().equals(served.id())
          || !served.active()
          || served.timeZone() == null) {
        throw RentalOrderProblems.invalidDependencyResponse();
      }
      OffsetDateTime planningAt;
      try {
        planningAt =
            scheduledDate
                .atTime(LocalTime.NOON)
                .atZone(ZoneId.of(served.timeZone()))
                .toOffsetDateTime();
      } catch (RuntimeException exception) {
        throw RentalOrderProblems.invalidDependencyResponse();
      }
      requireSupportLink(order.getWarehouseId(), source, planningAt);
    }
    return source;
  }

  /**
   * Authorizes one immediate cabin replacement source before any asset-side plan or reservation
   * effect. The order's service warehouse remains unchanged.
   */
  UUID requireWritableReplacementSource(
      OrderActor actor, UUID serviceWarehouseId, UUID requestedSource) {
    if (serviceWarehouseId == null) {
      throw RentalOrderProblems.conflict(
          "ORDER_WAREHOUSE_REQUIRED", "Сначала выберите склад заказа");
    }
    UUID source = requestedSource == null ? serviceWarehouseId : requestedSource;
    if (!actor.writeScope()
        || !canEdit(actor, serviceWarehouseId)
        || !canEdit(actor, source)) {
      throw new AccessDeniedException("Insufficient warehouse access");
    }
    if (!source.equals(serviceWarehouseId)) {
      requireSupportLink(serviceWarehouseId, source, OffsetDateTime.now(ZoneOffset.UTC));
    }
    return source;
  }

  private void requireSupportLink(
      UUID serviceWarehouseId, UUID sourceWarehouseId, OffsetDateTime at) {
    List<LogisticsDependencyGateway.WarehouseSupportLink> links;
    try {
      links = dependencies.listWarehouseSupportLinks(serviceWarehouseId, at);
    } catch (LogisticsDependencyException exception) {
      throw RentalOrderProblems.dependencyProblem(exception);
    }
    if (links == null) throw RentalOrderProblems.invalidDependencyResponse();
    boolean allowed =
        links.stream()
            .filter(java.util.Objects::nonNull)
            .anyMatch(
                link ->
                    link.allowInventory()
                        && link.allowDirectFulfillment()
                        && link.supportWarehouse() != null
                        && sourceWarehouseId.equals(link.supportWarehouse().id())
                        && link.supportWarehouse().active()
                        && link.servedWarehouse() != null
                        && serviceWarehouseId.equals(link.servedWarehouse().id())
                        && link.servedWarehouse().active());
    if (!allowed) {
      throw RentalOrderProblems.conflict(
          "DIRECT_SOURCE_NOT_ALLOWED",
          "Склад-источник не может напрямую выполнять заказ выбранного склада обслуживания");
    }
  }

  private static UUID source(RentalOrder order, UUID requestedSource) {
    if (order == null || order.getWarehouseId() == null) {
      throw RentalOrderProblems.conflict(
          "ORDER_WAREHOUSE_REQUIRED", "Сначала выберите склад заказа");
    }
    return requestedSource == null ? order.getWarehouseId() : requestedSource;
  }

  private static boolean canRead(OrderActor actor, UUID warehouseId) {
    return actor.globalAdministrator() || actor.readableWarehouses().contains(warehouseId);
  }

  private static boolean canEdit(OrderActor actor, UUID warehouseId) {
    return actor.globalAdministrator() || actor.editableWarehouses().contains(warehouseId);
  }
}
