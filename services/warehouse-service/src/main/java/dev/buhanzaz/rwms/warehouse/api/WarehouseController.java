package dev.buhanzaz.rwms.warehouse.api;

import dev.buhanzaz.rwms.warehouse.security.WarehouseAuthorizer;
import dev.buhanzaz.rwms.warehouse.service.WarehouseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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

@RestController
@Validated
@RequestMapping("/api/warehouse/v1/warehouses")
public class WarehouseController {
  private final WarehouseService service;
  private final WarehouseAuthorizer access;

  public WarehouseController(WarehouseService service, WarehouseAuthorizer access) {
    this.service = service;
    this.access = access;
  }

  @GetMapping
  public List<WarehouseResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "false") boolean includeInactive) {
    access.requireWarehouseRead(jwt);
    if (includeInactive) access.requireSystemAdmin(jwt);
    return service.list(includeInactive);
  }

  @GetMapping("/{id}")
  public WarehouseResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    access.requireWarehouseRead(jwt);
    return service.get(id);
  }

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

  @PutMapping("/{id}")
  public WarehouseResponse replace(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody ReplaceWarehouseRequest request) {
    access.requireSystemAdminWrite(jwt);
    return service.replace(id, request);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deactivate(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam @Min(0) long expectedVersion) {
    access.requireSystemAdminWrite(jwt);
    service.deactivate(id, expectedVersion);
    return ResponseEntity.noContent().build();
  }
}
