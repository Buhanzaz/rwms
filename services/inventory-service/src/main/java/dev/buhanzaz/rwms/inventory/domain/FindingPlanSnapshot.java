package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "finding_plan_snapshot")
@IdClass(FindingPlanSnapshot.Key.class)
public class FindingPlanSnapshot {
  @Id @Column(name = "finding_id", nullable = false) private UUID findingId;
  @Id @Column(name = "finding_revision", nullable = false) private long findingRevision;
  @Column(name = "inventory_id", nullable = false) private UUID inventoryId;
  @Column(name = "plan_mode", nullable = false, length = 16) private String planMode;
  @Column(name = "catalog_version_id", nullable = false) private UUID catalogVersionId;
  @Column(name = "plan_fingerprint_sha256", nullable = false, length = 64)
  private String fingerprint;
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "source_snapshot", nullable = false, columnDefinition = "jsonb")
  private String sourceSnapshot;
  @Column(name = "frozen_at", nullable = false) private OffsetDateTime frozenAt;

  protected FindingPlanSnapshot() {}

  public FindingPlanSnapshot(
      UUID findingId,
      long findingRevision,
      UUID inventoryId,
      String planMode,
      UUID catalogVersionId,
      String fingerprint,
      String sourceSnapshot) {
    this.findingId = findingId;
    this.findingRevision = findingRevision;
    this.inventoryId = inventoryId;
    this.planMode = planMode;
    this.catalogVersionId = catalogVersionId;
    this.fingerprint = fingerprint;
    this.sourceSnapshot = sourceSnapshot;
    frozenAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  public String getSourceSnapshot() {
    return sourceSnapshot;
  }

  public String getFingerprint() {
    return fingerprint;
  }

  public UUID getFindingId() {
    return findingId;
  }

  public long getFindingRevision() {
    return findingRevision;
  }

  public String getPlanMode() {
    return planMode;
  }

  public UUID getCatalogVersionId() {
    return catalogVersionId;
  }

  public static class Key implements Serializable {
    private UUID findingId;
    private long findingRevision;
    public Key() {}
    @Override public boolean equals(Object other) { return this == other || other instanceof Key key && findingRevision == key.findingRevision && Objects.equals(findingId, key.findingId); }
    @Override public int hashCode() { return Objects.hash(findingId, findingRevision); }
  }
}
