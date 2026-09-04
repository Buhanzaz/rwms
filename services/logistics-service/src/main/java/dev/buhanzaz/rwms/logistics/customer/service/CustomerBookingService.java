package dev.buhanzaz.rwms.logistics.customer.service;

import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.AcceptCustomerCabinRequest;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerBookingCabin;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerBookingResponse;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinAcceptanceResponse;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinProblemPhase;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinProblemResponse;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerProblemMediaReference;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerShipmentMediaOwner;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.ReportCustomerCabinProblemRequest;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerCabinAcceptance;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerCabinProblem;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.mapper.CustomerReceptionResponseMapper;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerCabinAcceptanceRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerCabinProblemRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerDeliverySlotRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerRentalSessionRepository;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerAuthorizer;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.LogisticsOwnerType;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDetailResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderUnitResponse;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderService;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns CustomerApp booking reception reads, signed per-cabin acceptance and immutable problem
 * reports. Arrival eligibility comes only from the exact grouped shipment driver task.
 */
@Service
@RequiredArgsConstructor
public class CustomerBookingService {
  private final CustomerRentalSessionRepository sessions;
  private final CustomerDeliverySlotRepository slots;
  private final CustomerCabinAcceptanceRepository acceptances;
  private final CustomerCabinProblemRepository problems;
  private final LogisticsDocumentRepository documents;
  private final LogisticsDocumentLineRepository lines;
  private final DriverLogisticsTaskRepository driverTasks;
  private final RentalOrderService rentalOrders;
  private final CustomerAuthorizer access;
  private final LogisticsDependencyGateway dependencies;
  private final CustomerReceptionResponseMapper responseMapper;
  private final LogisticsTransactionLock transactionLock;
  private final ObjectMapper json;
  private final Clock clock;

  /** Builds one customer-owned booking with current per-cabin reception state. */
  @Transactional(readOnly = true)
  public CustomerBookingResponse response(
      CustomerIdentity identity,
      CustomerRentalSession session,
      String status,
      String errorCode) {
    requireOwner(identity, session);
    CustomerDeliverySlot slot = deliverySlot(session);
    List<CustomerBookingCabin> cabins =
        session.getOrderId() == null ? List.of() : bookingCabins(identity, session);
    return new CustomerBookingResponse(
        session.getBookingId(),
        session.getVersion(),
        session.getOrderId(),
        status,
        errorCode,
        session.getInquiryId(),
        session.getDeliverySlotId(),
        session.getWarehouseId(),
        slot == null ? null : slot.getDeliveryAddress(),
        slot == null ? null : slot.getDeliveryDate(),
        slot == null ? null : slot.getWindowStart(),
        slot == null ? null : slot.getWindowEnd(),
        null,
        cabins);
  }

  /** Returns all current customer bookings with server-owned cabin arrival state. */
  @Transactional(readOnly = true)
  public List<CustomerBookingResponse> list(CustomerIdentity identity) {
    return sessions
        .findAllByCustomerSubjectIdOrderByCreatedAtDescIdDesc(identity.subjectId())
        .stream()
        .filter(session -> session.getBookingId() != null)
        .map(
            session ->
                response(
                    identity,
                    session,
                    status(session),
                    null))
        .toList();
  }

  /** Maps durable session state to the stable CustomerApp booking lifecycle. */
  public static String status(CustomerRentalSession session) {
    return switch (session.getState()) {
      case BOOKED -> "COMPLETED";
      case CANCEL_PENDING -> "CANCELLATION_PENDING";
      case CANCELLED -> "CANCELLED";
      default -> "PENDING";
    };
  }

