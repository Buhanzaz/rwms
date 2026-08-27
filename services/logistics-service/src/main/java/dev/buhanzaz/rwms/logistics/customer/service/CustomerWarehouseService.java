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

/** Exposes only active warehouses with an explicit CustomerApp delivery-depot configuration. */
@Service
@RequiredArgsConstructor
public class CustomerWarehouseService {
  private final CustomerDeliveryProperties properties;
  private final LogisticsDependencyGateway dependencies;

  /** Lists delivery-enabled active warehouses without granting general warehouse authority. */
  public List<CustomerWarehouseResponse> list() {
    Map<UUID, CustomerDeliveryProperties.Validated> delivery = validatedDepots();
    try {
      return dependencies.listWarehouseIdentities().stream()
          .filter(LogisticsDependencyGateway.WarehouseIdentity::active)
          .filter(warehouse -> delivery.containsKey(warehouse.id()))
          .map(
              warehouse -> {
                CustomerDeliveryProperties.Validated depot = delivery.get(warehouse.id());
                return new CustomerWarehouseResponse(
                      warehouse.id(),
                      warehouse.name(),
                      warehouse.city(),
                      null,
                      warehouse.timeZone(),
                      depot.depotLatitude(),
                      depot.depotLongitude());
              })
          .toList();
    } catch (LogisticsDependencyException exception) {
      throw unavailable();
    }
  }

  /** Verifies that a selected warehouse is active and has the configured depot. */
  public LogisticsDependencyGateway.WarehouseIdentity required(UUID warehouseId) {
    if (!validatedDepots().containsKey(warehouseId)) throw notFound();
    try {
      LogisticsDependencyGateway.WarehouseIdentity warehouse =
          dependencies.readWarehouseIdentity(warehouseId);
      if (!warehouse.active()) throw notFound();
      return warehouse;
    } catch (LogisticsDependencyException exception) {
      throw unavailable();
    }
  }

  /** Returns the selected warehouse's validated route-capacity configuration. */
  public CustomerDeliveryProperties.Validated validated(UUID warehouseId) {
    try {
      return properties.validated(warehouseId);
    } catch (IllegalStateException exception) {
      throw unavailable();
    }
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
