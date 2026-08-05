package dev.buhanzaz.rwms.maintenance.disposition.api;

import static dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.*;

import dev.buhanzaz.rwms.maintenance.disposition.application.PropertyDispositionApplicationService;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionState;
import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public decision endpoints. Commands stay in maintenance; clients only request transitions. */
@RestController
@Validated
@RequestMapping("/api/maintenance/v1")
@RequiredArgsConstructor
public class PropertyDispositionController {
  private final PropertyDispositionApplicationService dispositions;
  private final MaintenanceAuthorizer access;

  @PostMapping("/dispositions")
  public ResponseEntity<PropertyDispositionDecisionResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreatePropertyDispositionRequest request) {
    access.requireDispositionInitiator(jwt, request.warehouseId());
    CreateResult result = dispositions.createManual(access.subjectId(jwt), idempotencyKey, request);
    return created(result);
  }

  @GetMapping("/dispositions/{decisionId}")
  public PropertyDispositionDecisionResponse get(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID decisionId,
      @RequestParam UUID warehouseId) {
    access.requireRead(jwt, warehouseId);
    return dispositions.get(decisionId, warehouseId);
  }

  @PostMapping("/dispositions/{decisionId}/approve")
  public PropertyDispositionDecisionResponse approve(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID decisionId,
      @RequestParam UUID warehouseId,
      @Valid @RequestBody ApprovePropertyDispositionRequest request) {
    access.requireDispositionAdministrator(jwt, warehouseId);
    return dispositions.approve(decisionId, warehouseId, request);
  }

  @PostMapping("/dispositions/{decisionId}/reject")
  public PropertyDispositionDecisionResponse reject(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID decisionId,
      @RequestParam UUID warehouseId,
      @Valid @RequestBody RejectPropertyDispositionRequest request) {
    access.requireDispositionAdministrator(jwt, warehouseId);
    return dispositions.reject(decisionId, warehouseId, request);
  }

  @PostMapping("/dispositions/{decisionId}/recovery")
  public PropertyDispositionDecisionResponse recover(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID decisionId,
      @RequestParam UUID warehouseId,
      @Valid @RequestBody RecoverPropertyDispositionRequest request) {
    access.requireDispositionAdministrator(jwt, warehouseId);
    return dispositions.recover(decisionId, warehouseId, request);
  }

  @GetMapping("/write-offs")
  public PropertyDispositionPage writeOffs(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
      @RequestParam(required = false) PropertyDispositionState state) {
    access.requireRead(jwt, warehouseId);
    return dispositions.list(warehouseId, PropertyDispositionKind.WRITE_OFF, state, page, size);
  }

  @GetMapping("/losses")
  public PropertyDispositionPage losses(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
      @RequestParam(required = false) PropertyDispositionState state) {
    access.requireRead(jwt, warehouseId);
    return dispositions.list(warehouseId, PropertyDispositionKind.LOSS, state, page, size);
  }

  private static ResponseEntity<PropertyDispositionDecisionResponse> created(CreateResult result) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(
        result.replayed() ? HttpStatus.OK : HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }
}
