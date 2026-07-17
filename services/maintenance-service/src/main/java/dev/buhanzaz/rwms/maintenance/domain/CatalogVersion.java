package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "catalog_version")
public class CatalogVersion {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private CatalogVersionState state;

  @Column(name = "source_sha256", nullable = false, length = 64)
  private String sourceSha256;

  @Column(name = "node_count", nullable = false)
  private int nodeCount;

  @Column(name = "link_count", nullable = false)
  private int linkCount;

  @Column(name = "validation_report", nullable = false, length = 16000)
  private String validationReport;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Column(name = "activated_at")
  private OffsetDateTime activatedAt;

  protected CatalogVersion() {}

  public static CatalogVersion draft(
      UUID warehouseId, String sourceSha256, int nodeCount, int linkCount, String validationReport) {
    if (warehouseId == null) throw new IllegalArgumentException("warehouseId is required");
    if (sourceSha256 == null || !sourceSha256.matches("^[0-9a-f]{64}$")) {
      throw new IllegalArgumentException("sourceSha256 is invalid");
    }
    if (nodeCount < 0 || linkCount < 0) {
      throw new IllegalArgumentException("Catalog counts must not be negative");
    }
    CatalogVersion value = new CatalogVersion();
    value.warehouseId = warehouseId;
    value.state = CatalogVersionState.DRAFT;
    value.sourceSha256 = sourceSha256;
    value.nodeCount = nodeCount;
    value.linkCount = linkCount;
    value.validationReport = validationReport == null ? "{}" : validationReport;
    return value;
  }

  public void activate() {
    if (state != CatalogVersionState.DRAFT) {
      throw new IllegalStateException("Only a draft catalog version can be activated");
    }
    state = CatalogVersionState.ACTIVE;
    activatedAt = MaintenanceTime.now();
  }

  public void replaceDraft(int nodeCount, int linkCount, String validationReport) {
    if (state != CatalogVersionState.DRAFT) {
      throw new IllegalStateException("Published catalog versions are immutable");
    }
    if (nodeCount < 0 || linkCount < 0) {
      throw new IllegalArgumentException("Catalog counts must not be negative");
    }
    this.nodeCount = nodeCount;
    this.linkCount = linkCount;
    this.validationReport = validationReport == null ? "{}" : validationReport;
  }

  public void supersede() {
    if (state != CatalogVersionState.ACTIVE) {
      throw new IllegalStateException("Only an active catalog version can be superseded");
    }
    state = CatalogVersionState.SUPERSEDED;
  }

  @PrePersist
  void beforeInsert() {
    OffsetDateTime now = MaintenanceTime.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void beforeUpdate() {
    updatedAt = MaintenanceTime.now();
  }

  public UUID getId() { return id; }
  public long getVersion() { return version; }
  public UUID getWarehouseId() { return warehouseId; }
  public CatalogVersionState getState() { return state; }
  public String getSourceSha256() { return sourceSha256; }
  public int getNodeCount() { return nodeCount; }
  public int getLinkCount() { return linkCount; }
  public String getValidationReport() { return validationReport; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public OffsetDateTime getActivatedAt() { return activatedAt; }
}
