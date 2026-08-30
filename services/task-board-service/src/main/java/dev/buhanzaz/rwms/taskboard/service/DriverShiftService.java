package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.DriverShiftApiModels.*;
import static dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes.DRIVER_SHIFT_OWNER_PROOF_CHANGED;

import dev.buhanzaz.rwms.taskboard.config.DriverShiftProperties;
import dev.buhanzaz.rwms.taskboard.domain.*;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.DriverShiftOwnerProofFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventStore;
import dev.buhanzaz.rwms.taskboard.repository.*;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Transactional owner of the complete Driver Up daily-shift state machine.
 *
 * <p>All navigation is derived from server state. Warehouse time, task completion, inspection
 * snapshots, defects, command receipts and media reservations remain durable across process death.
 */
@Service
public class DriverShiftService {
  private final DriverShiftProperties properties;
  private final DriverShiftPlanRepository plans;
  private final DriverShiftRepository shifts;
  private final VehicleInspectionRepository inspections;
  private final VehicleInspectionItemResultRepository items;
  private final VehicleDefectRepository defects;
  private final DriverShiftPhotoRepository photos;
  private final WorkerRepository workers;
  private final WarehouseIdentityGateway warehouses;
  private final DriverWeatherProvider weather;
  private final TaskBoardEventStore events;
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  /** Creates the cohesive shift workflow with a replaceable clock for boundary tests. */
  public DriverShiftService(
      DriverShiftProperties properties,
      DriverShiftPlanRepository plans,
      DriverShiftRepository shifts,
      VehicleInspectionRepository inspections,
      VehicleInspectionItemResultRepository items,
      VehicleDefectRepository defects,
      DriverShiftPhotoRepository photos,
      WorkerRepository workers,
      WarehouseIdentityGateway warehouses,
      DriverWeatherProvider weather,
      TaskBoardEventStore events,
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      ObjectProvider<Clock> clocks) {
    this.properties = properties;
    this.plans = plans;
    this.shifts = shifts;
    this.inspections = inspections;
    this.items = items;
    this.defects = defects;
    this.photos = photos;
    this.workers = workers;
    this.warehouses = warehouses;
    this.weather = weather;
    this.events = events;
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.clock = clocks.getIfAvailable(Clock::systemUTC);
  }

  /** Applies one replay-safe logistics plan, replacing it only before actual-shift freezing. */
  @Transactional
  public DriverShiftPlanResponse putPlan(
      UUID sourceShiftId, String idempotencyKey, PutDriverShiftPlanRequest request) {
    requireIdempotencyKey(idempotencyKey, null);
    String fingerprint = fingerprint(request);
    OffsetDateTime now = now();
    Optional<DriverShiftPlan> bySource = plans.findBySourceShiftIdForUpdate(sourceShiftId);
    Optional<DriverShiftPlan> byDriver =
        plans.findByDriverIdAndWorkDateForUpdate(request.driverId(), request.workDate());
    if (bySource.isPresent()
        && byDriver.isPresent()
        && !bySource.get().getId().equals(byDriver.get().getId()))
      throw new ConflictException("Driver already has a different shift plan for this work date");
    DriverShiftPlan plan = bySource.orElseGet(() -> byDriver.orElse(null));
    if (plan != null) {
      if (!plan.getSourceShiftId().equals(sourceShiftId)
          || !plan.getSourcePlanId().equals(request.sourcePlanId()))
        throw new ConflictException("Shift plan identity conflicts with an existing plan");
      if (plan.getSourcePlanVersion() == request.sourcePlanVersion()
          && plan.getRequestFingerprint().equals(fingerprint))
        return planResponse(plan, PlanApplyResult.REPLAYED);
      if (plan.getFrozenShiftId() != null)
        throw new ConflictException("A frozen driver shift plan cannot be replaced");
      if (request.sourcePlanVersion() <= plan.getSourcePlanVersion())
        throw new ConflictException("Driver shift plan version is stale or divergent");
      replace(plan, request, fingerprint, now);
      plans.saveAndFlush(plan);
      return planResponse(plan, PlanApplyResult.REPLACED);
    }
    plan = new DriverShiftPlan();
    plan.assignReviewedId(UUID.randomUUID());
    plan.initialize(sourceShiftId, request.sourcePlanId(), now);
    replace(plan, request, fingerprint, now);
    plans.saveAndFlush(plan);
    return planResponse(plan, PlanApplyResult.CREATED);
  }

  /**
   * Returns one startup aggregate and creates today's shift only from an existing registered plan.
   */
  @Transactional
  public TodayShiftResponse today(UUID driverId, UUID warehouseId) {
    return today(driverId, warehouseId, true);
  }

  private TodayShiftResponse currentToday(UUID driverId, UUID warehouseId) {
    return today(driverId, warehouseId, false);
  }

