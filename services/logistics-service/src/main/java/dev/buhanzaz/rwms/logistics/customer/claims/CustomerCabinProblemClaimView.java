package dev.buhanzaz.rwms.logistics.customer.claims;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinProblemCategory;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinProblemPhase;
import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Company-scoped manager projection of evidence-preserving customer cabin problem lifecycle data. */
public record CustomerCabinProblemClaimView(
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
    UUID resolvedBySubjectId,
    String resolutionComment,
    OffsetDateTime resolvedAt,
    List<CustomerCabinProblemActionView> actions) {
  public CustomerCabinProblemClaimView {
    actions = List.copyOf(actions);
  }
}
