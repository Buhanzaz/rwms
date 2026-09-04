package dev.buhanzaz.rwms.logistics.retention;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds the approved online/archive policy while keeping destructive execution disabled. */
@ConfigurationProperties("rwms.logistics.retention")
public class LogisticsRetentionProperties {
  private Duration businessAuditProof = Duration.ofDays(1_825);
  private Duration eventOnline = Duration.ofDays(90);
  private Duration eventArchive = Duration.ofDays(365);
  private Duration gpsTelemetry = Duration.ofDays(30);
  private boolean deletionEnabled;

  public Duration getBusinessAuditProof() {
    return businessAuditProof;
  }

  public void setBusinessAuditProof(Duration value) {
    businessAuditProof = positive(value, "businessAuditProof");
  }

  public Duration getEventOnline() {
    return eventOnline;
  }

  public void setEventOnline(Duration value) {
    eventOnline = positive(value, "eventOnline");
  }

  public Duration getEventArchive() {
    return eventArchive;
  }

  public void setEventArchive(Duration value) {
    eventArchive = positive(value, "eventArchive");
  }

  public Duration getGpsTelemetry() {
    return gpsTelemetry;
  }

  public void setGpsTelemetry(Duration value) {
    gpsTelemetry = positive(value, "gpsTelemetry");
  }

  public boolean isDeletionEnabled() {
    return deletionEnabled;
  }

  public void setDeletionEnabled(boolean value) {
    deletionEnabled = value;
  }

  private static Duration positive(Duration value, String field) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(field + " retention must be positive");
    }
    return value;
  }
}
