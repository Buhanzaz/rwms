package dev.buhanzaz.rwms.logistics.pricing.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.CabinPricingCatalog;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.pricing.api.CabinRentalPricesResponse;
import dev.buhanzaz.rwms.logistics.pricing.api.RentalPricingSettingsResponse;
import dev.buhanzaz.rwms.logistics.pricing.api.UpdateRentalPriceRequest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Owns rental tariff resolution. The current asset taxonomy is read on every table request, so
 * additions, removals and renames appear without maintaining another catalog in logistics. Remote
 * reads always finish before entering the local tariff transaction.
 */
@Service
@RequiredArgsConstructor
public class RentalPricingService {
  private static final Set<String> ADMIN_ROLES = Set.of("SYSTEM_ADMIN", "WMS_ADMIN");
  private final LogisticsDependencyGateway dependencies;
  private final RentalPricingStore store;

  public RentalPricingSettingsResponse settings() {
    CabinPricingCatalog catalog = catalog();
    return settingsResponse(catalog, store.read());
  }

  public RentalPricingSettingsResponse update(
      OrderActor actor, UUID rentalTypeId, UUID categoryId, UpdateRentalPriceRequest request) {
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
    CabinPricingCatalog catalog = catalog();
    if (catalog.types().stream().noneMatch(type -> type.id().equals(rentalTypeId))
        || catalog.categories().stream().noneMatch(category -> category.id().equals(categoryId))) {
      throw new OrderProblemException(
          HttpStatus.NOT_FOUND,
          "RENTAL_PRICE_CLASSIFICATION_NOT_FOUND",
          "Тип или категория больше не существуют в справочнике бытовок");
    }
    return settingsResponse(
        catalog,
        store.update(
            request.expectedVersion(), rentalTypeId, categoryId, amount, actor.subjectId()));
  }

  /**
   * Caller supplies an already-authorized warehouse and cabin set, including customer ownership.
   */
  public CabinRentalPricesResponse prices(UUID warehouseId, List<UUID> rentalItemIds) {
    if (warehouseId == null
        || rentalItemIds == null
        || rentalItemIds.isEmpty()
        || rentalItemIds.size() > 100
        || rentalItemIds.stream().anyMatch(Objects::isNull)
        || new HashSet<>(rentalItemIds).size() != rentalItemIds.size()) {
      throw new OrderProblemException(
          HttpStatus.BAD_REQUEST,
          "RENTAL_PRICE_CABINS_INVALID",
          "Нужны склад и от 1 до 100 различных бытовок");
    }
    LogisticsDependencyGateway.CabinPricingReferences references;
    try {
      references = dependencies.readCabinPricingReferences(warehouseId, rentalItemIds);
    } catch (LogisticsDependencyException error) {
      if ("ASSET_NOT_FOUND".equals(error.dependencyCode())) {
        throw new OrderProblemException(
            HttpStatus.NOT_FOUND,
            "RENTAL_PRICE_CABIN_NOT_FOUND",
            "Одна или несколько бытовок не найдены на выбранном складе");
      }
      throw unavailable();
    }
    if (references == null
        || !warehouseId.equals(references.warehouseId())
        || references.cabins() == null
        || references.cabins().size() != rentalItemIds.size()
        || references.cabins().stream()
            .anyMatch(
                value ->
                    value == null
                        || value.rentalItemId() == null
                        || value.rentalItemVersion() < 0
                        || value.rentalTypeId() == null
                        || value.categoryId() == null)
        || !new HashSet<>(
                references.cabins().stream()
                    .map(LogisticsDependencyGateway.CabinPricingReference::rentalItemId)
                    .toList())
            .equals(new HashSet<>(rentalItemIds))) {
      throw unavailable();
    }
    RentalPricingSnapshot snapshot = store.read();
    Map<Pair, Long> rates = rates(snapshot);
    var byId = new HashMap<UUID, LogisticsDependencyGateway.CabinPricingReference>();
    references.cabins().forEach(reference -> byId.put(reference.rentalItemId(), reference));
    return new CabinRentalPricesResponse(
        warehouseId,
        snapshot.version(),
        rentalItemIds.stream()
            .map(
                id -> {
                  var reference = byId.get(id);
                  return new CabinRentalPricesResponse.Price(
                      id,
                      reference.rentalItemVersion(),
                      reference.rentalTypeId(),
                      reference.categoryId(),
                      rates.getOrDefault(
                          new Pair(reference.rentalTypeId(), reference.categoryId()), 0L));
                })
            .toList());
  }

  private CabinPricingCatalog catalog() {
    try {
      CabinPricingCatalog catalog = dependencies.readCabinPricingCatalog();
      if (catalog == null || catalog.types() == null || catalog.categories() == null)
        throw unavailable();
      var identities = new HashSet<UUID>();
      for (var dimension : List.of(catalog.types(), catalog.categories())) {
        for (var value : dimension) {
          if (value == null
              || value.id() == null
              || value.name() == null
              || value.name().isBlank()
              || !identities.add(value.id())) throw unavailable();
        }
      }
      return catalog;
    } catch (LogisticsDependencyException error) {
      throw unavailable();
    }
  }

  private static RentalPricingSettingsResponse settingsResponse(
      CabinPricingCatalog catalog, RentalPricingSnapshot snapshot) {
    Map<Pair, Long> rates = rates(snapshot);
    return new RentalPricingSettingsResponse(
        snapshot.version(),
        catalog.types().stream()
            .map(
                type ->
                    new RentalPricingSettingsResponse.Type(
                        type.id(),
                        type.name(),
                        type.active(),
                        catalog.categories().stream()
                            .map(
                                category ->
                                    new RentalPricingSettingsResponse.Category(
                                        category.id(),
                                        category.name(),
                                        category.active(),
                                        rates.getOrDefault(new Pair(type.id(), category.id()), 0L)))
                            .toList()))
            .toList(),
        snapshot.updatedAt());
  }

  private static Map<Pair, Long> rates(RentalPricingSnapshot snapshot) {
    Map<Pair, Long> rates = new HashMap<>();
    snapshot
        .rates()
        .forEach(
            rate ->
                rates.put(
                    new Pair(rate.rentalTypeId(), rate.categoryId()), rate.monthlyPriceRubles()));
    return rates;
  }

  private static OrderProblemException unavailable() {
    return new OrderProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "RENTAL_PRICING_UNAVAILABLE",
        "Цены аренды недоступны: не удалось получить актуальные данные бытовок");
  }

  /** Stable composite tariff identity; names are never keys. */
  private record Pair(UUID rentalTypeId, UUID categoryId) {}
}
