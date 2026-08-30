package dev.buhanzaz.rwms.taskboard.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Runtime switches, day boundary, odometer guard and non-blocking MET Norway settings. */
@Validated
@ConfigurationProperties("rwms.driver-shift")
public record DriverShiftProperties(
    boolean enabled,
    @NotNull LocalTime dayStart,
    @Min(1) long suspiciousOdometerJumpKm,
    @Valid @NotNull Weather weather,
    @Valid @NotNull Hazards hazards) {
  /** MET Norway client and bounded coordinate-cache settings. */
  public record Weather(
      @NotNull URI baseUrl,
      String userAgent,
      @NotNull Duration connectTimeout,
      @NotNull Duration readTimeout,
      @NotNull Duration cacheTtl,
      @Min(1) @Max(5) int maxAttempts) {
    /** Ensures transport and cache bounds cannot disable timeout or expiry behavior. */
    @AssertTrue(message = "weather timeouts and cache TTL must be positive")
    public boolean isPositiveDurations() {
      return positive(connectTimeout) && positive(readTimeout) && positive(cacheTtl);
    }

    /** Restricts provider traffic to an explicit HTTP(S) endpoint. */
    @AssertTrue(message = "weather base URL must be an absolute HTTP(S) URI")
    public boolean isHttpBaseUrl() {
      if (baseUrl == null || !baseUrl.isAbsolute()) return false;
      return "http".equalsIgnoreCase(baseUrl.getScheme())
          || "https".equalsIgnoreCase(baseUrl.getScheme());
    }

    private boolean positive(Duration value) {
      return value != null && !value.isZero() && !value.isNegative();
    }
  }

  /** Configurable conservative advisory thresholds applied to supported provider facts. */
  public record Hazards(
      @DecimalMin("0.0") double windGustWarning,
      @DecimalMin("0.0") double heavyRainMmPerHour,
      @DecimalMin("0.0") @DecimalMax("100.0") double fogAreaFractionWarningPercent) {}
}