  private TodayShiftResponse today(
      UUID driverId, UUID warehouseId, boolean serializeCreation) {
    OffsetDateTime serverTime = now();
    if (!properties.enabled())
      return new TodayShiftResponse(
          false,
          serverTime,
          properties.suspiciousOdometerJumpKm(),
          null,
          NextRequiredAction.SHOW_TASKS,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          List.of());
    var worker =
        workers
            .findById(driverId)
            .orElseThrow(() -> new NotFoundException("Driver profile was not found"));
    if (!worker.isActive() || !warehouseId.equals(worker.getWarehouseId()))
      throw new NotFoundException("Active driver profile was not found in this warehouse");
    var warehouse = warehouses.identity(warehouseId);
    ZoneId zone = requireZone(warehouse.timeZone());
    LocalDate workDate = resolveWorkDate(serverTime, zone, properties.dayStart());
    Optional<DriverShiftPlan> plan =
        serializeCreation
            ? plans.findByDriverIdAndWorkDateForUpdate(driverId, workDate)
            : plans.findByDriverIdAndWorkDate(driverId, workDate);
    if (plan.isEmpty() || !warehouseId.equals(plan.get().getWarehouseId()))
      return unavailable(serverTime, warehouse, zone);
    DriverShift shift =
        shifts
            .findByDriverIdAndWorkDate(driverId, workDate)
            .orElseGet(
                () -> {
                  if (!serializeCreation)
                    throw new NotFoundException("Driver shift was not found");
                  return createShift(plan.get(), warehouse, serverTime);
                });
    TaskSummaryView tasks = taskSummary(shift, plan.get());
    if (shift.getStatus() == DriverShiftStatus.SHIFT_ACTIVE && tasks.canStartClosing()) {
      shift.markTasksComplete(serverTime);
      shifts.saveAndFlush(shift);
      publishOwnerProof(shift, false);
      tasks = taskSummary(shift, plan.get());
    }
    return response(serverTime, warehouse, plan.get(), shift, tasks);
  }

  /** Records the once-per-work-date briefing acknowledgement. */
  @Transactional
  public TodayShiftResponse markBriefing(
      UUID driverId, UUID warehouseId, UUID shiftId, String key, ShiftTransitionRequest request) {
    return transition(
        driverId,
        warehouseId,
        shiftId,
        key,
        request.operationId(),
        request.expectedVersion(),
        "BRIEFING_SEEN",
        request,
        shift -> shift.markBriefingSeen(now()));
  }

  /** Records the extensible medical self-confirmation audit fact. */
  @Transactional
  public TodayShiftResponse confirmMedical(
      UUID driverId,
      UUID warehouseId,
      UUID shiftId,
      String key,
      ConfirmMedicalCheckRequest request) {
    return transition(
        driverId,
        warehouseId,
        shiftId,
        key,
        request.operationId(),
        request.expectedVersion(),
        "MEDICAL_CHECK",
        request,
        shift -> shift.confirmMedical(now(), request.clientCompletedAt()));
  }

  /**
   * Completes inspection only when every required result is answered and no blocking defect is
   * open.
   */
  @Transactional
  public TodayShiftResponse completeInspection(
      UUID driverId, UUID warehouseId, UUID shiftId, String key, ShiftTransitionRequest request) {
    return transition(
        driverId,
        warehouseId,
        shiftId,
        key,
        request.operationId(),
        request.expectedVersion(),
        "INSPECTION_COMPLETE",
        request,
        shift -> {
          VehicleInspection inspection =
              inspections
                  .findByShiftId(shiftId)
                  .orElseThrow(() -> new NotFoundException("Vehicle inspection was not found"));
          List<VehicleInspectionItemResult> values =
              items.findAllByInspectionIdOrderBySortOrderAsc(inspection.getId());
          if (values.stream()
              .anyMatch(
                  item -> item.isRequired() && item.getState() == InspectionItemState.NOT_CHECKED))
            throw new ConflictException("Every required inspection item must be checked");
          if (defects.countByShiftIdAndSeverityAndStatus(
                  shiftId, DefectSeverity.BLOCKING, DefectStatus.OPEN)
              > 0) throw new ConflictException("A blocking vehicle defect prevents shift start");
          inspection.complete(now());
          inspections.save(inspection);
          shift.completeInspection(now());
        });
  }

  /** Starts task execution after preparation gates pass. */
  @Transactional
  public TodayShiftResponse start(
      UUID driverId, UUID warehouseId, UUID shiftId, String key, ShiftTransitionRequest request) {
    return transition(
        driverId,
        warehouseId,
        shiftId,
        key,
        request.operationId(),
        request.expectedVersion(),
        "START",
        request,
        shift -> shift.start(now()));
  }

  /** Opens closing only after task-board proves one or more tasks exist and all are DONE. */
  @Transactional
  public TodayShiftResponse startClosing(
      UUID driverId, UUID warehouseId, UUID shiftId, String key, ShiftTransitionRequest request) {
    return transition(
        driverId,
        warehouseId,
        shiftId,
        key,
        request.operationId(),
        request.expectedVersion(),
        "CLOSING_START",
        request,
        shift -> {
          DriverShiftPlan plan = plans.findById(shift.getPlanId()).orElseThrow();
          if (!taskSummary(shift, plan).canStartClosing())
            throw new ConflictException(
                "Required driver tasks are not DONE or none were assigned");
          shift.startClosing(now());
        });
  }

  /** Records a manual return today while retaining a future geofence discriminator. */
  @Transactional
  public TodayShiftResponse confirmReturn(
      UUID driverId, UUID warehouseId, UUID shiftId, String key, ReturnToWarehouseRequest request) {
    return transition(
        driverId,
        warehouseId,
        shiftId,
        key,
        request.operationId(),
        request.expectedVersion(),
        "WAREHOUSE_RETURN",
        request,
        shift -> {
          if (request.confirmationType() != ReturnConfirmationType.MANUAL)
            throw new ConflictException("Geofence return confirmation is not enabled");
          shift.confirmReturn(request.confirmationType(), now());
        });
  }

