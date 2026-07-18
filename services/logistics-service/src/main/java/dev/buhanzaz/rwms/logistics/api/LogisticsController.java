package dev.buhanzaz.rwms.logistics.api;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateReturnRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateShipmentRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateTransferRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.AcceptReturnRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ArriveTransferLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.LogisticsDocumentView;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.RequestReturnEstimateRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReconcileRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentPlanRequest;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.security.LogisticsAuthorizer;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/logistics/v1")
@RequiredArgsConstructor
public class LogisticsController {
  private final LogisticsDocumentService service;
  private final LogisticsAuthorizer access;

  @GetMapping("/returns")
  public List<LogisticsDocumentView> listReturns(
      @AuthenticationPrincipal Jwt jwt, @RequestParam UUID warehouseId) {
    access.requireRead(jwt, warehouseId);
    return service.list(LogisticsDocumentType.RETURN, warehouseId);
  }

  @GetMapping("/returns/{documentId}")
  public LogisticsDocumentView getReturn(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID documentId) {
    return read(jwt, documentId, LogisticsDocumentType.RETURN);
  }

  @PostMapping("/returns")
  public ResponseEntity<LogisticsDocumentView> createReturn(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateReturnRequest request,
      HttpServletRequest servletRequest) {
    access.requireEdit(jwt, request.warehouseId());
    LogisticsDocumentService.CreateResult result =
        service.createReturn(access.subjectId(jwt), idempotencyKey, correlationId(servletRequest), request);
    return created(result);
  }

  @PostMapping("/returns/{documentId}/register")
  public ResponseEntity<LogisticsDocumentView> registerReturn(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID documentId,
      @RequestParam @Min(0) long expectedVersion,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      HttpServletRequest servletRequest) {
    LogisticsDocumentView current = service.get(documentId, LogisticsDocumentType.RETURN);
    access.requireEdit(jwt, current.warehouseId());
    LogisticsDocumentService.CreateResult result =
        service.registerReturn(
            access.subjectId(jwt),
            idempotencyKey,
            correlationId(servletRequest),
            documentId,
            expectedVersion);
    return accepted(result);
  }

  @PostMapping("/returns/{documentId}/accept-undamaged")
  public ResponseEntity<LogisticsDocumentView> acceptUndamagedReturn(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID documentId,
      @RequestParam @Min(0) long expectedVersion,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody AcceptReturnRequest request,
      HttpServletRequest servletRequest) {
    LogisticsDocumentView current = service.get(documentId, LogisticsDocumentType.RETURN);
    access.requireEdit(jwt, current.warehouseId());
    LogisticsDocumentService.CreateResult result =
        service.acceptUndamagedReturn(
            access.subjectId(jwt),
            idempotencyKey,
            correlationId(servletRequest),
            documentId,
            expectedVersion,
            request);
    return accepted(result);
  }

  @PostMapping("/returns/{documentId}/request-estimate")
  public ResponseEntity<LogisticsDocumentView> requestReturnEstimate(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID documentId,
      @RequestParam @Min(0) long expectedVersion,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody RequestReturnEstimateRequest request,
      HttpServletRequest servletRequest) {
    LogisticsDocumentView current = service.get(documentId, LogisticsDocumentType.RETURN);
    access.requireEdit(jwt, current.warehouseId());
    LogisticsDocumentService.CreateResult result =
        service.requestReturnEstimate(
            access.subjectId(jwt),
            idempotencyKey,
            correlationId(servletRequest),
            documentId,
            expectedVersion,
            request);
    return accepted(result);
  }

  @GetMapping("/shipments")
  public List<LogisticsDocumentView> listShipments(
      @AuthenticationPrincipal Jwt jwt, @RequestParam UUID warehouseId) {
    access.requireRead(jwt, warehouseId);
    return service.list(LogisticsDocumentType.SHIPMENT, warehouseId);
  }

