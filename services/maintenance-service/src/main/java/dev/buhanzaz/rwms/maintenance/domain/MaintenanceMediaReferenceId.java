package dev.buhanzaz.rwms.maintenance.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite persistence identity for MaintenanceMediaReferenceId. */
public class MaintenanceMediaReferenceId implements Serializable {
  private String aggregateType;
  private UUID aggregateId;
  private UUID mediaId;

  public MaintenanceMediaReferenceId() {}

  public MaintenanceMediaReferenceId(String aggregateType, UUID aggregateId, UUID mediaId) {
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.mediaId = mediaId;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof MaintenanceMediaReferenceId value)) return false;
    return Objects.equals(aggregateType, value.aggregateType)
        && Objects.equals(aggregateId, value.aggregateId)
        && Objects.equals(mediaId, value.mediaId);
  }

  @Override
  public int hashCode() { return Objects.hash(aggregateType, aggregateId, mediaId); }
}
