package dev.buhanzaz.rwms.logistics.order.api;

import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.AddOrderUnitRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.CreateOrderRentalShipmentRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.ClientPageResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.ClientResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.CreateClientRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.CreateOrderRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDetailResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderHistoryEventResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.ExtendOrderRentalTermsRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderPageResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderUnitPageResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.SetOrderUnitDesiredEquipmentRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.SetOrderRentalTermsRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.SelectWarehouseRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.UpdateOrderRequest;
import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.order.service.OrderAuditService;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.LogisticsDocumentView;
import dev.buhanzaz.rwms.logistics.order.service.OrderClientService;
import dev.buhanzaz.rwms.logistics.order.service.OrderUnitConflictException;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderService;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP boundary for authenticated rental-order commands and reads.
 */
@RestController
@Validated
@RequestMapping("/api/logistics/v1")
@RequiredArgsConstructor
public class OrderController {
  private final RentalOrderService orders;
  private final OrderClientService clients;
  private final OrderAuthorizer access;
  private final OrderAuditService audit;
  private final LogisticsWarehouseLifecycle warehouseLifecycle;

  @GetMapping("/orders")
  public OrderPageResponse list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "updatedAt") String sort,
      @RequestParam(defaultValue = "DESC") String direction,
      @RequestParam(required = false) List<RentalOrderStatus> status,
      @RequestParam(required = false) List<ClientType> clientType,
      @RequestParam(required = false) List<UUID> warehouseId,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime createdFrom,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime createdTo) {
    return orders.list(
        access.readActor(jwt),
        page,
        size,
        search,
        sort,
        direction,
        status,
        clientType,
        warehouseId,
        createdFrom,
        createdTo);
  }

  @PostMapping("/orders")
  public ResponseEntity<OrderDetailResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateOrderRequest request) {
    RentalOrderService.CreateResult result =
        orders.create(access.writeActor(jwt), idempotencyKey, request);
    return response(
        result.response(),
        result.replayed(),
        result.replayed() ? HttpStatus.OK : HttpStatus.CREATED);
  }

  @GetMapping("/orders/{orderId}")
  public OrderDetailResponse get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
    return orders.get(access.readActor(jwt), orderId);
  }

  @PutMapping("/orders/{orderId}")
  public ResponseEntity<OrderDetailResponse> update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID orderId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody UpdateOrderRequest request) {
    RentalOrderService.MutationResult result =
        orders.update(access.writeActor(jwt), orderId, idempotencyKey, request);
    return response(result.response(), result.replayed(), HttpStatus.OK);
  }

  @DeleteMapping("/orders/{orderId}")
  public ResponseEntity<OrderDetailResponse> cancel(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID orderId,
      @RequestParam @Min(0) long expectedVersion,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey) {
    RentalOrderService.MutationResult result =
        orders.cancel(access.writeActor(jwt), orderId, expectedVersion, idempotencyKey);
    return response(result.response(), result.replayed(), HttpStatus.OK);
  }

  @PostMapping("/orders/{orderId}/save")
  public ResponseEntity<OrderDetailResponse> save(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID orderId,
      @RequestParam @Min(0) long expectedVersion,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      HttpServletRequest servletRequest) {
    RentalOrderService.MutationResult result =
        orders.save(
            access.writeActor(jwt),
            orderId,
            expectedVersion,
            idempotencyKey,
            correlationId(servletRequest));
    return response(result.response(), result.replayed(), HttpStatus.OK);
  }

  @PutMapping("/orders/{orderId}/rental-terms")
  public ResponseEntity<OrderDetailResponse> setRentalTerms(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID orderId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody SetOrderRentalTermsRequest request) {
    RentalOrderService.MutationResult result =
        orders.setRentalTerms(access.writeActor(jwt), orderId, idempotencyKey, request);
    return response(result.response(), result.replayed(), HttpStatus.OK);
  }

  @PostMapping("/orders/{orderId}/rental-terms/extend")
  public ResponseEntity<OrderDetailResponse> extendRentalTerms(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID orderId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ExtendOrderRentalTermsRequest request) {
    RentalOrderService.MutationResult result =
        orders.extendRentalTerms(access.writeActor(jwt), orderId, idempotencyKey, request);
    return response(result.response(), result.replayed(), HttpStatus.OK);
  }

  /** Creates one selected-cabin shipment draft; preparation starts only via the shipment plan. */
  @PostMapping("/orders/{orderId}/shipments")
  public ResponseEntity<LogisticsDocumentView> createRentalShipment(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID orderId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateOrderRentalShipmentRequest request,
      HttpServletRequest servletRequest) {
    OrderActor actor = access.writeActor(jwt);
    UUID warehouseId =
        orders.rentalShipmentAdmissionWarehouse(actor, orderId, request.scheduledDate());
    var admission =
        warehouseLifecycle.prepareDocument(
            actor.subjectId(),
            "CREATE_RENTAL_ORDER_SHIPMENT",
            idempotencyKey,
            List.of(
                new AdmissionRequirement(
                    warehouseId, WarehouseOperationDirection.OUTGOING)));
    LogisticsDocumentService.CreateResult result =
        orders.createRentalShipment(
            actor,
            orderId,
            idempotencyKey,
            correlationId(servletRequest),
            request,
            admission);
    return documentResponse(result.response(), result.replayed());
  }

  @PutMapping("/orders/{orderId}/warehouse")
  public ResponseEntity<OrderDetailResponse> selectWarehouse(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID orderId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody SelectWarehouseRequest request) {
    RentalOrderService.MutationResult result =
        orders.selectWarehouse(
            access.writeActor(jwt), orderId, idempotencyKey, request);
    return response(result.response(), result.replayed(), HttpStatus.OK);
  }

  @GetMapping("/orders/{orderId}/available-units")
  public OrderUnitPageResponse availableUnits(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID orderId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size,
      @RequestParam(defaultValue = "") String search) {
    return orders.availableUnits(access.readActor(jwt), orderId, page, size, search);
  }

  @PostMapping("/orders/{orderId}/units")
  public ResponseEntity<OrderDetailResponse> addUnit(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID orderId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody AddOrderUnitRequest request) {
    OrderActor actor = access.writeActor(jwt);
    try {
      RentalOrderService.MutationResult result =
          orders.addUnit(actor, orderId, idempotencyKey, request);
      return response(
          result.response(),
          result.replayed(),
          result.replayed() ? HttpStatus.OK : HttpStatus.CREATED);
    } catch (OrderUnitConflictException exception) {
      // The service transaction (and its order row lock) has ended before this append.
      audit.appendUnitConflict(
          exception.orderId(), exception.unitId(), actor, exception.code());
      throw exception;
    }
  }

  @DeleteMapping("/orders/{orderId}/units/{unitId}")
  public ResponseEntity<OrderDetailResponse> removeUnit(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID orderId,
      @PathVariable UUID unitId,
      @RequestParam @Min(0) long expectedVersion,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey) {
    RentalOrderService.MutationResult result =
        orders.removeUnit(
            access.writeActor(jwt), orderId, unitId, expectedVersion, idempotencyKey);
    return response(result.response(), result.replayed(), HttpStatus.OK);
  }

  @PutMapping("/orders/{orderId}/units/{unitId}/desired-equipment")
  public ResponseEntity<OrderDetailResponse> setDesiredEquipment(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID orderId,
      @PathVariable UUID unitId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody SetOrderUnitDesiredEquipmentRequest request) {
    RentalOrderService.MutationResult result =
        orders.setDesiredEquipment(
            access.writeActor(jwt), orderId, unitId, idempotencyKey, request);
    return response(result.response(), result.replayed(), HttpStatus.OK);
  }

  @GetMapping("/orders/{orderId}/history")
  public List<OrderHistoryEventResponse> history(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
    return orders.history(access.readActor(jwt), orderId);
  }

  @GetMapping("/clients")
  public ClientPageResponse clients(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) ClientType type,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
    return clients.search(access.readActor(jwt), type, search, page, size);
  }

  @GetMapping("/clients/{clientId}")
  public ResponseEntity<ClientResponse> client(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID clientId) {
    ClientResponse response = clients.get(access.readActor(jwt), clientId);
    return ResponseEntity.ok().eTag(Long.toString(response.version())).body(response);
  }

  @GetMapping("/clients/{clientId}/orders")
  public OrderPageResponse clientOrders(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID clientId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
      @RequestParam(defaultValue = "updatedAt") String sort,
      @RequestParam(defaultValue = "DESC") String direction,
      @RequestParam(required = false) List<RentalOrderStatus> status,
      @RequestParam(required = false) List<UUID> warehouseId,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime createdFrom,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime createdTo) {
    return clients.orders(
        access.readActor(jwt),
        clientId,
        page,
        size,
        sort,
        direction,
        status,
        warehouseId,
        createdFrom,
        createdTo);
  }

  @PostMapping("/clients")
  public ResponseEntity<ClientResponse> createClient(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateClientRequest request) {
    OrderClientService.CreateResult result =
        clients.create(access.writeActor(jwt), idempotencyKey, request);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
            .eTag(Long.toString(result.response().version()));
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  private static ResponseEntity<OrderDetailResponse> response(
      OrderDetailResponse body, boolean replayed, HttpStatus status) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(status).eTag(Long.toString(body.version()));
    if (replayed) response.header("Idempotency-Replayed", "true");
    return response.body(body);
  }

  private static ResponseEntity<LogisticsDocumentView> documentResponse(
      LogisticsDocumentView body, boolean replayed) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(replayed ? HttpStatus.OK : HttpStatus.CREATED)
            .eTag(Long.toString(body.version()));
    if (replayed) response.header("Idempotency-Replayed", "true");
    return response.body(body);
  }

  private static UUID correlationId(HttpServletRequest request) {
    Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    try {
      return UUID.fromString(String.valueOf(value));
    } catch (IllegalArgumentException exception) {
      return UUID.randomUUID();
    }
  }
}