  @GetMapping("/shipments/{documentId}")
  public LogisticsDocumentView getShipment(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID documentId) {
    return read(jwt, documentId, LogisticsDocumentType.SHIPMENT);
  }

  @PostMapping("/shipments")
  public ResponseEntity<LogisticsDocumentView> createShipment(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateShipmentRequest request,
      HttpServletRequest servletRequest) {
    access.requireEdit(jwt, request.warehouseId());
    LogisticsDocumentService.CreateResult result =
        service.createShipment(access.subjectId(jwt), idempotencyKey, correlationId(servletRequest), request);
    return created(result);
  }

  @PutMapping("/shipments/{documentId}/plan")
  public ResponseEntity<LogisticsDocumentView> planShipment(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID documentId,
      @RequestParam @Min(0) long expectedVersion,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ShipmentPlanRequest request,
      HttpServletRequest servletRequest) {
    LogisticsDocumentView current = service.get(documentId, LogisticsDocumentType.SHIPMENT);
    access.requireEdit(jwt, current.warehouseId());
    return accepted(
        service.planShipment(
            access.subjectId(jwt),
            idempotencyKey,
            correlationId(servletRequest),
            documentId,
            expectedVersion,
            request));
  }

  @PostMapping("/shipments/{documentId}/confirm-preparation")
  public ResponseEntity<LogisticsDocumentView> confirmShipmentPreparation(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID documentId,
      @RequestParam @Min(0) long expectedVersion,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      HttpServletRequest servletRequest) {
    LogisticsDocumentView current = service.get(documentId, LogisticsDocumentType.SHIPMENT);
    access.requireEdit(jwt, current.warehouseId());
    return accepted(
        service.confirmShipmentPreparation(
            access.subjectId(jwt),
            idempotencyKey,
            correlationId(servletRequest),
            documentId,
            expectedVersion));
  }

  @PostMapping("/shipments/{documentId}/cancel")
  public ResponseEntity<LogisticsDocumentView> cancelShipment(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID documentId,
      @RequestParam @Min(0) long expectedVersion,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      HttpServletRequest servletRequest) {
    LogisticsDocumentView current = service.get(documentId, LogisticsDocumentType.SHIPMENT);
    access.requireEdit(jwt, current.warehouseId());
    return accepted(
        service.cancelShipment(
            access.subjectId(jwt),
            idempotencyKey,
            correlationId(servletRequest),
            documentId,
            expectedVersion));
  }

  @GetMapping("/transfers")
  public List<LogisticsDocumentView> listTransfers(
      @AuthenticationPrincipal Jwt jwt, @RequestParam UUID warehouseId) {
    access.requireRead(jwt, warehouseId);
    return service.list(LogisticsDocumentType.TRANSFER, warehouseId);
  }

  @GetMapping("/transfers/{documentId}")
  public LogisticsDocumentView getTransfer(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID documentId) {
    return read(jwt, documentId, LogisticsDocumentType.TRANSFER);
  }

  @PostMapping("/transfers")
  public ResponseEntity<LogisticsDocumentView> createTransfer(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateTransferRequest request,
      HttpServletRequest servletRequest) {
    access.requireEdit(jwt, request.warehouseId());
    access.requireEdit(jwt, request.destinationWarehouseId());
    LogisticsDocumentService.CreateResult result =
        service.createTransfer(access.subjectId(jwt), idempotencyKey, correlationId(servletRequest), request);
    return created(result);
  }

  @PostMapping("/transfers/{documentId}/lines/{lineId}/depart")
  public ResponseEntity<LogisticsDocumentView> departTransferLine(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID documentId,
      @PathVariable UUID lineId,
      @RequestParam @Min(0) long expectedVersion,
      @RequestParam @Min(0) long expectedLineVersion,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      HttpServletRequest servletRequest) {
    LogisticsDocumentView current = service.get(documentId, LogisticsDocumentType.TRANSFER);
    access.requireManageBoth(jwt, current.warehouseId(), current.destinationWarehouseId());
    return accepted(
        service.departTransferLine(
            access.subjectId(jwt),
            idempotencyKey,
            correlationId(servletRequest),
            documentId,
            lineId,
            expectedVersion,
            expectedLineVersion));
  }

