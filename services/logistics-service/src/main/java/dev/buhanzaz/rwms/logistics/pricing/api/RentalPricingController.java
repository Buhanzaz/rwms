package dev.buhanzaz.rwms.logistics.pricing.api;

import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.CabinAvailabilityRequest;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.pricing.service.EquipmentRentalPricingService;
import dev.buhanzaz.rwms.logistics.pricing.service.RentalPricingService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated global tariff administration and warehouse-scoped informational cabin pricing. */
@RestController
@RequestMapping("/api/logistics/v1")
@RequiredArgsConstructor
public class RentalPricingController {
  private final OrderAuthorizer access;
  private final RentalPricingService pricing;
  private final EquipmentRentalPricingService equipmentPricing;

  @GetMapping("/settings/equipment-rental-prices")
  public EquipmentRentalPricingResponse equipmentSettings(@AuthenticationPrincipal Jwt jwt) {
    access.readActor(jwt);
    return equipmentPricing.settings();
  }

  @PutMapping("/settings/equipment-rental-prices/{equipmentId}")
  public EquipmentRentalPricingResponse updateEquipment(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID equipmentId,
      @Valid @RequestBody UpdateRentalPriceRequest request) {
    return equipmentPricing.update(access.writeActor(jwt), equipmentId, request);
  }

  @GetMapping("/settings/rental-prices")
  public RentalPricingSettingsResponse settings(@AuthenticationPrincipal Jwt jwt) {
    access.readActor(jwt);
    return pricing.settings();
  }

  @PutMapping("/settings/rental-prices/{rentalTypeId}/{categoryId}")
  public RentalPricingSettingsResponse update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID rentalTypeId,
      @PathVariable UUID categoryId,
      @Valid @RequestBody UpdateRentalPriceRequest request) {
    return pricing.update(access.writeActor(jwt), rentalTypeId, categoryId, request);
  }

  @PostMapping("/cabins/rental-prices")
  public CabinRentalPricesResponse prices(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CabinAvailabilityRequest request) {
    OrderActor actor = access.readActor(jwt);
    access.requireWarehouseRead(actor, request.warehouseId());
    return pricing.prices(request.warehouseId(), request.rentalItemIds());
  }
}
