package dev.buhanzaz.rwms.asset.api;

import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.RentalItemReserveReadService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Private read adapter; only the exact logistics service credential may inspect hold provenance.
 */
@RestController
@RequestMapping("/api/internal/asset/v1/logistics/rental-items")
@RequiredArgsConstructor
public class RentalItemReserveController {
  private final RentalItemReserveReadService reserves;
  private final AssetAuthorizer access;

  @GetMapping("/{rentalItemId}/reserves")
  public ResponseEntity<RentalItemReserveSnapshot> read(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID rentalItemId,
      @RequestParam UUID warehouseId) {
    access.requireLogisticsAssetAccess(jwt);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(reserves.read(rentalItemId, warehouseId));
  }
}
