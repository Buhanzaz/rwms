package dev.buhanzaz.rwms.maintenance.api;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.ReturnEstimateSource;
import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.UpsertLogisticsReturnEstimateSourceRequest;

import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.LogisticsReturnShortageService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Private receiver for one immutable return-line estimate source and its maintenance estimate. */
@RestController
@Validated
@RequestMapping(
    "/api/internal/maintenance/v1/logistics/returns/{returnId}/lines/{lineId}/estimate-source")
@RequiredArgsConstructor
public class MaintenanceLogisticsController {
  private final LogisticsReturnShortageService logistics;
  private final MaintenanceAuthorizer access;

  @PutMapping
  public ResponseEntity<ReturnEstimateSource> upsert(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID returnId,
      @PathVariable UUID lineId,
      @Valid @RequestBody UpsertLogisticsReturnEstimateSourceRequest request) {
    access.requireLogisticsService(jwt);
    LogisticsReturnShortageService.UpsertResult result =
        logistics.upsert(returnId, lineId, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) {
      response.header("Idempotency-Replayed", "true");
    }
    return response.body(result.response());
  }

  @GetMapping
  public ReturnEstimateSource get(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID returnId,
      @PathVariable UUID lineId) {
    access.requireLogisticsService(jwt);
    return logistics.get(returnId, lineId);
  }
}
