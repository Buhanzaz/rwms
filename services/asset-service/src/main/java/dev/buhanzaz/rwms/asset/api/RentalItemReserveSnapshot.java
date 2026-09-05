package dev.buhanzaz.rwms.asset.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Private asset-owned occupancy facts; actor and scope identities never authorize public access.
 */
public record RentalItemReserveSnapshot(
    UUID rentalItemId,
    UUID warehouseId,
    OffsetDateTime serverTime,
    List<SelectionHold> holds,
    OrderReservation orderReservation) {
  /** One currently live selection hold, including its unrenewed persisted deadline. */
  public record SelectionHold(
      UUID holdId,
      long version,
      UUID holdScopeId,
      UUID actorSubjectId,
      String actorRole,
      OffsetDateTime createdAt,
      OffsetDateTime expiresAt) {}

  /** An actual active reservation remains visible until the asset owner records its release. */
  public record OrderReservation(
      UUID reservationId,
      long version,
      UUID orderId,
      UUID actorSubjectId,
      String actorRole,
      OffsetDateTime createdAt,
      OffsetDateTime draftExpiresAt) {}
}
