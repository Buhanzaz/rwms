package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.*;

import dev.buhanzaz.rwms.taskboard.security.WarehouseAccessAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.KpiPaletteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public administration API for the KPI palette shared by every warehouse. */
@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/task-board/kpi-palette")
public class KpiPaletteController {
  private final KpiPaletteService service;
  private final WarehouseAccessAuthorizer access;

  /** Returns the single installation-wide KPI palette. */
  @GetMapping
  public KpiPaletteResponse get(@AuthenticationPrincipal Jwt jwt) {
    access.requireUserScope(jwt, "rwms.read");
    return service.get();
  }

  /** Replaces the shared palette under its observed global version. */
  @PutMapping
  public KpiPaletteResponse replace(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SaveKpiPaletteRequest request) {
    access.requireUserScope(jwt, "rwms.write");
    access.requireGlobalManagement(jwt);
    return service.replace(request);
  }
}
