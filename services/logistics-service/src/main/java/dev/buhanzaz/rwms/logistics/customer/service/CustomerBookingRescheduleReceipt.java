package dev.buhanzaz.rwms.logistics.customer.service;

import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerBookingCabin;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerBookingResponse;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable owner result retained for exact replay of one completed booking reschedule. */
public record CustomerBookingRescheduleReceipt(
    UUID orderId,
    long orderVersion,
    UUID sessionId,
    long sessionVersion,
    UUID bookingId,
    UUID warehouseId,
    UUID inquiryId,
    String deliveryAddress,
    List<CustomerBookingCabin> cabins,
    Slot confirmedSlot) {
  public CustomerBookingRescheduleReceipt {
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(bookingId, "bookingId");
    Objects.requireNonNull(warehouseId, "warehouseId");
    Objects.requireNonNull(inquiryId, "inquiryId");
    Objects.requireNonNull(deliveryAddress, "deliveryAddress");
    cabins = List.copyOf(Objects.requireNonNull(cabins, "cabins"));
    Objects.requireNonNull(confirmedSlot, "confirmedSlot");
    if (orderVersion < 0 || sessionVersion < 0) {
      throw new IllegalArgumentException("Reschedule receipt versions are invalid");
    }
  }

  /** Reconstructs the exact CustomerApp success response without reading mutable booking state. */
  public CustomerBookingResponse customerResponse() {
    return new CustomerBookingResponse(
        bookingId,
        sessionVersion,
        orderId,
        "COMPLETED",
        null,
        inquiryId,
        confirmedSlot.slotId(),
        warehouseId,
        deliveryAddress,
        confirmedSlot.date(),
        confirmedSlot.windowStart(),
        confirmedSlot.windowEnd(),
        null,
        cabins);
  }

  /** Exact confirmed delivery-slot facts produced by the atomic owner transaction. */
  public record Slot(
      UUID slotId,
      long version,
      LocalDate date,
      String kind,
      LocalTime windowStart,
      LocalTime windowEnd,
      Long deliveryPriceRubles,
      OffsetDateTime expiresAt) {
    public Slot {
      Objects.requireNonNull(slotId, "slotId");
      Objects.requireNonNull(date, "date");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(windowStart, "windowStart");
      Objects.requireNonNull(windowEnd, "windowEnd");
      Objects.requireNonNull(expiresAt, "expiresAt");
      if (version < 0 || deliveryPriceRubles == null || deliveryPriceRubles < 0) {
        throw new IllegalArgumentException("Reschedule slot receipt is invalid");
      }
    }
  }
}
