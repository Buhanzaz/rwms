package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.domain.CustomerDeliveryPurpose;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.AppliedPlanningAssignment;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ApplyPlanningAssignmentsRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ApplyPlanningAssignmentsResponse;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningAssignmentRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningAssignmentStatus;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningAssignmentStatusResponse;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningAssignmentType;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDateOption;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverAudienceMode;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftPlanRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftRouteOperationKind;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftRouteOperationRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftTrailerRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftVehicleRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningRequestFeedResponse;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningRequestResponse;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ReplacePlanningAssignmentsRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningUnitReservation;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.RejectedPlanningAssignment;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotKind;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerDeliverySlotStore;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.PlanningReplacementShift;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.CreateOrderRentalShipmentRequest;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentState;
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
import java.util.HashMap;
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
  private final TransferRouteCargoEnricher transferRouteCargoEnricher;

  /**
   * Returns saved orders with at least one still-unplanned cabin and an eligible requested date.
   * Every cabin retains its owner-authoritative physical source independently from the order's
   * service warehouse.
   */
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
      List<LogisticsDependencyGateway.OrderUnitReservation> reservations =
          reads.readUnitsForShipment(order).stream()
              .sorted(
                  java.util.Comparator.comparing(
                      LogisticsDependencyGateway.OrderUnitReservation::unitId))
              .toList();
      List<UUID> unitIds =
          reservations.stream()
              .map(LogisticsDependencyGateway.OrderUnitReservation::unitId)
              .toList();
      if (unitIds.isEmpty()) continue;
      if (Set.copyOf(unitIds).size() != unitIds.size()) {
        throw new LogisticsConflictException(
            "Order reservation feed contains duplicate cabin identities");
      }
      Set<UUID> assigned =
          Set.copyOf(documentLines.findAssignedRentalShipmentAssetIds(order.getId(), unitIds));
      List<PlanningUnitReservation> availableReservations =
          reservations.stream()
              .filter(reservation -> !assigned.contains(reservation.unitId()))
              .map(
                  reservation ->
                      new PlanningUnitReservation(
                          reservation.unitId(), reservation.warehouseId()))
              .toList();
      if (availableReservations.isEmpty()) continue;
      List<UUID> available =
          availableReservations.stream().map(PlanningUnitReservation::unitId).toList();
      Boolean trailerAccessAllowed =
          customerSlot == null ? null : customerSlot.getSiteCabinCapacity() >= 2;
      Long deliveryPriceRubles =
          customerSlot == null ? null : customerSlot.getDeliveryPriceRubles();
      Integer priceIsochroneMinutes =
          customerSlot == null ? null : customerSlot.getPriceIsochroneMinutes();
      String contactName =
          order.getClient().getContactPerson() == null
              ? order.getClient().getDisplayName()
              : order.getClient().getContactPerson();
      String contactPhone =
          order.getContactPhone() == null
              ? order.getClient().getPhone()
              : order.getContactPhone();
      result.add(
          new PlanningRequestResponse(
              order.getId(),
              order.getVersion(),
              PlanningRequestRevision.sha256(
                  order,
                  availableReservations,
                  options,
                  trailerAccessAllowed,
                  deliveryPriceRubles,
                  priceIsochroneMinutes,
                  order.getClient().getClientType(),
                  contactName,
                  contactPhone),
              CustomerDeliveryPurpose.RENTAL_DELIVERY,
              order.getOrderNumber(),
              order.getClient().getDisplayName(),
              order.getClient().getClientType(),
              contactName,
              contactPhone,
              order.getDeliveryAddress(),
              order.getLatitude(),
              order.getLongitude(),
              available.size(),
              available,
              availableReservations,
              options,
              trailerAccessAllowed,
              deliveryPriceRubles,
              priceIsochroneMinutes,
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
    Map<UUID, RentalOrder> ordersById =
        orders.findAllWithClientByIdIn(
                plannerDocuments.stream().map(LogisticsDocument::getRentalOrderId).toList())
            .stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    RentalOrder::getId, java.util.function.Function.identity()));

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
      RentalOrder order = ordersById.get(document.getRentalOrderId());
      if (task.getKind() != DriverTaskKind.SHIPMENT
          || !warehouseId.equals(task.getWarehouseId())
          || !date.equals(task.getScheduledDate())
          || order == null) {
        throw new LogisticsConflictException(
            "Planner shipment driver-task projection does not match its document");
      }
      PlanningDriverAudienceMode audience = planningAudience(task.getDriverAudienceMode());
      assignments.add(
            new PlanningAssignmentStatus(
                document.getRentalOrderId(),
                order.getVersion(),
                document.getId(),
              task.getExternalTaskId(),
              task.getVersion(),
              task.getSourcePlanId(),
              task.getPlannerVisibleSourcePlanVersion(),
              document.getScheduledDate(),
              membership.stream().map(LogisticsDocumentLine::getAssetId).toList(),
              audience,
              task.getPlannedDriverWorkerId(),
              task.getPlannedDriverNameSnapshot(),
              task.getState().name()));
    }
    assignments.stream()
        .filter(assignment -> assignment.sourcePlanId() != null)
        .collect(
            java.util.stream.Collectors.groupingBy(
                PlanningAssignmentStatus::sourcePlanId,
                java.util.stream.Collectors.mapping(
                    PlanningAssignmentStatus::sourcePlanVersion,
                    java.util.stream.Collectors.toSet())))
        .forEach(
            (sourcePlanId, versions) -> {
              if (versions.size() != 1 || versions.contains(null)) {
                throw new LogisticsConflictException(
                    "Planner lineage contains mixed source revisions");
              }
            });
    return new PlanningAssignmentStatusResponse(
        warehouseId, date, List.copyOf(assignments));
  }

  /**
   * Rejects invalid new-plan payment and schedule facts before registering driver snapshots. All
   * relevant snapshots register before any shipment create/replay; later mutable assignment
   * conflicts retain independent successful parts. No local transaction spans registration.
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
    request = transferRouteCargoEnricher.enrich(request);
    requireValidShiftPlans(request);
    OffsetDateTime generatedAt = now();
    Map<UUID, LocalDate> warehouseDates = new HashMap<>();
    warehouseDates.put(request.warehouseId(), warehouseToday(request.warehouseId(), generatedAt));
    requireAuthorizedShiftPlans(request);
    requireAdmissiblePlan(batchIdempotencyKey, request, generatedAt, warehouseDates);
    registerShiftPlans(request);
    List<AppliedPlanningAssignment> applied = new ArrayList<>();
    List<RejectedPlanningAssignment> rejected = new ArrayList<>();
    for (PlanningAssignmentRequest assignment : request.assignments()) {
      UUID serviceWarehouseId = effectiveServiceWarehouse(request, assignment);
      UUID commandKey = commandKey(batchIdempotencyKey, request, assignment);
      OrderActor plannerActor = plannerActor();
      CreateOrderRentalShipmentRequest shipmentRequest = shipmentRequest(assignment);
      try {
        var replay =
            rentalOrders.replayRentalShipment(
                plannerActor, assignment.orderId(), commandKey, shipmentRequest);
        if (replay != null) {
          TaskIdentity taskIdentity =
              exactExternalTaskIdentity(replay.response().id(), request, assignment);
          applied.add(
              new AppliedPlanningAssignment(
                  assignment.orderId(),
                  replay.response().id(),
                  taskIdentity.externalTaskId(),
                  taskIdentity.version(),
                  true,
                  currentOrderVersion(assignment.orderId())));
          continue;
        }
        LocalDate serviceToday =
            warehouseDates.computeIfAbsent(
                serviceWarehouseId, id -> warehouseToday(id, generatedAt));
        RentalOrder order = validateAssignment(serviceWarehouseId, serviceToday, assignment);
        requireAuthorizedSupport(
            request.warehouseId(), serviceWarehouseId, assignment.scheduledDate());
        UUID inventorySourceWarehouseId =
            rentalOrders.rentalShipmentAdmissionWarehouse(
                plannerActor,
                order.getId(),
                assignment.scheduledDate(),
                assignment.inventorySourceWarehouseId());
        var admission =
            warehouseLifecycle.prepareDocument(
                PLANNER_SUBJECT,
                "CREATE_RENTAL_ORDER_SHIPMENT",
                commandKey,
                List.of(
                    new AdmissionRequirement(
                        inventorySourceWarehouseId, WarehouseOperationDirection.OUTGOING)));
        var result =
            rentalOrders.createRentalShipment(
                plannerActor,
                order.getId(),
                commandKey,
                deterministic("planning-correlation:" + commandKey),
                shipmentRequest,
                admission);
        TaskIdentity taskIdentity =
            exactExternalTaskIdentity(result.response().id(), request, assignment);
        applied.add(
            new AppliedPlanningAssignment(
                order.getId(),
                result.response().id(),
                taskIdentity.externalTaskId(),
                taskIdentity.version(),
                result.replayed(),
                currentOrderVersion(order.getId())));
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

  /** Hard plan admission is read-only; committed receipts bypass mutable payment and date checks. */
  private void requireAdmissiblePlan(
      UUID batchIdempotencyKey,
      ApplyPlanningAssignmentsRequest request,
      OffsetDateTime generatedAt,
      Map<UUID, LocalDate> warehouseDates) {
    for (PlanningAssignmentRequest assignment : request.assignments()) {
      if (rentalOrders.hasRentalShipmentReceipt(
          plannerActor(),
          assignment.orderId(),
          commandKey(batchIdempotencyKey, request, assignment),
          shipmentRequest(assignment))) {
        continue;
      }
      RentalOrder order =
          orders
              .findPlanningCandidateById(assignment.orderId())
              .orElseThrow(
                  () ->
                      new OrderProblemException(
                          HttpStatus.CONFLICT, "ORDER_NOT_FOUND", "Заказ не найден"));
      requirePayment(order);
      UUID serviceWarehouseId = effectiveServiceWarehouse(request, assignment);
      requireAssignmentSchedule(
          warehouseDates.computeIfAbsent(serviceWarehouseId, id -> warehouseToday(id, generatedAt)),
          assignment);
      requireProvisionalEta(serviceWarehouseId, assignment);
    }
  }

  private static CreateOrderRentalShipmentRequest shipmentRequest(
      PlanningAssignmentRequest assignment) {
    return new CreateOrderRentalShipmentRequest(
        assignment.expectedOrderVersion(),
        assignment.driverName().trim(),
        assignment.driverWorkerId(),
        assignment.scheduledDate(),
        List.copyOf(assignment.unitIds()),
        assignment.driverAudienceMode() == PlanningDriverAudienceMode.WAREHOUSE_DRIVERS,
        assignment.inventorySourceWarehouseId());
  }

  /**
   * Resolves the durable document-owned driver task produced by the existing shipment workflow. The
   * value is read from the local aggregate and is never derived from a plan or document UUID.
   */
  private TaskIdentity exactExternalTaskIdentity(
      UUID documentId,
      ApplyPlanningAssignmentsRequest request,
      PlanningAssignmentRequest assignment) {
    List<DriverLogisticsTask> tasks =
        driverTasks.findAllBySourceTypeAndSourceIdIn(
            DriverTaskSourceType.LOGISTICS_DOCUMENT, List.of(documentId));
    List<DriverLogisticsTask> shipments =
        tasks.stream().filter(task -> task.getKind() == DriverTaskKind.SHIPMENT).toList();
    if (shipments.size() != 1 || shipments.getFirst().getExternalTaskId() == null) {
      throw new LogisticsConflictException(
          "Planner shipment has no unique durable external driver-task identity");
    }
    DriverLogisticsTask task = shipments.getFirst();
    if (task.getSourcePlanId() == null
        && task.getState() == DriverTaskState.REGISTERING
        && task.getTaskBoardTaskId() == null) {
      task.bindPlannerLineage(
          request.planId(),
          request.planVersion(),
          request.warehouseId(),
          assignment.scheduledDate());
    } else if (task.getSourcePlanId() != null) {
      task.bindPlannerLineage(
          request.planId(),
          request.planVersion(),
          request.warehouseId(),
          assignment.scheduledDate());
    }
    if (assignment.provisionalEta() != null) {
      task.setProvisionalEta(
          assignment.provisionalEta(), request.planId(), request.planVersion());
    }
    driverTasks.saveAndFlush(task);
    return new TaskIdentity(task.getExternalTaskId(), task.getVersion());
  }

  private long currentOrderVersion(UUID orderId) {
    return orders
        .findPlanningCandidateById(orderId)
        .orElseThrow(
            () ->
                new LogisticsConflictException(
                    "Planner shipment order disappeared after publication"))
        .getVersion();
  }

  /** Durable driver-task identity and optimistic fence returned to the planner. */
  private record TaskIdentity(UUID externalTaskId, long version) {}

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
    requirePayment(order);
    requireAssignmentSchedule(today, assignment);
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
    List<LogisticsDependencyGateway.OrderUnitReservation> activeReservations =
        reads.readUnitsForShipment(order);
    List<UUID> activeUnitIds =
        activeReservations.stream()
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
    if (assignment.inventorySourceWarehouseId() != null
        && activeReservations.stream()
            .filter(reservation -> selected.contains(reservation.unitId()))
            .anyMatch(
                reservation ->
                    !assignment.inventorySourceWarehouseId().equals(reservation.warehouseId()))) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "INVENTORY_SOURCE_WAREHOUSE_MISMATCH",
          "Выбранные бытовки физически находятся на другом складе-источнике");
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

  private static void requirePayment(RentalOrder order) {
    if (!RentalOrderPaymentState.allowsFulfillment(order.getPaymentState())) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "ORDER_PAYMENT_REQUIRED",
          "Сначала подтвердите оплату бытовок и мебели");
    }
  }

  private static void requireAssignmentSchedule(
      LocalDate today, PlanningAssignmentRequest assignment) {
    requireAssignmentType(assignment);
    requireDriverAudience(today, assignment);
    if (assignment.assignmentType() == PlanningAssignmentType.ROUTE_PLAN
        && assignment.driverAudienceMode() == PlanningDriverAudienceMode.ASSIGNED_DRIVER
        && assignment.scheduledDate().isBefore(today.plusDays(2))) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "PLANNING_DATE_LOCKED",
          "Автоплан не меняет готовую логистику на сегодня и завтра");
    }
  }

  private static void requireAssignmentType(PlanningAssignmentRequest assignment) {
    if (assignment.assignmentType() == PlanningAssignmentType.CONTRACTOR_HANDOFF
        && assignment.driverAudienceMode() != PlanningDriverAudienceMode.ASSIGNED_DRIVER) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CONTRACTOR_HANDOFF_REQUIRES_DRIVER",
          "Передача наёмному водителю требует конкретного исполнителя");
    }
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

  private void requireProvisionalEta(
      UUID serviceWarehouseId, PlanningAssignmentRequest assignment) {
    if (assignment.provisionalEta() == null) return;
    String timeZone =
        dependencies
            .warehouseTimeZoneAt(serviceWarehouseId, assignment.provisionalEta())
            .timeZone();
    LocalDate etaDate =
        assignment.provisionalEta().toInstant().atZone(ZoneId.of(timeZone)).toLocalDate();
    if (!assignment.scheduledDate().equals(etaDate)) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "PROVISIONAL_ETA_DATE_MISMATCH",
          "Предварительное время прибытия относится к другому дню");
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
      Set<UUID> serviceWarehouseIds =
          request.assignments().stream()
              .filter(
                  assignment ->
                      assignment.assignmentType() == PlanningAssignmentType.ROUTE_PLAN
                          && assignment.driverAudienceMode()
                              == PlanningDriverAudienceMode.ASSIGNED_DRIVER
                          && plan.driverId().equals(assignment.driverWorkerId())
                          && plan.workDate().equals(assignment.scheduledDate()))
              .map(assignment -> effectiveServiceWarehouse(request, assignment))
              .collect(java.util.stream.Collectors.toUnmodifiableSet());
      if (serviceWarehouseIds.size() > 1) {
        throw new IllegalArgumentException(
            "One driver shift route can serve only one warehouse");
      }
      UUID serviceWarehouseId =
          serviceWarehouseIds.isEmpty()
              ? routeServiceWarehouse(plan)
              : serviceWarehouseIds.iterator().next();
      requireValidRouteOperations(plan, serviceWarehouseId);
    }
  }

  private static void requireValidRouteOperations(
      PlanningDriverShiftPlanRequest plan, UUID serviceWarehouseId) {
    List<PlanningDriverShiftRouteOperationRequest> operations = plan.operations();
    int priorLoad = 0;
    OffsetDateTime priorEnd = null;
    for (int index = 0; index < operations.size(); index++) {
      PlanningDriverShiftRouteOperationRequest operation = operations.get(index);
      if (operation == null
          || operation.sequence() != index + 1
          || operation.kind() == null
          || operation.locationLabel() == null
          || operation.locationLabel().isBlank()
          || operation.plannedArrival() == null
          || operation.plannedDeparture() == null) {
        throw new IllegalArgumentException(
            "Driver route operations must form one contiguous executable order");
      }
      boolean positioning =
          switch (operation.kind()) {
            case INBOUND_POSITIONING, RETURN_POSITIONING -> true;
            default -> false;
          };
      OffsetDateTime operationStart =
          positioning ? operation.plannedDeparture() : operation.plannedArrival();
      OffsetDateTime operationEnd =
          positioning ? operation.plannedArrival() : operation.plannedDeparture();
      if (operationStart.isAfter(operationEnd)
          || (priorEnd != null && operationStart.isBefore(priorEnd))) {
        throw new IllegalArgumentException("Driver route operation timing is invalid");
      }
      boolean customer =
          switch (operation.kind()) {
            case DELIVERY, PICKUP -> true;
            default -> false;
          };
      boolean transfer =
          switch (operation.kind()) {
            case TRANSFER_LOAD, TRANSFER_UNLOAD -> true;
            default -> false;
          };
      if ((customer
              && (operation.sourceTaskId() == null
                  || operation.sourceTransferId() != null
                  || operation.warehouseId() != null))
          || (transfer
              && (operation.sourceTaskId() != null
                  || operation.sourceTransferId() == null
                  || operation.warehouseId() == null))
          || (!customer
              && !transfer
              && (operation.sourceTaskId() != null
                  || operation.sourceTransferId() != null
                  || operation.warehouseId() == null))
          || operation.loadBefore() != priorLoad) {
        throw new IllegalArgumentException(
            "Driver route operation identity or load chain is invalid");
      }
      Integer cabinCapacity = plan.vehicle().cabinCapacity();
      if (transfer && cabinCapacity == null) {
        throw new IllegalArgumentException(
            "Transfer route operations require exact vehicle cabin capacity");
      }
      if (cabinCapacity != null
          && (operation.loadBefore() > cabinCapacity
              || operation.loadAfter() > cabinCapacity)) {
        throw new IllegalArgumentException("Driver route operation exceeds vehicle cabin capacity");
      }
      if ((operation.kind() == PlanningDriverShiftRouteOperationKind.TRANSFER_LOAD
              && operation.loadAfter() < operation.loadBefore())
          || (operation.kind() == PlanningDriverShiftRouteOperationKind.TRANSFER_UNLOAD
              && operation.loadAfter() > operation.loadBefore())) {
        throw new IllegalArgumentException("Transfer route load direction is invalid");
      }
      if ((positioning
              || operation.kind() == PlanningDriverShiftRouteOperationKind.ORIGIN_START)
          && operation.loadBefore() != operation.loadAfter()) {
        throw new IllegalArgumentException("Positioning cannot change the planned vehicle load");
      }
      priorLoad = operation.loadAfter();
      priorEnd = operationEnd;
    }
    requireValidPositioningShape(plan, serviceWarehouseId, operations);
  }

  private static void requireValidPositioningShape(
      PlanningDriverShiftPlanRequest plan,
      UUID serviceWarehouseId,
      List<PlanningDriverShiftRouteOperationRequest> operations) {
    UUID routeOriginWarehouseId = effectiveRouteOrigin(plan);
    boolean crossWarehouse = !routeOriginWarehouseId.equals(serviceWarehouseId);
    long syntheticCount =
        operations.stream()
            .filter(
                operation ->
                    operation.kind() == PlanningDriverShiftRouteOperationKind.ORIGIN_START
                        || operation.kind()
                            == PlanningDriverShiftRouteOperationKind.INBOUND_POSITIONING
                        || operation.kind()
                            == PlanningDriverShiftRouteOperationKind.RETURN_POSITIONING)
            .count();
    if (!crossWarehouse) {
      if (syntheticCount != 0
          || hasTransferOperation(plan)
          || operations.stream()
              .filter(operation -> operation.warehouseId() != null)
              .anyMatch(operation -> !serviceWarehouseId.equals(operation.warehouseId()))) {
        throw new IllegalArgumentException(
            "A local route operation must belong to the plan warehouse");
      }
      return;
    }
    int inboundIndex =
        indexOf(operations, PlanningDriverShiftRouteOperationKind.INBOUND_POSITIONING);
    if (operations.size() < 4
        || syntheticCount != 3
        || operations.getFirst().kind()
            != PlanningDriverShiftRouteOperationKind.ORIGIN_START
        || inboundIndex < 1
        || operations.getLast().kind()
            != PlanningDriverShiftRouteOperationKind.RETURN_POSITIONING) {
      throw new IllegalArgumentException(
          "A cross-warehouse route requires origin, inbound, and return positioning");
    }
    PlanningDriverShiftRouteOperationRequest origin = operations.getFirst();
    PlanningDriverShiftRouteOperationRequest inbound = operations.get(inboundIndex);
    PlanningDriverShiftRouteOperationRequest returned = operations.getLast();
    if (operations.subList(1, inboundIndex).stream()
        .anyMatch(
            operation ->
                operation.kind() != PlanningDriverShiftRouteOperationKind.TRANSFER_LOAD)) {
      throw new IllegalArgumentException(
          "Only transfer loading may precede inbound positioning");
    }
    int serviceOperationStart = inboundIndex + 1;
    while (serviceOperationStart < operations.size() - 1
        && operations.get(serviceOperationStart).kind()
            == PlanningDriverShiftRouteOperationKind.TRANSFER_UNLOAD) {
      serviceOperationStart++;
    }
    if (operations.subList(serviceOperationStart, operations.size() - 1).stream()
        .anyMatch(RentalOrderPlanningIntegrationService::isTransferOperation)) {
      throw new IllegalArgumentException(
          "Transfer unloading must immediately follow inbound positioning");
    }
    Map<UUID, Integer> loadedTransfers = new LinkedHashMap<>();
    for (PlanningDriverShiftRouteOperationRequest operation :
        operations.subList(1, inboundIndex)) {
      if (!routeOriginWarehouseId.equals(operation.warehouseId())
          || !operation.plannedArrival().equals(inbound.plannedDeparture())
          || !operation.plannedDeparture().equals(inbound.plannedDeparture())
          || loadedTransfers.put(
                  operation.sourceTransferId(), operation.loadAfter() - operation.loadBefore())
              != null) {
        throw new IllegalArgumentException("Transfer loading endpoints are invalid");
      }
    }
    Map<UUID, Integer> unloadedTransfers = new LinkedHashMap<>();
    for (PlanningDriverShiftRouteOperationRequest operation :
        operations.subList(inboundIndex + 1, serviceOperationStart)) {
      if (!serviceWarehouseId.equals(operation.warehouseId())
          || !operation.plannedArrival().equals(inbound.plannedArrival())
          || !operation.plannedDeparture().equals(inbound.plannedArrival())
          || unloadedTransfers.put(
                  operation.sourceTransferId(), operation.loadBefore() - operation.loadAfter())
              != null) {
        throw new IllegalArgumentException("Transfer unloading endpoints are invalid");
      }
    }
    if (!loadedTransfers.equals(unloadedTransfers)) {
      throw new IllegalArgumentException(
          "Transfer route load and unload operations are unbalanced");
    }
    if (!routeOriginWarehouseId.equals(origin.warehouseId())
        || !serviceWarehouseId.equals(inbound.warehouseId())
        || !routeOriginWarehouseId.equals(returned.warehouseId())
        || !origin.plannedArrival().equals(origin.plannedDeparture())
        || !origin.plannedDeparture().equals(inbound.plannedDeparture())
        || operations.subList(serviceOperationStart, operations.size() - 1).stream()
            .filter(operation -> operation.warehouseId() != null)
            .anyMatch(operation -> !serviceWarehouseId.equals(operation.warehouseId()))) {
      throw new IllegalArgumentException("Cross-warehouse positioning endpoints are invalid");
    }
  }

  private void registerShiftPlans(ApplyPlanningAssignmentsRequest request) {
    Set<String> referencedDriverWorkdays =
        request.assignments().stream()
            .map(RentalOrderPlanningIntegrationService::routeAssignmentDriverWorkday)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
    for (PlanningDriverShiftPlanRequest plan : request.driverShiftPlans()) {
      if (!hasTransferOperation(plan)
          && !referencedDriverWorkdays.contains(
              driverWorkdayKey(plan.driverId(), plan.workDate()))) {
        continue;
      }
      dependencies.registerDriverShiftPlan(
          driverShiftPlanKey(plan),
          plan.sourceShiftId(),
          new LogisticsDependencyGateway.DriverShiftPlanSnapshot(
              plan.sourcePlanId(),
              plan.sourcePlanVersion(),
              effectiveRouteOrigin(plan),
              plan.driverId(),
              plan.driverName(),
              plan.workDate(),
              vehicleSnapshot(plan.vehicle()),
              trailerSnapshot(plan.trailer()),
              plan.tripCount(),
              plan.routeDistanceMeters(),
              routeOperationSnapshots(plan.operations())));
    }
  }

  /**
   * Reuses the canonical route, support-link, audience, and provisional-ETA checks before an
   * existing planner revision is sent to task-board's atomic replacement boundary.
   */
  public List<PlanningReplacementShift> validateAndSnapshotReplacement(
      UUID sourcePlanId, ReplacePlanningAssignmentsRequest request) {
    List<PlanningAssignmentRequest> assignments =
        request.assignments().stream()
            .map(
                assignment ->
                    new PlanningAssignmentRequest(
                        assignment.orderId(),
                        assignment.serviceWarehouseId(),
                        null,
                        assignment.expectedOrderVersion(),
                        assignment.scheduledDate(),
                        PlanningAssignmentType.ROUTE_PLAN,
                        assignment.driverAudienceMode(),
                        assignment.driverWorkerId(),
                        assignment.driverName(),
                        assignment.unitIds(),
                        assignment.provisionalEta()))
            .toList();
    ApplyPlanningAssignmentsRequest validation =
        new ApplyPlanningAssignmentsRequest(
            request.warehouseId(),
            sourcePlanId,
            request.replacementPlanVersion(),
            assignments,
            request.driverShiftPlans());
    requireUniqueAssignments(assignments);
    requireValidShiftPlans(validation);
    requireAuthorizedShiftPlans(validation);
    OffsetDateTime generatedAt = now();
    for (PlanningAssignmentRequest assignment : assignments) {
      UUID serviceWarehouseId = effectiveServiceWarehouse(validation, assignment);
      String timeZone =
          dependencies.warehouseTimeZoneAt(serviceWarehouseId, generatedAt).timeZone();
      LocalDate today = generatedAt.toInstant().atZone(ZoneId.of(timeZone)).toLocalDate();
      requireDriverAudience(today, assignment);
      requireProvisionalEta(serviceWarehouseId, assignment);
    }
    return request.driverShiftPlans().stream()
        .map(
            plan ->
                new PlanningReplacementShift(
                    plan.sourceShiftId(),
                    new LogisticsDependencyGateway.DriverShiftPlanSnapshot(
                        plan.sourcePlanId(),
                        plan.sourcePlanVersion(),
                        effectiveRouteOrigin(plan),
                        plan.driverId(),
                        plan.driverName(),
                        plan.workDate(),
                        vehicleSnapshot(plan.vehicle()),
                        trailerSnapshot(plan.trailer()),
                        plan.tripCount(),
                        plan.routeDistanceMeters(),
                        routeOperationSnapshots(plan.operations()))))
        .toList();
  }

  /** Stable service identity attached to every route-planner-created shipment document. */
  public static UUID plannerSubjectId() {
    return PLANNER_SUBJECT;
  }

  private void requireAuthorizedShiftPlans(ApplyPlanningAssignmentsRequest request) {
    for (PlanningDriverShiftPlanRequest plan : request.driverShiftPlans()) {
      List<PlanningAssignmentRequest> routeAssignments =
          request.assignments().stream()
              .filter(
                  assignment ->
                      assignment.assignmentType() == PlanningAssignmentType.ROUTE_PLAN
                          && assignment.driverAudienceMode()
                              == PlanningDriverAudienceMode.ASSIGNED_DRIVER
                          && plan.driverId().equals(assignment.driverWorkerId())
                          && plan.workDate().equals(assignment.scheduledDate()))
              .toList();
      if (routeAssignments.isEmpty() && !hasTransferOperation(plan)) continue;
      UUID routeOriginWarehouseId = effectiveRouteOrigin(plan);
      boolean carriesTransfer = hasTransferOperation(plan);
      boolean carriesTransferCabins =
          plan.operations().stream()
              .anyMatch(
                  operation ->
                      operation.kind() == PlanningDriverShiftRouteOperationKind.TRANSFER_LOAD
                          && operation.loadAfter() > operation.loadBefore());
      Set<UUID> crossWarehouseServices = new HashSet<>();
      routeAssignments.stream()
          .map(assignment -> effectiveServiceWarehouse(request, assignment))
          .filter(serviceWarehouseId -> !routeOriginWarehouseId.equals(serviceWarehouseId))
          .forEach(crossWarehouseServices::add);
      plan.operations().stream()
          .filter(
              operation ->
                  operation.kind() == PlanningDriverShiftRouteOperationKind.TRANSFER_UNLOAD)
          .map(PlanningDriverShiftRouteOperationRequest::warehouseId)
          .filter(serviceWarehouseId -> !routeOriginWarehouseId.equals(serviceWarehouseId))
          .forEach(crossWarehouseServices::add);
      if (crossWarehouseServices.isEmpty()) {
        if (plan.supportWarehouseLinkId() != null) {
          throw new OrderProblemException(
              HttpStatus.CONFLICT,
              "SHIFT_SUPPORT_LINK_NOT_APPLICABLE",
              "Для локального рейса не требуется связь опорных складов");
        }
        continue;
      }
      if (plan.supportWarehouseLinkId() == null) {
        throw new OrderProblemException(
            HttpStatus.CONFLICT,
            "SHIFT_SUPPORT_LINK_REQUIRED",
            "Для межскладского обслуживания требуется связь опорных складов");
      }
      List<LogisticsDependencyGateway.WarehouseSupportLink> network =
          dependencies.listWarehouseSupportNetwork(routeOriginWarehouseId);
      for (UUID serviceWarehouseId : crossWarehouseServices) {
        boolean authorized =
            network != null
                && network.stream()
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(
                        link ->
                            plan.supportWarehouseLinkId().equals(link.id())
                                && supportLinkAllowsDriver(
                                    link,
                                    routeOriginWarehouseId,
                                    serviceWarehouseId,
                                    plan.workDate(),
                                    carriesTransfer,
                                    carriesTransferCabins));
        if (!authorized) {
          throw new OrderProblemException(
              HttpStatus.CONFLICT,
              "SHIFT_SUPPORT_LINK_NOT_AUTHORIZED",
              "Связь складов не разрешает этот рейс водителя в выбранную дату");
        }
      }
    }
  }

  private static boolean supportLinkAllowsDriver(
      LogisticsDependencyGateway.WarehouseSupportLink link,
      UUID routeOriginWarehouseId,
      UUID serviceWarehouseId,
      LocalDate workDate,
      boolean carriesTransfer,
      boolean carriesTransferCabins) {
    return link.supportWarehouse() != null
        && routeOriginWarehouseId.equals(link.supportWarehouse().id())
        && link.supportWarehouse().active()
        && link.servedWarehouse() != null
        && serviceWarehouseId.equals(link.servedWarehouse().id())
        && link.servedWarehouse().active()
        && link.allowDrivers()
        && (!carriesTransfer || link.allowInterwarehouseTransfer())
        && (!carriesTransferCabins || link.allowInventory())
        && link.allowedWeekdays() != null
        && link.allowedDates() != null
        && link.excludedDates() != null
        && !link.excludedDates().contains(workDate)
        && (link.allowedDates().contains(workDate)
            || link.allowedWeekdays().isEmpty()
            || link.allowedWeekdays().contains(workDate.getDayOfWeek()));
  }

  private static UUID effectiveRouteOrigin(PlanningDriverShiftPlanRequest plan) {
    return plan.routeOriginWarehouseId() == null
        ? plan.warehouseId()
        : plan.routeOriginWarehouseId();
  }

  private static String routeAssignmentDriverWorkday(PlanningAssignmentRequest assignment) {
    if (assignment.assignmentType() != PlanningAssignmentType.ROUTE_PLAN
        || assignment.driverAudienceMode() != PlanningDriverAudienceMode.ASSIGNED_DRIVER
        || assignment.driverWorkerId() == null) {
      return null;
    }
    return driverWorkdayKey(assignment.driverWorkerId(), assignment.scheduledDate());
  }

  private static String driverWorkdayKey(UUID driverId, LocalDate workDate) {
    return driverId + ":" + workDate;
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
        vehicle.cabinCapacity(),
        vehicle.startOdometer());
  }

  private static LogisticsDependencyGateway.DriverShiftPlanTrailer trailerSnapshot(
      PlanningDriverShiftTrailerRequest trailer) {
    if (trailer == null) return null;
    return new LogisticsDependencyGateway.DriverShiftPlanTrailer(
        trailer.id(), trailer.name(), trailer.registrationNumber());
  }

  private static List<LogisticsDependencyGateway.DriverShiftRouteOperation>
      routeOperationSnapshots(List<PlanningDriverShiftRouteOperationRequest> operations) {
    return operations.stream()
        .map(
            operation ->
                new LogisticsDependencyGateway.DriverShiftRouteOperation(
                    operation.sequence(),
                    operation.kind().name(),
                    operation.warehouseId(),
                    operation.sourceTaskId(),
                    operation.sourceTransferId(),
                    operation.locationLabel(),
                    operation.plannedArrival(),
                    operation.plannedDeparture(),
                    operation.loadBefore(),
                    operation.loadAfter()))
        .toList();
  }

  private static UUID routeServiceWarehouse(PlanningDriverShiftPlanRequest plan) {
    return plan.operations().stream()
        .filter(
            operation ->
                operation.kind() == PlanningDriverShiftRouteOperationKind.INBOUND_POSITIONING)
        .map(PlanningDriverShiftRouteOperationRequest::warehouseId)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(plan.warehouseId());
  }

  private static int indexOf(
      List<PlanningDriverShiftRouteOperationRequest> operations,
      PlanningDriverShiftRouteOperationKind kind) {
    for (int index = 0; index < operations.size(); index++) {
      if (operations.get(index).kind() == kind) return index;
    }
    return -1;
  }

  private static boolean hasTransferOperation(PlanningDriverShiftPlanRequest plan) {
    return plan.operations().stream()
        .anyMatch(RentalOrderPlanningIntegrationService::isTransferOperation);
  }

  private static boolean isTransferOperation(
      PlanningDriverShiftRouteOperationRequest operation) {
    return operation.kind() == PlanningDriverShiftRouteOperationKind.TRANSFER_LOAD
        || operation.kind() == PlanningDriverShiftRouteOperationKind.TRANSFER_UNLOAD;
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
            + effectiveServiceWarehouse(request, assignment)
            + ":"
            + assignment.scheduledDate()
            + ":"
            + assignment.driverAudienceMode()
            + ":"
            + sortedUnitIds);
  }

  private static UUID effectiveServiceWarehouse(
      ApplyPlanningAssignmentsRequest request, PlanningAssignmentRequest assignment) {
    return assignment.serviceWarehouseId() == null
        ? request.warehouseId()
        : assignment.serviceWarehouseId();
  }

  private LocalDate warehouseToday(UUID warehouseId, OffsetDateTime generatedAt) {
    String timeZone = dependencies.warehouseTimeZoneAt(warehouseId, generatedAt).timeZone();
    return generatedAt.toInstant().atZone(ZoneId.of(timeZone)).toLocalDate();
  }

  private void requireAuthorizedSupport(
      UUID rootWarehouseId, UUID serviceWarehouseId, LocalDate scheduledDate) {
    if (rootWarehouseId.equals(serviceWarehouseId)) return;
    List<LogisticsDependencyGateway.WarehouseSupportLink> network =
        dependencies.listWarehouseSupportNetwork(rootWarehouseId);
    boolean eligible =
        network != null
            && network.stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(
                    link ->
                        supportLinkAllowsDriver(
                            link,
                            rootWarehouseId,
                            serviceWarehouseId,
                            scheduledDate,
                            false,
                            false));
    if (!eligible) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "SUPPORT_WAREHOUSE_NOT_AUTHORIZED",
          "Основной склад не может обслужить выбранный региональный склад в эту дату");
    }
  }

  private static UUID deterministic(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }
}
