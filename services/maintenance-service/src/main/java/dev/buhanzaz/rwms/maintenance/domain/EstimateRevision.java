package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable metadata and audit header for one estimate revision. */
@Entity
@Table(
    name = "estimate_revision",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_estimate_revision", columnNames = {"estimate_id", "revision"}))
public class EstimateRevision {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "estimate_id", nullable = false)
  private UUID estimateId;

  @Column(name = "revision", nullable = false)
  private int revision;

  @Column(name = "dispatch_date", nullable = false)
  private LocalDate dispatchDate;

  @Column(name = "source_party", length = 512)
  private String sourceParty;

  @Column(name = "amendment_reason", length = 2000)
  private String amendmentReason;

  @Column(name = "total_minor", nullable = false)
  private long totalMinor;

  @Column(name = "actor_ref", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String actorRef;

  @Column(name = "recorded_at", nullable = false)
  private OffsetDateTime recordedAt;

  protected EstimateRevision() {}

  public EstimateRevision(
      UUID estimateId,
      int revision,
      LocalDate dispatchDate,
      String sourceParty,
      String amendmentReason,
      long totalMinor,
      String actorRef) {
    if (estimateId == null || revision < 1 || dispatchDate == null || totalMinor < 0) {
      throw new IllegalArgumentException("Estimate revision identity and totals are required");
    }
    this.estimateId = estimateId;
    this.revision = revision;
    this.dispatchDate = dispatchDate;
    this.sourceParty = normalize(sourceParty, 512);
    this.amendmentReason = normalize(amendmentReason, 2000);
    this.totalMinor = totalMinor;
    this.actorRef = actorRef == null ? "{}" : actorRef;
    this.recordedAt = MaintenanceTime.now();
  }

  /** The only mutable header is the current DRAFT revision. Completed revisions are never replaced. */
  public void replaceDraft(
      LocalDate dispatchDate, String sourceParty, long totalMinor, String actorRef) {
    if (dispatchDate == null || totalMinor < 0) {
      throw new IllegalArgumentException("Estimate revision values are invalid");
    }
    this.dispatchDate = dispatchDate;
    this.sourceParty = normalize(sourceParty, 512);
    this.totalMinor = totalMinor;
    this.actorRef = actorRef == null ? "{}" : actorRef;
    this.recordedAt = MaintenanceTime.now();
  }

  private static String normalize(String value, int maximum) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim();
    if (normalized.length() > maximum) throw new IllegalArgumentException("Revision text is too long");
    return normalized;
  }

  public UUID getId() { return id; }
  public UUID getEstimateId() { return estimateId; }
  public int getRevision() { return revision; }
  public LocalDate getDispatchDate() { return dispatchDate; }
  public String getSourceParty() { return sourceParty; }
  public String getAmendmentReason() { return amendmentReason; }
  public long getTotalMinor() { return totalMinor; }
  public String getActorRef() { return actorRef; }
  public OffsetDateTime getRecordedAt() { return recordedAt; }
}
