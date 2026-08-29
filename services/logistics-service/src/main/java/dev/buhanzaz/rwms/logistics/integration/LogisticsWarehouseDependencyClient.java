package dev.buhanzaz.rwms.logistics.integration;

import static dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.*;
import static dev.buhanzaz.rwms.logistics.integration.LogisticsOAuthHttpTransport.FailurePolicy.DEFAULT;
import static dev.buhanzaz.rwms.logistics.integration.LogisticsOAuthHttpTransport.malformed;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Private warehouse-service client for logistics-owned reads and operation markers.
 *
 * <p>The distinct OAuth registrations intentionally remain at this boundary because lifecycle,
 * timezone, and operation-mark capabilities have different approved scopes.
 */
final class LogisticsWarehouseDependencyClient {
  private static final String WAREHOUSE_CLIENT = "logistics-warehouse";
  private static final String WAREHOUSE_TIME_ZONE_CLIENT = "logistics-warehouse-timezone";
  private static final String WAREHOUSE_OPERATION_CLIENT = "logistics-warehouse-operation";
  private static final String WAREHOUSE_LIFECYCLE_READ_CLIENT =
      "logistics-warehouse-lifecycle-read";
  private static final String WAREHOUSE_LIFECYCLE_CONFIRM_CLIENT =
      "logistics-warehouse-lifecycle-confirm";
  private static final String WAREHOUSE_SCOPE = "warehouse.logistics";
  private static final String WAREHOUSE_TIME_ZONE_SCOPE = "warehouse.timezone.read";
  private static final String WAREHOUSE_OPERATION_SCOPE = "warehouse.operation.mark";
  private static final String WAREHOUSE_LIFECYCLE_READ_SCOPE = "warehouse.lifecycle.read";
  private static final String WAREHOUSE_LIFECYCLE_CONFIRM_SCOPE =
      "warehouse.lifecycle.confirm";

  private final LogisticsOAuthHttpTransport transport;
  private final String warehouseBase;
  private final String warehouseInternalBase;

  LogisticsWarehouseDependencyClient(
      LogisticsOAuthHttpTransport transport, String warehouseBase, String warehouseInternalBase) {
    this.transport = transport;
    this.warehouseBase = warehouseBase;
    this.warehouseInternalBase = warehouseInternalBase;
  }

