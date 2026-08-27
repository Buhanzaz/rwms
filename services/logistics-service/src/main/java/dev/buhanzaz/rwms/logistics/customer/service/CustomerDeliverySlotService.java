package dev.buhanzaz.rwms.logistics.customer.service;

import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerDeliverySlotResponse;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.DeliverySlotSearchRequest;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.HeldCustomerDeliverySlotResponse;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.HoldCustomerDeliverySlotRequest;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.ScenarioCapacityJob;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.ScenarioCapacityJobRepository;
import dev.buhanzaz.rwms.logistics.customer.config.CustomerDeliveryProperties;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotState;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner.CapacityDecision;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner.DeliveryJob;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerTravelTimeMatrix;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerTravelTimeMatrix.GeoPoint;
import dev.buhanzaz.rwms.logistics.customer.routing.ValhallaCustomerTravelTimeClient;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Calculates, offers and holds fixed CustomerApp arrival windows against the complete local-day
 * held/confirmed delivery workload and the private Valhalla truck matrix. Date-only pickup tasks
 * do not consume a whole driver: the route planner may attach them after priority deliveries.
 */
@Service
@RequiredArgsConstructor
public class CustomerDeliverySlotService {
  private static final int MAX_MATRIX_POINTS = 32;
  private static final List<Window> WINDOWS =
      List.of(
          new Window(LocalTime.of(9, 0), LocalTime.of(12, 0)),
          new Window(LocalTime.of(12, 0), LocalTime.of(15, 0)),
          new Window(LocalTime.of(15, 0), LocalTime.of(18, 0)));
  private final CustomerRentalService rentals;
  private final CustomerWarehouseService warehouses;
  private final CustomerRentalSessionStore sessions;
  private final CustomerDeliverySlotStore slotStore;
  private final CustomerDeliverySlotHoldStore holdStore;
  private final ScenarioCapacityJobRepository scenarioCapacityJobs;
  private final ValhallaCustomerTravelTimeClient travelTimes;
  private final CustomerRouteCapacityPlanner capacity;
  private final DriverLogisticsTaskRepository driverTasks;
  private final Clock clock;

