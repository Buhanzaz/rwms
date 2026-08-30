package dev.buhanzaz.rwms.logistics.order.service;

import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.AppliedPlanningAssignment;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ApplyPlanningAssignmentsRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ApplyPlanningAssignmentsResponse;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningAssignmentStatus;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningAssignmentStatusResponse;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningAssignmentRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDateOption;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverAudienceMode;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftPlanRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftTrailerRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftVehicleRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningRequestFeedResponse;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningRequestResponse;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.RejectedPlanningAssignment;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotKind;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerDeliverySlotStore;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.CreateOrderRentalShipmentRequest;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the private planner hand-off without transferring rental-order or shipment authority to the
 * standalone simulator.
 *
 * <p>Reads export only the minimum route facts. Apply commands are decomposed into existing
 * idempotent rental-shipment commands, so a retry resumes a partially applied batch rather than
 * duplicating documents.
 */
@Service
@RequiredArgsConstructor
public class RentalOrderPlanningIntegrationService {
  private static final UUID PLANNER_SUBJECT =
      UUID.nameUUIDFromBytes("rwms:logistics-planner".getBytes(StandardCharsets.UTF_8));
  private static final int MAX_FEED_DAYS = 31;

  private final RentalOrderRepository orders;
  private final RentalOrderReadService reads;
  private final LogisticsDocumentLineRepository documentLines;
  private final LogisticsDocumentRepository documents;
  private final DriverLogisticsTaskRepository driverTasks;
  private final RentalOrderService rentalOrders;
  private final LogisticsWarehouseLifecycle warehouseLifecycle;
  private final LogisticsDependencyGateway dependencies;
  private final CustomerDeliverySlotStore customerDeliverySlots;

  /** Returns saved orders with at least one still-unplanned cabin and an eligible requested date. */
  public PlanningRequestFeedResponse feed(
      UUID warehouseId, LocalDate dateFrom, LocalDate dateTo) {
    requireFeedRange(warehouseId, dateFrom, dateTo);
    OffsetDateTime generatedAt = now();
    String timeZone =
        dependencies.warehouseTimeZoneAt(warehouseId, generatedAt).timeZone();
    ZoneId.of(timeZone);
    List<RentalOrder> candidates =
        orders.findAllPlanningCandidates(warehouseId, RentalOrderStatus.SAVED);
    Map<UUID, CustomerDeliverySlot> slotsByOrder =
        customerDeliverySlots.confirmedForOrders(
            candidates.stream().map(RentalOrder::getId).toList());
    List<PlanningRequestResponse> result = new ArrayList<>();
    for (RentalOrder order : candidates) {
      List<LocalDate> approvedDates =
          order.getDesiredDeliveryWindows().stream()
              .filter(window -> window.getStartDate().equals(window.getEndDate()))
              .map(window -> window.getStartDate())
              .distinct()
              .sorted()
              .toList();
      List<PlanningDateOption> options = new ArrayList<>();
      CustomerDeliverySlot customerSlot = slotsByOrder.get(order.getId());
      for (int index = 0; index < approvedDates.size(); index++) {
        LocalDate date = approvedDates.get(index);
        if (!date.isBefore(dateFrom) && !date.isAfter(dateTo)) {
          if (customerSlot != null && customerSlot.getDeliveryDate().equals(date)) {
            boolean fixedWindow = customerSlot.getKind() == CustomerDeliverySlotKind.FIXED_WINDOW;
            options.add(
                new PlanningDateOption(
                    date,
                    index,
                    fixedWindow,
                    fixedWindow ? customerSlot.getWindowStart() : null,
                    fixedWindow ? customerSlot.getWindowEnd() : null,
                    customerSlot.getTravelZoneHours()));
          } else {
            options.add(new PlanningDateOption(date, index, approvedDates.size() == 1));
          }
        }
      }
      if (options.isEmpty()) continue;
      List<UUID> unitIds =
          reads.readUnits(order).stream()
              .map(LogisticsDependencyGateway.OrderUnitReservation::unitId)
              .sorted()
              .toList();
      if (unitIds.isEmpty()) continue;
      Set<UUID> assigned =
          Set.copyOf(documentLines.findAssignedRentalShipmentAssetIds(order.getId(), unitIds));
      List<UUID> available = unitIds.stream().filter(id -> !assigned.contains(id)).toList();
      if (available.isEmpty()) continue;
      Boolean trailerAccessAllowed =
          customerSlot == null ? null : customerSlot.getSiteCabinCapacity() >= 2;
      result.add(
          new PlanningRequestResponse(
              order.getId(),
              order.getVersion(),
              PlanningRequestRevision.sha256(
                  order, available, options, trailerAccessAllowed),
              order.getOrderNumber(),
              order.getClient().getDisplayName(),
              order.getDeliveryAddress(),
              order.getLatitude(),
              order.getLongitude(),
              available.size(),
              available,
              options,
              trailerAccessAllowed,
              order.getCreatedAt()));
    }
    return new PlanningRequestFeedResponse(
        warehouseId, timeZone, generatedAt, List.copyOf(result));
  }

