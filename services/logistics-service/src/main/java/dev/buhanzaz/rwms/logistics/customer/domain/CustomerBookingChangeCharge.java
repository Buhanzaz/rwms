package dev.buhanzaz.rwms.logistics.customer.domain;

import dev.buhanzaz.rwms.logistics.inquiry.domain.LateChangeFeeMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Immutable quoted policy and booking fences with a separately fenced application/settlement.
 * TEST_PAID is recorded only after the linked owner mutation is durably complete, never on click.
 */
@Entity
@Table(name = "customer_booking_change_charge")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerBookingChangeCharge {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "customer_subject_id", nullable = false)
  private UUID customerSubjectId;

  @Column(name = "booking_id", nullable = false)
  private UUID bookingId;

  @Column(name = "booking_version", nullable = false)
  private long bookingVersion;

  @Column(name = "order_id", nullable = false)
  private UUID orderId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "warehouse_version", nullable = false)
  private long warehouseVersion;

  @Column(name = "warehouse_time_zone", nullable = false, length = 64)
  private String warehouseTimeZone;

  @Column(name = "old_slot_id", nullable = false)
  private UUID oldSlotId;

  @Column(name = "slot_id")
  private UUID slotId;

  @Column(name = "slot_version")
  private Long slotVersion;

  @Column(name = "target_delivery_date")
  private LocalDate targetDeliveryDate;

  @Column(name = "target_window_start")
  private LocalTime targetWindowStart;

  @Column(name = "target_window_end")
  private LocalTime targetWindowEnd;

  @Enumerated(EnumType.STRING)
  @Column(name = "operation", nullable = false, length = 32)
  private CustomerBookingMutationOperation operation;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "policy_version", nullable = false)
  private long policyVersion;

  @Column(name = "notice_days", nullable = false)
  private int noticeDays;

  @Column(name = "notice_date", nullable = false)
  private LocalDate noticeDate;

  @Column(name = "delivery_date", nullable = false)
  private LocalDate deliveryDate;

  @Column(name = "original_delivery_price_rubles")
  private Long originalDeliveryPriceRubles;

  @Enumerated(EnumType.STRING)
  @Column(name = "fee_mode", length = 16)
  private LateChangeFeeMode feeMode;

  @Column(name = "fee_value", precision = 21, scale = 2)
  private BigDecimal feeValue;

  @Column(name = "amount_rubles")
  private Long amountRubles;

  @Enumerated(EnumType.STRING)
  @Column(name = "settlement", nullable = false, length = 32)
  private CustomerChangeSettlement settlement;

  @Enumerated(EnumType.STRING)
  @Column(name = "application_state", nullable = false, length = 16)
  private CustomerChangeApplicationState applicationState;

  @Column(name = "support_phone", length = 16)
  private String supportPhone;

  @Column(name = "mutation_id")
  private UUID mutationId;

  @Column(name = "test_payment_requested", nullable = false)
  private boolean testPaymentRequested;

  @Column(name = "waived_by")
  private UUID waivedBy;

  @Column(name = "waiver_reason", length = 2000)
  private String waiverReason;

  @Column(name = "waiver_key")
  private UUID waiverKey;

  @Column(name = "waiver_expected_version")
  private Long waiverExpectedVersion;

  @Column(name = "expires_at", nullable = false)
  private OffsetDateTime expiresAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "applied_at")
  private OffsetDateTime appliedAt;

  /** Captures only already-owned facts; creating a quote does not reserve or cancel capacity. */
  public static CustomerBookingChangeCharge quote(
      CustomerRentalSession session,
      CustomerDeliverySlot original,
      CustomerDeliverySlot replacement,
      CustomerBookingMutationOperation operation,
      UUID idempotencyKey,
      String requestSha256,
      long warehouseVersion,
      String warehouseTimeZone,
      long policyVersion,
      int noticeDays,
      LateChangeFeeMode feeMode,
      BigDecimal feeValue,
      LocalDate noticeDate,
      Long amountRubles,
      CustomerChangeSettlement settlement,
      String supportPhone,
      OffsetDateTime expiresAt,
      OffsetDateTime now) {
    CustomerBookingChangeCharge charge = new CustomerBookingChangeCharge();
    charge.customerSubjectId = session.getCustomerSubjectId();
    charge.bookingId = Objects.requireNonNull(session.getBookingId());
    charge.bookingVersion = session.getVersion();
    charge.orderId = Objects.requireNonNull(session.getOrderId());
    charge.warehouseId = session.getWarehouseId();
    charge.warehouseVersion = warehouseVersion;
    charge.warehouseTimeZone = Objects.requireNonNull(warehouseTimeZone);
    charge.oldSlotId = original.getId();
    charge.slotId = replacement == null ? null : replacement.getId();
    charge.slotVersion = replacement == null ? null : replacement.getVersion();
    charge.targetDeliveryDate = replacement == null ? null : replacement.getDeliveryDate();
    charge.targetWindowStart = replacement == null ? null : replacement.getWindowStart();
    charge.targetWindowEnd = replacement == null ? null : replacement.getWindowEnd();
    charge.operation = Objects.requireNonNull(operation);
    charge.idempotencyKey = Objects.requireNonNull(idempotencyKey);
    charge.requestSha256 = Objects.requireNonNull(requestSha256);
    charge.policyVersion = policyVersion;
    charge.noticeDays = noticeDays;
    charge.noticeDate = Objects.requireNonNull(noticeDate);
    charge.deliveryDate = original.getDeliveryDate();
    charge.originalDeliveryPriceRubles = original.getDeliveryPriceRubles();
    charge.feeMode = feeMode;
    charge.feeValue = feeValue;
    charge.amountRubles = amountRubles;
    charge.settlement = Objects.requireNonNull(settlement);
    charge.applicationState = CustomerChangeApplicationState.OFFERED;
    charge.supportPhone = supportPhone;
    charge.expiresAt = Objects.requireNonNull(expiresAt);
    charge.createdAt = Objects.requireNonNull(now);
    return charge;
  }

  /**
   * Binds one exact owner mutation; the caller must fence quote, session, slot and consent first.
   */
  public void beginApplication(UUID mutationId, boolean testPaymentRequested) {
    if (applicationState != CustomerChangeApplicationState.OFFERED) {
      throw new IllegalStateException("Quote already has an application");
    }
    if (settlement == CustomerChangeSettlement.POLICY_UNCONFIGURED
        || (settlement == CustomerChangeSettlement.PAYMENT_REQUIRED && !testPaymentRequested)) {
      throw new IllegalStateException("Charge has no settlement consent");
    }
    this.mutationId = Objects.requireNonNull(mutationId);
    this.testPaymentRequested =
        settlement == CustomerChangeSettlement.PAYMENT_REQUIRED && testPaymentRequested;
    applicationState = CustomerChangeApplicationState.APPLYING;
  }

  /** Runs in the same local transaction that completes the owner cancellation or slot swap. */
  public void completeApplication(UUID mutationId, OffsetDateTime now) {
    if (!Objects.equals(this.mutationId, mutationId))
      throw new IllegalStateException("Different mutation");
    if (applicationState == CustomerChangeApplicationState.APPLIED) return;
    if (applicationState != CustomerChangeApplicationState.APPLYING)
      throw new IllegalStateException("Not applying");
    if (testPaymentRequested) settlement = CustomerChangeSettlement.TEST_PAID;
    applicationState = CustomerChangeApplicationState.APPLIED;
    appliedAt = Objects.requireNonNull(now);
  }

  /**
   * Records a staff-authorized force-majeure decision without changing the original quote facts.
   */
  public void waive(UUID actor, UUID key, long expectedVersion, String reason) {
    if (applicationState != CustomerChangeApplicationState.OFFERED
        || (settlement != CustomerChangeSettlement.PAYMENT_REQUIRED
            && settlement != CustomerChangeSettlement.POLICY_UNCONFIGURED)) {
      throw new IllegalStateException("Only an unapplied outstanding charge may be waived");
    }
    if (reason == null || reason.isBlank() || reason.trim().length() > 2000) {
      throw new IllegalArgumentException("A force-majeure reason is required");
    }
    waivedBy = Objects.requireNonNull(actor);
    waiverKey = Objects.requireNonNull(key);
    waiverExpectedVersion = expectedVersion;
    waiverReason = reason.trim();
    settlement = CustomerChangeSettlement.WAIVED;
  }
}