  /** Accepts exactly one arrived cabin with an idempotent canonical drawn signature. */
  @Transactional
  public CustomerCabinAcceptanceResponse accept(
      CustomerIdentity identity,
      UUID bookingId,
      UUID cabinId,
      UUID idempotencyKey,
      AcceptCustomerCabinRequest request) {
    String signatureJson = write(request);
    String requestSha256 = sha256(signatureJson);
    transactionLock.acquire(
        "customer-cabin-acceptance:"
            + identity.subjectId()
            + ":"
            + idempotencyKey);
    CustomerCabinAcceptance replay =
        acceptances
            .findByCustomerSubjectIdAndIdempotencyKey(identity.subjectId(), idempotencyKey)
            .orElse(null);
    if (replay != null) {
      if (!bookingId.equals(replay.getBookingId())
          || !cabinId.equals(replay.getCabinUnitId())
          || !replay.matchesRequest(requestSha256)) {
        throw conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key уже использован для другой приёмки");
      }
      return responseMapper.toResponse(replay);
    }
    if (acceptances
        .findByBookingIdAndCabinUnitId(bookingId, cabinId)
        .isPresent()) {
      throw conflict("CUSTOMER_CABIN_ALREADY_ACCEPTED", "Бытовка уже принята");
    }
    ArrivedCabin arrived = requiredArrivedCabin(identity, bookingId, cabinId);
    int pointCount =
        Math.toIntExact(
            request.strokes().stream().mapToLong(stroke -> stroke.points().size()).sum());
    CustomerCabinAcceptance saved =
        acceptances.saveAndFlush(
            CustomerCabinAcceptance.create(
                identity.subjectId(),
                bookingId,
                arrived.orderId(),
                arrived.warehouseId(),
                cabinId,
                arrived.documentId(),
                arrived.lineId(),
                arrived.driverTaskId(),
                idempotencyKey,
                requestSha256,
                signatureJson,
                pointCount,
                now()));
    return responseMapper.toResponse(saved);
  }

  /** Reports a problem for one arrived cabin before or after its signed acceptance. */
  @Transactional
  public CustomerCabinProblemResponse reportProblem(
      CustomerIdentity identity,
      UUID bookingId,
      UUID cabinId,
      UUID idempotencyKey,
      ReportCustomerCabinProblemRequest request) {
    List<CustomerProblemMediaReference> media =
        request.mediaReferences().stream()
            .sorted(
                Comparator.comparing(CustomerProblemMediaReference::mediaId)
                    .thenComparingLong(CustomerProblemMediaReference::generation))
            .toList();
    String description = request.description().trim();
    String mediaJson = write(media);
    String requestSha256 =
        sha256(request.category().name() + "\n" + description + "\n" + mediaJson);
    transactionLock.acquire(
        "customer-cabin-problem:"
            + identity.subjectId()
            + ":"
            + idempotencyKey);
    CustomerCabinProblem replay =
        problems
            .findByCustomerSubjectIdAndIdempotencyKey(identity.subjectId(), idempotencyKey)
            .orElse(null);
    if (replay != null) {
      if (!bookingId.equals(replay.getBookingId())
          || !cabinId.equals(replay.getCabinUnitId())
          || !replay.matchesRequest(requestSha256)) {
        throw conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key уже использован для другой проблемы");
      }
      return problem(replay);
    }
    ArrivedCabin arrived = requiredArrivedCabin(identity, bookingId, cabinId);
    if (!media.isEmpty()) {
      dependencies.validateMediaReferences(
          LogisticsOwnerType.LOGISTICS_SHIPMENT,
          arrived.documentId(),
          arrived.lineId(),
          arrived.warehouseId(),
          media.stream()
              .map(
                  reference ->
                      new LogisticsDependencyGateway.MediaReference(
                          reference.mediaId(), reference.generation()))
              .toList());
    }
    CustomerCabinProblemPhase phase =
        acceptances
                .findByBookingIdAndCabinUnitId(bookingId, cabinId)
                .isPresent()
            ? CustomerCabinProblemPhase.AFTER_ACCEPTANCE
            : CustomerCabinProblemPhase.BEFORE_ACCEPTANCE;
    CustomerCabinProblem saved =
        problems.saveAndFlush(
            CustomerCabinProblem.create(
                identity.subjectId(),
                bookingId,
                arrived.orderId(),
                arrived.warehouseId(),
                cabinId,
                arrived.documentId(),
                arrived.lineId(),
                arrived.driverTaskId(),
                request.category(),
                phase,
                description,
                mediaJson,
                idempotencyKey,
                requestSha256,
                now()));
    return problem(saved);
  }

