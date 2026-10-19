package dev.buhanzaz.rwms.warehouse.api;

import dev.buhanzaz.rwms.warehouse.security.WarehouseAuthorizer;
import dev.buhanzaz.rwms.warehouse.service.WarehouseSupportLinkService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Warehouse-manager API for the directed support-link collection of one warehouse. */
@RestController
@Validated
@RequestMapping("/api/warehouse/v1/warehouses/{servedWarehouseId}/support-links")
public class WarehouseSupportLinkController {
  private final WarehouseSupportLinkService service;
  private final WarehouseAuthorizer access;

  /**
   * Creates the support-link controller.
   *
   * @param service transactional support-link boundary
   * @param access user authorization boundary
   */
  public WarehouseSupportLinkController(
      WarehouseSupportLinkService service, WarehouseAuthorizer access) {
    this.service = service;
    this.access = access;
  }

  /**
   * Returns all configured links, including inactive calendar edges.
   *
   * @param jwt authenticated warehouse manager or administrator
   * @param servedWarehouseId warehouse being served
   * @return version-fenced support collection
   */
  @GetMapping
  public WarehouseSupportLinksResponse list(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID servedWarehouseId) {
    access.requireWarehouseSupportRead(jwt, servedWarehouseId);
    return service.list(servedWarehouseId);
  }

  /**
   * Atomically replaces the complete support collection.
   *
   * @param jwt authenticated warehouse manager or administrator
   * @param servedWarehouseId representative warehouse being served
   * @param request version-fenced replacement
   * @return current support collection
   */
  @PutMapping
  public WarehouseSupportLinksResponse replace(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID servedWarehouseId,
      @Valid @RequestBody ReplaceWarehouseSupportLinksRequest request) {
    access.requireWarehouseSupportWrite(
        jwt,
        servedWarehouseId,
        request.links().stream().map(WarehouseSupportLinkInput::supportWarehouseId).toList());
    return service.replace(servedWarehouseId, request);
  }
}
