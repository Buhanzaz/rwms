package dev.buhanzaz.rwms.logistics.pricing.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.EquipmentPricingCatalog;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.pricing.api.EquipmentRentalPricingResponse;
import dev.buhanzaz.rwms.logistics.pricing.api.UpdateRentalPriceRequest;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns monthly furniture unit tariffs. Live catalog reads precede the local tariff transaction;
 * no remote call runs while this service holds the shared cabin/furniture pricing lock.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class EquipmentRentalPricingService {
  private static final Set<String> ADMIN_ROLES = Set.of("SYSTEM_ADMIN", "WMS_ADMIN");
  private final LogisticsDependencyGateway dependencies;
  private final RentalPricingStore store;

  public EquipmentRentalPricingResponse settings() {
    EquipmentPricingCatalog catalog = catalog();
    return response(catalog, store.read());
  }

  public EquipmentRentalPricingResponse update(
      OrderActor actor, UUID equipmentId, UpdateRentalPriceRequest request) {
    if (!ADMIN_ROLES.contains(actor.role()) || !actor.writeScope() || !actor.rentalAccess()) {
      throw new AccessDeniedException("Global rental settings administration is required");
    }
    long amount;
    try {
      amount = Long.parseLong(request.monthlyPriceRubles());
      if (amount < 0) throw new NumberFormatException();
    } catch (NumberFormatException error) {
      throw new OrderProblemException(
          HttpStatus.BAD_REQUEST,
          "RENTAL_PRICE_INVALID",
          "Цена должна быть целым неотрицательным числом рублей до 9223372036854775807");
    }
    EquipmentPricingCatalog catalog = catalog();
    if (catalog.items().stream().noneMatch(item -> item.id().equals(equipmentId))) {
      throw new OrderProblemException(
          HttpStatus.NOT_FOUND,
          "RENTAL_PRICE_EQUIPMENT_NOT_FOUND",
          "Позиция мебели больше не существует в справочнике");
    }
    return response(
        catalog,
        store.updateEquipment(request.expectedVersion(), equipmentId, amount, actor.subjectId()));
  }

  private EquipmentPricingCatalog catalog() {
    try {
      EquipmentPricingCatalog catalog = dependencies.readEquipmentPricingCatalog();
      var identities = new HashSet<UUID>();
      if (catalog == null
          || catalog.items() == null
          || catalog.items().stream()
              .anyMatch(
                  value ->
                      value == null
                          || value.id() == null
                          || !identities.add(value.id())
                          || value.name() == null
                          || value.name().isBlank()
                          || value.name().length() > 255)) {
        throw unavailable();
      }
      return catalog;
    } catch (LogisticsDependencyException error) {
      throw unavailable();
    }
  }

  private static EquipmentRentalPricingResponse response(
      EquipmentPricingCatalog catalog, RentalPricingSnapshot snapshot) {
    return new EquipmentRentalPricingResponse(
        snapshot.version(),
        catalog.items().stream()
            .map(
                item ->
                    new EquipmentRentalPricingResponse.Item(
                        item.id(),
                        item.name(),
                        item.active(),
                        snapshot.equipmentRates().getOrDefault(item.id(), 0L)))
            .toList(),
        snapshot.updatedAt());
  }

  private static OrderProblemException unavailable() {
    return new OrderProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "RENTAL_PRICING_UNAVAILABLE",
        "Цены аренды недоступны: не удалось получить актуальный справочник мебели");
  }
}
