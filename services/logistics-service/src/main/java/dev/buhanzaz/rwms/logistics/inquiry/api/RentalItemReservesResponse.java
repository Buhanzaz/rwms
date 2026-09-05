package dev.buhanzaz.rwms.logistics.inquiry.api;

import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentState;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Current asset occupancy enriched only with metadata the caller may already read. */
public record RentalItemReservesResponse(
    UUID rentalItemId, UUID warehouseId, OffsetDateTime serverTime, List<Entry> reserves) {
  /** Selection holds and committed asset order reservations have different lifecycle owners. */
  public enum Kind {
    SELECTION_HOLD,
    ORDER_RESERVATION
  }

  /** Booking origin, not the identity of the person currently viewing or confirming it. */
  public enum Source {
    CUSTOMER,
    MANAGER
  }

  /**
   * Null private metadata does not mean the cabin is free; only the asset snapshot decides that.
   */
  public record Entry(
      UUID reservationId,
      Kind kind,
      Source source,
      OffsetDateTime createdAt,
      OffsetDateTime expiresAt,
      String clientDisplayName,
      String managerDisplayName,
      UUID orderId,
      String orderNumber,
      RentalOrderStatus orderStatus,
      RentalOrderPaymentState paymentState,
      boolean canOpenOrder) {}
}
