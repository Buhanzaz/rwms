package dev.buhanzaz.rwms.logistics.customer.claims.api;

import static dev.buhanzaz.rwms.logistics.customer.claims.api.CustomerCabinProblemClaimApiModels.CustomerCabinProblemActionResponse;
import static dev.buhanzaz.rwms.logistics.customer.claims.api.CustomerCabinProblemClaimApiModels.CustomerCabinProblemClaimResponse;
import static dev.buhanzaz.rwms.logistics.customer.claims.api.CustomerCabinProblemClaimApiModels.ResolveCustomerCabinProblemRequest;
import static dev.buhanzaz.rwms.logistics.customer.claims.api.CustomerCabinProblemClaimApiModels.StartCustomerCabinProblemRequest;

import dev.buhanzaz.rwms.logistics.customer.claims.CustomerCabinProblemActionView;
import dev.buhanzaz.rwms.logistics.customer.claims.CustomerCabinProblemClaimService;
import dev.buhanzaz.rwms.logistics.customer.claims.CustomerCabinProblemClaimView;
import dev.buhanzaz.rwms.logistics.customer.claims.CustomerCabinProblemStatus;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP boundary for company- and warehouse-fenced customer cabin problem lifecycle actions. */
@RestController
@Validated
@RequestMapping("/api/logistics/v1/customer-cabin-problems")
@RequiredArgsConstructor
public class CustomerCabinProblemClaimController {
  private final CustomerCabinProblemClaimService claims;
  private final OrderAuthorizer access;

  @GetMapping
  public List<CustomerCabinProblemClaimResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) CustomerCabinProblemStatus status) {
    return claims.list(access.readActor(jwt), status).stream().map(this::response).toList();
  }

  @GetMapping("/{problemId}")
  public CustomerCabinProblemClaimResponse get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID problemId) {
    return response(claims.get(access.readActor(jwt), problemId));
  }

  @PostMapping("/{problemId}/start-progress")
  public CustomerCabinProblemClaimResponse startProgress(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID problemId,
      @Valid @RequestBody StartCustomerCabinProblemRequest request) {
    return response(claims.startProgress(access.writeActor(jwt), problemId, request.expectedVersion()));
  }

  @PostMapping("/{problemId}/resolve")
  public CustomerCabinProblemClaimResponse resolve(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID problemId,
      @Valid @RequestBody ResolveCustomerCabinProblemRequest request) {
    return response(
        claims.resolve(
            access.writeActor(jwt),
            problemId,
            request.expectedVersion(),
            request.resolutionKind(),
            request.resolutionComment()));
  }

  private CustomerCabinProblemClaimResponse response(CustomerCabinProblemClaimView view) {
    return new CustomerCabinProblemClaimResponse(
        view.problemId(),
        view.orderId(),
        view.warehouseId(),
        view.bookingId(),
        view.cabinUnitId(),
        view.orderNumber(),
        view.category(),
        view.phase(),
        view.description(),
        view.reportedAt(),
        view.clientDisplayName(),
        view.clientType(),
        view.clientPhone(),
        view.orderContactPhone(),
        view.deliveryAddress(),
        view.status(),
        view.resolutionDeadline(),
        view.version(),
        view.resolutionKind(),
        view.resolutionComment(),
        view.resolvedAt(),
        view.actions().stream().map(this::action).toList());
  }

  private CustomerCabinProblemActionResponse action(CustomerCabinProblemActionView view) {
    return new CustomerCabinProblemActionResponse(
        view.actionId(),
        view.actionKind(),
        view.previousStatus(),
        view.lifecycleStatus(),
        view.resolutionKind(),
        view.commentText(),
        view.occurredAt());
  }
}