  /**
   * Returns current ownership of planner-created shipment parts without exposing ordinary manual
   * logistics documents or querying task-board from the read path.
   */
  @Transactional(readOnly = true)
  public PlanningAssignmentStatusResponse assignmentStatuses(
      UUID warehouseId, LocalDate date) {
    if (warehouseId == null || date == null) {
      throw new IllegalArgumentException("Planning assignment status identity is required");
    }
    List<LogisticsDocument> plannerDocuments =
        documents
            .findAllByDocumentTypeAndWarehouseIdAndScheduledDateAndRequestedBySubjectIdOrderByCreatedAtAscIdAsc(
                LogisticsDocumentType.SHIPMENT, warehouseId, date, PLANNER_SUBJECT);
    if (plannerDocuments.isEmpty()) {
      return new PlanningAssignmentStatusResponse(warehouseId, date, List.of());
    }

    List<UUID> documentIds = plannerDocuments.stream().map(LogisticsDocument::getId).toList();
    Map<UUID, List<LogisticsDocumentLine>> linesByDocument = new LinkedHashMap<>();
    for (LogisticsDocumentLine line : documentLines.findAllByDocumentIdIn(documentIds)) {
      linesByDocument
          .computeIfAbsent(line.getDocument().getId(), ignored -> new ArrayList<>())
          .add(line);
    }
    Map<UUID, List<DriverLogisticsTask>> tasksByDocument = new LinkedHashMap<>();
    for (DriverLogisticsTask task :
        driverTasks.findAllBySourceTypeAndSourceIdIn(
            DriverTaskSourceType.LOGISTICS_DOCUMENT, documentIds)) {
      tasksByDocument.computeIfAbsent(task.getSourceId(), ignored -> new ArrayList<>()).add(task);
    }

    List<PlanningAssignmentStatus> assignments = new ArrayList<>(plannerDocuments.size());
    for (LogisticsDocument document : plannerDocuments) {
      List<LogisticsDocumentLine> membership =
          linesByDocument.getOrDefault(document.getId(), List.of());
      List<DriverLogisticsTask> projections =
          tasksByDocument.getOrDefault(document.getId(), List.of());
      if (document.getRentalOrderId() == null || membership.isEmpty() || projections.size() != 1) {
        throw new LogisticsConflictException(
            "Planner shipment has incomplete or ambiguous driver-task projection");
      }
      DriverLogisticsTask task = projections.getFirst();
      if (task.getKind() != DriverTaskKind.SHIPMENT
          || !warehouseId.equals(task.getWarehouseId())
          || !date.equals(task.getScheduledDate())) {
        throw new LogisticsConflictException(
            "Planner shipment driver-task projection does not match its document");
      }
      PlanningDriverAudienceMode audience = planningAudience(task.getDriverAudienceMode());
      assignments.add(
          new PlanningAssignmentStatus(
              document.getRentalOrderId(),
              document.getId(),
              document.getScheduledDate(),
              membership.stream().map(LogisticsDocumentLine::getAssetId).toList(),
              audience,
              task.getPlannedDriverWorkerId(),
              task.getPlannedDriverNameSnapshot(),
              task.getState().name()));
    }
    return new PlanningAssignmentStatusResponse(
        warehouseId, date, List.copyOf(assignments));
  }

