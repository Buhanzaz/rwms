package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.api.LogisticsRepairPlaceAllocationResponse;
import dev.buhanzaz.rwms.maintenance.api.LogisticsRepairPlaceProjectionAllocationResponse;
import dev.buhanzaz.rwms.maintenance.api.LogisticsRepairPlaceProjectionResponse;
import dev.buhanzaz.rwms.maintenance.api.RepairPlaceAllocationResponse;
import dev.buhanzaz.rwms.maintenance.api.RepairPlaceProjectionResponse;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairPlaceAllocation;
import dev.buhanzaz.rwms.maintenance.domain.RepairPlaceAllocationState;
import dev.buhanzaz.rwms.maintenance.domain.RepairReclassificationState;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageState;
import dev.buhanzaz.rwms.maintenance.mapper.RepairPlaceAllocationResponseMapper;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairPlaceAllocationRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
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

/** Application service for RepairPlaceService; it coordinates maintenance-owned state and durable effects. */
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
  private final RepairStageRepository repairStages;
  private final RepairCapacitySettingsService capacity;
  private final MaintenanceIdempotencyStore idempotency;
  private final RepairPlaceAllocationResponseMapper responseMapper;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public RepairPlaceService(
      RepairPlaceAllocationRepository allocations,
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages,
      RepairCapacitySettingsService capacity,
      MaintenanceIdempotencyStore idempotency,
      RepairPlaceAllocationResponseMapper responseMapper,
      JdbcTemplate jdbc,
      ObjectMapper mapper) {
    this.allocations = allocations;
    this.repairs = repairs;
    this.repairStages = repairStages;
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
    long physicalLoad = reserved + occupied + ready;
    long schedulingLoad = schedulingLoad(reserved, occupied, ready);
    return new RepairPlaceProjectionResponse(
        warehouseId,
        placeCount,
        reserved,
        occupied,
        ready,
        Math.max(0, placeCount - physicalLoad),
        schedulingLoad > placeCount,
        values);
  }

  @Transactional(readOnly = true)
  public LogisticsRepairPlaceProjectionResponse logisticsProjection(UUID warehouseId) {
    var settings = capacity.get(warehouseId);
    int placeCount = settings.repairPlaceCount();
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
    Map<UUID, RepairStage> activeStageByRepair =
        activeStagesByRepair(repairById.keySet());
    List<LogisticsRepairPlaceProjectionAllocationResponse> values =
        allocationEntities.stream()
            .map(
                allocation -> {
                  MaintenanceRepair repair = repairById.get(allocation.getRepairId());
                  if (repair == null) {
                    throw new IllegalStateException(
                        "Repair-place allocation refers to a missing repair");
                  }
                  RepairStage stage = activeStageByRepair.get(repair.getId());
                  return responseMapper.toLogisticsProjectionResponse(
                      allocation,
                      repair.getRentalItemId(),
                      stage == null ? null : stage.getRoutingQueueName(),
                      stage == null ? null : stage.getState(),
                      repair.getPriority());
                })
            .toList();
    long reserved = countLogistics(values, RepairPlaceAllocationState.RESERVED);
    long occupied = countLogistics(values, RepairPlaceAllocationState.OCCUPIED);
    long ready =
        countLogistics(values, RepairPlaceAllocationState.READY_TO_RELEASE);
    long physicalLoad = reserved + occupied + ready;
    long schedulingLoad = schedulingLoad(reserved, occupied, ready);
    return new LogisticsRepairPlaceProjectionResponse(
        warehouseId,
        placeCount,
        settings.automaticRefillDelayMinutes(),
        reserved,
        occupied,
        ready,
        Math.max(0, placeCount - physicalLoad),
        schedulingLoad > placeCount,
        values);
  }

  /**
   * A repair stage is maintenance-owned. Logistics receives only the earliest unfinished stage
   * as a read-only progress hint for its repair-place projection; it cannot command that stage.
   */
  private Map<UUID, RepairStage> activeStagesByRepair(
      java.util.Collection<UUID> repairIds) {
    if (repairIds.isEmpty()) return Map.of();
    return repairStages.findAllByRepairIdInOrderByRepairIdAscStageNoAscIdAsc(repairIds).stream()
        .filter(
            stage ->
                stage.getState() != RepairStageState.DONE
                    && stage.getState() != RepairStageState.CANCELLED)
        .collect(
            java.util.stream.Collectors.toMap(
                RepairStage::getRepairId,
                value -> value,
                (first, ignored) -> first,
                java.util.LinkedHashMap::new));
  }

  @Transactional(readOnly = true)
  public boolean isOccupied(UUID warehouseId, UUID repairId) {
    if (warehouseId == null || repairId == null) {
      throw new IllegalArgumentException("Repair-place identity is required");
    }
    return allocations.existsByWarehouseIdAndRepairIdAndState(
        warehouseId, repairId, RepairPlaceAllocationState.OCCUPIED);
  }

  /**
   * Fails closed if an unstarted inventory replacement would leave a live repair-place
   * allocation behind its cancelled predecessor.
   */
  @Transactional
  public void requireNoActiveAllocationForInventoryReplacement(
      UUID warehouseId,
      UUID predecessorRepairId,
      UUID compensationAllocationId,
      Long compensationAllocationVersion) {
    if (warehouseId == null
        || predecessorRepairId == null
        || compensationAllocationId != null
        || compensationAllocationVersion != null) {
      throw new IllegalArgumentException("Inventory replacement repair-place identity is required");
    }
    lockWarehouse(warehouseId);
    RepairPlaceAllocation allocation =
        allocations.findByRepairIdForUpdate(predecessorRepairId).orElse(null);
    if (allocation == null || allocation.getState() == RepairPlaceAllocationState.RELEASED) return;
    throw new MaintenanceConflictException(
        "MAINTENANCE_STATE_CONFLICT",
        "Pre-start inventory replacement cannot release a lease with active repair-place allocation %s"
            .formatted(allocation.getId()));
  }

  /**
   * Transfers an already delivered cabin to the replacement repair without releasing the physical
   * place. The warehouse advisory lock makes the old/new allocation identity atomic.
   */
  @Transactional
  public void reassignOccupiedForInventoryReplacement(
      UUID warehouseId,
      UUID predecessorRepairId,
      UUID successorRepairId,
      UUID expectedAllocationId,
      long expectedAllocationVersion) {
    if (warehouseId == null
        || predecessorRepairId == null
        || successorRepairId == null
        || predecessorRepairId.equals(successorRepairId)
        || expectedAllocationId == null
        || expectedAllocationVersion < 0) {
      throw new IllegalArgumentException("Inventory replacement repair-place identity is invalid");
    }
    lockWarehouse(warehouseId);
    MaintenanceRepair predecessor = requireOpenRepair(warehouseId, predecessorRepairId);
    MaintenanceRepair successor = requireOpenRepair(warehouseId, successorRepairId);
    if (!predecessor.getRentalItemId().equals(successor.getRentalItemId())) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Inventory replacement repair-place allocation must keep the same rental item");
    }
    if (successor.getExecutionState() != RepairExecutionState.DRAFT) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Inventory replacement successor must still be a draft");
    }
    RepairPlaceAllocation successorAllocation =
        allocations.findByRepairIdForUpdate(successorRepairId).orElse(null);
    if (successorAllocation != null) {
      if (expectedAllocationId.equals(successorAllocation.getId())
          && successorAllocation.getState() == RepairPlaceAllocationState.OCCUPIED
          && successorAllocation.getVersion() == Math.addExact(expectedAllocationVersion, 1)) {
        return;
      }
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Inventory replacement successor already has an active repair-place allocation");
    }
    RepairPlaceAllocation allocation =
        allocations
            .findByRepairIdForUpdate(predecessorRepairId)
            .orElseThrow(
                () ->
                    new MaintenanceConflictException(
                        "MAINTENANCE_STATE_CONFLICT",
                        "Delivered inventory replacement requires an occupied repair-place allocation"));
    if (!expectedAllocationId.equals(allocation.getId())
        || allocation.getVersion() != expectedAllocationVersion) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_VERSION_CONFLICT",
          "Delivered inventory replacement allocation no longer matches logistics compensation truth");
    }
    try {
      allocation.reassignForInventoryReplacement(successorRepairId);
    } catch (IllegalStateException exception) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", exception.getMessage());
    }
    allocations.saveAndFlush(allocation);
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
                existing,
                repair.getRentalItemId(),
                RepairPlaceAllocationState.RESERVED,
                expectedVersion);
          }
          if (expectedVersion != 0) {
            throw versionConflict(repairId, expectedVersion, null);
          }
          long reserved =
              allocations.countByWarehouseIdAndState(
                  warehouseId, RepairPlaceAllocationState.RESERVED);
          long occupied =
              allocations.countByWarehouseIdAndState(
                  warehouseId, RepairPlaceAllocationState.OCCUPIED);
          long ready =
              allocations.countByWarehouseIdAndState(
                  warehouseId, RepairPlaceAllocationState.READY_TO_RELEASE);
          int limit = capacity.get(warehouseId).repairPlaceCount();
          long loadAfterReservation =
              schedulingLoad(reserved + 1, occupied, ready);
          if (loadAfterReservation > limit) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT",
                "Warehouse %s has no available or paired repair place (%d/%d)"
                    .formatted(warehouseId, loadAfterReservation, limit));
          }
          return logisticsResponse(
              allocations.saveAndFlush(
                  RepairPlaceAllocation.reserve(warehouseId, repair.getId())),
              repair.getRentalItemId());
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

  /**
   * Finishes an ordinary repair-place allocation.
   *
   * <p>A repair that was delivered through the selected repair movement becomes visible to
   * logistics as ready for the automatic movement from repair. A repair that did not require
   * delivery releases the place immediately.
   */
  @Transactional
  public void completeAfterRepair(
      UUID warehouseId, UUID repairId, boolean removalRequired) {
    lockWarehouse(warehouseId);
    RepairPlaceAllocation value = allocations.findByRepairIdForUpdate(repairId).orElse(null);
    if (value == null || value.getState() == RepairPlaceAllocationState.RELEASED) {
      return;
    }
    if (removalRequired) {
      if (value.getState() == RepairPlaceAllocationState.OCCUPIED) {
        value.readyToRelease();
        allocations.save(value);
      }
      return;
    }
    if (value.getState() == RepairPlaceAllocationState.OCCUPIED) {
      value.readyToRelease();
    }
    if (value.getState() == RepairPlaceAllocationState.READY_TO_RELEASE) {
      value.release();
      allocations.save(value);
    }
  }

  private LogisticsRepairPlaceAllocationResponse transition(
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
      return logisticsResponse(value, repair.getRentalItemId());
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
    return logisticsResponse(allocations.saveAndFlush(value), repair.getRentalItemId());
  }

  private LogisticsRepairPlaceAllocationResponse requireIdempotent(
      RepairPlaceAllocation value,
      UUID rentalItemId,
      RepairPlaceAllocationState target,
      long expectedVersion) {
    if (value.getState() == target && value.getVersion() == expectedVersion) {
      return logisticsResponse(value, rentalItemId);
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
      List<LogisticsRepairPlaceProjectionAllocationResponse> values,
      RepairPlaceAllocationState state) {
    return values.stream().filter(value -> value.state() == state).count();
  }

  /**
   * A ready-to-release cabin and the inbound cabin queued immediately behind
   * its removal share one scheduling slot. Both remain explicit allocations,
   * while the ordered logistics lane guarantees that removal is actionable
   * first.
   */
  private static long schedulingLoad(long reserved, long occupied, long ready) {
    return occupied + Math.max(reserved, ready);
  }

  private TransitionResult command(
      String operation,
      UUID warehouseId,
      UUID repairId,
      long expectedVersion,
      UUID idempotencyKey,
      java.util.function.Supplier<LogisticsRepairPlaceAllocationResponse> action) {
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
      return new TransitionResult(read(replay.orElseThrow(), warehouseId, repairId), true);
    }
    LogisticsRepairPlaceAllocationResponse response = action.get();
    idempotency.store(
        LOGISTICS_SERVICE_SUBJECT,
        scope,
        idempotencyKey,
        requestHash,
        200,
        response);
    return new TransitionResult(response, false);
  }

  private LogisticsRepairPlaceAllocationResponse read(
      JsonNode value, UUID warehouseId, UUID repairId) {
    try {
      LogisticsRepairPlaceAllocationResponse response =
          mapper.treeToValue(value, LogisticsRepairPlaceAllocationResponse.class);
      if (response.rentalItemId() != null) return response;
      MaintenanceRepair repair = requireRepairIdentity(warehouseId, repairId);
      return new LogisticsRepairPlaceAllocationResponse(
          response.id(),
          response.version(),
          response.warehouseId(),
          response.repairId(),
          repair.getRentalItemId(),
          response.state(),
          response.createdAt(),
          response.updatedAt());
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

  private LogisticsRepairPlaceAllocationResponse logisticsResponse(
      RepairPlaceAllocation value, UUID rentalItemId) {
    return responseMapper.toLogisticsResponse(value, rentalItemId);
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
      LogisticsRepairPlaceAllocationResponse response, boolean replayed) {}
}
