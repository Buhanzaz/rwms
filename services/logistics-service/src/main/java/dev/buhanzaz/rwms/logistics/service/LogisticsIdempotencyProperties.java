package dev.buhanzaz.rwms.logistics.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rwms.logistics.idempotency")
public record LogisticsIdempotencyProperties(Duration retention) {
  public LogisticsIdempotencyProperties {
    if (retention == null || retention.isNegative() || retention.isZero()) {
      throw new IllegalArgumentException("Idempotency retention must be positive");
    }
  }
}