  private List<CustomerBookingCabin> bookingCabins(
      CustomerIdentity identity, CustomerRentalSession session) {
    OrderDetailResponse order =
        rentalOrders.get(access.orderActor(identity, session.getWarehouseId()), session.getOrderId());
    ShipmentProjection shipments = shipmentProjection(order.id());
    Map<UUID, CustomerCabinAcceptance> acceptanceByCabin =
        acceptances
            .findAllByBookingIdOrderByAcceptedAtAscIdAsc(session.getBookingId())
            .stream()
            .collect(Collectors.toMap(CustomerCabinAcceptance::getCabinUnitId, value -> value));
    Map<UUID, List<CustomerCabinProblem>> problemsByCabin =
        problems
            .findAllByBookingIdOrderByReportedAtAscIdAsc(session.getBookingId())
            .stream()
            .collect(Collectors.groupingBy(CustomerCabinProblem::getCabinUnitId, LinkedHashMap::new, Collectors.toList()));
    List<CustomerBookingCabin> result = new ArrayList<>();
    for (OrderUnitResponse unit : order.units()) {
      if (!unit.added()) continue;
      UUID cabinId = unit.unit().id();
      ShipmentCabin shipment = shipments.byCabin().get(cabinId);
      boolean arrived = shipment != null && shipment.arrived();
      long rentalMonths =
          unit.rentalTerm() == null ? 0 : unit.rentalTerm().rentalMonths();
      if (rentalMonths < 1) continue;
      result.add(
          new CustomerBookingCabin(
              cabinId,
              unit.unit().number(),
              rentalMonths,
              shipment == null ? "PLANNING" : arrived ? "ARRIVED" : "SCHEDULED",
              arrived,
              shipment == null
                  ? null
                  : new CustomerShipmentMediaOwner(
                      "LOGISTICS_SHIPMENT",
                      shipment.documentId(),
                      shipment.lineId(),
                      session.getWarehouseId(),
                      "SHIPMENT"),
              responseMapper.toResponse(acceptanceByCabin.get(cabinId)),
              problemsByCabin.getOrDefault(cabinId, List.of()).stream()
                  .map(this::problem)
                  .toList()));
    }
    return List.copyOf(result);
  }

  private ArrivedCabin requiredArrivedCabin(
      CustomerIdentity identity, UUID bookingId, UUID cabinId) {
    CustomerRentalSession session =
        sessions
            .findByBookingIdAndCustomerSubjectId(bookingId, identity.subjectId())
            .orElseThrow(CustomerBookingService::bookingNotFound);
    if (session.getOrderId() == null) throw bookingNotFound();
    ShipmentCabin shipment = shipmentProjection(session.getOrderId()).byCabin().get(cabinId);
    if (shipment == null || !shipment.arrived() || shipment.driverTaskId() == null) {
      throw conflict(
          "CUSTOMER_CABIN_NOT_ARRIVED",
          "Приёмка и проблема доступны только после завершения доставки бытовки");
    }
    return new ArrivedCabin(
        session.getOrderId(),
        session.getWarehouseId(),
        shipment.documentId(),
        shipment.lineId(),
        shipment.driverTaskId());
  }

