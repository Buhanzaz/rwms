package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "inventory_publication_attempt_result")
public class InventoryPublicationAttemptResult {
  @Id @Column(name = "publication_attempt_id", nullable = false) private UUID publicationAttemptId;
  @Column(name = "outcome", nullable = false, length = 24) private String outcome;
  @Column(name = "failure_code", length = 64) private String failureCode;
  @Column(name = "message_sha256", length = 64) private String messageSha256;
  @Column(name = "repair_id") private UUID repairId;
  @Column(name = "finished_at", nullable = false) private OffsetDateTime finishedAt;

  protected InventoryPublicationAttemptResult() {}

  public InventoryPublicationAttemptResult(UUID attemptId, String outcome, String failureCode,
      String messageSha256, UUID repairId) {
    publicationAttemptId = attemptId; this.outcome = outcome; this.failureCode = failureCode;
    this.messageSha256 = messageSha256; this.repairId = repairId;
    finishedAt = OffsetDateTime.now(ZoneOffset.UTC);
  }
}
