package dev.buhanzaz.rwms.asset.api;

import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.EquipmentPricingReferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Logistics-only catalog read used to price one furniture unit per rental month. */
@RestController
@RequestMapping("/api/internal/asset/v1/logistics")
@RequiredArgsConstructor
public class EquipmentPricingReferenceController {
  private final EquipmentPricingReferenceService service;
  private final AssetAuthorizer access;

  @GetMapping("/equipment-pricing-catalog")
  public EquipmentPricingCatalogResponse catalog(@AuthenticationPrincipal Jwt jwt) {
    access.requireLogisticsAssetAccess(jwt);
    return service.catalog();
  }
}
