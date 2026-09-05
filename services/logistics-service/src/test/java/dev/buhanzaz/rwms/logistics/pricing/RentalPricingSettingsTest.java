package dev.buhanzaz.rwms.logistics.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.logistics.pricing.domain.RentalPricingRate;
import dev.buhanzaz.rwms.logistics.pricing.domain.RentalPricingSettings;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RentalPricingSettingsTest {
  private static final UUID TYPE = UUID.randomUUID();
  private static final UUID CATEGORY = UUID.randomUUID();
  private static final UUID ACTOR = UUID.randomUUID();
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-05T11:00:00Z");

  @Test
  void defaultsAndExplicitZeroHaveNoOverridesOrInventedActor() {
    var settings = RentalPricingSettings.defaults(NOW);
    settings.setMonthlyPrice(TYPE, CATEGORY, 0, ACTOR, NOW.plusMinutes(1));
    assertThat(settings.getRates()).isEmpty();
    assertThat(settings.getUpdatedBySubjectId()).isNull();
    assertThat(settings.getUpdatedAt()).isEqualTo(NOW);
  }

  @Test
  void replacesOnePairWithoutChangingAnotherAndZeroRemovesOnlyThatOverride() {
    var settings = RentalPricingSettings.defaults(NOW);
    UUID otherCategory = UUID.randomUUID();
    settings.setMonthlyPrice(TYPE, CATEGORY, 8000, ACTOR, NOW);
    settings.setMonthlyPrice(TYPE, otherCategory, 10000, ACTOR, NOW);
    settings.setMonthlyPrice(TYPE, CATEGORY, Long.MAX_VALUE, ACTOR, NOW.plusMinutes(1));
    assertThat(settings.getRates())
        .containsExactlyInAnyOrder(
            new RentalPricingRate(TYPE, CATEGORY, Long.MAX_VALUE),
            new RentalPricingRate(TYPE, otherCategory, 10000));
    settings.setMonthlyPrice(TYPE, CATEGORY, 0, ACTOR, NOW.plusMinutes(2));
    assertThat(settings.getRates())
        .containsExactly(new RentalPricingRate(TYPE, otherCategory, 10000));
  }

  @Test
  void rejectsInvalidInputBeforeChangingTheExistingRate() {
    var settings = RentalPricingSettings.defaults(NOW);
    settings.setMonthlyPrice(TYPE, CATEGORY, 8000, ACTOR, NOW);
    assertThatThrownBy(() -> settings.setMonthlyPrice(TYPE, CATEGORY, -1, ACTOR, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> settings.setMonthlyPrice(TYPE, CATEGORY, 9000, null, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> settings.getRates().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(settings.getRates()).containsExactly(new RentalPricingRate(TYPE, CATEGORY, 8000));
  }

  @Test
  void unchangedPricePreservesAuditMetadataAndValueEquality() {
    var settings = RentalPricingSettings.defaults(NOW);
    settings.setMonthlyPrice(TYPE, CATEGORY, 8000, ACTOR, NOW);
    settings.setMonthlyPrice(TYPE, CATEGORY, 8000, UUID.randomUUID(), NOW.plusMinutes(1));
    assertThat(settings.getUpdatedAt()).isEqualTo(NOW);
    assertThat(settings.getUpdatedBySubjectId()).isEqualTo(ACTOR);
    assertThat(new RentalPricingRate(TYPE, CATEGORY, 8000))
        .isEqualTo(new RentalPricingRate(TYPE, CATEGORY, 8000));
    assertThat(new RentalPricingRate(TYPE, CATEGORY, 8000).hashCode())
        .isEqualTo(new RentalPricingRate(TYPE, CATEGORY, 8000).hashCode());
  }
}
