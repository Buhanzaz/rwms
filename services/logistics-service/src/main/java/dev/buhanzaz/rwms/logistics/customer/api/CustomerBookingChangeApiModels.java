package dev.buhanzaz.rwms.logistics.customer.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingChangeCharge;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingMutationOperation;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerChangeApplicationState;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerChangeSettlement;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Least-privilege fee quote and staff waiver boundaries; clients never supply a calculated fee. */
public final class CustomerBookingChangeApiModels {
  private CustomerBookingChangeApiModels() {}

  /** Requests one immutable proposal for an exact booking and optional replacement slot. */
  public record CreateCustomerBookingChangeQuoteRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull CustomerBookingMutationOperation operation,
      @JsonProperty(required = true) UUID slotId,
      @JsonProperty(required = true) @Min(0) Long slotVersion) {}

  /** Customer projection of {@link CustomerBookingChangeCharge}; amount is exact whole RUB text. */
  public record CustomerBookingChangeQuoteResponse(
      UUID quoteId,
      long version,
      UUID bookingId,
      long bookingVersion,
      CustomerBookingMutationOperation operation,
      UUID oldSlotId,
      UUID slotId,
      Long slotVersion,
      @JsonFormat(shape = JsonFormat.Shape.STRING) Long amountRubles,
      CustomerChangeSettlement settlement,
      CustomerChangeApplicationState applicationState,
      boolean testPaymentAvailable,
      String supportPhone,
      OffsetDateTime expiresAt,
      int noticeDays,
      LocalDate deliveryDate,
      String warehouseTimeZone,
      LocalDate targetDeliveryDate,
      LocalTime targetWindowStart,
      LocalTime targetWindowEnd) {}

  /** Limited fee-management view; it does not grant access to the underlying full rental order. */
  public record PendingCustomerBookingChangeQuoteResponse(
      UUID orderId, UUID warehouseId, CustomerBookingChangeQuoteResponse quote) {}

  /** Requires an auditable reason, exact quote version and transport idempotency for a waiver. */
  public record WaiveCustomerBookingChangeChargeRequest(
      @NotNull @Min(0) Long expectedVersion, @NotBlank @Size(max = 2000) String reason) {}

  /** Completed change only; per-user acknowledgement never changes this source mutation version. */
  public record CustomerBookingChangeAlertResponse(
      UUID mutationId,
      long version,
      UUID bookingId,
      UUID orderId,
      UUID warehouseId,
      CustomerBookingMutationOperation operation,
      OffsetDateTime occurredAt,
      LocalDate previousDeliveryDate,
      LocalDate newDeliveryDate,
      String deliveryAddress,
      @JsonFormat(shape = JsonFormat.Shape.STRING) Long feeRubles,
      CustomerChangeSettlement settlement,
      boolean canOpenOrder) {}

  /** Acknowledges one immutable completed mutation for the authenticated manager only. */
  public record AcknowledgeCustomerBookingChangeAlertRequest(
      @NotNull @Min(0) Long expectedVersion) {}
}
