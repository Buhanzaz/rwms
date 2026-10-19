package dev.buhanzaz.rwms.taskboard.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Verifies invalid Driver Shift business and provider limits fail during configuration binding. */
class DriverShiftPropertiesValidationTest {
  private static final String[] VALID_PROPERTIES = {
    "rwms.driver-shift.enabled=true",
    "rwms.driver-shift.day-start=06:00",
    "rwms.driver-shift.suspicious-odometer-jump-km=1500",
    "rwms.driver-shift.weather.base-url=https://api.met.no/weatherapi/locationforecast/2.0/complete",
    "rwms.driver-shift.weather.user-agent=driver-up-tests test@example.invalid",
    "rwms.driver-shift.weather.connect-timeout=2s",
    "rwms.driver-shift.weather.read-timeout=5s",
    "rwms.driver-shift.weather.cache-ttl=15m",
    "rwms.driver-shift.weather.max-attempts=2",
    "rwms.driver-shift.hazards.wind-gust-warning=13.0",
    "rwms.driver-shift.hazards.heavy-rain-mm-per-hour=10.0",
    "rwms.driver-shift.hazards.fog-area-fraction-warning-percent=30.0"
  };

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(DriverShiftConfiguration.class);

  @Test
  void validConfigurationStartsProviderInfrastructure() {
    contextRunner
        .withPropertyValues(VALID_PROPERTIES)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(DriverShiftProperties.class);
            });
  }

  @Test
  void nonPositiveBusinessAndProviderLimitsFailStartupBinding() {
    contextRunner
        .withPropertyValues(VALID_PROPERTIES)
        .withPropertyValues(
            "rwms.driver-shift.suspicious-odometer-jump-km=0",
            "rwms.driver-shift.weather.max-attempts=0",
            "rwms.driver-shift.weather.cache-ttl=0s",
            "rwms.driver-shift.hazards.wind-gust-warning=-1")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasMessageContaining("Could not bind properties to 'DriverShiftProperties'");
            });
  }
}