  private ShipmentProjection shipmentProjection(UUID orderId) {
    List<LogisticsDocument> shipmentDocuments =
        documents
            .findAllByDocumentTypeAndRentalOrderIdOrderByCreatedAtAscIdAsc(
                LogisticsDocumentType.SHIPMENT, orderId)
            .stream()
            .filter(document -> document.getState() != LogisticsDocumentState.CANCELLED)
            .toList();
    if (shipmentDocuments.isEmpty()) return new ShipmentProjection(Map.of());
    Set<UUID> documentIds =
        shipmentDocuments.stream().map(LogisticsDocument::getId).collect(Collectors.toSet());
    Map<UUID, LogisticsDocument> documentById =
        shipmentDocuments.stream()
            .collect(Collectors.toMap(LogisticsDocument::getId, value -> value));
    Map<UUID, DriverLogisticsTask> taskByDocument = new HashMap<>();
    for (DriverLogisticsTask task :
        driverTasks.findAllBySourceTypeAndSourceIdIn(
            DriverTaskSourceType.LOGISTICS_DOCUMENT, documentIds)) {
      if (task.getKind() == DriverTaskKind.SHIPMENT
          && task.getState() != DriverTaskState.CANCELLED) {
        taskByDocument.put(task.getSourceId(), task);
      }
    }
    Map<UUID, ShipmentCabin> byCabin = new LinkedHashMap<>();
    for (LogisticsDocumentLine line : lines.findAllByDocumentIdIn(documentIds)) {
      LogisticsDocument document = documentById.get(line.getDocument().getId());
      if (document == null) continue;
      DriverLogisticsTask task = taskByDocument.get(document.getId());
      boolean exactMember =
          task != null
              && task.getMembers().stream()
                  .anyMatch(
                      member ->
                          line.getId().equals(member.getDocumentLineId())
                              && line.getAssetId().equals(member.getCabinId()));
      boolean arrived = exactMember && task.getState() == DriverTaskState.COMPLETED;
      byCabin.put(
          line.getAssetId(),
          new ShipmentCabin(
              document.getId(),
              line.getId(),
              task == null ? null : task.getId(),
              arrived));
    }
    return new ShipmentProjection(Map.copyOf(byCabin));
  }

  private CustomerDeliverySlot deliverySlot(CustomerRentalSession session) {
    if (session.getDeliverySlotId() == null) return null;
    CustomerDeliverySlot slot =
        slots
            .findById(session.getDeliverySlotId())
            .orElse(null);
    if (slot == null
        || !session.getCustomerSubjectId().equals(slot.getCustomerSubjectId())
        || !session.getInquiryId().equals(slot.getInquiryId())) return null;
    return slot;
  }

  private static void requireOwner(CustomerIdentity identity, CustomerRentalSession session) {
    if (!identity.subjectId().equals(session.getCustomerSubjectId())) {
      throw bookingNotFound();
    }
  }

  private CustomerCabinProblemResponse problem(CustomerCabinProblem problem) {
    return responseMapper.toResponse(problem, readMedia(problem.getMediaReferencesJson()));
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Customer reception evidence could not be serialized", exception);
    }
  }

  private List<CustomerProblemMediaReference> readMedia(String value) {
    try {
      return json.readValue(
          value, new TypeReference<List<CustomerProblemMediaReference>>() {});
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored customer problem media is invalid", exception);
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock)
        .withOffsetSameInstant(ZoneOffset.UTC)
        .truncatedTo(ChronoUnit.MICROS);
  }

  private static OrderProblemException bookingNotFound() {
    return new OrderProblemException(
        HttpStatus.NOT_FOUND, "CUSTOMER_BOOKING_NOT_FOUND", "Бронирование не найдено");
  }

  private static OrderProblemException conflict(String code, String message) {
    return new OrderProblemException(HttpStatus.CONFLICT, code, message);
  }

  /** Current shipment, line and exact driver-task outcome for one order cabin. */
  private record ShipmentCabin(
      UUID documentId, UUID lineId, UUID driverTaskId, boolean arrived) {}

  /** Immutable cabin-indexed shipment projection used by one booking read. */
  private record ShipmentProjection(Map<UUID, ShipmentCabin> byCabin) {}

  /** Exact arrived shipment facts required by one acceptance or problem command. */
  private record ArrivedCabin(
      UUID orderId, UUID warehouseId, UUID documentId, UUID lineId, UUID driverTaskId) {}
}