  /** Updates one snapshotted inspection item without losing prior progress on restart. */
  @Transactional
  public TodayShiftResponse updateInspectionItem(
      UUID driverId,
      UUID warehouseId,
      UUID shiftId,
      UUID itemId,
      String key,
      UpdateInspectionItemRequest request) {
    requireIdempotencyKey(key, request.operationId());
    String hash = fingerprint(request);
    if (replay(request.operationId(), driverId, shiftId, "INSPECTION_ITEM", hash))
      return currentToday(driverId, warehouseId);
    DriverShift shift = ownedShift(driverId, warehouseId, shiftId);
    requireVersion(shift, request.expectedVersion());
    if (shift.getStatus() != DriverShiftStatus.VEHICLE_INSPECTION_REQUIRED)
      throw new ConflictException("Vehicle inspection is not the current shift step");
    VehicleInspectionItemResult item =
        items
            .findByIdForUpdate(itemId)
            .orElseThrow(() -> new NotFoundException("Inspection item was not found"));
    if (!shiftId.equals(item.getShiftId()))
      throw new NotFoundException("Inspection item was not found in this shift");
    if (item.getVersion() != request.expectedItemVersion())
      throw new StaleVersionException("Inspection item version is stale");
    OffsetDateTime acceptedAt = now();
    if (request.result() == InspectionItemState.DEFECT) {
      if (request.defectId() == null
          || request.defectDescription() == null
          || request.defectDescription().isBlank())
        throw new IllegalArgumentException("DEFECT requires defectId and description");
      if (item.getDefectId() != null && !item.getDefectId().equals(request.defectId()))
        throw new ConflictException("Inspection item already references another defect identity");
      VehicleDefect defect = defects.findById(request.defectId()).orElse(null);
      if (item.getState() == InspectionItemState.DEFECT
          && request.defectId().equals(item.getDefectId())
          && defect != null
          && request.defectDescription().trim().equals(defect.getDescription())) {
        recordReceipt(
            request.operationId(), UUID.fromString(key), shift, "INSPECTION_ITEM", hash);
        return currentToday(driverId, warehouseId);
      }
      if (defect == null) {
        defect = new VehicleDefect();
        defect.assignReviewedId(request.defectId());
        defect.initialize(
            shiftId,
            driverId,
            shift.getVehicleId(),
            shift.getWorkDate(),
            itemId,
            request.defectDescription(),
            DefectSeverity.BLOCKING,
            acceptedAt);
      } else if (!shiftId.equals(defect.getShiftId())
          || !itemId.equals(defect.getInspectionItemId()))
        throw new ConflictException("Defect identity belongs to another inspection item");
      else defect.revise(request.defectDescription());
      defects.save(defect);
      item.answer(InspectionItemState.DEFECT, request.defectId(), acceptedAt);
    } else if (request.result() == InspectionItemState.OK) {
      if (request.defectId() != null || request.defectDescription() != null)
        throw new IllegalArgumentException("OK does not accept defect fields");
      if (item.getDefectId() != null)
        throw new ConflictException(
            "A reported defect cannot be erased by changing the item to OK");
      if (item.getState() == InspectionItemState.OK) {
        recordReceipt(
            request.operationId(), UUID.fromString(key), shift, "INSPECTION_ITEM", hash);
        return currentToday(driverId, warehouseId);
      }
      item.answer(InspectionItemState.OK, null, acceptedAt);
    } else throw new IllegalArgumentException("Inspection result must be OK or DEFECT");
    items.save(item);
    shift.touch(acceptedAt);
    shifts.saveAndFlush(shift);
    recordReceipt(request.operationId(), UUID.fromString(key), shift, "INSPECTION_ITEM", hash);
    return currentToday(driverId, warehouseId);
  }

  /** Persists one closing report after odometer, fuel, defect and photo validation. */
  @Transactional
  public TodayShiftResponse submitClosingReport(
      UUID driverId,
      UUID warehouseId,
      UUID shiftId,
      String key,
      SubmitClosingReportRequest request) {
    return transition(
        driverId,
        warehouseId,
        shiftId,
        key,
        request.operationId(),
        request.expectedVersion(),
        "CLOSING_REPORT",
        request,
        shift -> {
          if (request.endOdometer() < 0) {
            throw new IllegalArgumentException("End odometer cannot be negative");
          }
          if (request.fuelLevelPercent() < 0 || request.fuelLevelPercent() > 100) {
            throw new IllegalArgumentException("Fuel level must be between 0 and 100 percent");
          }
          DriverShiftPlan plan = plans.findById(shift.getPlanId()).orElseThrow();
          long baseline = priorOdometer(shift, plan);
          if (request.endOdometer() < baseline)
            throw new ConflictException(
                "End odometer cannot be lower than the known starting or previous value");
          if (request.endOdometer() - baseline > properties.suspiciousOdometerJumpKm()
              && !request.confirmSuspiciousOdometer())
            throw new ConflictException("ODOMETER_CONFIRMATION_REQUIRED");
          UUID defectId = null;
          if (request.vehicleCondition() == EndVehicleCondition.DEFECT_REPORTED) {
            if (request.defectId() == null
                || request.defectDescription() == null
                || request.defectDescription().isBlank())
              throw new IllegalArgumentException(
                  "DEFECT_REPORTED requires defectId and description");
            VehicleDefect defect = defects.findById(request.defectId()).orElse(null);
            if (defect == null) {
              defect = new VehicleDefect();
              defect.assignReviewedId(request.defectId());
              defect.initialize(
                  shiftId,
                  driverId,
                  shift.getVehicleId(),
                  shift.getWorkDate(),
                  null,
                  request.defectDescription(),
                  DefectSeverity.BLOCKING,
                  now());
              defects.save(defect);
            } else if (!shiftId.equals(defect.getShiftId()))
              throw new ConflictException("Defect identity belongs to another shift");
            defectId = request.defectId();
          } else if (request.defectId() != null || request.defectDescription() != null)
            throw new IllegalArgumentException("NO_NEW_DEFECTS does not accept defect fields");
          shift.submitClosing(
              request.vehicleCondition(),
              request.endOdometer(),
              request.fuelLevelPercent(),
              defectId,
              now());
        });
  }

