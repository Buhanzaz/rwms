package dev.buhanzaz.rwms.logistics.inquiry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

  @Column(name = "presentation_hold_minutes", nullable = false)
  private int presentationHoldMinutes;

  @Column(name = "draft_reservation_hold_minutes", nullable = false)
  private int draftReservationHoldMinutes;

  @Column(name = "updated_by_subject_id", nullable = false)
  private UUID updatedBySubjectId;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public static RentalSettings defaults(UUID actorSubjectId, OffsetDateTime now) {
    RentalSettings settings = new RentalSettings();
    settings.id = SINGLETON_ID;
    settings.chatSelectionHoldMinutes = 10;
    settings.presentationHoldMinutes = 60;
    settings.draftReservationHoldMinutes = 1_440;
    settings.updatedBySubjectId = Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    settings.updatedAt = Objects.requireNonNull(now, "now");
    return settings;
  }

  public void update(
      int chatMinutes,
      int presentationMinutes,
      int draftReservationMinutes,
      UUID actorSubjectId,
      OffsetDateTime now) {
    if (chatMinutes < 1 || chatMinutes > 1_440) {
      throw new IllegalArgumentException(
          "chatSelectionHoldMinutes must be between 1 and 1440");
    }
    if (presentationMinutes < 5 || presentationMinutes > 1_440) {
      throw new IllegalArgumentException("presentationHoldMinutes must be between 5 and 1440");
    }
    if (draftReservationMinutes < 1_440 || draftReservationMinutes > 14_400) {
      throw new IllegalArgumentException(
          "draftReservationHoldMinutes must be between 1440 and 14400");
    }
    chatSelectionHoldMinutes = chatMinutes;
    presentationHoldMinutes = presentationMinutes;
    draftReservationHoldMinutes = draftReservationMinutes;
    updatedBySubjectId = Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    updatedAt = Objects.requireNonNull(now, "now");
  }
}
