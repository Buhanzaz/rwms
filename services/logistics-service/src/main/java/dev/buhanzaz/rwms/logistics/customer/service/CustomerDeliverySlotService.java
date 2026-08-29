package dev.buhanzaz.rwms.logistics.customer.service;

import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerDeliverySlotResponse;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerRouteProfile;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.DeliverySlotSearchRequest;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.HeldCustomerDeliverySlotResponse;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.HoldCustomerDeliverySlotRequest;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityJob;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityPriceZone;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityRestrictionZone;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityShift;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacitySnapshot;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityTaskType;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityJobRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityPriceZoneRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityRestrictionZoneRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityShiftRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacitySnapshotRepository;
import dev.buhanzaz.rwms.logistics.customer.config.CustomerDeliveryProperties;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotKind;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotState;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner.CapacityDecision;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner.CapacityShift;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner.DeliveryJob;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerTravelTimeMatrix;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerTravelTimeMatrix.GeoPoint;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerVehicleRouteProfile;
import dev.buhanzaz.rwms.logistics.customer.routing.ValhallaCustomerTravelTimeClient;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerDeliveryPriceClassifier.PriceQuote;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerDeliveryPriceClassifier.DeliveryPolicy;
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
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Calculates, offers and holds fixed or full-day CustomerApp arrival choices against the complete
 * local-day held/confirmed delivery workload and the private Valhalla truck matrix. Date-only
 * pickup tasks do not consume a whole driver: the route planner may attach them after priority
 * deliveries.
 */