  /** Reserves an upload against the existing media-service owner model. */
  @Transactional
  public TodayShiftResponse reservePhoto(
      UUID driverId, UUID warehouseId, UUID shiftId, String key, ReserveShiftPhotoRequest request) {
    requireIdempotencyKey(key, request.operationId());
    String hash = fingerprint(request);
    if (replay(request.operationId(), driverId, shiftId, "PHOTO_RESERVATION", hash))
      return currentToday(driverId, warehouseId);
    DriverShift shift = ownedShift(driverId, warehouseId, shiftId);
    requireVersion(shift, request.expectedVersion());
    Optional<DriverShiftPhoto> existing =
        photos.findByShiftIdAndClientReferenceId(shiftId, request.clientReferenceId());
    if (existing.isPresent()) {
      DriverShiftPhoto value = existing.get();
      if (value.getEvidenceId().equals(request.evidenceId())
          && value.getRole() == request.role()
          && java.util.Objects.equals(value.getDefectId(), request.defectId())
          && java.util.Objects.equals(value.getInspectionItemId(), request.inspectionItemId())
          && value.getCapturedAt().equals(request.capturedAt())
          && value.getContentType().equals(request.contentType())
          && value.getSizeBytes() == request.sizeBytes()
          && value.getSha256().equals(request.sha256())) {
        recordReceipt(
            request.operationId(), UUID.fromString(key), shift, "PHOTO_RESERVATION", hash);
        return currentToday(driverId, warehouseId);
      }
      throw new ConflictException("Photo client reference was reused with changed content");
    }
    validatePhotoCorrelation(shift, request);
    OffsetDateTime acceptedAt = now();
    DriverShiftPhoto photo = new DriverShiftPhoto();
    photo.assignReviewedId(request.evidenceId());
    photo.initialize(
        shiftId,
        driverId,
        warehouseId,
        request.clientReferenceId(),
        request.evidenceId(),
        request.role(),
        request.defectId(),
        request.inspectionItemId(),
        request.capturedAt(),
        request.contentType(),
        request.sizeBytes(),
        request.sha256(),
        acceptedAt);
    photos.save(photo);
    shift.touch(acceptedAt);
    shifts.saveAndFlush(shift);
    recordReceipt(request.operationId(), UUID.fromString(key), shift, "PHOTO_RESERVATION", hash);
    return currentToday(driverId, warehouseId);
  }

  /**
   * Closes the fully reported shift exactly once, after any closing defect has a correlated READY
   * media photo, and withdraws its active media owner proof.
   */
  @Transactional
  public TodayShiftResponse close(
      UUID driverId, UUID warehouseId, UUID shiftId, String key, ShiftTransitionRequest request) {
    return transition(
        driverId,
        warehouseId,
        shiftId,
        key,
        request.operationId(),
        request.expectedVersion(),
        "CLOSE",
        request,
        shift -> {
          if (shift.getClosingDefectId() != null
              && photos.countByDefectIdAndRoleAndState(
                      shift.getClosingDefectId(), ShiftPhotoRole.END_SHIFT_DEFECT, ShiftPhotoState.READY)
                  < 1) {
            throw new ConflictException(
                "A new end-of-shift defect requires a READY end-shift defect photo");
          }
          shift.close(now());
        });
  }

  private TodayShiftResponse transition(
      UUID driverId,
      UUID warehouseId,
      UUID shiftId,
      String key,
      UUID operationId,
      long expectedVersion,
      String type,
      Object request,
      Consumer<DriverShift> mutation) {
    requireIdempotencyKey(key, operationId);
    String hash = fingerprint(request);
    if (replay(operationId, driverId, shiftId, type, hash))
      return currentToday(driverId, warehouseId);
    DriverShift shift = ownedShift(driverId, warehouseId, shiftId);
    requireVersion(shift, expectedVersion);
    try {
      mutation.accept(shift);
    } catch (IllegalStateException exception) {
      throw new ConflictException(exception.getMessage());
    }
    shifts.saveAndFlush(shift);
    recordReceipt(operationId, UUID.fromString(key), shift, type, hash);
    publishOwnerProof(shift, false);
    return currentToday(driverId, warehouseId);
  }