  /**
   * Applies every valid assignment independently and reports domain rejections without hiding a
   * successfully created shipment from the caller.
   */
  public ApplyPlanningAssignmentsResponse apply(
      UUID batchIdempotencyKey, ApplyPlanningAssignmentsRequest request) {
    if (batchIdempotencyKey == null
        || request == null
        || request.warehouseId() == null
        || request.planId() == null
        || request.planVersion() == null
        || request.assignments() == null) {
      throw new IllegalArgumentException("Planner command identity is required");
    }
    requireUniqueAssignments(request.assignments());
    requireValidShiftPlans(request);
    OffsetDateTime generatedAt = now();
    String timeZone =
        dependencies.warehouseTimeZoneAt(request.warehouseId(), generatedAt).timeZone();
    LocalDate today = generatedAt.toInstant().atZone(ZoneId.of(timeZone)).toLocalDate();
    registerShiftPlans(request);
    List<AppliedPlanningAssignment> applied = new ArrayList<>();
    List<RejectedPlanningAssignment> rejected = new ArrayList<>();
    for (PlanningAssignmentRequest assignment : request.assignments()) {
      UUID commandKey = commandKey(batchIdempotencyKey, request, assignment);
      CreateOrderRentalShipmentRequest shipmentRequest =
          new CreateOrderRentalShipmentRequest(
              assignment.expectedOrderVersion(),
              assignment.driverName().trim(),
              assignment.driverWorkerId(),
              assignment.scheduledDate(),
              List.copyOf(assignment.unitIds()),
              assignment.driverAudienceMode() == PlanningDriverAudienceMode.WAREHOUSE_DRIVERS);
      try {
        var replay =
            rentalOrders.replayRentalShipment(
                plannerActor(), assignment.orderId(), commandKey, shipmentRequest);
        if (replay != null) {
          applied.add(
              new AppliedPlanningAssignment(
                  assignment.orderId(), replay.response().id(), true));
          continue;
        }
        RentalOrder order = validateAssignment(request.warehouseId(), today, assignment);
        var admission =
            warehouseLifecycle.prepareDocument(
                PLANNER_SUBJECT,
                "CREATE_RENTAL_ORDER_SHIPMENT",
                commandKey,
                List.of(
                    new AdmissionRequirement(
                        request.warehouseId(), WarehouseOperationDirection.OUTGOING)));
        var result =
            rentalOrders.createRentalShipment(
                plannerActor(),
                order.getId(),
                commandKey,
                deterministic("planning-correlation:" + commandKey),
                shipmentRequest,
                admission);
        applied.add(
            new AppliedPlanningAssignment(
                order.getId(), result.response().id(), result.replayed()));
      } catch (OrderProblemException exception) {
        if (exception.status().is5xxServerError()) throw exception;
        rejected.add(
            new RejectedPlanningAssignment(
                assignment.orderId(), exception.code(), exception.getMessage()));
      } catch (LogisticsConflictException exception) {
        rejected.add(
            new RejectedPlanningAssignment(
                assignment.orderId(), "LOGISTICS_CONFLICT", exception.getMessage()));
      }
    }
    return new ApplyPlanningAssignmentsResponse(List.copyOf(applied), List.copyOf(rejected));
  }

