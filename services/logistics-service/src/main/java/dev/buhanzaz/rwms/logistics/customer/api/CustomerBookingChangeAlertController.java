package dev.buhanzaz.rwms.logistics.customer.api;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerBookingChangeApiModels.AcknowledgeCustomerBookingChangeAlertRequest;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerBookingChangeApiModels.CustomerBookingChangeAlertResponse;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingChangeAlertService;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Rental-manager notifications do not grant access to the underlying order's other contents. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/logistics/v1/rental-booking-change-alerts")
public class CustomerBookingChangeAlertController {
  private final OrderAuthorizer access;
  private final CustomerBookingChangeAlertService alerts;

  @GetMapping
  public List<CustomerBookingChangeAlertResponse> list(@AuthenticationPrincipal Jwt jwt) {
    return alerts.list(access.readActor(jwt));
  }

  @PostMapping("/{mutationId}/acknowledgement")
  public ResponseEntity<Void> acknowledge(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID mutationId,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody AcknowledgeCustomerBookingChangeAlertRequest request) {
    alerts.acknowledge(access.readActor(jwt), mutationId, key, request);
    return ResponseEntity.noContent().build();
  }
}
