package dev.buhanzaz.rwms.asset.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.DIMENSION_24_X_6;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.FINISHING_DVP;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.TYPE_BK_1;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RentalItemTest {

  @Test
  void canonicalizesDisplayAndIdentityNumbersWithoutGuessingPunctuation() {
    assertThat(RentalItem.canonicalNumber("  ab-12   тест ")).isEqualTo("AB-12 ТЕСТ");
    assertThat(RentalItem.identityMatchKey("  ab-12   тест ")).isEqualTo("AB12ТЕСТ");
    assertThat(RentalItem.canonicalNumber(" тест_1 ")).isEqualTo("ТЕСТ_1");
    assertThat(RentalItem.identityMatchKey(" тест_1 ")).isEqualTo("ТЕСТ1");
    assertThatThrownBy(() -> RentalItem.canonicalNumber("ab-12 / тест"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RentalItem.canonicalNumber("---"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void inventorySourceCreateStartsOperationallyFree() {
    RentalItem item = RentalItem.createFromInventory(
        UUID.randomUUID(), "  инв- 77 ", TYPE_BK_1, DIMENSION_24_X_6, FINISHING_DVP,
        null, null, "{}", "[]");

    assertThat(item.getNumber()).isEqualTo("ИНВ- 77");
    assertThat(item.getIdentityMatchKey()).isEqualTo("ИНВ77");
    assertThat(item.getStatus()).isEqualTo(RentalItemStatus.FREE);
  }

  @Test
  void warehouseCreateStartsFreeAndDefaultsToTheNewCategory() {
    RentalItem item =
        RentalItem.create(
            UUID.randomUUID(), "СКЛАД-1", TYPE_BK_1, DIMENSION_24_X_6, FINISHING_DVP,
            null, null, "{}", "[]");

    assertThat(item.getStatus()).isEqualTo(RentalItemStatus.FREE);
    assertThat(item.getCategory()).isEqualTo("Новая");
  }

  @Test
  void htmlImportCanCreateAnIncompleteCabinForLaterCompletion() {
    RentalItem item =
        RentalItem.createFromHtmlImport(
            UUID.randomUUID(),
            "HTML-1",
            RentalItemStatus.FREE,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    assertThat(item.getRentalTypeId()).isNull();
    assertThat(item.getDimensionId()).isNull();
    assertThat(item.getFinishingId()).isNull();
    assertThat(item.getCategoryId()).isNull();
    assertThat(item.getCategory()).isNull();
    assertThat(item.getPassportJson()).isEqualTo("{}");
    assertThat(item.getTagsJson()).isEqualTo("[]");
  }

  @Test
  void htmlImportCannotCreateFencedOrTerminalCabins() {
    for (RentalItemStatus status :
        List.of(
            RentalItemStatus.IN_TRANSFER,
            RentalItemStatus.WRITTEN_OFF,
            RentalItemStatus.LOST)) {
      assertThatThrownBy(
              () ->
                  RentalItem.createFromHtmlImport(
                      UUID.randomUUID(),
                      "HTML-" + status,
                      status,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null))
          .as("HTML import status %s", status)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("fenced or terminal");
    }
  }

  @Test
  void regularCabinCreationAndPassportEditingStillRequireComposition() {
    assertThatThrownBy(
            () ->
                RentalItem.create(
                    UUID.randomUUID(), "MANUAL-1", null, null, null, null, null, "{}", "[]"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Cabin composition IDs are required");
    assertThatThrownBy(
            () ->
                RentalItem.createFromInventory(
                    UUID.randomUUID(), "INVENTORY-1", null, null, null, null, null, "{}", "[]"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Cabin composition IDs are required");

    RentalItem item =
        RentalItem.create(
            UUID.randomUUID(),
            "MANUAL-2",
            TYPE_BK_1,
            DIMENSION_24_X_6,
            FINISHING_DVP,
            null,
            null,
            "{}",
            "[]");

    assertThatThrownBy(() -> item.changePassport(null, null, null, null, null, "{}", "[]"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Cabin composition IDs are required");
  }

  @Test
  void reservesOperationalAndTerminalStatusesFromThePublicManualTransition() {
    RentalItem item = RentalItem.create(
        UUID.randomUUID(), "A-1", TYPE_BK_1, DIMENSION_24_X_6, FINISHING_DVP,
        null, null, "{}", "[]");

    for (RentalItemStatus terminal :
        List.of(RentalItemStatus.WRITTEN_OFF, RentalItemStatus.LOST)) {
      assertThatThrownBy(() -> item.changeStatus(terminal))
          .as("terminal target %s", terminal)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("fenced or terminal");
    }
    assertThatThrownBy(() -> item.changeStatus(RentalItemStatus.RENTED))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fenced or terminal");

    item.departTransferUnderLease(RentalItemStatus.FREE);
    assertThatThrownBy(() -> item.changeStatus(RentalItemStatus.FREE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fenced or terminal");
    assertThatThrownBy(() -> item.changeStatusUnderLease(RentalItemStatus.FREE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dedicated transfer transition");
    item.arriveTransferUnderLease(RentalItemStatus.FREE);
    assertThat(item.getStatus()).isEqualTo(RentalItemStatus.FREE);
    assertThat(item.getTransferOriginStatus()).isNull();
  }

  @Test
  void permitsManualChangesOnlyWithinTheCommercialAndStorageSubset() {
    RentalItem item = RentalItem.create(
        UUID.randomUUID(), "A-2", TYPE_BK_1, DIMENSION_24_X_6, FINISHING_DVP,
        null, null, "{}", "[]");

    assertThat(item.changeStatus(RentalItemStatus.SALE)).isTrue();
    assertThat(item.changeStatus(RentalItemStatus.USED_SALE)).isTrue();
    assertThat(item.changeStatus(RentalItemStatus.WAREHOUSE)).isTrue();
    assertThat(item.changeStatus(RentalItemStatus.OWN_NEEDS)).isTrue();
    assertThat(item.changeStatus(RentalItemStatus.FREE)).isTrue();

    item.changeStatusUnderLease(RentalItemStatus.RENTED);
    assertThatThrownBy(() -> item.changeStatus(RentalItemStatus.FREE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fenced or terminal");

    for (RentalItemStatus terminal :
        List.of(RentalItemStatus.WRITTEN_OFF, RentalItemStatus.LOST)) {
      RentalItem terminalItem = RentalItem.create(
          UUID.randomUUID(), "A-3-" + terminal, TYPE_BK_1, DIMENSION_24_X_6, FINISHING_DVP,
          null, null, "{}", "[]");
      terminalItem.changeStatusUnderLease(terminal);
      assertThatThrownBy(() -> terminalItem.changeStatus(terminal))
          .as("terminal source %s", terminal)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("fenced or terminal");
    }
  }

  @Test
  void keepsDispositionedCabinsTerminalUnderLease() {
    for (RentalItemStatus terminal :
        List.of(RentalItemStatus.WRITTEN_OFF, RentalItemStatus.LOST)) {
      RentalItem item = RentalItem.create(
          UUID.randomUUID(), "A-4-" + terminal, TYPE_BK_1, DIMENSION_24_X_6, FINISHING_DVP,
          null, null, "{}", "[]");

      item.changeStatusUnderLease(terminal);

      assertThat(item.changeStatusUnderLease(terminal)).as("terminal %s", terminal).isFalse();
      assertThatThrownBy(() -> item.changeStatusUnderLease(RentalItemStatus.FREE))
          .as("terminal %s", terminal)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Terminal");
      assertThatThrownBy(() -> item.changeWarehouse(UUID.randomUUID()))
          .as("terminal %s", terminal)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("cannot change warehouse");
    }
  }
}
