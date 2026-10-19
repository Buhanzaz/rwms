package dev.buhanzaz.rwms.logistics.inquiry.api;

import dev.buhanzaz.rwms.logistics.inquiry.service.RentalItemReservesReadService;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
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

/** Public read-only reserve register; no command or payment capability is granted by occupancy. */
@RestController
@RequestMapping("/api/logistics/v1/rental-items")
@RequiredArgsConstructor
public class RentalItemReservesController {
  private final RentalItemReservesReadService reserves;
  private final OrderAuthorizer access;

  @GetMapping("/{rentalItemId}/reserves")
  public ResponseEntity<RentalItemReservesResponse> read(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID rentalItemId,
      @RequestParam UUID warehouseId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(reserves.read(access.readActor(jwt), rentalItemId, warehouseId));
  }
}
