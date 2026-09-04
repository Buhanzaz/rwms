package dev.buhanzaz.rwms.logistics.customer.claims.api;

import dev.buhanzaz.rwms.logistics.customer.claims.CustomerCabinProblemActionKind;
import dev.buhanzaz.rwms.logistics.customer.claims.CustomerCabinProblemResolutionKind;
import dev.buhanzaz.rwms.logistics.customer.claims.CustomerCabinProblemStatus;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinProblemCategory;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinProblemPhase;
import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Transport records for the manager-visible lifecycle of an existing customer cabin problem. */
public final class CustomerCabinProblemClaimApiModels {
  private CustomerCabinProblemClaimApiModels() {}

  public record CustomerCabinProblemClaimResponse(
      UUID problemId,
      UUID orderId,
      UUID warehouseId,
      UUID bookingId,
      UUID cabinUnitId,
      String orderNumber,
      CustomerCabinProblemCategory category,
      CustomerCabinProblemPhase phase,
      String description,
      OffsetDateTime reportedAt,
      String clientDisplayName,
      ClientType clientType,
      String clientPhone,
      String orderContactPhone,
      String deliveryAddress,
      CustomerCabinProblemStatus status,
      OffsetDateTime resolutionDeadline,
      long version,
      CustomerCabinProblemResolutionKind resolutionKind,
      String resolutionComment,
      OffsetDateTime resolvedAt,
      List<CustomerCabinProblemActionResponse> actions) {
    public CustomerCabinProblemClaimResponse {
      actions = List.copyOf(actions);
    }
  }

  public record CustomerCabinProblemActionResponse(
      UUID actionId,
      CustomerCabinProblemActionKind actionKind,
      CustomerCabinProblemStatus previousStatus,
      CustomerCabinProblemStatus lifecycleStatus,
      CustomerCabinProblemResolutionKind resolutionKind,
      String commentText,
      OffsetDateTime occurredAt) {}

  public record StartCustomerCabinProblemRequest(@Min(0) long expectedVersion) {}

  public record ResolveCustomerCabinProblemRequest(
      @Min(0) long expectedVersion,
      @NotNull CustomerCabinProblemResolutionKind resolutionKind,
      @NotBlank @Size(max = 2_000) String resolutionComment) {}
}
