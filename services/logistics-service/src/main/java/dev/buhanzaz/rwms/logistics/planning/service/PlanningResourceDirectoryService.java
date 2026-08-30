package dev.buhanzaz.rwms.logistics.planning.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseDriverIdentity;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseIdentity;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseSupportLink;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverResource;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverAvailabilityKind;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverEmploymentType;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningWarehouseResource;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningWarehouseSupportLinkResource;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Composes the planner's least-privilege warehouse and driver directories from their domain owners.
 *
 * <p>The service validates owner identities before exposing them to the standalone planner. It
 * stores no duplicate warehouse or workforce projection and never falls back to fabricated
 * identities when an owner is unavailable.
 */
@Service
@RequiredArgsConstructor
public class PlanningResourceDirectoryService {
  private final LogisticsDependencyGateway dependencies;

  /** Returns active warehouses in warehouse-service canonical order. */
  public List<PlanningWarehouseResource> warehouses() {
    List<WarehouseIdentity> identities = dependencies.listWarehouseIdentities();
    requireUniqueWarehouses(identities);
    return identities.stream().map(PlanningResourceDirectoryService::warehouseResource).toList();
  }

  /** Returns every warehouse-service-filtered support edge for one exact planning instant. */
  public List<PlanningWarehouseSupportLinkResource> supportLinks(
      UUID servedWarehouseId, OffsetDateTime at) {
    Objects.requireNonNull(servedWarehouseId, "servedWarehouseId");
    Objects.requireNonNull(at, "at");
    WarehouseIdentity served = dependencies.readWarehouseIdentity(servedWarehouseId);
    requireActiveWarehouse(served, servedWarehouseId);
    List<WarehouseSupportLink> links =
        dependencies.listWarehouseSupportLinks(servedWarehouseId, at);
    if (links == null) {
      throw malformed("Warehouse-service returned no support-link directory");
    }
    Set<UUID> linkIds = new HashSet<>();
    Set<UUID> supportWarehouseIds = new HashSet<>();
    return links.stream()
        .map(
            link -> {
              if (link == null
                  || link.id() == null
                  || link.version() < 0
                  || link.priority() < 1
                  || !linkIds.add(link.id())
                  || link.supportWarehouse() == null
                  || !supportWarehouseIds.add(link.supportWarehouse().id())
                  || link.servedWarehouse() == null
                  || !servedWarehouseId.equals(link.servedWarehouse().id())) {
                throw malformed("Warehouse-service returned an invalid support-link directory");
              }
              requireActiveWarehouse(link.supportWarehouse(), link.supportWarehouse().id());
              requireActiveWarehouse(link.servedWarehouse(), servedWarehouseId);
              return new PlanningWarehouseSupportLinkResource(
                  link.id(),
                  link.version(),
                  warehouseResource(link.supportWarehouse()),
                  warehouseResource(link.servedWarehouse()),
                  link.priority(),
                  link.allowDrivers(),
                  link.allowVehicles(),
                  link.allowInventory(),
                  link.allowDirectFulfillment(),
                  link.allowInterwarehouseTransfer(),
                  link.allowContractorFallback(),
                  link.allowedWeekdays(),
                  link.allowedDates(),
                  link.excludedDates(),
                  link.serviceStart(),
                  link.serviceEnd());
            })
        .toList();
  }

  /** Returns every active direct support edge adjacent to the selected planning warehouse. */
  public List<PlanningWarehouseSupportLinkResource> supportNetwork(UUID warehouseId) {
    Objects.requireNonNull(warehouseId, "warehouseId");
    WarehouseIdentity selected = dependencies.readWarehouseIdentity(warehouseId);
    requireActiveWarehouse(selected, warehouseId);
    List<WarehouseSupportLink> links = dependencies.listWarehouseSupportNetwork(warehouseId);
    if (links == null) {
      throw malformed("Warehouse-service returned no support-network directory");
    }
    Set<UUID> linkIds = new HashSet<>();
    return links.stream()
        .map(
            link -> {
              if (link == null
                  || link.id() == null
                  || link.version() < 0
                  || link.priority() < 1
                  || !linkIds.add(link.id())
                  || link.supportWarehouse() == null
                  || link.servedWarehouse() == null
                  || (!warehouseId.equals(link.supportWarehouse().id())
                      && !warehouseId.equals(link.servedWarehouse().id()))) {
                throw malformed("Warehouse-service returned an invalid support-network directory");
              }
              requireActiveWarehouse(link.supportWarehouse(), link.supportWarehouse().id());
              requireActiveWarehouse(link.servedWarehouse(), link.servedWarehouse().id());
              if (!link.servedWarehouse().representative()) {
                throw malformed("Warehouse-service returned a non-representative served endpoint");
              }
              return supportLinkResource(link);
            })
        .toList();
  }

  private static PlanningWarehouseSupportLinkResource supportLinkResource(
      WarehouseSupportLink link) {
    return new PlanningWarehouseSupportLinkResource(
        link.id(),
        link.version(),
        warehouseResource(link.supportWarehouse()),
        warehouseResource(link.servedWarehouse()),
        link.priority(),
        link.allowDrivers(),
        link.allowVehicles(),
        link.allowInventory(),
        link.allowDirectFulfillment(),
        link.allowInterwarehouseTransfer(),
        link.allowContractorFallback(),
        link.allowedWeekdays(),
        link.allowedDates(),
        link.excludedDates(),
        link.serviceStart(),
        link.serviceEnd());
  }

