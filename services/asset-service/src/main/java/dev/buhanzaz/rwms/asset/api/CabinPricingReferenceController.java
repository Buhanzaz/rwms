package dev.buhanzaz.rwms.asset.api;

import dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.CabinAvailabilityRequest;
import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.CabinPricingReferenceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Logistics-only read boundary for current cabin identities used by rental tariffs. */
@RestController
@RequestMapping("/api/internal/asset/v1/logistics")
@RequiredArgsConstructor
public class CabinPricingReferenceController {
  private final CabinPricingReferenceService service;
  private final AssetAuthorizer access;

  @GetMapping("/cabin-pricing-catalog")
  public CabinPricingCatalogResponse catalog(@AuthenticationPrincipal Jwt jwt) {
    access.requireLogisticsAssetAccess(jwt);
    return service.catalog();
  }

  @PostMapping("/cabin-pricing-references")
  public CabinPricingReferencesResponse references(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CabinAvailabilityRequest request) {
    access.requireLogisticsAssetAccess(jwt);
    return service.references(request);
  }
}