  private DriverShift createShift(
      DriverShiftPlan plan,
      WarehouseIdentityGateway.WarehouseIdentity warehouse,
      OffsetDateTime serverTime) {
    DriverShift shift = new DriverShift();
    shift.assignReviewedId(UUID.randomUUID());
    shift.initialize(plan, warehouse.timeZone(), serverTime);
    shifts.saveAndFlush(shift);
    createInspectionSnapshot(shift, plan, serverTime);
    plan.freeze(shift.getId(), serverTime);
    plans.save(plan);
    publishOwnerProof(shift, true);
    return shift;
  }

  private void createInspectionSnapshot(
      DriverShift shift, DriverShiftPlan plan, OffsetDateTime now) {
    List<TemplateItem> templates =
        jdbc.query(
            """
select template.template_version,item.item_code,item.section_name,item.item_label,item.required,item.sort_order
  from vehicle_inspection_template template
  join vehicle_inspection_template_item item on item.template_id=template.id
 where template.configuration_type=? and template.active=true
 order by item.sort_order
""",
            (result, row) ->
                new TemplateItem(
                    result.getLong("template_version"),
                    result.getString("item_code"),
                    result.getString("section_name"),
                    result.getString("item_label"),
                    result.getBoolean("required"),
                    result.getInt("sort_order")),
            plan.getConfigurationType().name());
    if (templates.isEmpty())
      throw new IllegalStateException("No active vehicle inspection template");
    VehicleInspection inspection = new VehicleInspection();
    inspection.assignReviewedId(UUID.randomUUID());
    inspection.initialize(
        shift.getId(),
        plan.getVehicleId(),
        plan.getConfigurationType(),
        templates.getFirst().templateVersion(),
        now);
    inspections.saveAndFlush(inspection);
    List<VehicleInspectionItemResult> snapshots =
        templates.stream()
            .map(
                template -> {
                  VehicleInspectionItemResult item = new VehicleInspectionItemResult();
                  item.assignReviewedId(UUID.randomUUID());
                  item.initialize(
                      inspection.getId(),
                      shift.getId(),
                      template.code(),
                      template.section(),
                      template.label(),
                      template.required(),
                      template.sortOrder());
                  return item;
                })
            .toList();
    items.saveAll(snapshots);
  }

  private TodayShiftResponse response(
      OffsetDateTime serverTime,
      WarehouseIdentityGateway.WarehouseIdentity warehouse,
      DriverShiftPlan plan,
      DriverShift shift,
      TaskSummaryView taskSummary) {
    VehicleInspection inspection = inspections.findByShiftId(shift.getId()).orElseThrow();
    List<VehicleInspectionItemResult> itemResults =
        items.findAllByInspectionIdOrderBySortOrderAsc(inspection.getId());
    List<VehicleDefect> shiftDefects = defects.findAllByShiftIdOrderByCreatedAtAsc(shift.getId());
    List<DriverShiftPhoto> shiftPhotos = photos.findAllByShiftIdOrderByRecordedAtAsc(shift.getId());
    DailyBriefingView briefing =
        shift.getStatus() == DriverShiftStatus.DAILY_BRIEFING_REQUIRED
            ? new DailyBriefingView(
                warehouse.city(), weather.briefing(warehouse.latitude(), warehouse.longitude()))
            : null;
    return new TodayShiftResponse(
        true,
        serverTime,
        properties.suspiciousOdometerJumpKm(),
        null,
        nextAction(shift.getStatus()),
        shiftView(shift),
        warehouseView(warehouse),
        briefing,
        vehicleView(plan),
        inspectionView(inspection, itemResults, shiftDefects, shiftPhotos),
        taskSummary,
        closingView(shift, plan, shiftPhotos),
        shiftPhotos.stream().map(this::photoView).toList());
  }

  private TodayShiftResponse unavailable(
      OffsetDateTime serverTime,
      WarehouseIdentityGateway.WarehouseIdentity warehouse,
      ZoneId zone) {
    ZonedDateTime local = serverTime.atZoneSameInstant(zone);
    ZonedDateTime next =
        local.toLocalTime().isBefore(properties.dayStart())
            ? local.toLocalDate().atTime(properties.dayStart()).atZone(zone)
            : local.toLocalDate().plusDays(1).atTime(properties.dayStart()).atZone(zone);
    return new TodayShiftResponse(
        true,
        serverTime,
        properties.suspiciousOdometerJumpKm(),
        next.toOffsetDateTime(),
        NextRequiredAction.SHIFT_NOT_AVAILABLE,
        null,
        warehouseView(warehouse),
        null,
        null,
        null,
        null,
        null,
        List.of());
  }

  /** Resolves the work date at the explicit warehouse-local daily availability boundary. */
  static LocalDate resolveWorkDate(OffsetDateTime now, ZoneId zone, java.time.LocalTime dayStart) {
    ZonedDateTime local = now.atZoneSameInstant(zone);
    return local.toLocalTime().isBefore(dayStart)
        ? local.toLocalDate().minusDays(1)
        : local.toLocalDate();
  }

