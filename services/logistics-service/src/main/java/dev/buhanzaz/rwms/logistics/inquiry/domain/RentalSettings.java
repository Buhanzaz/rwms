package dev.buhanzaz.rwms.logistics.inquiry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * JPA persistence model for Rental Settings in the logistics-owned database.
 */
@Entity
@Table(name = "rental_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RentalSettings {
  public static final UUID SINGLETON_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "chat_selection_hold_minutes", nullable = false)
  private int chatSelectionHoldMinutes;

  @Column(name = "manual_booking_hold_minutes", nullable = false)
  private int manualBookingHoldMinutes;

  @Column(name = "presentation_hold_minutes", nullable = false)
  private int presentationHoldMinutes;

  @Column(name = "draft_reservation_hold_minutes", nullable = false)
  private int draftReservationHoldMinutes;

  @Column(name = "late_change_notice_days", nullable = false)
  private int lateChangeNoticeDays;

  @Enumerated(EnumType.STRING)
  @Column(name = "late_change_fee_mode", length = 16)
  private LateChangeFeeMode lateChangeFeeMode;

  @Column(name = "late_change_fee_value", precision = 21, scale = 2)
  private BigDecimal lateChangeFeeValue;

  @Column(name = "rental_support_phone", length = 16)
  private String rentalSupportPhone;

  @Column(name = "updated_by_subject_id", nullable = false)
  private UUID updatedBySubjectId;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public static RentalSettings defaults(UUID actorSubjectId, OffsetDateTime now) {
    RentalSettings settings = new RentalSettings();
    settings.id = SINGLETON_ID;
    settings.chatSelectionHoldMinutes = 10;
    settings.manualBookingHoldMinutes = 60;
    settings.presentationHoldMinutes = 60;
    settings.draftReservationHoldMinutes = 1_440;
    settings.lateChangeNoticeDays = 2;
    settings.updatedBySubjectId = Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    settings.updatedAt = Objects.requireNonNull(now, "now");
    return settings;
  }

  public void update(
      int chatMinutes,
      int manualBookingMinutes,
      int presentationMinutes,
      int draftReservationMinutes,
      int noticeDays,
      LateChangeFeeMode feeMode,
      BigDecimal feeValue,
      String supportPhone,
      UUID actorSubjectId,
      OffsetDateTime now) {
    if (chatMinutes < 1 || chatMinutes > 1_440) {
      throw new IllegalArgumentException(
          "chatSelectionHoldMinutes must be between 1 and 1440");
    }
    if (manualBookingMinutes < 5 || manualBookingMinutes > 1_440) {
      throw new IllegalArgumentException(
          "manualBookingHoldMinutes must be between 5 and 1440");
    }
    if (presentationMinutes < 5 || presentationMinutes > 1_440) {
      throw new IllegalArgumentException("presentationHoldMinutes must be between 5 and 1440");
    }
    if (draftReservationMinutes < 1_440 || draftReservationMinutes > 14_400) {
      throw new IllegalArgumentException(
          "draftReservationHoldMinutes must be between 1440 and 14400");
    }
    if (noticeDays < 0) {
      throw new IllegalArgumentException("lateChangeNoticeDays must not be negative");
    }
    if ((feeMode == null) != (feeValue == null)) {
      throw new IllegalArgumentException(
          "lateChangeFeeMode and lateChangeFeeValue must be configured together");
    }
    if (feeValue != null) {
      BigDecimal normalized = feeValue.stripTrailingZeros();
      if (feeValue.signum() < 0
          || (feeMode == LateChangeFeeMode.FIXED
              && (normalized.scale() > 0
                  || feeValue.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0))
          || (feeMode == LateChangeFeeMode.PERCENT
              && (normalized.scale() > 2 || feeValue.compareTo(BigDecimal.valueOf(100)) > 0))) {
        throw new IllegalArgumentException(
            "Late-change fee must be whole rubles up to Long.MAX_VALUE or a percentage from 0 to"
                + " 100 with at most two decimal places");
      }
    }
    if (supportPhone != null && !supportPhone.matches("\\+[1-9][0-9]{7,14}")) {
      throw new IllegalArgumentException(
          "rentalSupportPhone must be an international phone number such as +74951234567");
    }
    Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    Objects.requireNonNull(now, "now");
    chatSelectionHoldMinutes = chatMinutes;
    manualBookingHoldMinutes = manualBookingMinutes;
    presentationHoldMinutes = presentationMinutes;
    draftReservationHoldMinutes = draftReservationMinutes;
    lateChangeNoticeDays = noticeDays;
    lateChangeFeeMode = feeMode;
    lateChangeFeeValue = feeValue;
    rentalSupportPhone = supportPhone;
    updatedBySubjectId = Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    updatedAt = Objects.requireNonNull(now, "now");
  }
}
