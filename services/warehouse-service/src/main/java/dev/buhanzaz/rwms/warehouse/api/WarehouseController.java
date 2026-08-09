package dev.buhanzaz.rwms.warehouse.api;

import dev.buhanzaz.rwms.warehouse.security.WarehouseAuthorizer;
import dev.buhanzaz.rwms.warehouse.service.WarehouseService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
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

/**
 * Public warehouse directory and administration API.
 *
 * <p>Directory reads deliberately expose the approved global registry rather than filtering it by
 * individual warehouse grants. Mutating operations are reserved for system administrators and
 * delegate concurrency and idempotency rules to {@link WarehouseService}.
 */
@RestController
@Validated
@RequestMapping("/api/warehouse/v1/warehouses")
public class WarehouseController {
  private final WarehouseService service;
  private final WarehouseAuthorizer access;

  /**
   * Creates the public controller with its application and authorization boundaries.
   *
   * @param service application boundary that owns warehouse mutations and reads
   * @param access authorization boundary for this public API
   */
  public WarehouseController(WarehouseService service, WarehouseAuthorizer access) {
    this.service = service;
    this.access = access;
  }

  /**
   * Lists the active directory, including warehouses that are draining by default.
   *
   * <p>Inactive entries are historical references and require elevated access to include in a
   * directory response.
   *
   * @param jwt authenticated caller
   * @param includeInactive whether to include terminal historical entries
   * @return ordered warehouse directory
   */
  @GetMapping
  public List<WarehouseResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "false") boolean includeInactive) {
    access.requireWarehouseRead(jwt);
    if (includeInactive) access.requireSystemAdmin(jwt);
    return service.list(includeInactive);
  }

  /**
   * Resolves one warehouse by its stable UUID.
   *
   * <p>Unlike the default directory, this lookup also returns inactive warehouses so historical
   * cross-service references remain resolvable.
   *
   * @param jwt authenticated caller
   * @param id stable warehouse identity
   * @return warehouse metadata
   */
  @GetMapping("/{id}")
  public WarehouseResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    access.requireWarehouseRead(jwt);
    return service.get(id);
  }

  /**
   * Creates a warehouse under a caller-scoped idempotency key.
   *
   * <p>An exact retry returns the original successful representation and marks the response with
   * {@code Idempotency-Replayed: true}; reusing the key for different content is a conflict.
   *
   * @param jwt authenticated system administrator
   * @param idempotencyKey caller-generated stable retry identity
   * @param request validated warehouse data
   * @return created or replayed warehouse response
   */
  @PostMapping
  public ResponseEntity<WarehouseResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateWarehouseRequest request) {
    access.requireSystemAdminWrite(jwt);
    WarehouseService.CreateResult result =
        service.create(access.subjectId(jwt), idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  /**
   * Replaces mutable metadata under the supplied aggregate version fence.
   *
   * <p>Lifecycle state is deliberately absent from this command. A timezone can be corrected here
   * only before the warehouse has a durable operation mark.
   *
   * @param jwt authenticated system administrator
   * @param id stable warehouse identity
   * @param request version-fenced replacement data
   * @return replaced warehouse metadata
   */
  @PutMapping("/{id}")
  public WarehouseResponse replace(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody ReplaceWarehouseRequest request) {
    access.requireSystemAdminWrite(jwt);
    return service.replace(id, request);
  }

  /**
   * Starts the irreversible drain from {@code ACTIVE} to {@code DRAINING}.
   *
   * <p>New incoming work is denied after this transition while admitted outgoing work can drain.
   *
   * @param jwt authenticated system administrator
   * @param id stable warehouse identity
   * @param request version-fenced lifecycle command
   * @return warehouse in {@code DRAINING}
   */
  @PostMapping("/{id}/draining")
  public WarehouseResponse startDraining(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody WarehouseLifecycleTransitionRequest request) {
    access.requireSystemAdminWrite(jwt);
    return service.startDraining(id, request);
  }

  /**
   * Completes deactivation once every owning service has recorded readiness.
   *
   * <p>The terminal {@code INACTIVE} state cannot be reversed through this API.
   *
   * @param jwt authenticated system administrator
   * @param id stable warehouse identity
   * @param request version-fenced lifecycle command
   * @return terminal warehouse metadata
   */
  @PostMapping("/{id}/inactivation")
  public WarehouseResponse completeInactivation(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody WarehouseLifecycleTransitionRequest request) {
    access.requireSystemAdminWrite(jwt);
    return service.completeInactivation(id, request);
  }

  /**
   * Appends a future-effective timezone decision for an already operated warehouse.
   *
   * <p>This preserves the timezone used for historical operations instead of rewriting it.
   *
   * @param jwt authenticated system administrator
   * @param id stable warehouse identity
   * @param request version-fenced future timezone decision
   * @return appended timezone decision
   */
  @PostMapping("/{id}/time-zone-changes")
  public WarehouseTimeZoneChangeResponse scheduleTimeZone(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody ScheduleWarehouseTimeZoneRequest request) {
    access.requireSystemAdminWrite(jwt);
    return service.scheduleTimeZone(id, request);
  }

}
