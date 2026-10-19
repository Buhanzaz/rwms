package dev.buhanzaz.rwms.logistics.integration;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Private asset transport facts, not logistics persistence or public authorization evidence. */
public record RentalItemReserveSnapshot(
    UUID rentalItemId,
    UUID warehouseId,
    OffsetDateTime serverTime,
    List<SelectionHold> holds,
    @JsonProperty(required = true) OrderReservation orderReservation) {
  /**
   * Live hold scope and actor provenance are consumed locally and never returned to the browser.
   */
  public record SelectionHold(
      UUID holdId,
      Long version,
      UUID holdScopeId,
      UUID actorSubjectId,
      String actorRole,
      OffsetDateTime createdAt,
      OffsetDateTime expiresAt) {}

  /**
   * Active asset reservation identity; its logistics order may have more restrictive visibility.
   */
  public record OrderReservation(
      UUID reservationId,
      Long version,
      UUID orderId,
      UUID actorSubjectId,
      String actorRole,
      OffsetDateTime createdAt,
      @JsonProperty(required = true) OffsetDateTime draftExpiresAt) {}
}
