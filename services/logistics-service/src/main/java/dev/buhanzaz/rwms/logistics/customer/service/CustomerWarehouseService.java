package dev.buhanzaz.rwms.logistics.customer.service;

import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerWarehouseResponse;

import dev.buhanzaz.rwms.logistics.customer.config.CustomerDeliveryProperties;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Exposes routable active warehouses to CustomerApp without duplicating warehouse coordinates. */
@Service
@RequiredArgsConstructor
public class CustomerWarehouseService {
  private final CustomerDeliveryProperties properties;
  private final LogisticsDependencyGateway dependencies;

  /**
   * Lists configured ordinary depots and representative warehouses from canonical owner facts.
   */
  public List<CustomerWarehouseResponse> list() {
    Map<UUID, CustomerDeliveryProperties.Validated> delivery = validatedDepots();
    try {
      return dependencies.listWarehouseIdentities().stream()
          .filter(LogisticsDependencyGateway.WarehouseIdentity::active)
          .filter(warehouse -> visible(warehouse, delivery))
          .filter(CustomerWarehouseService::hasValidCoordinates)
          .map(
              warehouse ->
                  new CustomerWarehouseResponse(
                      warehouse.id(),
                      warehouse.name(),
                      warehouse.city(),
                      warehouse.address(),
                      warehouse.timeZone(),
                      warehouse.latitude(),
                      warehouse.longitude()))
          .toList();
    } catch (LogisticsDependencyException exception) {
      throw unavailable();
    }
  }

  /** Verifies that a selected warehouse is active, customer-visible and physically routable. */
  public LogisticsDependencyGateway.WarehouseIdentity required(UUID warehouseId) {
    Map<UUID, CustomerDeliveryProperties.Validated> delivery = validatedDepots();
    try {
      LogisticsDependencyGateway.WarehouseIdentity warehouse =
          dependencies.readWarehouseIdentity(warehouseId);
      if (!warehouse.active()
          || !visible(warehouse, delivery)
          || !hasValidCoordinates(warehouse)) {
        throw notFound();
      }
      return warehouse;
    } catch (LogisticsDependencyException exception) {
      throw unavailable();
    }
  }

  /** Returns route capacity with the selected Warehouse's owner-held route origin. */
  public CustomerDeliveryProperties.Validated validated(UUID warehouseId) {
    try {
      LogisticsDependencyGateway.WarehouseIdentity warehouse = required(warehouseId);
      return properties.validated(
          warehouse.id(), warehouse.latitude(), warehouse.longitude());
    } catch (IllegalStateException exception) {
      throw unavailable();
    }
  }

  private static boolean visible(
      LogisticsDependencyGateway.WarehouseIdentity warehouse,
      Map<UUID, CustomerDeliveryProperties.Validated> configuredDepots) {
    return warehouse.representative() || configuredDepots.containsKey(warehouse.id());
  }

  private static boolean hasValidCoordinates(
      LogisticsDependencyGateway.WarehouseIdentity warehouse) {
    return warehouse.latitude() != null
        && warehouse.latitude().compareTo(java.math.BigDecimal.valueOf(-90)) >= 0
        && warehouse.latitude().compareTo(java.math.BigDecimal.valueOf(90)) <= 0
        && warehouse.longitude() != null
        && warehouse.longitude().compareTo(java.math.BigDecimal.valueOf(-180)) >= 0
        && warehouse.longitude().compareTo(java.math.BigDecimal.valueOf(180)) <= 0
        && (warehouse.latitude().signum() != 0 || warehouse.longitude().signum() != 0);
  }

  private Map<UUID, CustomerDeliveryProperties.Validated> validatedDepots() {
    try {
      return properties.validatedDepots();
    } catch (IllegalStateException exception) {
      throw unavailable();
    }
  }

  private static OrderProblemException notFound() {
    return new OrderProblemException(
        HttpStatus.NOT_FOUND,
        "CUSTOMER_WAREHOUSE_NOT_FOUND",
        "Склад недоступен для клиентского бронирования");
  }

  private static OrderProblemException unavailable() {
    return new OrderProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CUSTOMER_DELIVERY_NOT_CONFIGURED",
        "Клиентская доставка для склада временно недоступна");
  }
}
