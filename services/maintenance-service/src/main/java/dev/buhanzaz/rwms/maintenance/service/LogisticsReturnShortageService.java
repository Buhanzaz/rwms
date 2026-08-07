package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.MediaReferenceInput;
import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.ReturnEstimateSource;
import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.UpsertLogisticsReturnEstimateSourceRequest;

import dev.buhanzaz.rwms.maintenance.domain.LogisticsReturnShortage;
import dev.buhanzaz.rwms.maintenance.domain.LogisticsReturnShortageId;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Immutable logistics source intake paired with exactly one empty DRAFT estimate.
 *
 * <p>Repair creation remains exclusively owned by the normal estimate-completion transition.
 */
@Service
@RequiredArgsConstructor
public class LogisticsReturnShortageService {
  private final LogisticsReturnShortageRepository sources;
  private final LogisticsReturnShortageRegistrar registrar;
  private final ObjectMapper mapper;

  public UpsertResult upsert(
      UUID returnId, UUID lineId, UpsertLogisticsReturnEstimateSourceRequest request) {
    LogisticsReturnShortageId id = new LogisticsReturnShortageId(returnId, lineId);
    List<MediaReferenceInput> mediaReferences =
        canonicalMediaReferences(request.mediaReferences());
    String snapshot = write(mediaReferences);
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
                    request.dispatchDate(),
                    mediaReferences)));
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
      registered = registrar.register(candidate, request.dispatchDate(), mediaReferences);
    } catch (DataIntegrityViolationException ignored) {
      registered = false;
    }
    LogisticsReturnShortage source =
        sources
            .findById(id)
            .orElseThrow(
                () ->
                    new MaintenanceConflictException(
                        "LOGISTICS_RETURN_ESTIMATE_SOURCE_CONCURRENT_WRITE",
                        "Logistics return estimate source was not persisted"));
    if (!sourceSha256.equals(source.getSourceSha256())) {
      throw new MaintenanceConflictException(
          "LOGISTICS_RETURN_ESTIMATE_SOURCE_CONFLICT",
          "Return-line source is already bound to a different immutable estimate source");
    }
    return new UpsertResult(response(source), !registered);
  }

  @Transactional(readOnly = true)
  public ReturnEstimateSource get(UUID returnId, UUID lineId) {
    return response(
        sources
            .findById(new LogisticsReturnShortageId(returnId, lineId))
            .orElseThrow(
                () -> new MaintenanceNotFoundException("Logistics return estimate source not found")));
  }

  @Transactional(readOnly = true)
  public List<ReturnEstimateSource> list(UUID warehouseId, UUID returnId) {
    return sources.findAllById_ReturnIdAndWarehouseIdOrderById_LineId(returnId, warehouseId).stream()
        .map(this::response)
        .toList();
  }

  private ReturnEstimateSource response(LogisticsReturnShortage source) {
    return new ReturnEstimateSource(
        source.getId().getReturnId(),
        source.getId().getLineId(),
        source.getVersion(),
        source.getWarehouseId(),
        source.getRentalItemId(),
        source.getRentalItemVersionSnapshot(),
        source.getEstimateId(),
        source.getSnapshotSha256(),
        source.getCreatedAt());
  }

  private static List<MediaReferenceInput> canonicalMediaReferences(
      List<MediaReferenceInput> values) {
    if (values == null || values.isEmpty() || values.size() > 20) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_VALIDATION_FAILED",
          "At least one and at most twenty return inspection photos are required");
    }
    Set<UUID> mediaIds = new HashSet<>();
    for (MediaReferenceInput value : values) {
      if (value == null
          || value.mediaId() == null
          || value.generation() == null
          || value.generation() < 1) {
        throw new MaintenanceValidationException(
            "MAINTENANCE_VALIDATION_FAILED", "Return inspection photo reference is invalid");
      }
      if (!mediaIds.add(value.mediaId())) {
        throw new MaintenanceValidationException(
            "MAINTENANCE_VALIDATION_FAILED",
            "Return inspection photos contain a duplicate media ID");
      }
    }
    return values.stream()
        .sorted(Comparator.comparing(value -> value.mediaId().toString()))
        .toList();
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Logistics return estimate source snapshot is invalid", exception);
    }
  }

  private static String sha256(String value) {
    return MaintenanceChecksum.sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  public record UpsertResult(ReturnEstimateSource response, boolean replayed) {}

  private record SourceFingerprint(
      UUID returnId,
      UUID lineId,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      java.time.LocalDate dispatchDate,
      List<MediaReferenceInput> mediaReferences) {}
}