@Service
@RequiredArgsConstructor
public class CustomerDeliverySlotService {
  private static final int MAX_MATRIX_POINTS = 32;
  private final CustomerRentalService rentals;
  private final CustomerWarehouseService warehouses;
  private final CustomerRentalSessionStore sessions;
  private final CustomerDeliverySlotStore slotStore;
  private final CustomerDeliverySlotHoldStore holdStore;
  private final WarehouseCapacityJobRepository warehouseCapacityJobs;
  private final WarehouseCapacityShiftRepository warehouseCapacityShifts;
  private final WarehouseCapacityPriceZoneRepository warehouseCapacityPriceZones;
  private final WarehouseCapacityRestrictionZoneRepository warehouseCapacityRestrictionZones;
  private final WarehouseCapacitySnapshotRepository warehouseCapacitySnapshots;
  private final CustomerDeliveryPriceClassifier prices;
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
    int siteCabinCapacity = cabinCount == 1 ? 1 : request.siteCabinCapacity();
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
              siteCabinCapacity,
              configuration,
              now);
      if (context.points().isEmpty()) continue;
      for (Window window : windows(configuration)) {
        OfferDecision decision =
            evaluate(
                context,
                date,
                window,
                cabinCount,
                siteCabinCapacity,
                configuration);
        if (!decision.capacity().feasible()) continue;
        long oneWay = decision.matrix().travelSeconds(0, decision.candidateIndex());
        PriceDecision price = price(context, oneWay);
        if (price == null) continue;
        int zoneHours = (int) Math.max(1, (oneWay + 3_599L) / 3_600L);
        offers.add(
            CustomerDeliverySlot.offer(
                identity.subjectId(),
                request.inquiryId(),
                session.getWarehouseId(),
                date,
                window.kind(),
                window.start(),
                window.end(),
                request.address(),
                request.latitude(),
                request.longitude(),
                cabinCount,
                oneWay,
                zoneHours,
                decision.capacity().capacityRemaining(),
                siteCabinCapacity,
                price.deliveryPriceRubles(),
                price.priceZoneId(),
                price.priceIsochroneMinutes(),
                request.privateSiteAccessConfirmed(),
                request.failedTripChargeAcknowledged(),
                decision.profile().heightMeters(),
                decision.profile().widthMeters(),
                decision.profile().lengthMeters(),
                decision.profile().weightTons(),
                decision.profile().axleLoadTons(),
                decision.profile().axleCount(),
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
    if (request.siteCabinCapacity() != offered.getSiteCabinCapacity()) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CUSTOMER_DELIVERY_SITE_CAPACITY_CHANGED",
          "Вместимость приёмки изменилась; рассчитайте доступное время заново");
    }
    boolean privateSiteAccessConfirmed =
        offered.isPrivateSiteAccessConfirmed()
            || Boolean.TRUE.equals(request.privateSiteAccessConfirmed());
    boolean failedTripChargeAcknowledged =
        offered.isFailedTripChargeAcknowledged()
            || Boolean.TRUE.equals(request.failedTripChargeAcknowledged());
    if (!privateSiteAccessConfirmed || !failedTripChargeAcknowledged) {
      throw new OrderProblemException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "CUSTOMER_ROUTE_ATTESTATIONS_REQUIRED",
          "Подтвердите доступ автопоезда и ответственность за ложные сведения");
    }
    CustomerDeliveryProperties.Validated configuration =
        warehouses.validated(session.getWarehouseId());
    RouteContext context =
        routeContext(
            session,
            offered.getDeliveryDate(),
            offered.getLatitude(),
            offered.getLongitude(),
            offered.getSiteCabinCapacity(),
            configuration,
            now);
    OfferDecision decision =
        evaluate(
            context,
            offered.getDeliveryDate(),
            new Window(offered.getKind(), offered.getWindowStart(), offered.getWindowEnd()),
            offered.getCabinCount(),
            offered.getSiteCabinCapacity(),
            configuration);
    if (!decision.capacity().feasible()) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CUSTOMER_DELIVERY_SLOT_TAKEN",
          "Этот слот только что заняли; выберите другое время");
    }
    long oneWay = decision.matrix().travelSeconds(0, decision.candidateIndex());
    PriceDecision recalculatedPrice = price(context, oneWay);
    if (recalculatedPrice == null
        || !Objects.equals(
            offered.getDeliveryPriceRubles(), recalculatedPrice.deliveryPriceRubles())
        || !Objects.equals(offered.getPriceZoneId(), recalculatedPrice.priceZoneId())
        || !Objects.equals(
            offered.getPriceIsochroneMinutes(), recalculatedPrice.priceIsochroneMinutes())) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CUSTOMER_DELIVERY_SLOT_TAKEN",
          "Тариф или правила доставки изменились; выберите доступное время заново");
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
                privateSiteAccessConfirmed,
                failedTripChargeAcknowledged,
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
      int siteCabinCapacity,
      CustomerDeliveryProperties.Validated configuration,
      OffsetDateTime now) {
    List<CustomerDeliverySlot> existing =
        CustomerCapacityWorkloadFingerprint.capacitySlots(
            slotStore.workload(session.getWarehouseId(), date, now),
            session.getCustomerSubjectId(),
            session.getInquiryId());
    List<WarehouseCapacityJob> generated =
        warehouseCapacityJobs.findCapacityWorkload(session.getWarehouseId(), date).stream()
            .filter(job -> job.getTaskType() == WarehouseCapacityTaskType.DELIVERY)
            .toList();
    List<WarehouseCapacityShift> shifts =
        warehouseCapacityShifts.findCapacityShifts(session.getWarehouseId(), date);
    List<WarehouseCapacityPriceZone> priceZones =
        warehouseCapacityPriceZones.findTariffZones(session.getWarehouseId());
    List<WarehouseCapacityRestrictionZone> restrictionZones =
        warehouseCapacityRestrictionZones.findRestrictionZones(session.getWarehouseId());
    WarehouseCapacitySnapshot snapshot =
        warehouseCapacitySnapshots.findByWarehouseId(session.getWarehouseId()).orElse(null);
    DeliveryPolicy policy =
        prices.classifyPolicy(priceZones, restrictionZones, latitude, longitude);
    long wholeDayReservations =
        driverTasks.countWholeDayDeliveryReservations(session.getWarehouseId(), date);
    String workloadSha256 =
        CustomerCapacityWorkloadFingerprint.sha256(
            existing,
            generated,
            shifts,
            snapshot,
            priceZones,
            restrictionZones,
            wholeDayReservations);
    if (policy.forbidden()) {
      return new RouteContext(
          List.of(),
          0,
          List.of(),
          workloadSha256,
          wholeDayReservations,
          List.of(),
          1,
          snapshot,
          policy);
    }
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
    int maximumShiftCapacity =
        shifts.stream().mapToInt(WarehouseCapacityShift::getCabinCapacity).max().orElse(1);
    int conservativeTripCapacity = Math.min(siteCabinCapacity, maximumShiftCapacity);
    if (!policy.trailerAccessAllowed()) conservativeTripCapacity = 1;
    for (CustomerDeliverySlot slot : existing) {
      conservativeTripCapacity =
          Math.min(conservativeTripCapacity, slot.getSiteCabinCapacity());
    }
    for (WarehouseCapacityJob job : generated) {
      if (!job.isTrailerAccessAllowed()) conservativeTripCapacity = 1;
    }
    if (points.size() > MAX_MATRIX_POINTS) {
      return new RouteContext(
          List.of(),
          candidateIndex,
          List.of(),
          workloadSha256,
          wholeDayReservations,
          List.of(),
          conservativeTripCapacity,
          snapshot,
          policy);
    }
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
              false,
              slot.getSiteCabinCapacity() >= 2));
    }
    for (int index = 0; index < generated.size(); index++) {
      WarehouseCapacityJob job = generated.get(index);
      baseJobs.add(
          new DeliveryJob(
              existing.size() + index + 1,
              job.getCabinCount(),
              job.getWindowStart(),
              job.getWindowEnd(),
              job.getServiceMinutes(),
              false,
              job.isTrailerAccessAllowed()));
    }
    return new RouteContext(
        List.copyOf(points),
        candidateIndex,
        List.copyOf(baseJobs),
        workloadSha256,
        wholeDayReservations,
        shifts.stream()
            .map(
                shift ->
                    new CapacityShift(
                        shift.getShiftStart(),
                        shift.getShiftEnd(),
                        shift.getBreakMinutes(),
                        shift.getCabinCapacity()))
            .toList(),
        conservativeTripCapacity,
        snapshot,
        policy);
  }

  private OfferDecision evaluate(
      RouteContext context,
      LocalDate date,
      Window window,
      int cabinCount,
      int siteCabinCapacity,
      CustomerDeliveryProperties.Validated configuration) {
    CustomerVehicleRouteProfile profile =
        CustomerVehicleRouteProfile.forTripCapacity(
            configuration, context.conservativeTripCapacity());
    if (context.points().isEmpty()) {
      return new OfferDecision(
          null, context.candidateIndex(), new CapacityDecision(false, 0), profile);
    }
    CustomerTravelTimeMatrix matrix =
        travelTimes.matrix(context.points(), date, window.start(), configuration, profile);
    List<DeliveryJob> jobs = new ArrayList<>(context.baseJobs());
    jobs.add(
        new DeliveryJob(
            context.candidateIndex(),
            cabinCount,
            window.start(),
            window.end(),
            configuration.serviceMinutes(),
            true,
            context.policy().trailerAccessAllowed() && siteCabinCapacity >= 2));
    int reservedDrivers =
        (int) Math.min(context.shifts().size(), context.wholeDayDriverReservations());
    return new OfferDecision(
        matrix,
        context.candidateIndex(),
        capacity.evaluate(
            matrix, jobs, configuration, context.shifts(), reservedDrivers),
        profile);
  }

  private static CustomerDeliverySlotResponse response(CustomerDeliverySlot slot) {
    return new CustomerDeliverySlotResponse(
        slot.getId(),
        slot.getVersion(),
        slot.getDeliveryDate(),
        slot.getKind(),
        slot.getWindowStart(),
        slot.getWindowEnd(),
        slot.getTravelZoneHours(),
        slot.getCapacityRemaining(),
        slot.getSiteCabinCapacity(),
        slot.getDeliveryPriceRubles(),
        slot.getPriceZoneId(),
        slot.getPriceIsochroneMinutes(),
        slot.isRoadRouteConfirmed(),
        slot.isPrivateSiteAccessConfirmed(),
        slot.isFailedTripChargeAcknowledged(),
        new CustomerRouteProfile(
            slot.getRouteHeightMeters(),
            slot.getRouteWidthMeters(),
            slot.getRouteLengthMeters(),
            slot.getRouteWeightTons(),
            slot.getRouteAxleLoadTons(),
            slot.getRouteAxleCount()),
        slot.getExpiresAt(),
        slot.getState().name());
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock)
        .withOffsetSameInstant(ZoneOffset.UTC)
        .truncatedTo(ChronoUnit.MICROS);
  }

  private static List<Window> windows(CustomerDeliveryProperties.Validated configuration) {
    List<Window> windows = new ArrayList<>();
    LocalTime start = configuration.customerDeliveryStart();
    while (start.isBefore(configuration.customerDeliveryEnd())) {
      LocalTime end = start.plusMinutes(configuration.deliverySlotMinutes());
      windows.add(new Window(CustomerDeliverySlotKind.FIXED_WINDOW, start, end));
      start = end;
    }
    windows.add(
        new Window(
            CustomerDeliverySlotKind.DURING_DAY,
            configuration.customerDeliveryStart(),
            configuration.customerDeliveryEnd()));
    return List.copyOf(windows);
  }

  private static PriceDecision price(RouteContext context, long oneWayTravelSeconds) {
    PriceQuote special = context.policy().specialPrice();
    if (special.deliveryPriceRubles() != null) {
      return new PriceDecision(special.deliveryPriceRubles(), special.priceZoneId(), null);
    }
    Integer tier = isochroneTier(oneWayTravelSeconds);
    if (tier == null) return null;
    long deliveryPrice =
        context.snapshot() == null
            ? defaultIsochronePrice(tier)
            : context.snapshot().deliveryPriceForIsochroneMinutes(tier);
    return new PriceDecision(deliveryPrice, null, tier);
  }

  private static Integer isochroneTier(long oneWayTravelSeconds) {
    if (oneWayTravelSeconds <= 3_600) return 60;
    if (oneWayTravelSeconds <= 7_200) return 120;
    if (oneWayTravelSeconds <= 10_800) return 180;
    if (oneWayTravelSeconds <= 14_400) return 240;
    return null;
  }

  private static long defaultIsochronePrice(int tier) {
    return switch (tier) {
      case 60 -> WarehouseCapacitySnapshot.DEFAULT_ISOCHRONE_PRICE_60_MINUTES;
      case 120 -> WarehouseCapacitySnapshot.DEFAULT_ISOCHRONE_PRICE_120_MINUTES;
      case 180 -> WarehouseCapacitySnapshot.DEFAULT_ISOCHRONE_PRICE_180_MINUTES;
      case 240 -> WarehouseCapacitySnapshot.DEFAULT_ISOCHRONE_PRICE_240_MINUTES;
      default -> throw new IllegalArgumentException("Unsupported isochrone price tier");
    };
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

  /** One immutable fixed or complete-day arrival choice evaluated by the same route planner. */
  private record Window(CustomerDeliverySlotKind kind, LocalTime start, LocalTime end) {}

  /** Matrix and capacity result for one potential offer. */
  private record OfferDecision(
      CustomerTravelTimeMatrix matrix,
      int candidateIndex,
      CapacityDecision capacity,
      CustomerVehicleRouteProfile profile) {}

  /** Exactly one special-zone or ordinary-isocrone price explanation for an offered slot. */
  private record PriceDecision(
      long deliveryPriceRubles, UUID priceZoneId, Integer priceIsochroneMinutes) {}

  /** Exact local workload facts shared by all configured windows of one day. */
  private record RouteContext(
      List<GeoPoint> points,
      int candidateIndex,
      List<DeliveryJob> baseJobs,
      String workloadSha256,
      long wholeDayDriverReservations,
      List<CapacityShift> shifts,
      int conservativeTripCapacity,
      WarehouseCapacitySnapshot snapshot,
      DeliveryPolicy policy) {}
}