  WarehouseIdentity readWarehouseIdentity(UUID warehouseId) {
    WarehouseIdentityResponse response =
        transport.get(
            warehouseBase + "/" + warehouseId + "/identity",
            WarehouseIdentityResponse.class,
            WAREHOUSE_CLIENT,
            WAREHOUSE_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    if (response == null) throw malformed("Warehouse-service returned an empty identity");
    return identity(response);
  }

  WarehouseOperationAdmission warehouseAdmission(
      UUID warehouseId, WarehouseOperationDirection direction) {
    if (warehouseId == null || direction == null) {
      throw new IllegalArgumentException("Warehouse admission identity is required");
    }
    String uri =
        UriComponentsBuilder.fromUriString(
                warehouseInternalBase + "/warehouses/" + warehouseId + "/admission")
            .queryParam("direction", direction.name())
            .build()
            .encode()
            .toUriString();
    WarehouseOperationAdmission response =
        transport.get(
            uri,
            WarehouseOperationAdmission.class,
            WAREHOUSE_LIFECYCLE_READ_CLIENT,
            WAREHOUSE_LIFECYCLE_READ_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    boolean expectedAdmission =
        response.lifecycleState() == WarehouseLifecycleState.ACTIVE
            || (response.lifecycleState() == WarehouseLifecycleState.DRAINING
                && direction == WarehouseOperationDirection.OUTGOING);
    if (!warehouseId.equals(response.warehouseId())
        || response.direction() != direction
        || response.admitted() != expectedAdmission) {
      throw malformed("Warehouse-service returned mismatched admission truth");
    }
    return response;
  }

  WarehouseLifecycleReadinessWorkPage warehouseLifecycleReadinessWork(UUID after, int limit) {
    if (limit < 1 || limit > 500) {
      throw new IllegalArgumentException("Warehouse readiness page size must be from 1 to 500");
    }
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromUriString(warehouseInternalBase + "/lifecycle/readiness-work")
            .queryParam("limit", limit);
    if (after != null) uri.queryParam("after", after);
    WarehouseLifecycleReadinessWorkPage response =
        transport.get(
            uri.build().encode().toUriString(),
            WarehouseLifecycleReadinessWorkPage.class,
            WAREHOUSE_LIFECYCLE_READ_CLIENT,
            WAREHOUSE_LIFECYCLE_READ_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    if (response.items().stream()
            .map(WarehouseLifecycleReadinessWork::warehouseId)
            .distinct()
            .count()
        != response.items().size()) {
      throw malformed("Warehouse-service returned duplicate readiness work");
    }
    if (response.nextAfter() != null
        && (response.items().isEmpty()
            || !response.nextAfter().equals(response.items().getLast().warehouseId()))) {
      throw malformed("Warehouse-service returned an invalid readiness cursor");
    }
    return response;
  }

  WarehouseLifecycleReadinessConfirmation confirmWarehouseLifecycleReadiness(
      UUID warehouseId, long expectedVersion) {
    if (warehouseId == null || expectedVersion < 0) {
      throw new IllegalArgumentException("Warehouse readiness fence is invalid");
    }
    WarehouseLifecycleReadinessConfirmation response =
        transport.postWithoutIdempotency(
            warehouseInternalBase + "/warehouses/" + warehouseId + "/lifecycle-readiness",
            new WarehouseLifecycleReadinessRequest(expectedVersion),
            WarehouseLifecycleReadinessConfirmation.class,
            WAREHOUSE_LIFECYCLE_CONFIRM_CLIENT,
            WAREHOUSE_LIFECYCLE_CONFIRM_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    if (!warehouseId.equals(response.warehouseId())
        || response.warehouseVersion() < expectedVersion) {
      throw malformed("Warehouse-service returned mismatched logistics readiness confirmation");
    }
    return response;
  }

  WarehouseTimeZone warehouseTimeZoneAt(UUID warehouseId, OffsetDateTime at) {
    if (warehouseId == null || at == null) {
      throw new IllegalArgumentException("Warehouse timezone lookup identity is required");
    }
    String uri =
        UriComponentsBuilder.fromUriString(
                warehouseInternalBase + "/warehouses/" + warehouseId + "/time-zone")
            .queryParam("at", at)
            .build()
            .encode()
            .toUriString();
    WarehouseTimeZone response =
        transport.get(
            uri,
            WarehouseTimeZone.class,
            WAREHOUSE_TIME_ZONE_CLIENT,
            WAREHOUSE_TIME_ZONE_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    if (!warehouseId.equals(response.warehouseId()) || response.effectiveFrom().isAfter(at)) {
      throw malformed("Warehouse-service returned mismatched effective timezone truth");
    }
    return response;
  }

  void markWarehouseOperation(UUID warehouseId, UUID operationId, OffsetDateTime occurredAt) {
    if (warehouseId == null || operationId == null || occurredAt == null) {
      throw new IllegalArgumentException("Warehouse operation mark identity is required");
    }
    transport.postWithoutIdempotencyBodiless(
        warehouseInternalBase + "/warehouses/" + warehouseId + "/operation-marks",
        new WarehouseOperationMarkRequest(operationId, occurredAt),
        WAREHOUSE_OPERATION_CLIENT,
        WAREHOUSE_OPERATION_SCOPE,
        DEFAULT);
  }

  List<WarehouseIdentity> listWarehouseIdentities() {
    List<WarehouseIdentityResponse> response =
        transport.getList(
            warehouseBase,
            new ParameterizedTypeReference<>() {},
            WAREHOUSE_CLIENT,
            WAREHOUSE_SCOPE,
            "Warehouse-service returned an empty identity list",
            DEFAULT);
    return response.stream()
        .map(value -> value == null ? null : identity(value))
        .toList();
  }

  List<WarehouseSupportLink> listWarehouseSupportLinks(
      UUID servedWarehouseId, OffsetDateTime at) {
    if (servedWarehouseId == null || at == null) {
      throw new IllegalArgumentException("Warehouse support-link lookup requires an instant");
    }
    String uri =
        UriComponentsBuilder.fromUriString(
                warehouseBase + "/" + servedWarehouseId + "/support-links")
            .queryParam("at", at)
            .build()
            .encode()
            .toUriString();
    List<WarehouseSupportLinkResponse> response =
        transport.getList(
            uri,
            new ParameterizedTypeReference<>() {},
            WAREHOUSE_CLIENT,
            WAREHOUSE_SCOPE,
            "Warehouse-service returned an empty support-link list",
            DEFAULT);
    Set<UUID> linkIds = new HashSet<>();
    Set<UUID> supportWarehouseIds = new HashSet<>();
    return response.stream()
        .map(
            value -> {
              if (invalidSupportLink(value, servedWarehouseId)
                  || !linkIds.add(value.id())
                  || !supportWarehouseIds.add(value.supportWarehouse().id())) {
                throw malformed("Warehouse-service returned invalid support-link truth");
              }
              return new WarehouseSupportLink(
                  value.id(),
                  value.version(),
                  identity(value.supportWarehouse()),
                  identity(value.servedWarehouse()),
                  value.priority(),
                  value.allowDrivers(),
                  value.allowVehicles(),
                  value.allowInventory(),
                  value.allowDirectFulfillment(),
                  value.allowInterwarehouseTransfer(),
                  value.allowContractorFallback(),
                  Set.copyOf(value.allowedWeekdays()),
                  Set.copyOf(value.allowedDates()),
                  Set.copyOf(value.excludedDates()),
                  value.serviceStart(),
                  value.serviceEnd());
            })
        .toList();
  }

  private static WarehouseIdentity identity(WarehouseIdentityResponse response) {
    return new WarehouseIdentity(
        response.id(),
        response.version(),
        response.active(),
        response.name(),
        response.city(),
        response.address(),
        response.latitude(),
        response.longitude(),
        response.timeZone(),
        response.representative());
  }

  private static boolean invalidSupportLink(
      WarehouseSupportLinkResponse value, UUID servedWarehouseId) {
    return value == null
        || value.id() == null
        || value.version() < 0
        || value.priority() < 1
        || value.supportWarehouse() == null
        || value.servedWarehouse() == null
        || value.supportWarehouse().id() == null
        || value.servedWarehouse().id() == null
        || value.supportWarehouse().id().equals(value.servedWarehouse().id())
        || !servedWarehouseId.equals(value.servedWarehouse().id())
        || !value.supportWarehouse().active()
        || !value.servedWarehouse().active()
        || !value.servedWarehouse().representative()
        || value.allowedWeekdays() == null
        || value.allowedDates() == null
        || value.excludedDates() == null
        || ((value.serviceStart() == null) != (value.serviceEnd() == null))
        || (value.serviceStart() != null && !value.serviceStart().isBefore(value.serviceEnd()));
  }

  /** Versioned warehouse identity and timezone snapshot returned by warehouse-service. */
  private record WarehouseIdentityResponse(
      UUID id,
      long version,
      boolean active,
      String name,
      String city,
      String address,
      BigDecimal latitude,
      BigDecimal longitude,
      String timeZone,
      boolean representative) {}

  /** Exact calendar-filtered support edge returned by warehouse-service. */
  private record WarehouseSupportLinkResponse(
      UUID id,
      long version,
      WarehouseIdentityResponse supportWarehouse,
      WarehouseIdentityResponse servedWarehouse,
      int priority,
      boolean allowDrivers,
      boolean allowVehicles,
      boolean allowInventory,
      boolean allowDirectFulfillment,
      boolean allowInterwarehouseTransfer,
      boolean allowContractorFallback,
      Set<DayOfWeek> allowedWeekdays,
      Set<LocalDate> allowedDates,
      Set<LocalDate> excludedDates,
      LocalTime serviceStart,
      LocalTime serviceEnd) {}

  /** Optimistically fenced confirmation of a warehouse lifecycle-readiness work item. */
  private record WarehouseLifecycleReadinessRequest(long expectedVersion) {}

  /** Idempotent operation marker carrying the logistics operation identity and occurrence time. */
  private record WarehouseOperationMarkRequest(UUID operationId, OffsetDateTime occurredAt) {}
}
