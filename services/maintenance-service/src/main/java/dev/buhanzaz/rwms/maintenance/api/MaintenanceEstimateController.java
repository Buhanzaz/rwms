package dev.buhanzaz.rwms.maintenance.api;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceApplicationService;
import dev.buhanzaz.rwms.maintenance.domain.EstimateState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/api/maintenance/v1/estimates")
@RequiredArgsConstructor
public class MaintenanceEstimateController {
  private final MaintenanceApplicationService service;
  private final MaintenanceAuthorizer access;

  @GetMapping
  public PageResponse<EstimateResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
      @RequestParam(required = false) EstimateState lifecycle,
      @RequestParam(required = false) UUID rentalItemId) {
    access.requireRead(jwt, warehouseId);
    List<EstimateResponse> values = service.estimates(warehouseId).stream()
        .filter(value -> lifecycle == null || value.lifecycle() == lifecycle)
        .filter(value -> rentalItemId == null || value.rentalItemId().equals(rentalItemId))
        .toList();
    return page(values, page, size);
  }

  @PostMapping
  public ResponseEntity<EstimateResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateEstimateRequest request) {
    access.requireEdit(jwt, request.warehouseId());
    MaintenanceApplicationService.CreateResult<EstimateResponse> result =
        service.createEstimate(access.subjectId(jwt), idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  @GetMapping("/{id}")
  public EstimateResponse get(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam UUID warehouseId) {
    access.requireRead(jwt, warehouseId);
    return requireWarehouse(id, warehouseId);
  }

  @PutMapping("/{id}")
  public EstimateResponse replace(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam UUID warehouseId,
      @Valid @RequestBody UpdateEstimateRequest request) {
    access.requireEdit(jwt, warehouseId);
    requireWarehouse(id, warehouseId);
    return service.updateEstimate(id, request);
  }

  @PostMapping("/{id}/complete")
  public ResponseEntity<EstimateCommandResult> complete(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam UUID warehouseId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CompleteEstimateRequest request) {
    access.requireEdit(jwt, warehouseId);
    requireWarehouse(id, warehouseId);
    MaintenanceApplicationService.CreateResult<EstimateCommandResult> result = service.completeEstimate(
        access.subjectId(jwt), idempotencyKey, id, request);
    return idempotentOk(result);
  }

  @PostMapping("/{id}/amendments")
  public ResponseEntity<EstimateCommandResult> amend(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam UUID warehouseId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody AmendEstimateRequest request) {
    access.requireEdit(jwt, warehouseId);
    requireWarehouse(id, warehouseId);
    MaintenanceApplicationService.CreateResult<EstimateCommandResult> result = service.amendEstimate(
        access.subjectId(jwt), idempotencyKey, id, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  private EstimateResponse requireWarehouse(UUID id, UUID warehouseId) {
    return service.estimate(id, warehouseId);
  }

  private static <T> PageResponse<T> page(List<T> values, int page, int size) {
    int from = Math.min(Math.multiplyExact(page, size), values.size());
    int to = Math.min(from + size, values.size());
    return new PageResponse<>(values.subList(from, to), page, size, values.size());
  }

  private static <T> ResponseEntity<T> idempotentOk(
      MaintenanceApplicationService.CreateResult<T> result) {
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }
}
