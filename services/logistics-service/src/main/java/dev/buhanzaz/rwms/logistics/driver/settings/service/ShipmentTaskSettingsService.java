package dev.buhanzaz.rwms.logistics.driver.settings.service;

import dev.buhanzaz.rwms.logistics.driver.settings.api.ShipmentTaskSettingsApiModels.ShipmentTaskSettingsResponse;
import dev.buhanzaz.rwms.logistics.driver.settings.api.ShipmentTaskSettingsApiModels.UpdateShipmentTaskSettingsRequest;
import dev.buhanzaz.rwms.logistics.driver.settings.domain.ShipmentTaskSettings;
import dev.buhanzaz.rwms.logistics.driver.settings.mapper.ShipmentTaskSettingsResponseMapper;
import dev.buhanzaz.rwms.logistics.driver.settings.repository.ShipmentTaskSettingsRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns warehouse-local shipment grouping policy and its server-side enforcement for new shipment
 * documents.
 */
@Service
@RequiredArgsConstructor
public class ShipmentTaskSettingsService {
  private final ShipmentTaskSettingsRepository settings;
  private final ShipmentTaskSettingsResponseMapper mapper;

  /** Returns the effective cap, creating the compatibility default only once for this warehouse. */
  @Transactional
  public ShipmentTaskSettingsResponse get(UUID warehouseId, UUID actorSubjectId) {
    return mapper.toResponse(loadOrCreate(warehouseId, actorSubjectId));
  }

  /** Updates a warehouse cap only when the caller's settings version remains current. */
  @Transactional
  public ShipmentTaskSettingsResponse update(
      UUID warehouseId, UUID actorSubjectId, UpdateShipmentTaskSettingsRequest request) {
    if (warehouseId == null || actorSubjectId == null || request == null) {
      throw new IllegalArgumentException("Shipment task settings identity and request are required");
    }
    ShipmentTaskSettings value = lockOrCreate(warehouseId, actorSubjectId);
    if (value.getVersion() != request.expectedVersion()) {
      throw new LogisticsConflictException("Настройки отгрузок уже изменены другим пользователем");
    }
    value.update(request.maxCabinsPerShipmentTask(), actorSubjectId, now());
    return mapper.toResponse(settings.saveAndFlush(value));
  }

  /**
   * Rejects a selected cabin count above the warehouse's current cap before a shipment document
   * or driver task is created.
   */
  @Transactional
  public void requireWithinLimit(UUID warehouseId, int selectedCabinCount, UUID actorSubjectId) {
    if (selectedCabinCount < 1) {
      throw new IllegalArgumentException("Shipment must contain at least one cabin");
    }
    ShipmentTaskSettings value = loadOrCreate(warehouseId, actorSubjectId);
    if (selectedCabinCount > value.getMaxCabinsPerShipmentTask()) {
      throw new LogisticsConflictException(
          "В одном задании отгрузки нельзя выбрать больше "
              + value.getMaxCabinsPerShipmentTask()
              + " бытовок");
    }
  }

  private ShipmentTaskSettings loadOrCreate(UUID warehouseId, UUID actorSubjectId) {
    if (warehouseId == null || actorSubjectId == null) {
      throw new IllegalArgumentException("Shipment task settings warehouse and actor are required");
    }
    return settings
        .findById(warehouseId)
        .orElseGet(
            () -> {
              settings.insertDefaultIfAbsent(warehouseId, actorSubjectId, now());
              return settings
                  .findById(warehouseId)
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "Shipment task settings default was not persisted"));
            });
  }

  /** Locks the current policy after atomically materializing the default if necessary. */
  private ShipmentTaskSettings lockOrCreate(UUID warehouseId, UUID actorSubjectId) {
    return settings
        .findForUpdate(warehouseId)
        .orElseGet(
            () -> {
              settings.insertDefaultIfAbsent(warehouseId, actorSubjectId, now());
              return settings
                  .findForUpdate(warehouseId)
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "Shipment task settings default was not persisted"));
            });
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }
}
