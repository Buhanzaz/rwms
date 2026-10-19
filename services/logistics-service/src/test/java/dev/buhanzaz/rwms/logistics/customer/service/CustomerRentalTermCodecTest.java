package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinRentalTerm;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Covers canonical per-cabin terms used by cart fencing and booking idempotency. */
class CustomerRentalTermCodecTest {
  private final CustomerRentalTermCodec codec =
      new CustomerRentalTermCodec(JsonMapper.builder().findAndAddModules().build());

  @Test
  void preservesIndependentTermsInStableCabinOrder() {
    UUID first = UUID.fromString("00000000-0000-0000-0000-000000000101");
    UUID second = UUID.fromString("00000000-0000-0000-0000-000000000102");

    String encoded =
        codec.encode(
            List.of(
                new CustomerCabinRentalTerm(second, 6L),
                new CustomerCabinRentalTerm(first, 2L)),
            Set.of(first, second));

    assertThat(codec.decode(encoded))
        .containsExactly(
            new CustomerCabinRentalTerm(first, 2L),
            new CustomerCabinRentalTerm(second, 6L));
  }

  @Test
  void rejectsDuplicateOrUnselectedCabinTerms() {
    UUID selected = UUID.fromString("00000000-0000-0000-0000-000000000103");
    UUID other = UUID.fromString("00000000-0000-0000-0000-000000000104");

    assertThatThrownBy(
            () ->
                codec.encode(
                    List.of(
                        new CustomerCabinRentalTerm(selected, 1L),
                        new CustomerCabinRentalTerm(selected, 2L)),
                    Set.of(selected)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Rental term contains a duplicate cabin");
    assertThatThrownBy(
            () ->
                codec.encode(
                    List.of(new CustomerCabinRentalTerm(other, 1L)), Set.of(selected)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Rental term belongs to an unselected cabin");
  }

  @Test
  void preservesExistingTermsAndDefaultsEveryNewlySelectedCabinToOneMonth() {
    UUID retained = UUID.fromString("00000000-0000-0000-0000-000000000105");
    UUID added = UUID.fromString("00000000-0000-0000-0000-000000000106");
    UUID removed = UUID.fromString("00000000-0000-0000-0000-000000000107");
    String stored =
        codec.encode(
            List.of(
                new CustomerCabinRentalTerm(retained, 6L),
                new CustomerCabinRentalTerm(removed, 3L)),
            Set.of(retained, removed));

    assertThat(codec.completeWithDefaults(stored, Set.of(retained, added)))
        .containsExactly(
            new CustomerCabinRentalTerm(retained, 6L),
            new CustomerCabinRentalTerm(added, 1L));
    assertThat(codec.decode(codec.retain("[]", Set.of(added))))
        .containsExactly(new CustomerCabinRentalTerm(added, 1L));
  }
}