  private RentalOrder validateAssignment(
      UUID warehouseId, LocalDate today, PlanningAssignmentRequest assignment) {
    RentalOrder order =
        orders
            .findPlanningCandidateById(assignment.orderId())
            .orElseThrow(
                () ->
                    new OrderProblemException(
                        HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Заказ не найден"));
    if (!warehouseId.equals(order.getWarehouseId())) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "ORDER_WAREHOUSE_MISMATCH",
          "Заказ относится к другому складу");
    }
    if (order.getStatus() != RentalOrderStatus.SAVED) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "ORDER_NOT_SHIPPABLE",
          "Отгрузка доступна только для сохранённого заказа");
    }
    if (order.getVersion() != assignment.expectedOrderVersion()) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "ORDER_VERSION_CONFLICT",
          "Заказ изменился после синхронизации с планировщиком");
    }
    requireDriverAudience(today, assignment);
    if (assignment.driverAudienceMode() == PlanningDriverAudienceMode.ASSIGNED_DRIVER
        && assignment.scheduledDate().isBefore(today.plusDays(2))) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "PLANNING_DATE_LOCKED",
          "Автоплан не меняет готовую логистику на сегодня и завтра");
    }
    boolean acceptedDate =
        order.getDesiredDeliveryWindows().stream()
            .anyMatch(
                window ->
                    !assignment.scheduledDate().isBefore(window.getStartDate())
                        && !assignment.scheduledDate().isAfter(window.getEndDate()));
    if (!acceptedDate) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "DELIVERY_DATE_NOT_ACCEPTED",
          "Клиент не подтвердил выбранную планировщиком дату");
    }
    List<UUID> activeUnitIds =
        reads.readUnits(order).stream()
            .map(LogisticsDependencyGateway.OrderUnitReservation::unitId)
            .toList();
    Set<UUID> selected = Set.copyOf(assignment.unitIds());
    if (selected.size() != assignment.unitIds().size()
        || !Set.copyOf(activeUnitIds).containsAll(selected)) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "ORDER_UNITS_CHANGED",
          "Состав бытовок заказа изменился после синхронизации");
    }
    Set<UUID> alreadyAssigned =
        Set.copyOf(documentLines.findAssignedRentalShipmentAssetIds(order.getId(), activeUnitIds));
    if (selected.stream().anyMatch(alreadyAssigned::contains)) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "ORDER_UNIT_ALREADY_PLANNED",
          "Одна из бытовок уже включена в другую отгрузку");
    }
    return order;
  }

  private static void requireDriverAudience(
      LocalDate today, PlanningAssignmentRequest assignment) {
    if (assignment.driverAudienceMode() == PlanningDriverAudienceMode.ASSIGNED_DRIVER) {
      if (assignment.driverWorkerId() == null) {
        throw new OrderProblemException(
            HttpStatus.CONFLICT,
            "DRIVER_REQUIRED",
            "Для назначенной доставки требуется водитель RWMS");
      }
      return;
    }
    if (assignment.driverWorkerId() != null) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "SHARED_TASK_DRIVER_CONFLICT",
          "Общая доставка не может одновременно иметь назначенного водителя");
    }
    if (!assignment.scheduledDate().isAfter(today)) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "SHARED_TASK_REQUIRES_FUTURE_DATE",
          "В общий пул водителей можно опубликовать только будущую доставку");
    }
  }

  private static PlanningDriverAudienceMode planningAudience(
      DriverTaskAudienceMode audienceMode) {
    return switch (audienceMode) {
      case ASSIGNED_DRIVER -> PlanningDriverAudienceMode.ASSIGNED_DRIVER;
      case WAREHOUSE_DRIVERS -> PlanningDriverAudienceMode.WAREHOUSE_DRIVERS;
      case UNASSIGNED ->
          throw new LogisticsConflictException(
              "Planner shipment is no longer published to a driver audience");
    };
  }

  private static void requireFeedRange(
      UUID warehouseId, LocalDate dateFrom, LocalDate dateTo) {
    if (warehouseId == null || dateFrom == null || dateTo == null || dateFrom.isAfter(dateTo)) {
      throw new IllegalArgumentException("Planning feed range is invalid");
    }
    if (ChronoUnit.DAYS.between(dateFrom, dateTo) >= MAX_FEED_DAYS) {
      throw new IllegalArgumentException("Planning feed range cannot exceed 31 days");
    }
  }

  private static void requireUniqueAssignments(List<PlanningAssignmentRequest> assignments) {
    if (assignments == null) throw new IllegalArgumentException("Assignments are required");
    Set<UUID> unitIds = new HashSet<>();
    for (PlanningAssignmentRequest assignment : assignments) {
      if (assignment == null || assignment.unitIds().stream().anyMatch(id -> !unitIds.add(id))) {
        throw new IllegalArgumentException("A cabin can be assigned only once in one plan batch");
      }
    }
  }

  private static void requireValidShiftPlans(ApplyPlanningAssignmentsRequest request) {
    Set<UUID> sourceShiftIds = new HashSet<>();
    Set<String> driverWorkdays = new HashSet<>();
    for (PlanningDriverShiftPlanRequest plan : request.driverShiftPlans()) {
      if (plan == null
          || plan.sourceShiftId() == null
          || plan.sourcePlanId() == null
          || plan.sourcePlanVersion() == null
          || plan.warehouseId() == null
          || plan.driverId() == null
          || plan.workDate() == null
          || plan.vehicle() == null) {
        throw new IllegalArgumentException("Driver shift plan identity is required");
      }
      if (!sourceShiftIds.add(plan.sourceShiftId())) {
        throw new IllegalArgumentException("A source driver shift can occur only once in a batch");
      }
      if (!driverWorkdays.add(plan.driverId() + ":" + plan.workDate())) {
        throw new IllegalArgumentException(
            "A driver can have only one shift plan for one work date");
      }
      if (!request.warehouseId().equals(plan.warehouseId())
          || !request.planId().equals(plan.sourcePlanId())
          || !request.planVersion().equals(plan.sourcePlanVersion())) {
        throw new IllegalArgumentException(
            "Driver shift plans must belong to the exact planner command");
      }
    }
  }

  private void registerShiftPlans(ApplyPlanningAssignmentsRequest request) {
    for (PlanningDriverShiftPlanRequest plan : request.driverShiftPlans()) {
      dependencies.registerDriverShiftPlan(
          driverShiftPlanKey(plan),
          plan.sourceShiftId(),
          new LogisticsDependencyGateway.DriverShiftPlanSnapshot(
              plan.sourcePlanId(),
              plan.sourcePlanVersion(),
              plan.warehouseId(),
              plan.driverId(),
              plan.driverName(),
              plan.workDate(),
              vehicleSnapshot(plan.vehicle()),
              trailerSnapshot(plan.trailer()),
              plan.tripCount(),
              plan.routeDistanceMeters()));
    }
  }

  private static LogisticsDependencyGateway.DriverShiftPlanVehicle vehicleSnapshot(
      PlanningDriverShiftVehicleRequest vehicle) {
    return new LogisticsDependencyGateway.DriverShiftPlanVehicle(
        vehicle.id(),
        vehicle.name(),
        vehicle.registrationNumber(),
        vehicle.vehicleType(),
        vehicle.manufacturer(),
        vehicle.model(),
        vehicle.configurationType().name(),
        vehicle.startOdometer());
  }

  private static LogisticsDependencyGateway.DriverShiftPlanTrailer trailerSnapshot(
      PlanningDriverShiftTrailerRequest trailer) {
    if (trailer == null) return null;
    return new LogisticsDependencyGateway.DriverShiftPlanTrailer(
        trailer.id(), trailer.name(), trailer.registrationNumber());
  }

  private static UUID driverShiftPlanKey(PlanningDriverShiftPlanRequest plan) {
    return deterministic(
        "planning-driver-shift:"
            + plan.sourceShiftId()
            + ":"
            + plan.sourcePlanId()
            + ":"
            + plan.sourcePlanVersion());
  }

  private static OrderActor plannerActor() {
    return new OrderActor(
        PLANNER_SUBJECT,
        "SYSTEM_ADMIN",
        "logistics-planner",
        Set.of(),
        Set.of(),
        true,
        false,
        true,
        true);
  }

  private static UUID commandKey(
      UUID batchIdempotencyKey,
      ApplyPlanningAssignmentsRequest request,
      PlanningAssignmentRequest assignment) {
    String sortedUnitIds =
        assignment.unitIds().stream()
            .sorted()
            .map(UUID::toString)
            .reduce((left, right) -> left + "," + right)
            .orElseThrow();
    return deterministic(
        "planning-assignment:"
            + batchIdempotencyKey
            + ":"
            + request.planId()
            + ":"
            + request.planVersion()
            + ":"
            + assignment.orderId()
            + ":"
            + assignment.scheduledDate()
            + ":"
            + assignment.driverAudienceMode()
            + ":"
            + sortedUnitIds);
  }

  private static UUID deterministic(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }
}
