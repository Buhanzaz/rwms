package dev.buhanzaz.rwms.logistics.api;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateHistoricalRentalMovementRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.HistoricalRentalMovementKind;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.LogisticsDocumentView;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.order.service.OrderClientService;
import dev.buhanzaz.rwms.logistics.security.LogisticsAuthorizer;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public boundary for importing one past rental shipment or return from a warehouse cabin card.
 * It authorizes the warehouse and rental client independently before the logistics saga records
 * its durable document and recovery work.
 */
@RestController
@Validated
@RequestMapping("/api/logistics/v1/historical-rental-movements")
@RequiredArgsConstructor
public class HistoricalRentalMovementController {
  private static final String CREATE_HISTORICAL_RENTAL_MOVEMENT =
      "CREATE_HISTORICAL_RENTAL_MOVEMENT";

  private final LogisticsDocumentService documents;
  private final LogisticsWarehouseLifecycle warehouseLifecycle;
  private final LogisticsAuthorizer logisticsAccess;
  private final OrderAuthorizer orderAccess;
  private final OrderClientService clients;

  @PostMapping
  public ResponseEntity<LogisticsDocumentView> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateHistoricalRentalMovementRequest request,
      HttpServletRequest servletRequest) {
    logisticsAccess.requireEdit(jwt, request.warehouseId());
    var actor = orderAccess.writeActor(jwt);
    orderAccess.requireWarehouseEdit(actor, request.warehouseId());
    OrderClient client = clients.required(actor, request.clientId());
    UUID subjectId = logisticsAccess.subjectId(jwt);
    var admission =
        warehouseLifecycle.prepareDocument(
            subjectId,
            CREATE_HISTORICAL_RENTAL_MOVEMENT,
            idempotencyKey,
            List.of(
                new AdmissionRequirement(request.warehouseId(), direction(request.kind()))));
    LogisticsDocumentService.CreateResult result =
        documents.createHistoricalRentalMovement(
            subjectId,
            idempotencyKey,
            correlationId(servletRequest),
            request,
            client.getDisplayName(),
            admission);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
            .eTag(Long.toString(result.response().version()));
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  private static WarehouseOperationDirection direction(HistoricalRentalMovementKind kind) {
    if (kind == null) throw new IllegalArgumentException("Historical rental operation kind is required");
    return kind == HistoricalRentalMovementKind.SHIPMENT
        ? WarehouseOperationDirection.OUTGOING
        : WarehouseOperationDirection.INCOMING;
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
