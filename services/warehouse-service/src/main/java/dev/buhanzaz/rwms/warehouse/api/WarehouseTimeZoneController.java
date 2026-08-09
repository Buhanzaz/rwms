package dev.buhanzaz.rwms.warehouse.api;

import dev.buhanzaz.rwms.warehouse.security.WarehouseAuthorizer;
import dev.buhanzaz.rwms.warehouse.service.WarehouseOperationSource;
import dev.buhanzaz.rwms.warehouse.service.WarehouseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Private timezone and operation-evidence boundaries for owning services.
 *
 * <p>Neither route is gateway-routable. The required timestamp on the read prevents a caller from
 * accidentally applying current metadata to a historical operation.
 */
@RestController
@Validated
@RequestMapping("/api/internal/warehouse/v1/warehouses")
public class WarehouseTimeZoneController {
  private final WarehouseService service;
  private final WarehouseAuthorizer access;

  /**
   * Creates the private operational-time controller.
   *
   * @param service application boundary that resolves time and records operation evidence
   * @param access authorization boundary that derives an operation owner
   */
  public WarehouseTimeZoneController(WarehouseService service, WarehouseAuthorizer access) {
    this.service = service;
    this.access = access;
  }

  /**
   * Resolves the IANA timezone effective at the supplied operation timestamp.
   *
   * @param jwt authenticated lifecycle-owner credential
   * @param id stable warehouse identity
   * @param at operation timestamp to resolve
   * @return effective timezone decision
   */
  @GetMapping("/{id}/time-zone")
  public WarehouseTimeZoneAtResponse timeZoneAt(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam @NotNull OffsetDateTime at) {
    access.requireInternalTimeZoneReader(jwt);
    return service.timeZoneAt(id, at);
  }

  /**
   * Records immutable evidence that the authenticated owner performed an operation.
   *
   * <p>The service infers the source from the credential and treats {@code operationId} as the
   * idempotency identity.
   *
   * @param jwt authenticated operation-owner credential
   * @param id stable warehouse identity
   * @param request immutable operation evidence
   * @return no-content acknowledgement
   */
  @PostMapping("/{id}/operation-marks")
  public ResponseEntity<Void> markOperation(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody WarehouseOperationMarkRequest request) {
    WarehouseOperationSource source = access.requireInternalOperationMarker(jwt);
    service.markOperation(id, source, request);
    return ResponseEntity.noContent().build();
  }
}
