package dev.buhanzaz.rwms.logistics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    document.beginReturnRegistration();
    document.requireReturnInspection();
    document.beginReturnAcceptance();
    document.acceptReturn();

    assertThat(document.getDocumentType()).isEqualTo(LogisticsDocumentType.RETURN);
    assertThat(document.getState()).isEqualTo(LogisticsDocumentState.ACCEPTED);
  }

  @Test
  void shipmentSnapshotsAreTrimmedAndCannotBeCancelledAfterConfirmationBegins() {
    LogisticsDocument document =
        LogisticsDocument.createShipment(WAREHOUSE, "  Арендатор  ", "  Водитель  ", SUBJECT, CORRELATION);

    assertThat(document.getPartySnapshot()).isEqualTo("Арендатор");
    assertThat(document.getDriverSnapshot()).isEqualTo("Водитель");

    document.beginShipmentPreparation();
    document.awaitShipmentConfirmation();
    document.beginShipmentConfirmation();

    assertThatThrownBy(document::cancel)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot be cancelled");
  }

  @Test
  void transferRejectsSameOriginAndDestination() {
    assertThatThrownBy(() -> LogisticsDocument.createTransfer(WAREHOUSE, WAREHOUSE, SUBJECT, CORRELATION))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("differ from origin");
  }

  @Test
  void transferLineDoesNotPermitArrivalBeforeDeparture() {
    LogisticsDocument document = LogisticsDocument.createTransfer(WAREHOUSE, DESTINATION, SUBJECT, CORRELATION);
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
