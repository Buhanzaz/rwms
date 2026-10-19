package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.*;

import dev.buhanzaz.rwms.taskboard.security.WarehouseAccessAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.KpiSettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public global configuration API for work schedules and KPI activation state.
 *
 * <p>A pending schedule is activated with an idempotency key so a retry cannot create a second
 * effective configuration decision.
 */
@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/kpi-settings")
public class KpiSettingsController {
  private final KpiSettingsService service;
  private final WarehouseAccessAuthorizer access;

  /** Returns the active and pending settings shared by every warehouse. */
  @GetMapping
  public KpiSettingsResponse get(@AuthenticationPrincipal Jwt jwt) {
    access.requireUserScope(jwt, "rwms.read");
    return service.get();
  }

  /** Saves a current-day or future-effective shared schedule without activating it. */
  @PutMapping("/work-schedule")
  public KpiSettingsResponse saveWorkSchedule(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody SaveWorkScheduleRequest request) {
    requireWrite(jwt);
    return service.saveWorkSchedule(request);
  }

  /** Removes the pending schedule under the currently observed settings version. */
  @DeleteMapping("/work-schedule/pending")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deletePendingWorkSchedule(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam @Min(0) long expectedVersion) {
    requireWrite(jwt);
    service.deletePendingWorkSchedule(expectedVersion);
  }

  /** Activates the pending schedule exactly once, applying a current-day revision immediately. */
  @PostMapping("/activate")
  public KpiSettingsResponse activate(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID operationId,
      @Valid @RequestBody ActivateKpiSettingsRequest request) {
    requireWrite(jwt);
    return service.activate(operationId, request);
  }

  private void requireWrite(Jwt jwt) {
    access.requireUserScope(jwt, "rwms.write");
    access.requireGlobalManagement(jwt);
  }
}