  /** Returns only route-feasible offers across the configured booking horizon. */
  public List<CustomerDeliverySlotResponse> search(
      CustomerIdentity identity, DeliverySlotSearchRequest request) {
    CustomerRentalSession session = sessions.required(identity.subjectId(), request.inquiryId());
    CustomerDeliveryProperties.Validated configuration =
        warehouses.validated(session.getWarehouseId());
    int cabinCount = rentals.selectedCabinIds(identity, request.inquiryId()).size();
    if (cabinCount < 1) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CUSTOMER_CABINS_REQUIRED",
          "Добавьте хотя бы одну бытовку перед выбором доставки");
    }
    OffsetDateTime now = now();
    ZoneId zone = ZoneId.of(warehouses.required(session.getWarehouseId()).timeZone());
    LocalDate first = now.toInstant().atZone(zone).toLocalDate().plusDays(configuration.earliestDeliveryDays());
    LocalDate last =
        now.toInstant().atZone(zone).toLocalDate().plusDays(configuration.bookingHorizonDays());
    List<CustomerDeliverySlot> offers = new ArrayList<>();
    for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
      RouteContext context =
          routeContext(
              session,
              date,
              request.latitude(),
              request.longitude(),
              configuration,
              now);
      if (context.matrix() == null) continue;
      for (Window window : WINDOWS) {
        OfferDecision decision =
            evaluate(context, window, cabinCount, configuration);
        if (!decision.capacity().feasible()) continue;
        long oneWay = decision.matrix().travelSeconds(0, decision.candidateIndex());
        int zoneHours = (int) Math.max(1, (oneWay + 3_599L) / 3_600L);
        offers.add(
            CustomerDeliverySlot.offer(
                identity.subjectId(),
                request.inquiryId(),
                session.getWarehouseId(),
                date,
                window.start(),
                window.end(),
                request.address(),
                request.latitude(),
                request.longitude(),
                cabinCount,
                oneWay,
                zoneHours,
                decision.capacity().capacityRemaining(),
                now.plus(configuration.offerLifetime())));
      }
    }
    return slotStore.replaceOffers(identity.subjectId(), request.inquiryId(), offers).stream()
        .map(CustomerDeliverySlotService::response)
        .toList();
  }

  /** Recomputes and holds one offer under a warehouse/day advisory lock. */
  public HeldCustomerDeliverySlotResponse hold(
      CustomerIdentity identity,
      UUID slotId,
      long expectedSlotVersion,
      HoldCustomerDeliverySlotRequest request) {
    CustomerRentalSession session = sessions.required(identity.subjectId(), request.inquiryId());
    if (session.getVersion() != request.expectedVersion()) throw cartVersionConflict();
    CustomerDeliverySlot offered =
        slotStore.required(identity.subjectId(), request.inquiryId(), slotId);
    if (!identity.subjectId().equals(offered.getCustomerSubjectId())
        || !request.inquiryId().equals(offered.getInquiryId())) {
      throw slotNotFound();
    }
    OffsetDateTime now = now();
    if (offered.getVersion() != expectedSlotVersion
        || offered.getState() != CustomerDeliverySlotState.OFFERED
        || !offered.getExpiresAt().isAfter(now)) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CUSTOMER_DELIVERY_SLOT_EXPIRED",
          "Слот уже изменился; выберите доступное время заново");
    }
    int selectedCabinCount = rentals.selectedCabinIds(identity, request.inquiryId()).size();
    if (selectedCabinCount != offered.getCabinCount()) throw cartVersionConflict();
    CustomerDeliveryProperties.Validated configuration =
        warehouses.validated(session.getWarehouseId());
    RouteContext context =
        routeContext(
            session,
            offered.getDeliveryDate(),
            offered.getLatitude(),
            offered.getLongitude(),
            configuration,
            now);
    OfferDecision decision =
        evaluate(
            context,
            new Window(offered.getWindowStart(), offered.getWindowEnd()),
            offered.getCabinCount(),
            configuration);
    if (!decision.capacity().feasible()) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CUSTOMER_DELIVERY_SLOT_TAKEN",
          "Этот слот только что заняли; выберите другое время");
    }
    CustomerDeliverySlotHoldStore.HeldSlot held =
        holdStore.hold(
            new CustomerDeliverySlotHoldStore.HoldCommand(
                identity.subjectId(),
                request.inquiryId(),
                offered.getId(),
                expectedSlotVersion,
                request.expectedVersion(),
                offered.getWarehouseId(),
                offered.getDeliveryDate(),
                context.workloadSha256(),
                decision.capacity().capacityRemaining(),
                configuration.holdLifetime()));
    return new HeldCustomerDeliverySlotResponse(
        held.session().getVersion(), response(held.slot()));
  }

  /** Resolves and validates the cart's current held slot for checkout. */
  public CustomerDeliverySlot requiredHeld(
      CustomerIdentity identity, UUID inquiryId, UUID slotId, long expectedSlotVersion) {
    CustomerDeliverySlot slot = slotStore.required(identity.subjectId(), inquiryId, slotId);
    if (slot.getVersion() != expectedSlotVersion
        || slot.getState() != CustomerDeliverySlotState.HELD
        || !slot.getExpiresAt().isAfter(now())) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CUSTOMER_DELIVERY_SLOT_EXPIRED",
          "Удержание времени доставки истекло");
    }
    return slot;
  }

  /** Confirms the held slot as permanent planner workload. */
  public CustomerDeliverySlot confirm(
      CustomerIdentity identity, UUID inquiryId, UUID slotId, UUID bookingId, UUID orderId) {
    return slotStore.confirm(identity.subjectId(), inquiryId, slotId, bookingId, orderId);
  }

  /** Reconciles a protected command reservation with its durable booking receipt. */
  public CustomerDeliverySlot bindCheckoutBooking(
      CustomerIdentity identity,
      UUID inquiryId,
      UUID slotId,
      UUID reservationKey,
      UUID bookingId,
      UUID orderId) {
    return slotStore.bindCheckoutBooking(
        identity.subjectId(), inquiryId, slotId, reservationKey, bookingId, orderId);
  }

  /** Releases a non-confirmed slot after a terminal booking rejection. */
  public void release(CustomerIdentity identity, UUID inquiryId, UUID slotId) {
    slotStore.release(identity.subjectId(), inquiryId, slotId);
  }

  private RouteContext routeContext(
      CustomerRentalSession session,
      LocalDate date,
      BigDecimal latitude,
      BigDecimal longitude,
      CustomerDeliveryProperties.Validated configuration,
      OffsetDateTime now) {
    List<CustomerDeliverySlot> existing =
        CustomerCapacityWorkloadFingerprint.capacitySlots(
            slotStore.workload(session.getWarehouseId(), date, now),
            session.getCustomerSubjectId(),
            session.getInquiryId());
    List<ScenarioCapacityJob> generated =
        scenarioCapacityJobs.findCapacityWorkload(session.getWarehouseId(), date);
    long wholeDayReservations =
        driverTasks.countWholeDayDeliveryReservations(session.getWarehouseId(), date);
    String workloadSha256 =
        CustomerCapacityWorkloadFingerprint.sha256(
            existing, generated, wholeDayReservations);
    List<GeoPoint> points = new ArrayList<>(existing.size() + generated.size() + 2);
    points.add(
        new GeoPoint(
            configuration.depotLatitude().doubleValue(),
            configuration.depotLongitude().doubleValue()));
    existing.forEach(
        slot -> points.add(new GeoPoint(slot.getLatitude().doubleValue(), slot.getLongitude().doubleValue())));
    generated.forEach(
        job -> points.add(new GeoPoint(job.getLatitude().doubleValue(), job.getLongitude().doubleValue())));
    int candidateIndex = points.size();
    points.add(new GeoPoint(latitude.doubleValue(), longitude.doubleValue()));
    if (points.size() > MAX_MATRIX_POINTS) {
      return new RouteContext(
          null, candidateIndex, List.of(), workloadSha256, wholeDayReservations);
    }
    CustomerTravelTimeMatrix matrix = travelTimes.matrix(points, date, configuration);
    List<DeliveryJob> baseJobs = new ArrayList<>();
    for (int index = 0; index < existing.size(); index++) {
      CustomerDeliverySlot slot = existing.get(index);
      baseJobs.add(
          new DeliveryJob(
              index + 1,
              slot.getCabinCount(),
              slot.getWindowStart(),
              slot.getWindowEnd(),
              configuration.serviceMinutes(),
              false));
    }
    for (int index = 0; index < generated.size(); index++) {
      ScenarioCapacityJob job = generated.get(index);
      baseJobs.add(
          new DeliveryJob(
              existing.size() + index + 1,
              job.getCabinCount(),
              job.getWindowStart(),
              job.getWindowEnd(),
              job.getServiceMinutes(),
              false));
    }
    return new RouteContext(
        matrix,
        candidateIndex,
        List.copyOf(baseJobs),
        workloadSha256,
        wholeDayReservations);
  }

  private OfferDecision evaluate(
      RouteContext context,
      Window window,
      int cabinCount,
      CustomerDeliveryProperties.Validated configuration) {
    if (context.matrix() == null) {
      return new OfferDecision(
          null, context.candidateIndex(), new CapacityDecision(false, 0));
    }
    List<DeliveryJob> jobs = new ArrayList<>(context.baseJobs());
    jobs.add(
        new DeliveryJob(
            context.candidateIndex(),
            cabinCount,
            window.start(),
            window.end(),
            configuration.serviceMinutes(),
            true));
    int reservedDrivers =
        (int) Math.min(configuration.driverCount(), context.wholeDayDriverReservations());
    return new OfferDecision(
        context.matrix(),
        context.candidateIndex(),
        capacity.evaluate(context.matrix(), jobs, configuration, reservedDrivers));
  }

  private static CustomerDeliverySlotResponse response(CustomerDeliverySlot slot) {
    return new CustomerDeliverySlotResponse(
        slot.getId(),
        slot.getVersion(),
        slot.getDeliveryDate(),
        slot.getWindowStart(),
        slot.getWindowEnd(),
        slot.getTravelZoneHours(),
        slot.getCapacityRemaining(),
        slot.getExpiresAt(),
        slot.getState().name());
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  private static OrderProblemException slotNotFound() {
    return new OrderProblemException(
        HttpStatus.NOT_FOUND, "CUSTOMER_DELIVERY_SLOT_NOT_FOUND", "Слот доставки не найден");
  }

  private static OrderProblemException cartVersionConflict() {
    return new OrderProblemException(
        HttpStatus.CONFLICT,
        "CUSTOMER_CART_VERSION_CONFLICT",
        "Корзина уже изменилась; обновите данные и повторите действие");
  }

  /** One immutable fixed working-day window. */
  private record Window(LocalTime start, LocalTime end) {}

  /** Matrix and capacity result for one potential offer. */
  private record OfferDecision(
      CustomerTravelTimeMatrix matrix, int candidateIndex, CapacityDecision capacity) {}

  /** Route matrix and exact local workload facts shared by all three windows of one day. */
  private record RouteContext(
      CustomerTravelTimeMatrix matrix,
      int candidateIndex,
      List<DeliveryJob> baseJobs,
      String workloadSha256,
      long wholeDayDriverReservations) {}
}
