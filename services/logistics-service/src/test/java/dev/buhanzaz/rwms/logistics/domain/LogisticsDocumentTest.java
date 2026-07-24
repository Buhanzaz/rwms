package dev.buhanzaz.rwms.logistics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LogisticsDocumentTest {
  private static final UUID WAREHOUSE = UUID.fromString("00000000-0000-0000-0000-000000000101");
  private static final UUID DESTINATION = UUID.fromString("00000000-0000-0000-0000-000000000102");
  private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000103");
  private static final UUID CORRELATION = UUID.fromString("00000000-0000-0000-0000-000000000104");

  @Test
  void returnFollowsTheFencedIntakeAndAcceptanceLifecycle() {
    LogisticsDocument document = LogisticsDocument.createReturn(WAREHOUSE, SUBJECT, CORRELATION);

    document.scheduleReturn("Водитель", LocalDate.parse("2026-07-01"));
    document.beginReturnRegistration();
    document.requireReturnInspection();
    document.beginReturnAcceptance();
    document.acceptReturn();

    assertThat(document.getDocumentType()).isEqualTo(LogisticsDocumentType.RETURN);
    assertThat(document.getState()).isEqualTo(LogisticsDocumentState.ACCEPTED);
    assertThat(document.getScheduledDate()).isEqualTo(LocalDate.parse("2026-07-01"));
    assertThat(document.getScheduledAt()).isNull();
  }

  @Test
  void shipmentSnapshotsAreTrimmedAndCannotBeCancelledAfterConfirmationBegins() {
    LogisticsDocument document =
        LogisticsDocument.createShipment(WAREHOUSE, "  Арендатор  ", "  Водитель  ", SUBJECT, CORRELATION);

    assertThat(document.getPartySnapshot()).isEqualTo("Арендатор");
    assertThat(document.getDriverSnapshot()).isEqualTo("Водитель");

    document.scheduleShipment("Водитель", LocalDate.parse("2026-07-01"));
    document.beginShipmentPreparation();
    document.awaitShipmentConfirmation();
    document.beginShipmentConfirmation();

    assertThatThrownBy(document::cancel)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot be cancelled");
  }

  @Test
  void shipmentCannotDepartBeforeItsScheduledDate() {
    LogisticsDocument document =
        LogisticsDocument.createShipment(
            WAREHOUSE, "Арендатор", "Водитель", SUBJECT, CORRELATION);
    OffsetDateTime now = OffsetDateTime.parse("2026-07-22T08:00:00Z");
    document.scheduleShipment("Водитель", now.toLocalDate().plusDays(1));
    document.beginShipmentPreparation();
    document.awaitShipmentConfirmation();

    assertThatThrownBy(() -> document.requireShipmentDepartureAllowed(now))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("future");

    document.scheduleShipment("Водитель", now.toLocalDate());
    document.requireShipmentDepartureAllowed(now);
  }

  @Test
  void transferRejectsSameOriginAndDestination() {
    assertThatThrownBy(
            () ->
                LogisticsDocument.createTransfer(
                    WAREHOUSE,
                    WAREHOUSE,
                    null,
                    LocalDate.parse("2026-07-01"),
                    SUBJECT,
                    CORRELATION))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("differ from origin");
  }

  @Test
  void transferLineDoesNotPermitArrivalBeforeDeparture() {
    LogisticsDocument document =
        LogisticsDocument.createTransfer(
            WAREHOUSE,
            DESTINATION,
            null,
            LocalDate.parse("2026-07-01"),
            SUBJECT,
            CORRELATION);
    LogisticsDocumentLine line =
        LogisticsDocumentLine.create(
            document,
            1,
            UUID.fromString("00000000-0000-0000-0000-000000000105"),
            4,
            null);

    assertThatThrownBy(line::beginArrival)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("transition");
  }
}
