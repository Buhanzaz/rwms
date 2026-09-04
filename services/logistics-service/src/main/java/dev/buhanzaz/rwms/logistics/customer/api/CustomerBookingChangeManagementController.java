package dev.buhanzaz.rwms.logistics.customer.api;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerBookingChangeApiModels.CustomerBookingChangeQuoteResponse;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerBookingChangeApiModels.PendingCustomerBookingChangeQuoteResponse;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerBookingChangeApiModels.WaiveCustomerBookingChangeChargeRequest;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingChangeWaiverService;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Warehouse EDIT gates fee management independently of full rental-order visibility. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/logistics/v1")
public class CustomerBookingChangeManagementController {
  private final OrderAuthorizer access;
  private final CustomerBookingChangeWaiverService waivers;

  @GetMapping("/rental-booking-change-quotes")
  public List<PendingCustomerBookingChangeQuoteResponse> pending(@AuthenticationPrincipal Jwt jwt) {
    return waivers.pending(access.writeActor(jwt));
  }

  @GetMapping("/orders/{orderId}/booking-change-quotes")
  public List<CustomerBookingChangeQuoteResponse> list(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
    return waivers.list(access.writeActor(jwt), orderId);
  }

  @PostMapping("/orders/{orderId}/booking-change-quotes/{quoteId}/waiver")
  public CustomerBookingChangeQuoteResponse waive(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID orderId,
      @PathVariable UUID quoteId,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody WaiveCustomerBookingChangeChargeRequest request) {
    return waivers.waive(access.writeActor(jwt), orderId, quoteId, key, request);
  }
}