  /** Returns task-board-qualified drivers after validating the exact active warehouse identity. */
  public List<PlanningDriverResource> drivers(UUID warehouseId) {
    return drivers(warehouseId, null, false);
  }

  /**
   * Returns staff, assigned, incoming, and contractor availability for one exact planning instant.
   */
  public List<PlanningDriverResource> drivers(
      UUID warehouseId, OffsetDateTime at, boolean includeIncoming) {
    Objects.requireNonNull(warehouseId, "warehouseId");
    WarehouseIdentity warehouse = dependencies.readWarehouseIdentity(warehouseId);
    requireActiveWarehouse(warehouse, warehouseId);
    List<WarehouseDriverIdentity> drivers =
        at == null && !includeIncoming
            ? dependencies.listWarehouseDrivers(warehouseId)
            : dependencies.listWarehouseDrivers(warehouseId, at, includeIncoming);
    if (drivers == null) {
      throw malformed("Task-board returned no warehouse driver directory");
    }
    Set<UUID> identities = new HashSet<>();
    return drivers.stream()
        .map(
            driver -> {
              if (driver == null
                  || driver.workerId() == null
                  || !identities.add(driver.workerId())
                  || driver.displayName() == null
                  || driver.displayName().isBlank()
                  || driver.displayName().length() > 256
                  || driver.operationalWarehouseId() == null
                  || !warehouseId.equals(driver.operationalWarehouseId())
                  || driver.employmentType() == null
                  || driver.availabilityKind() == null
                  || (!includeIncoming && "INCOMING".equals(driver.availabilityKind()))
                  || invalidAvailabilityRange(driver.availableFrom(), driver.availableUntil())
                  || ("CONTRACTOR".equals(driver.employmentType())
                      && (driver.phone() == null
                          || driver.phone().isBlank()))) {
                throw malformed("Task-board returned an invalid warehouse driver directory");
              }
              try {
                return new PlanningDriverResource(
                    driver.workerId(),
                    driver.displayName().trim(),
                    PlanningDriverEmploymentType.valueOf(driver.employmentType()),
                    normalize(driver.phone()),
                    driver.operationalWarehouseId(),
                    driver.availableFrom(),
                    driver.availableUntil(),
                    PlanningDriverAvailabilityKind.valueOf(driver.availabilityKind()));
              } catch (IllegalArgumentException exception) {
                throw malformed("Task-board returned an unknown driver availability kind");
              }
            })
        .toList();
  }

  /** Rejects an end without a start while allowing a date-free profile or open-ended assignment. */
  private static boolean invalidAvailabilityRange(
      OffsetDateTime availableFrom, OffsetDateTime availableUntil) {
    return availableUntil != null
        && (availableFrom == null || !availableFrom.isBefore(availableUntil));
  }

  private static void requireUniqueWarehouses(List<WarehouseIdentity> identities) {
    if (identities == null) throw malformed("Warehouse-service returned no warehouse directory");
    Set<UUID> warehouseIds = new HashSet<>();
    for (WarehouseIdentity identity : identities) {
      requireActiveWarehouse(identity, identity == null ? null : identity.id());
      if (!warehouseIds.add(identity.id())) {
        throw malformed("Warehouse-service returned duplicate warehouse identities");
      }
    }
  }

  private static PlanningWarehouseResource warehouseResource(WarehouseIdentity warehouse) {
    return new PlanningWarehouseResource(
        warehouse.id(),
        warehouse.version(),
        warehouse.name().trim(),
        warehouse.city().trim(),
        warehouse.address() == null ? null : warehouse.address().trim(),
        warehouse.latitude(),
        warehouse.longitude(),
        warehouse.timeZone(),
        warehouse.representative(),
        warehouse.latitude() != null);
  }

  private static void requireActiveWarehouse(WarehouseIdentity warehouse, UUID expectedId) {
    if (warehouse == null
        || warehouse.id() == null
        || !warehouse.id().equals(expectedId)
        || !warehouse.active()
        || warehouse.version() < 0
        || !validText(warehouse.name(), 255)
        || !validText(warehouse.city(), 255)
        || (warehouse.address() != null && !validText(warehouse.address(), 1_000))
        || (warehouse.latitude() == null) != (warehouse.longitude() == null)
        || !validCoordinate(warehouse.latitude(), new BigDecimal("-90"), new BigDecimal("90"))
        || !validCoordinate(
            warehouse.longitude(), new BigDecimal("-180"), new BigDecimal("180"))
        || !validText(warehouse.timeZone(), 64)
        || !validTimeZone(warehouse.timeZone())) {
      throw malformed("Warehouse-service returned an invalid active warehouse identity");
    }
  }

  private static boolean validText(String value, int maximumLength) {
    return value != null && !value.isBlank() && value.length() <= maximumLength;
  }

  private static String normalize(String value) {
    if (value == null) return null;
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private static boolean validTimeZone(String value) {
    try {
      ZoneId.of(value);
      return true;
    } catch (DateTimeException exception) {
      return false;
    }
  }

  private static boolean validCoordinate(
      BigDecimal value, BigDecimal minimum, BigDecimal maximum) {
    return value == null || value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
  }

  private static LogisticsDependencyException malformed(String message) {
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.CONFIGURATION, message);
  }
}