  private ZoneId requireZone(String value) {
    try {
      ZoneId zone = ZoneId.of(value);
      if (!zone.getId().equals(value)) throw new IllegalArgumentException();
      return zone;
    } catch (RuntimeException exception) {
      throw new ExternalServiceException(
          "Warehouse identity contains an invalid timezone", exception);
    }
  }

  private OffsetDateTime now() {
    return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  private DriverShift ownedShift(UUID driverId, UUID warehouseId, UUID shiftId) {
    DriverShift shift =
        shifts
            .findByIdForUpdate(shiftId)
            .orElseThrow(() -> new NotFoundException("Driver shift was not found"));
    if (!driverId.equals(shift.getDriverId()) || !warehouseId.equals(shift.getWarehouseId()))
      throw new NotFoundException("Driver shift was not found in this principal context");
    return shift;
  }

  private void requireVersion(DriverShift shift, long expected) {
    if (shift.getVersion() != expected)
      throw new StaleVersionException("Driver shift version is stale");
  }

  private void requireIdempotencyKey(String key, UUID operationId) {
    UUID parsed;
    try {
      parsed = UUID.fromString(key);
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Idempotency-Key must be a UUID", exception);
    }
    if (operationId != null && !parsed.equals(operationId))
      throw new IllegalArgumentException("Idempotency-Key must equal operationId");
  }

  private boolean replay(UUID operationId, UUID driverId, UUID shiftId, String type, String hash) {
    List<java.util.Map<String, Object>> rows =
        jdbc.queryForList(
            "select shift_id,driver_id,command_type,request_sha256 from"
                + " driver_shift_command_receipt where operation_id=?",
            operationId);
    if (rows.isEmpty()) return false;
    var row = rows.getFirst();
    if (!shiftId.equals(row.get("shift_id"))
        || !driverId.equals(row.get("driver_id"))
        || !type.equals(row.get("command_type"))
        || !hash.equals(row.get("request_sha256")))
      throw new ConflictException("Idempotency key was reused with a changed command");
    return true;
  }

  private void recordReceipt(
      UUID operationId, UUID key, DriverShift shift, String type, String hash) {
    jdbc.update(
        "insert into"
            + " driver_shift_command_receipt(operation_id,idempotency_key,shift_id,driver_id,command_type,request_sha256,recorded_at)"
            + " values (?,?,?,?,?,?,?)",
        operationId,
        key,
        shift.getId(),
        shift.getDriverId(),
        type,
        hash,
        now());
  }

  private String fingerprint(Object value) {
    try {
      return TaskBoardEventStore.sha256(objectMapper.writeValueAsBytes(value));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Driver shift command cannot be serialized", exception);
    }
  }

  private void replace(
      DriverShiftPlan plan,
      PutDriverShiftPlanRequest request,
      String fingerprint,
      OffsetDateTime now) {
    PlannedVehicle vehicle = request.vehicle();
    PlannedTrailer trailer = request.trailer();
    if (vehicle.configurationType() == VehicleConfigurationType.TRUCK_WITH_TRAILER
        && trailer == null)
      throw new IllegalArgumentException("TRUCK_WITH_TRAILER requires a trailer snapshot");
    if (vehicle.configurationType() != VehicleConfigurationType.TRUCK_WITH_TRAILER
        && trailer != null)
      throw new IllegalArgumentException("Trailer snapshot is valid only for TRUCK_WITH_TRAILER");
    plan.replace(
        request.sourcePlanVersion(),
        fingerprint,
        request.warehouseId(),
        request.driverId(),
        request.driverName(),
        request.workDate(),
        vehicle.id(),
        vehicle.name(),
        vehicle.registrationNumber(),
        vehicle.vehicleType(),
        vehicle.manufacturer(),
        vehicle.model(),
        vehicle.configurationType(),
        vehicle.startOdometer(),
        trailer == null ? null : trailer.id(),
        trailer == null ? null : trailer.name(),
        trailer == null ? null : trailer.registrationNumber(),
        request.tripCount(),
        request.routeDistanceMeters(),
        now);
  }

  private DriverShiftPlanResponse planResponse(DriverShiftPlan plan, PlanApplyResult result) {
    return new DriverShiftPlanResponse(
        plan.getSourceShiftId(), plan.getSourcePlanId(), plan.getSourcePlanVersion(), result);
  }

  private TaskSummaryView taskSummary(DriverShift shift, DriverShiftPlan plan) {
    List<java.util.Map<String, Object>> rows =
        jdbc.queryForList(
            """
with driver_tasks as (
  select distinct task.id,task.status
    from board_task task
   where task.warehouse_id=? and task.scheduled_date=? and task.status<>'CANCELLED'
     and (task.planned_driver_worker_id=? or exists (
       select 1 from queue_entry entry
       join task_assignment assignment on assignment.queue_entry_id=entry.id
        where entry.task_id=task.id and assignment.worker_id=? and assignment.status<>'CANCELLED'
     ))
) select count(*) total_count,count(*) filter(where status='ACTIVE') active_count,count(*) filter(where status='DONE') completed_count from driver_tasks
""",
            shift.getWarehouseId(),
            shift.getWorkDate(),
            shift.getDriverId(),
            shift.getDriverId());
    var row = rows.getFirst();
    int total = ((Number) row.get("total_count")).intValue(),
        active = ((Number) row.get("active_count")).intValue(),
        done = ((Number) row.get("completed_count")).intValue();
    return new TaskSummaryView(
        total,
        active,
        done,
        plan.getTripCount(),
        plan.getRouteDistanceMeters(),
        total > 0 && done == total);
  }

  private long priorOdometer(DriverShift shift, DriverShiftPlan plan) {
    Long prior =
        jdbc.queryForObject(
            "select max(end_odometer) from driver_shift where vehicle_id=? and work_date<? and"
                + " end_odometer is not null",
            Long.class,
            shift.getVehicleId(),
            shift.getWorkDate());
    long baseline = plan.getStartOdometer() == null ? 0 : plan.getStartOdometer();
    return prior == null ? baseline : Math.max(baseline, prior);
  }

  private void validatePhotoCorrelation(DriverShift shift, ReserveShiftPhotoRequest request) {
    if (!"image/jpeg".equals(request.contentType()))
      throw new IllegalArgumentException("Driver Shift accepts JPEG photos only");
    DriverShiftStatus requiredState =
        switch (request.role()) {
          case INSPECTION_DEFECT -> DriverShiftStatus.VEHICLE_INSPECTION_REQUIRED;
          case VEHICLE_OVERVIEW -> DriverShiftStatus.END_VEHICLE_CHECK_REQUIRED;
          case END_SHIFT_DEFECT -> DriverShiftStatus.SHIFT_READY_TO_CLOSE;
        };
    if (shift.getStatus() != requiredState)
      throw new ConflictException(
          request.role() + " photo is not accepted while shift state is " + shift.getStatus());
    if (request.role() == ShiftPhotoRole.VEHICLE_OVERVIEW) {
      if (request.defectId() != null || request.inspectionItemId() != null)
        throw new IllegalArgumentException("Vehicle overview must not reference a defect");
      return;
    }
    if (request.defectId() == null)
      throw new IllegalArgumentException("Defect photo requires defectId");
    VehicleDefect defect =
        defects
            .findByIdAndShiftId(request.defectId(), shift.getId())
            .orElseThrow(() -> new NotFoundException("Vehicle defect was not found"));
    if (request.role() == ShiftPhotoRole.INSPECTION_DEFECT
        && !java.util.Objects.equals(defect.getInspectionItemId(), request.inspectionItemId()))
      throw new ConflictException("Inspection defect photo correlation is invalid");
    if (request.role() == ShiftPhotoRole.END_SHIFT_DEFECT) {
      if (request.inspectionItemId() != null)
        throw new IllegalArgumentException(
            "End-shift defect photo cannot reference an inspection item");
      if (!request.defectId().equals(shift.getClosingDefectId()))
        throw new ConflictException("End-shift photo must reference the closing report defect");
    }
  }

  private NextRequiredAction nextAction(DriverShiftStatus status) {
    return switch (status) {
      case DAILY_BRIEFING_REQUIRED -> NextRequiredAction.SHOW_DAILY_BRIEFING;
      case MEDICAL_CHECK_REQUIRED -> NextRequiredAction.COMPLETE_MEDICAL_CHECK;
      case VEHICLE_INSPECTION_REQUIRED -> NextRequiredAction.COMPLETE_VEHICLE_INSPECTION;
      case READY_TO_START -> NextRequiredAction.START_SHIFT;
      case SHIFT_ACTIVE -> NextRequiredAction.SHOW_TASKS;
      case SHIFT_CLOSING -> NextRequiredAction.START_SHIFT_CLOSING;
      case RETURN_TO_WAREHOUSE_REQUIRED -> NextRequiredAction.CONFIRM_WAREHOUSE_RETURN;
      case END_VEHICLE_CHECK_REQUIRED -> NextRequiredAction.COMPLETE_END_OF_SHIFT_REPORT;
      case SHIFT_READY_TO_CLOSE -> NextRequiredAction.CLOSE_SHIFT;
      case SHIFT_CLOSED -> NextRequiredAction.SHIFT_CLOSED;
    };
  }

  private DriverShiftView shiftView(DriverShift shift) {
    MedicalCheckView medical =
        shift.getMedicalCompletedAt() == null
            ? null
            : new MedicalCheckView(
                shift.getDriverId(),
                shift.getId(),
                shift.getWorkDate(),
                shift.getMedicalConfirmationType(),
                shift.getMedicalCompletedAt(),
                shift.getMedicalExternalCheckId(),
                shift.getMedicalDoctorId(),
                shift.getMedicalProvider(),
                shift.getMedicalCheckedAt());
    return new DriverShiftView(
        shift.getId(),
        shift.getVersion(),
        shift.getDriverId(),
        shift.getDriverName(),
        shift.getWarehouseId(),
        shift.getWorkDate(),
        shift.getTimeZone(),
        shift.getStatus(),
        shift.getBriefingSeenAt(),
        medical,
        shift.getVehicleInspectionCompletedAt(),
        shift.getStartedAt(),
        shift.getClosingStartedAt(),
        shift.getReturnedToWarehouseAt(),
        shift.getReturnConfirmationType(),
        shift.getClosedAt());
  }

  private WarehouseView warehouseView(WarehouseIdentityGateway.WarehouseIdentity warehouse) {
    return new WarehouseView(
        warehouse.id(),
        warehouse.name(),
        warehouse.city(),
        warehouse.address(),
        warehouse.latitude(),
        warehouse.longitude(),
        warehouse.timeZone());
  }

  private VehicleView vehicleView(DriverShiftPlan plan) {
    TrailerView trailer =
        plan.getTrailerId() == null
            ? null
            : new TrailerView(
                plan.getTrailerId(), plan.getTrailerName(), plan.getTrailerRegistrationNumber());
    return new VehicleView(
        plan.getVehicleId(),
        plan.getVehicleName(),
        plan.getVehicleRegistrationNumber(),
        plan.getVehicleType(),
        plan.getVehicleManufacturer(),
        plan.getVehicleModel(),
        plan.getConfigurationType(),
        plan.getStartOdometer(),
        trailer);
  }

  private InspectionView inspectionView(
      VehicleInspection inspection,
      List<VehicleInspectionItemResult> values,
      List<VehicleDefect> allDefects,
      List<DriverShiftPhoto> allPhotos) {
    java.util.Map<UUID, VehicleDefect> byId =
        allDefects.stream()
            .collect(java.util.stream.Collectors.toMap(VehicleDefect::getId, value -> value));
    int total = (int) values.stream().filter(VehicleInspectionItemResult::isRequired).count(),
        checked =
            (int)
                values.stream()
                    .filter(
                        value ->
                            value.isRequired()
                                && value.getState() != InspectionItemState.NOT_CHECKED)
                    .count(),
        blocking =
            (int)
                allDefects.stream()
                    .filter(
                        value ->
                            value.getSeverity() == DefectSeverity.BLOCKING
                                && value.getStatus() == DefectStatus.OPEN)
                    .count();
    List<InspectionItemView> projected =
        values.stream()
            .map(
                value ->
                    new InspectionItemView(
                        value.getId(),
                        value.getVersion(),
                        value.getTemplateItemCode(),
                        value.getSection(),
                        value.getLabel(),
                        value.isRequired(),
                        value.getSortOrder(),
                        value.getState(),
                        value.getDefectId() == null
                            ? null
                            : defectView(byId.get(value.getDefectId()), allPhotos)))
            .toList();
    return new InspectionView(
        inspection.getId(),
        inspection.getVersion(),
        inspection.getCompletedAt(),
        total,
        checked,
        blocking,
        projected);
  }

  private VehicleDefectView defectView(VehicleDefect defect, List<DriverShiftPhoto> allPhotos) {
    List<UUID> photoIds =
        allPhotos.stream()
            .filter(photo -> defect.getId().equals(photo.getDefectId()))
            .map(DriverShiftPhoto::getId)
            .toList();
    return new VehicleDefectView(
        defect.getId(),
        defect.getShiftId(),
        defect.getVehicleId(),
        defect.getInspectionItemId(),
        defect.getDescription(),
        defect.getSeverity(),
        defect.getStatus(),
        photoIds,
        defect.getCreatedAt());
  }

  private ClosingReportView closingView(
      DriverShift shift, DriverShiftPlan plan, List<DriverShiftPhoto> allPhotos) {
    if (shift.getClosingReportCompletedAt() == null) return null;
    Long distance =
        plan.getStartOdometer() == null ? null : shift.getEndOdometer() - plan.getStartOdometer();
    return new ClosingReportView(
        shift.getEndVehicleCondition(),
        shift.getEndOdometer(),
        distance,
        shift.getFuelLevelPercent(),
        shift.getClosingDefectId(),
        allPhotos.size(),
        shift.getClosingReportCompletedAt());
  }

  private ShiftPhotoView photoView(DriverShiftPhoto photo) {
    return new ShiftPhotoView(
        photo.getId(),
        photo.getClientReferenceId(),
        photo.getEvidenceId(),
        photo.getRole(),
        photo.getDefectId(),
        photo.getInspectionItemId(),
        photo.getState(),
        photo.getMediaId(),
        photo.getMediaGeneration(),
        photo.getCapturedAt(),
        photo.getContentType(),
        photo.getSizeBytes(),
        photo.getSha256());
  }

  private void publishOwnerProof(DriverShift shift, boolean initialize) {
    boolean active = shift.getStatus() != DriverShiftStatus.SHIFT_CLOSED;
    List<UUID> workers = active ? List.of(shift.getDriverId()) : List.of();
    DriverShiftOwnerProofFact payload =
        new DriverShiftOwnerProofFact(
            "DRIVER_SHIFT", shift.getId(), shift.getWarehouseId(), active, workers, workers);
    if (initialize)
      events.initialize(
          TaskBoardAggregateType.DRIVER_SHIFT_OWNER_PROOF,
          shift.getId(),
          0,
          DRIVER_SHIFT_OWNER_PROOF_CHANGED,
          payload);
    else {
      long version =
          events.lockCurrentVersion(TaskBoardAggregateType.DRIVER_SHIFT_OWNER_PROOF, shift.getId());
      events.append(
          TaskBoardAggregateType.DRIVER_SHIFT_OWNER_PROOF,
          shift.getId(),
          version,
          DRIVER_SHIFT_OWNER_PROOF_CHANGED,
          payload);
    }
  }

  /** Immutable DB template row used only while snapshotting a new inspection. */
  private record TemplateItem(
      long templateVersion,
      String code,
      String section,
      String label,
      boolean required,
      int sortOrder) {}
}
