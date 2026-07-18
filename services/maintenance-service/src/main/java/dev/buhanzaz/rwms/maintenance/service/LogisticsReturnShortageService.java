package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.LogisticsEquipmentShortage;
import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.LogisticsReturnShortageResponse;
import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.UpsertLogisticsReturnShortageRequest;

import dev.buhanzaz.rwms.maintenance.domain.LogisticsReturnShortage;
import dev.buhanzaz.rwms.maintenance.domain.LogisticsReturnShortageId;
import dev.buhanzaz.rwms.maintenance.mapper.LogisticsReturnShortageResponseMapper;
import dev.buhanzaz.rwms.maintenance.mapper.LogisticsReturnShortageResponseMapper.StoredSnapshot;
import dev.buhanzaz.rwms.maintenance.repository.LogisticsReturnShortageRepository;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Stage 8 source intake only: logistics cannot create or mutate a repair,
 * estimate, task, lease or asset state through this service.
 */
@Service
@RequiredArgsConstructor
public class LogisticsReturnShortageService {
  private final LogisticsReturnShortageRepository sources;
  private final LogisticsReturnShortageRegistrar registrar;
  private final LogisticsReturnShortageResponseMapper responseMapper;
  private final ObjectMapper mapper;

  public UpsertResult upsert(
      UUID returnId, UUID lineId, UpsertLogisticsReturnShortageRequest request) {
    LogisticsReturnShortageId id = new LogisticsReturnShortageId(returnId, lineId);
    List<LogisticsEquipmentShortage> shortages = canonicalShortages(request.shortages());
    String snapshot = write(shortages);
    String snapshotSha256 = sha256(snapshot);
    String sourceSha256 =
        sha256(
            write(
                new SourceFingerprint(
                    returnId,
                    lineId,
                    request.warehouseId(),
                    request.rentalItemId(),
                    request.rentalItemVersion(),
                    shortages)));
    LogisticsReturnShortage candidate =
        LogisticsReturnShortage.receive(
            id,
            request.warehouseId(),
            request.rentalItemId(),
            request.rentalItemVersion(),
            sourceSha256,
            snapshotSha256,
            snapshot);

    boolean registered;
    try {
      registered = registrar.register(candidate);
    } catch (DataIntegrityViolationException ignored) {
      registered = false;
    }
    LogisticsReturnShortage source =
        sources
            .findById(id)
            .orElseThrow(
                () ->
                    new MaintenanceConflictException(
                        "LOGISTICS_RETURN_SHORTAGE_CONCURRENT_WRITE",
                        "Logistics return shortage source was not persisted"));
    if (!sourceSha256.equals(source.getSourceSha256())) {
      throw new MaintenanceConflictException(
          "LOGISTICS_RETURN_SHORTAGE_CONFLICT",
          "Return-line source is already bound to a different immutable shortage snapshot");
    }
    return new UpsertResult(response(source), !registered);
  }

  @Transactional(readOnly = true)
  public LogisticsReturnShortageResponse get(UUID returnId, UUID lineId) {
    return response(
        sources
            .findById(new LogisticsReturnShortageId(returnId, lineId))
            .orElseThrow(
                () -> new MaintenanceNotFoundException("Logistics return shortage source not found")));
  }

  private LogisticsReturnShortageResponse response(LogisticsReturnShortage source) {
    StoredSnapshot stored = responseMapper.toStoredSnapshot(source);
    return new LogisticsReturnShortageResponse(
        stored.returnId(),
        stored.lineId(),
        stored.sourceVersion(),
        stored.warehouseId(),
        stored.rentalItemId(),
        stored.rentalItemVersion(),
        readShortages(stored.shortageSnapshot()),
        stored.snapshotSha256(),
        stored.receivedAt());
  }

  private static List<LogisticsEquipmentShortage> canonicalShortages(
      List<LogisticsEquipmentShortage> values) {
    if (values == null || values.isEmpty()) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_VALIDATION_FAILED", "At least one equipment shortage is required");
    }
    Set<UUID> equipmentIds = new HashSet<>();
    for (LogisticsEquipmentShortage value : values) {
      if (value == null || value.equipmentId() == null || value.missingQuantity() < 1) {
        throw new MaintenanceValidationException(
            "MAINTENANCE_VALIDATION_FAILED", "Equipment shortage is invalid");
      }
      if (!equipmentIds.add(value.equipmentId())) {
        throw new MaintenanceValidationException(
            "MAINTENANCE_VALIDATION_FAILED", "Equipment shortage contains a duplicate equipment ID");
      }
    }
    return values.stream()
        .sorted(Comparator.comparing(value -> value.equipmentId().toString()))
        .toList();
  }

  private List<LogisticsEquipmentShortage> readShortages(String value) {
    try {
      return mapper.readValue(value, new TypeReference<List<LogisticsEquipmentShortage>>() {});
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored logistics return shortage snapshot is invalid", exception);
    }
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Logistics return shortage snapshot is invalid", exception);
    }
  }

  private static String sha256(String value) {
    return MaintenanceChecksum.sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  public record UpsertResult(LogisticsReturnShortageResponse response, boolean replayed) {}

  private record SourceFingerprint(
      UUID returnId,
      UUID lineId,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      List<LogisticsEquipmentShortage> shortages) {}
}
