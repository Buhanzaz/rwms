package dev.buhanzaz.rwms.logistics.inquiry.eventing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Finite attempt and per-run limits for rental-inquiry event delivery. */
@ConfigurationProperties("rwms.logistics.rental-inquiry")
public record RentalInquiryOutboxProperties(boolean outboxEnabled, int maxAttempts, int maxPerRun) {
  public RentalInquiryOutboxProperties {
    if (maxAttempts < 1 || maxPerRun < 1) {
      throw new IllegalArgumentException("Rental inquiry outbox limits must be positive");
    }
  }
}
