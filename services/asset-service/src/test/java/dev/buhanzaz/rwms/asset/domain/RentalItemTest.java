package dev.buhanzaz.rwms.asset.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RentalItemTest {

  @Test
  void canonicalizesDisplayAndIdentityNumbersWithoutGuessingPunctuation() {
    assertThat(RentalItem.canonicalNumber("  ab-12   тест ")).isEqualTo("AB-12 ТЕСТ");
    assertThat(RentalItem.identityMatchKey("  ab-12   тест ")).isEqualTo("AB12ТЕСТ");
    assertThatThrownBy(() -> RentalItem.canonicalNumber("ab-12 / тест"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RentalItem.canonicalNumber("---"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void inventorySourceCreateStartsOperationallyFree() {
    RentalItem item = RentalItem.createFromInventory(
        UUID.randomUUID(), "  инв- 77 ", null, null, null, null, null, null, "{}", "[]");

    assertThat(item.getNumber()).isEqualTo("ИНВ- 77");
    assertThat(item.getIdentityMatchKey()).isEqualTo("ИНВ77");
    assertThat(item.getStatus()).isEqualTo(RentalItemStatus.FREE);
  }

  @Test
  void reservesTerminalAndWorkflowStatusesFromThePublicManualTransition() {
    RentalItem item = RentalItem.create(UUID.randomUUID(), "A-1", null, null, null, null, null, null, "{}", "[]");

    assertThatThrownBy(() -> item.changeStatus(RentalItemStatus.WRITTEN_OFF))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fenced or terminal");
  }
}