  @PostMapping("/transfers/{documentId}/lines/{lineId}/arrive")
  public ResponseEntity<LogisticsDocumentView> arriveTransferLine(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID documentId,
      @PathVariable UUID lineId,
      @RequestParam @Min(0) long expectedVersion,
      @RequestParam @Min(0) long expectedLineVersion,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ArriveTransferLineRequest request,
      HttpServletRequest servletRequest) {
    LogisticsDocumentView current = service.get(documentId, LogisticsDocumentType.TRANSFER);
    access.requireManageBoth(jwt, current.warehouseId(), current.destinationWarehouseId());
    return accepted(
        service.arriveTransferLine(
            access.subjectId(jwt),
            idempotencyKey,
            correlationId(servletRequest),
            documentId,
            lineId,
            expectedVersion,
            expectedLineVersion,
            request));
  }

  @PostMapping("/transfers/{documentId}/cancel")
  public ResponseEntity<LogisticsDocumentView> cancelTransfer(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID documentId,
      @RequestParam @Min(0) long expectedVersion,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      HttpServletRequest servletRequest) {
    LogisticsDocumentView current = service.get(documentId, LogisticsDocumentType.TRANSFER);
    access.requireManageBoth(jwt, current.warehouseId(), current.destinationWarehouseId());
    return accepted(
        service.cancelTransfer(
            access.subjectId(jwt),
            idempotencyKey,
            correlationId(servletRequest),
            documentId,
            expectedVersion));
  }

  @PostMapping("/{documentType}/{documentId}/reconcile")
  public ResponseEntity<LogisticsDocumentView> reconcile(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String documentType,
      @PathVariable UUID documentId,
      @RequestParam @Min(0) long expectedVersion,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ReconcileRequest request,
      HttpServletRequest servletRequest) {
    LogisticsDocumentType type = documentType(documentType);
    LogisticsDocumentView current = service.get(documentId, type);
    if (current.destinationWarehouseId() == null) {
      access.requireManage(jwt, current.warehouseId());
    } else {
      access.requireManageBoth(jwt, current.warehouseId(), current.destinationWarehouseId());
    }
    return accepted(
        service.reconcile(
            access.subjectId(jwt),
            idempotencyKey,
            correlationId(servletRequest),
            documentId,
            type,
            expectedVersion,
            request));
  }

  private LogisticsDocumentView read(Jwt jwt, UUID documentId, LogisticsDocumentType type) {
    LogisticsDocumentView document = service.get(documentId, type);
    access.requireRead(jwt, document.warehouseId());
    if (document.destinationWarehouseId() != null) {
      access.requireRead(jwt, document.destinationWarehouseId());
    }
    return document;
  }

  private static ResponseEntity<LogisticsDocumentView> created(
      LogisticsDocumentService.CreateResult result) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.CREATED)
            .header(HttpHeaders.ETAG, eTag(result.response().version()));
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  private static ResponseEntity<LogisticsDocumentView> accepted(
      LogisticsDocumentService.CreateResult result) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.accepted().header(HttpHeaders.ETAG, eTag(result.response().version()));
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  private static String eTag(long version) {
    return "\"" + version + "\"";
  }

  private static LogisticsDocumentType documentType(String value) {
    return switch (value) {
      case "returns" -> LogisticsDocumentType.RETURN;
      case "shipments" -> LogisticsDocumentType.SHIPMENT;
      case "transfers" -> LogisticsDocumentType.TRANSFER;
      default -> throw new IllegalArgumentException("Unsupported logistics document type");
    };
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
