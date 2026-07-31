package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.api.LogisticsRepairPlaceAllocationResponse;
import dev.buhanzaz.rwms.maintenance.api.LogisticsRepairPlaceProjectionResponse;
import dev.buhanzaz.rwms.maintenance.api.RepairPlaceAllocationResponse;
import dev.buhanzaz.rwms.maintenance.api.RepairPlaceProjectionResponse;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairPlaceAllocation;
import dev.buhanzaz.rwms.maintenance.domain.RepairPlaceAllocationState;
import dev.buhanzaz.rwms.maintenance.domain.RepairReclassificationState;
import dev.buhanzaz.rwms.maintenance.mapper.RepairPlaceAllocationResponseMapper;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairPlaceAllocationRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class RepairPlaceService {
  private static final UUID LOGISTICS_SERVICE_SUBJECT =
      UUID.nameUUIDFromBytes("rwms:logistics-service".getBytes(StandardCharsets.UTF_8));
  private static final List<RepairPlaceAllocationState> CONSUMING_STATES =
      List.of(
          RepairPlaceAllocationState.RESERVED,
          RepairPlaceAllocationState.OCCUPIED,
          RepairPlaceAllocationState.READY_TO_RELEASE);

  private final RepairPlaceAllocationRepository allocations;
  private final MaintenanceRepairRepository repairs;
  private final RepairCapacitySettingsService capacity;
  private final MaintenanceIdempotencyStore idempotency;
  private final RepairPlaceAllocationResponseMapper responseMapper;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public RepairPlaceService(
      RepairPlaceAllocationRepository allocations,
      MaintenanceRepairRepository repairs,
      RepairCapacitySettingsService capacity,
      MaintenanceIdempotencyStore idempotency,
      RepairPlaceAllocationResponseMapper responseMapper,
      JdbcTemplate jdbc,
      ObjectMapper mapper) {
    this.allocations = allocations;
    this.repairs = repairs;
    this.capacity = capacity;
    this.idempotency = idempotency;
    this.responseMapper = responseMapper;
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Transactional(readOnly = true)
  public RepairPlaceProjectionResponse projection(UUID warehouseId) {
    int placeCount = capacity.get(warehouseId).repairPlaceCount();
    List<RepairPlaceAllocationResponse> values =
        allocations.findAllByWarehouseIdOrderByCreatedAtAscIdAsc(warehouseId).stream()
            .map(responseMapper::toResponse)
            .toList();
    long reserved =
        count(values, RepairPlaceAllocationState.RESERVED);
    long occupied =
        count(values, RepairPlaceAllocationState.OCCUPIED);
    long ready =
        count(values, RepairPlaceAllocationState.READY_TO_RELEASE);
    long consumed = reserved + occupied + ready;
    return new RepairPlaceProjectionResponse(
        warehouseId,
        placeCount,
        reserved,
        occupied,
        ready,
        Math.max(0, placeCount - consumed),
        consumed > placeCount,
        values);
  }

  @Transactional(readOnly = true)
  public LogisticsRepairPlaceProjectionResponse logisticsProjection(UUID warehouseId) {
    int placeCount = capacity.get(warehouseId).repairPlaceCount();
    List<RepairPlaceAllocation> allocationEntities =
        allocations.findAllByWarehouseIdAndStateInOrderByCreatedAtAscIdAsc(
            warehouseId, CONSUMING_STATES);
    Map<UUID, MaintenanceRepair> repairById =
        repairs.findAllById(
                allocationEntities.stream()
                    .map(RepairPlaceAllocation::getRepairId)
                    .toList())
            .stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    MaintenanceRepair::getId, value -> value));
    List<LogisticsRepairPlaceAllocationResponse> values =
        allocationEntities.stream()
            .map(
                allocation -> {
                  MaintenanceRepair repair = repairById.get(allocation.getRepairId());
                  if (repair == null) {
                    throw new IllegalStateException(
                        "Repair-place allocation refers to a missing repair");
                  }
                  return responseMapper.toLogisticsResponse(
                      allocation, repair.getRentalItemId());
                })
            .toList();
    long reserved = countLogistics(values, RepairPlaceAllocationState.RESERVED);
    long occupied = countLogistics(values, RepairPlaceAllocationState.OCCUPIED);
    long ready =
        countLogistics(values, RepairPlaceAllocationState.READY_TO_RELEASE);
    long consumed = reserved + occupied + ready;
    return new LogisticsRepairPlaceProjectionResponse(
        warehouseId,
        placeCount,
        reserved,
        occupied,
        ready,
        Math.max(0, placeCount - consumed),
        consumed > placeCount,
        values);
  }

  @Transactional
  public TransitionResult reserve(
      UUID warehouseId, UUID repairId, long expectedVersion, UUID idempotencyKey) {
    return command(
        "reserve",
        warehouseId,
        repairId,
        expectedVersion,
        idempotencyKey,
        () -> {
          lockWarehouse(warehouseId);
          MaintenanceRepair repair = requireOpenRepair(warehouseId, repairId);
          if (repair.getReclassificationState()
              == RepairReclassificationState.EXTERNAL_CAPITAL) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT",
                "External capital repair cannot reserve an ordinary repair place");
          }
          RepairPlaceAllocation existing =
              allocations.findByRepairIdForUpdate(repairId).orElse(null);
          if (existing != null) {
            return requireIdempotent(
                existing, RepairPlaceAllocationState.RESERVED, expectedVersion);
          }
          if (expectedVersion != 0) {
            throw versionConflict(repairId, expectedVersion, null);
          }
          long consumed =
              allocations.countByWarehouseIdAndStateIn(warehouseId, CONSUMING_STATES);
          int limit = capacity.get(warehouseId).repairPlaceCount();
          if (consumed >= limit) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT",
                "Warehouse %s has no available repair place (%d/%d)"
                    .formatted(warehouseId, consumed, limit));
          }
          return response(
              allocations.saveAndFlush(
                  RepairPlaceAllocation.reserve(warehouseId, repair.getId())));
        });
  }

  @Transactional
  public TransitionResult occupy(
      UUID warehouseId, UUID repairId, long expectedVersion, UUID idempotencyKey) {
    return command(
        "occupy",
        warehouseId,
        repairId,
        expectedVersion,
        idempotencyKey,
        () ->
            transition(
                warehouseId,
                repairId,
                expectedVersion,
                RepairPlaceAllocationState.OCCUPIED));
  }

  @Transactional
  public TransitionResult readyToRelease(
      UUID warehouseId, UUID repairId, long expectedVersion, UUID idempotencyKey) {
    return command(
        "ready-to-release",
        warehouseId,
        repairId,
        expectedVersion,
        idempotencyKey,
        () ->
            transition(
                warehouseId,
                repairId,
                expectedVersion,
                RepairPlaceAllocationState.READY_TO_RELEASE));
  }

  @Transactional
  public TransitionResult release(
      UUID warehouseId, UUID repairId, long expectedVersion, UUID idempotencyKey) {
    return command(
        "release",
        warehouseId,
        repairId,
        expectedVersion,
        idempotencyKey,
        () ->
            transition(
                warehouseId,
                repairId,
                expectedVersion,
                RepairPlaceAllocationState.RELEASED));
  }

  @Transactional
  public void markReadyToReleaseIfOccupied(UUID warehouseId, UUID repairId) {
    lockWarehouse(warehouseId);
    RepairPlaceAllocation value = allocations.findByRepairIdForUpdate(repairId).orElse(null);
    if (value == null || value.getState() == RepairPlaceAllocationState.READY_TO_RELEASE) {
      return;
    }
    if (value.getState() == RepairPlaceAllocationState.OCCUPIED) {
      value.readyToRelease();
      allocations.saveAndFlush(value);
    }
  }

  private RepairPlaceAllocationResponse transition(
      UUID warehouseId,
      UUID repairId,
      long expectedVersion,
      RepairPlaceAllocationState target) {
    lockWarehouse(warehouseId);
    MaintenanceRepair repair = requireRepairIdentity(warehouseId, repairId);
    if (target == RepairPlaceAllocationState.OCCUPIED) {
      requireOpenRepair(repair);
    }
    RepairPlaceAllocation value =
        allocations
            .findByRepairIdForUpdate(repairId)
            .orElseThrow(
                () ->
                    new MaintenanceNotFoundException(
                        "Repair-place allocation not found"));
    if (value.getVersion() != expectedVersion) {
      throw versionConflict(repairId, expectedVersion, value.getVersion());
    }
    if (value.getState() == target) {
      return response(value);
    }
    try {
      switch (target) {
        case OCCUPIED -> value.occupy();
        case READY_TO_RELEASE -> value.readyToRelease();
        case RELEASED -> value.release();
        case RESERVED ->
            throw new IllegalArgumentException("Reservation is a create transition");
      }
    } catch (IllegalStateException exception) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", exception.getMessage());
    }
    return response(allocations.saveAndFlush(value));
  }

  private RepairPlaceAllocationResponse requireIdempotent(
      RepairPlaceAllocation value,
      RepairPlaceAllocationState target,
      long expectedVersion) {
    if (value.getState() == target && value.getVersion() == expectedVersion) {
      return response(value);
    }
    if (value.getState() == target) {
      throw versionConflict(value.getRepairId(), expectedVersion, value.getVersion());
    }
    throw new MaintenanceConflictException(
        "MAINTENANCE_STATE_CONFLICT",
        "Repair %s already has repair-place allocation in state %s"
            .formatted(value.getRepairId(), value.getState()));
  }

  private MaintenanceRepair requireOpenRepair(UUID warehouseId, UUID repairId) {
    return requireOpenRepair(requireRepairIdentity(warehouseId, repairId));
  }

  private MaintenanceRepair requireOpenRepair(MaintenanceRepair repair) {
    if (repair.getExecutionState() == RepairExecutionState.CANCELLED
        || repair.getAcceptanceState() == RepairAcceptanceState.ACCEPTED
        || repair.getAcceptanceState() == RepairAcceptanceState.WRITTEN_OFF) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Closed repair cannot consume a repair place");
    }
    return repair;
  }

  private MaintenanceRepair requireRepairIdentity(UUID warehouseId, UUID repairId) {
    MaintenanceRepair repair =
        repairs
            .findByIdAndWarehouseId(repairId, warehouseId)
            .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
    return repair;
  }

  private void lockWarehouse(UUID warehouseId) {
    if (warehouseId == null) {
      throw new IllegalArgumentException("warehouseId is required");
    }
    jdbc.query(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        prepared -> prepared.setString(1, "maintenance-repair-places:" + warehouseId),
        result -> null);
  }

  private static long count(
      List<RepairPlaceAllocationResponse> values, RepairPlaceAllocationState state) {
    return values.stream().filter(value -> value.state() == state).count();
  }

  private static long countLogistics(
      List<LogisticsRepairPlaceAllocationResponse> values,
      RepairPlaceAllocationState state) {
    return values.stream().filter(value -> value.state() == state).count();
  }

  private TransitionResult command(
      String operation,
      UUID warehouseId,
      UUID repairId,
      long expectedVersion,
      UUID idempotencyKey,
      java.util.function.Supplier<RepairPlaceAllocationResponse> action) {
    String scope =
        "rp.%s:%s".formatted(operationCode(operation), repairId);
    String requestHash =
        MaintenanceChecksum.sha256(
            ("%s:%s:%s:%d".formatted(operation, warehouseId, repairId, expectedVersion))
                .getBytes(StandardCharsets.UTF_8));
    Optional<JsonNode> replay =
        idempotency.replay(
            LOGISTICS_SERVICE_SUBJECT, scope, idempotencyKey, requestHash);
    if (replay.isPresent()) {
      return new TransitionResult(read(replay.orElseThrow()), true);
    }
    RepairPlaceAllocationResponse response = action.get();
    idempotency.store(
        LOGISTICS_SERVICE_SUBJECT,
        scope,
        idempotencyKey,
        requestHash,
        200,
        response);
    return new TransitionResult(response, false);
  }

  private RepairPlaceAllocationResponse read(JsonNode value) {
    try {
      return mapper.treeToValue(value, RepairPlaceAllocationResponse.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Stored repair-place transition response is invalid", exception);
    }
  }

  private static String operationCode(String operation) {
    return switch (operation) {
      case "reserve" -> "res";
      case "occupy" -> "occ";
      case "ready-to-release" -> "rtr";
      case "release" -> "rel";
      default -> throw new IllegalArgumentException("Unknown repair-place operation");
    };
  }

  private RepairPlaceAllocationResponse response(RepairPlaceAllocation value) {
    return responseMapper.toResponse(value);
  }

  private static MaintenanceConflictException versionConflict(
      UUID repairId, long expectedVersion, Long actualVersion) {
    String actual = actualVersion == null ? "absent" : actualVersion.toString();
    return new MaintenanceConflictException(
        "MAINTENANCE_VERSION_CONFLICT",
        "Repair-place allocation for repair %s expected version %d but was %s"
            .formatted(repairId, expectedVersion, actual));
  }

  public record TransitionResult(
      RepairPlaceAllocationResponse response, boolean replayed) {}
}
