package dev.buhanzaz.rwms.logistics.customer.service;

import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerDeliverySlotResponse;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerRouteProfile;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.DeliverySlotSearchRequest;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.HeldCustomerDeliverySlotResponse;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.HoldCustomerDeliverySlotRequest;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.RescheduleCustomerBookingRequest;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.SearchCustomerBookingRescheduleRequest;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityIsochroneTariff;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityJob;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityPriceZone;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityRestrictionZone;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityShift;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacitySnapshot;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityTaskType;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityIsochroneTariffRepository;
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
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerSessionState;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner.CapacityDecision;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner.CapacityShift;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner.DeliveryJob;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerTravelTimeMatrix;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerTravelTimeMatrix.GeoPoint;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerVehicleRouteProfile;
import dev.buhanzaz.rwms.logistics.customer.routing.ValhallaCustomerTravelTimeClient;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerAuthorizer;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingLifecycleStore.RescheduleDecision;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerDeliveryPriceClassifier.DeliveryPolicy;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerDeliveryPriceClassifier.PriceQuote;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseIdentity;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderService;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDetailResponse;
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
  private final WarehouseCapacityIsochroneTariffRepository warehouseCapacityIsochroneTariffs;
  private final WarehouseCapacityPriceZoneRepository warehouseCapacityPriceZones;
  private final WarehouseCapacityRestrictionZoneRepository warehouseCapacityRestrictionZones;
  private final WarehouseCapacitySnapshotRepository warehouseCapacitySnapshots;
  private final CustomerDeliveryPriceClassifier prices;
  private final ValhallaCustomerTravelTimeClient travelTimes;
  private final CustomerRouteCapacityPlanner capacity;
  private final RepresentativeDeliverySlotPolicy representativePolicy;
  private final DriverLogisticsTaskRepository driverTasks;
  private final RentalOrderService rentalOrders;
  private final CustomerAuthorizer access;
  private final Clock clock;

  /** Returns only route-feasible offers across the configured booking horizon. */
  public List<CustomerDeliverySlotResponse> search(
      CustomerIdentity identity, DeliverySlotSearchRequest request) {
    CustomerRentalSession session =
        sessions.required(identity.subjectId(), request.inquiryId());
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
    return searchOffers(
        identity,
        session,
        request.address(),
        request.latitude(),
        request.longitude(),
        siteCabinCapacity,
        request.privateSiteAccessConfirmed(),
        request.failedTripChargeAcknowledged(),
        cabinCount,
        configuration,
        null);
  }

  /**
   * Searches replacements from the completed order's exact address and cabin count. The cart and
   * its original selection are never reopened or accepted from the client.
   */
  public List<CustomerDeliverySlotResponse> searchBooking(
      CustomerIdentity identity,
      UUID bookingId,
      SearchCustomerBookingRescheduleRequest request) {
    BookingContext booking = bookingContext(identity, bookingId, request.expectedVersion());
    CustomerDeliveryProperties.Validated configuration =
        warehouses.validated(booking.session().getWarehouseId());
    return searchOffers(
        identity,
        booking.session(),
        booking.slot().getDeliveryAddress(),
        booking.slot().getLatitude(),
        booking.slot().getLongitude(),
        booking.slot().getSiteCabinCapacity(),
        booking.slot().isPrivateSiteAccessConfirmed(),
        booking.slot().isFailedTripChargeAcknowledged(),
        booking.slot().getCabinCount(),
        configuration,
        booking.slot().getId());
  }

  private List<CustomerDeliverySlotResponse> searchOffers(
      CustomerIdentity identity,
      CustomerRentalSession session,
      String address,
      BigDecimal latitude,
      BigDecimal longitude,
      int siteCabinCapacity,
      boolean privateSiteAccessConfirmed,
      boolean failedTripChargeAcknowledged,
      int cabinCount,
      CustomerDeliveryProperties.Validated configuration,
      UUID excludedSlotId) {
    OffsetDateTime now = now();
    WarehouseIdentity warehouse = warehouses.required(session.getWarehouseId());
    ZoneId zone = ZoneId.of(warehouse.timeZone());
    LocalDate first = now.toInstant().atZone(zone).toLocalDate().plusDays(configuration.earliestDeliveryDays());
    LocalDate last =
        now.toInstant().atZone(zone).toLocalDate().plusDays(configuration.bookingHorizonDays());
    List<CustomerDeliverySlot> offers = new ArrayList<>();
    for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
      RouteContext context =
          routeContext(
              session,
              date,
              latitude,
              longitude,
              siteCabinCapacity,
              configuration,
              now,
              excludedSlotId);
      if (context.points().isEmpty()) continue;
      for (Window window : windows(configuration, warehouse.representative())) {
        OfferDecision decision =
            evaluate(
                context,
                date,
                window,
                cabinCount,
                siteCabinCapacity,
                configuration);
        long oneWay = decision.matrix().travelSeconds(0, decision.candidateIndex());
        PriceDecision price = price(context, oneWay);
        if (price == null) continue;
        if (context.policy().forbidden()) continue;
        RepresentativeDeliverySlotPolicy.Decision policy =
            representativePolicy.evaluate(
                warehouse,
                date,
                window.kind(),
                window.start(),
                window.end(),
                decision.capacity().feasible());
        if (!policy.allowed()) continue;
        int zoneHours = (int) Math.max(1, (oneWay + 3_599L) / 3_600L);
        offers.add(
            CustomerDeliverySlot.offer(
                identity.subjectId(),
                session.getInquiryId(),
                session.getWarehouseId(),
                date,
                window.kind(),
                window.start(),
                window.end(),
                address,
                latitude,
                longitude,
                cabinCount,
                oneWay,
                zoneHours,
                policy.flexibleSupport() ? 0 : decision.capacity().capacityRemaining(),
                siteCabinCapacity,
                price.deliveryPriceRubles(),
                price.priceZoneId(),
                price.priceIsochroneMinutes(),
                privateSiteAccessConfirmed,
                failedTripChargeAcknowledged,
                decision.profile().heightMeters(),
                decision.profile().widthMeters(),
                decision.profile().lengthMeters(),
                decision.profile().weightTons(),
                decision.profile().axleLoadTons(),
                decision.profile().axleCount(),
                now.plus(configuration.offerLifetime())));
      }
    }
    return slotStore
        .replaceOffers(identity.subjectId(), session.getInquiryId(), offers)
        .stream()
        .map(CustomerDeliverySlotService::response)
        .toList();
  }

  /** Recomputes and holds one offer under a warehouse/day advisory lock. */
  public HeldCustomerDeliverySlotResponse hold(
      CustomerIdentity identity,
      UUID slotId,
      long expectedSlotVersion,
      HoldCustomerDeliverySlotRequest request) {
    CustomerRentalSession session =
        sessions.required(identity.subjectId(), request.inquiryId());
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
            now,
            null);
    OfferDecision decision =
        evaluate(
            context,
            offered.getDeliveryDate(),
            new Window(offered.getKind(), offered.getWindowStart(), offered.getWindowEnd()),
            offered.getCabinCount(),
            offered.getSiteCabinCapacity(),
            configuration);
    if (decision.matrix() == null) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CUSTOMER_DELIVERY_SLOT_TAKEN",
          "Этот слот только что заняли; выберите другое время");
    }
    long oneWay = decision.matrix().travelSeconds(0, decision.candidateIndex());
    PriceDecision recalculatedPrice = price(context, oneWay);
    if (recalculatedPrice == null
        || context.policy().forbidden()
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
    WarehouseIdentity warehouse = warehouses.required(session.getWarehouseId());
    RepresentativeDeliverySlotPolicy.Decision policy =
        representativePolicy.evaluate(
            warehouse,
            offered.getDeliveryDate(),
            offered.getKind(),
            offered.getWindowStart(),
            offered.getWindowEnd(),
            decision.capacity().feasible());
    if (!policy.allowed()) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CUSTOMER_DELIVERY_SLOT_TAKEN",
          "Этот слот больше недоступен; выберите другое время");
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
                policy.flexibleSupport() ? 0 : decision.capacity().capacityRemaining(),
                privateSiteAccessConfirmed,
                failedTripChargeAcknowledged,
                configuration.holdLifetime()));
    return new HeldCustomerDeliverySlotResponse(
        held.session().getVersion(), response(held.slot()));
  }

  /** Resolves and validates the cart's current held slot for checkout. */
  public CustomerDeliverySlot requiredHeld(
      CustomerIdentity identity, UUID inquiryId, UUID slotId, long expectedSlotVersion) {
    CustomerDeliverySlot slot =
        slotStore.required(identity.subjectId(), inquiryId, slotId);
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
    return slotStore.confirm(
        identity.subjectId(), inquiryId, slotId, bookingId, orderId);
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
        identity.subjectId(),
        inquiryId,
        slotId,
        reservationKey,
        bookingId,
        orderId);
  }

  /** Releases a non-confirmed slot after a terminal booking rejection. */
  public void release(CustomerIdentity identity, UUID inquiryId, UUID slotId) {
    slotStore.release(identity.subjectId(), inquiryId, slotId);
  }

  /** Recalculates the exact replacement offer before the atomic store locks all final inputs. */
  RescheduleDecision prepareReschedule(
      CustomerIdentity identity,
      UUID bookingId,
      RescheduleCustomerBookingRequest request) {
    BookingContext booking = bookingContext(identity, bookingId, request.expectedVersion());
    CustomerDeliverySlot offered =
        slotStore.required(
            identity.subjectId(),
            booking.session().getInquiryId(),
            request.slotId());
    OffsetDateTime now = now();
    if (offered.getVersion() != request.slotVersion()
        || offered.getState() != CustomerDeliverySlotState.OFFERED
        || !offered.getExpiresAt().isAfter(now)
        || offered.getId().equals(booking.slot().getId())
        || offered.getCabinCount() != booking.slot().getCabinCount()
        || offered.getSiteCabinCapacity() != booking.slot().getSiteCabinCapacity()
        || !sameAddress(offered, booking.slot())) {
      throw slotTaken();
    }
    CustomerDeliveryProperties.Validated configuration =
        warehouses.validated(booking.session().getWarehouseId());
    RouteContext context =
        routeContext(
            booking.session(),
            offered.getDeliveryDate(),
            offered.getLatitude(),
            offered.getLongitude(),
            offered.getSiteCabinCapacity(),
            configuration,
            now,
            booking.slot().getId());
    OfferDecision decision =
        evaluate(
            context,
            offered.getDeliveryDate(),
            new Window(offered.getKind(), offered.getWindowStart(), offered.getWindowEnd()),
            offered.getCabinCount(),
            offered.getSiteCabinCapacity(),
            configuration);
    if (decision.matrix() == null) throw slotTaken();
    long oneWay = decision.matrix().travelSeconds(0, decision.candidateIndex());
    PriceDecision price = price(context, oneWay);
    WarehouseIdentity warehouse = warehouses.required(booking.session().getWarehouseId());
    RepresentativeDeliverySlotPolicy.Decision policy =
        representativePolicy.evaluate(
            warehouse,
            offered.getDeliveryDate(),
            offered.getKind(),
            offered.getWindowStart(),
            offered.getWindowEnd(),
            decision.capacity().feasible());
    if (price == null
        || context.policy().forbidden()
        || !policy.allowed()
        || !Objects.equals(offered.getDeliveryPriceRubles(), price.deliveryPriceRubles())
        || !Objects.equals(offered.getPriceZoneId(), price.priceZoneId())
        || !Objects.equals(offered.getPriceIsochroneMinutes(), price.priceIsochroneMinutes())) {
      throw slotTaken();
    }
    return new RescheduleDecision(
        request.expectedVersion(),
        booking.session().getWarehouseId(),
        booking.slot().getId(),
        booking.slot().getDeliveryDate(),
        offered.getDeliveryDate(),
        offered.getId(),
        request.slotVersion(),
        context.workloadSha256(),
        policy.flexibleSupport() ? 0 : decision.capacity().capacityRemaining());
  }

  private RouteContext routeContext(
      CustomerRentalSession session,
      LocalDate date,
      BigDecimal latitude,
      BigDecimal longitude,
      int siteCabinCapacity,
      CustomerDeliveryProperties.Validated configuration,
      OffsetDateTime now,
      UUID excludedSlotId) {
    List<CustomerDeliverySlot> existing =
        excludedSlotId == null
            ? CustomerCapacityWorkloadFingerprint.capacitySlots(
                slotStore.workload(session.getWarehouseId(), date, now),
                session.getCustomerSubjectId(),
                session.getInquiryId())
            : CustomerCapacityWorkloadFingerprint.capacitySlots(
                slotStore.workload(session.getWarehouseId(), date, now),
                excludedSlotId);
    List<WarehouseCapacityJob> generated =
        warehouseCapacityJobs.findCapacityWorkload(session.getWarehouseId(), date).stream()
            .filter(job -> job.getTaskType() == WarehouseCapacityTaskType.DELIVERY)
            .toList();
    List<WarehouseCapacityShift> shifts =
        warehouseCapacityShifts.findCapacityShifts(session.getWarehouseId(), date);
    List<WarehouseCapacityIsochroneTariff> isochroneTariffs =
        warehouseCapacityIsochroneTariffs.findTariffs(session.getWarehouseId());
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
            isochroneTariffs,
            priceZones,
            restrictionZones,
            wholeDayReservations);
    List<GeoPoint> points = new ArrayList<>(existing.size() + generated.size() + 2);
    points.add(
        new GeoPoint(
            configuration.depotLatitude().doubleValue(),
            configuration.depotLongitude().doubleValue()));
    existing.forEach(
        slot ->
            points.add(
                new GeoPoint(
                    slot.getLatitude().doubleValue(), slot.getLongitude().doubleValue())));
    generated.forEach(
        job ->
            points.add(
                new GeoPoint(job.getLatitude().doubleValue(), job.getLongitude().doubleValue())));
    int candidateIndex = points.size();
    points.add(new GeoPoint(latitude.doubleValue(), longitude.doubleValue()));
    int maximumShiftCapacity =
        shifts.stream()
            .mapToInt(WarehouseCapacityShift::getCabinCapacity)
            .max()
            .orElse(siteCabinCapacity);
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
          isochroneTariffs,
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
        isochroneTariffs,
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

  private static List<Window> windows(
      CustomerDeliveryProperties.Validated configuration, boolean representativeWarehouse) {
    List<Window> windows = new ArrayList<>();
    if (!representativeWarehouse) {
      LocalTime start = configuration.customerDeliveryStart();
      while (start.isBefore(configuration.customerDeliveryEnd())) {
        LocalTime end = start.plusMinutes(configuration.deliverySlotMinutes());
        windows.add(new Window(CustomerDeliverySlotKind.FIXED_WINDOW, start, end));
        start = end;
      }
    }
    windows.add(
        new Window(
            CustomerDeliverySlotKind.DURING_DAY,
            configuration.customerDeliveryStart(),
            configuration.customerDeliveryEnd()));
    return List.copyOf(windows);
  }

  private static PriceDecision price(RouteContext context, long oneWayTravelSeconds) {
    if (oneWayTravelSeconds < 0) {
      throw new IllegalArgumentException("One-way travel time must be non-negative");
    }
    long requiredMinutes = Math.max(60L, ((oneWayTravelSeconds + 3_599L) / 3_600L) * 60L);
    WarehouseCapacityIsochroneTariff boundaryTariff =
        context.isochroneTariffs().stream()
            .filter(tariff -> tariff.getTravelMinutes() >= requiredMinutes)
            .findFirst()
            .orElse(null);
    if (boundaryTariff == null) return null;
    PriceQuote special = context.policy().specialPrice();
    if (special.deliveryPriceRubles() != null) {
      return new PriceDecision(special.deliveryPriceRubles(), special.priceZoneId(), null);
    }
    return new PriceDecision(
        boundaryTariff.getPriceRubles(), null, boundaryTariff.getTravelMinutes());
  }

  private BookingContext bookingContext(
      CustomerIdentity identity, UUID bookingId, long expectedVersion) {
    CustomerRentalSession session =
        sessions.requiredBooking(identity.subjectId(), bookingId);
    if (session.getVersion() != expectedVersion) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CUSTOMER_BOOKING_VERSION_CONFLICT",
          "Бронирование уже изменилось; обновите данные и повторите действие");
    }
    if (session.getState() != CustomerSessionState.BOOKED || session.getOrderId() == null) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CUSTOMER_BOOKING_NOT_EDITABLE",
          "Бронирование нельзя перенести в его текущем состоянии");
    }
    CustomerDeliverySlot slot =
        slotStore.required(
            identity.subjectId(),
            session.getInquiryId(),
            session.getDeliverySlotId());
    if (slot.getState() != CustomerDeliverySlotState.CONFIRMED
        || !bookingId.equals(slot.getBookingId())
        || !session.getOrderId().equals(slot.getOrderId())) {
      throw slotNotFound();
    }
    OrderDetailResponse order =
        rentalOrders.get(access.orderActor(identity, session.getWarehouseId()), session.getOrderId());
    long activeCabins = order.units().stream().filter(unit -> unit.added()).count();
    if (order.status() != RentalOrderStatus.SAVED
        || !order.permissions().canEdit()
        || !session.getWarehouseId().equals(order.warehouseId())
        || activeCabins != slot.getCabinCount()
        || !sameAddress(order, slot)) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CUSTOMER_BOOKING_NOT_EDITABLE",
          "Бронирование нельзя перенести после начала отгрузки или изменения его состава");
    }
    return new BookingContext(session, slot);
  }

  private static boolean sameAddress(OrderDetailResponse order, CustomerDeliverySlot slot) {
    return Objects.equals(order.deliveryAddress(), slot.getDeliveryAddress())
        && sameDecimal(order.latitude(), slot.getLatitude())
        && sameDecimal(order.longitude(), slot.getLongitude());
  }

  private static boolean sameAddress(
      CustomerDeliverySlot left, CustomerDeliverySlot right) {
    return Objects.equals(left.getDeliveryAddress(), right.getDeliveryAddress())
        && sameDecimal(left.getLatitude(), right.getLatitude())
        && sameDecimal(left.getLongitude(), right.getLongitude());
  }

  private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
    return left != null && right != null && left.compareTo(right) == 0;
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

  private static OrderProblemException slotTaken() {
    return new OrderProblemException(
        HttpStatus.CONFLICT,
        "CUSTOMER_DELIVERY_SLOT_TAKEN",
        "Нагрузка изменилась; выберите доступное время заново");
  }

  /** One immutable fixed or complete-day arrival choice evaluated by the same route planner. */
  private record Window(CustomerDeliverySlotKind kind, LocalTime start, LocalTime end) {}

  /** Matrix and capacity result for one potential offer. */
  private record OfferDecision(
      CustomerTravelTimeMatrix matrix,
      int candidateIndex,
      CapacityDecision capacity,
      CustomerVehicleRouteProfile profile) {}

  /** Exactly one special-zone or ordinary isochrone price explanation for an offered slot. */
  private record PriceDecision(
      long deliveryPriceRubles, UUID priceZoneId, Integer priceIsochroneMinutes) {}

  /** Completed customer booking and its currently confirmed workload row. */
  private record BookingContext(
      CustomerRentalSession session, CustomerDeliverySlot slot) {}

  /** Exact local workload facts shared by all configured windows of one day. */
  private record RouteContext(
      List<GeoPoint> points,
      int candidateIndex,
      List<DeliveryJob> baseJobs,
      String workloadSha256,
      long wholeDayDriverReservations,
      List<CapacityShift> shifts,
      int conservativeTripCapacity,
      List<WarehouseCapacityIsochroneTariff> isochroneTariffs,
      DeliveryPolicy policy) {}
}
