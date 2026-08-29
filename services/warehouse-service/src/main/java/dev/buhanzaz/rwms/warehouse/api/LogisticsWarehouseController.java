package dev.buhanzaz.rwms.warehouse.api;

import dev.buhanzaz.rwms.warehouse.security.WarehouseAuthorizer;
import dev.buhanzaz.rwms.warehouse.service.WarehouseService;
import dev.buhanzaz.rwms.warehouse.service.WarehouseSupportLinkService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Least-privilege internal identity API for {@code logistics-service}.
 *
 * <p>The single-resource lookup preserves historical identity, while the collection endpoint
 * returns only currently active warehouses for bounded availability choices.
 */
@RestController
@RequestMapping("/api/internal/warehouse/v1/warehouses/logistics")
public class LogisticsWarehouseController {
  private final WarehouseService service;
  private final WarehouseSupportLinkService supportLinks;
  private final WarehouseAuthorizer access;

  /**
   * Creates the logistics-specific private controller.
   *
   * @param service application boundary that resolves logistics identities
   * @param supportLinks owner boundary for directed support-link calendars
   * @param access authorization boundary for the logistics-service credential
   */
  public LogisticsWarehouseController(
      WarehouseService service,
      WarehouseSupportLinkService supportLinks,
      WarehouseAuthorizer access) {
    this.service = service;
    this.supportLinks = supportLinks;
    this.access = access;
  }

  /**
   * Returns a warehouse identity with its owner-held address but without exposing topology.
   *
   * @param jwt authenticated logistics-service credential
   * @param id stable warehouse identity
   * @return least-privilege warehouse identity
   */
  @GetMapping("/{id}/identity")
  public LogisticsWarehouseIdentityResponse identity(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    access.requireInternalLogisticsService(jwt);
    return service.logisticsIdentity(id);
  }

  /**
   * Returns active identities in the canonical directory order.
   *
   * @param jwt authenticated logistics-service credential
   * @return active warehouse identities
   */
  @GetMapping
  public List<LogisticsWarehouseIdentityResponse> list(
      @AuthenticationPrincipal Jwt jwt) {
    access.requireInternalLogisticsService(jwt);
    return service.logisticsIdentities();
  }

  /**
   * Returns active support edges eligible at the served warehouse's local planner time.
   *
   * @param jwt authenticated logistics-service credential
   * @param servedWarehouseId representative warehouse being served
   * @param at exact planner instant used for calendar filtering
   * @return eligible support links with both endpoint coordinates
   */
  @GetMapping("/{servedWarehouseId}/support-links")
  public List<LogisticsWarehouseSupportLinkResponse> supportLinks(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID servedWarehouseId,
      @RequestParam OffsetDateTime at) {
    access.requireInternalLogisticsService(jwt);
    return supportLinks.logisticsLinks(servedWarehouseId, at);
  }
}
