package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Mutable lifecycle companion to an immutable completed-inventory publication source.
 *
 * <p>The source records what was decided; this row records the one local transition which may
 * release its already-created successor to the normal repair queue. Keeping it separate preserves
 * the source's append-only audit trigger.
 */
@Entity
@Table(name = "inventory_publication_successor")
public class InventoryPublicationSuccessor {
  @EmbeddedId private InventoryPublicationSourceId id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "predecessor_repair_id", nullable = false)
  private UUID predecessorRepairId;

  @Column(name = "successor_repair_id", nullable = false)
  private UUID successorRepairId;

  @Column(name = "state", nullable = false, length = 32)
  private String state;

  @Column(name = "terminal_fact", length = 32)
  private String terminalFact;

  @Column(name = "terminal_fact_event_id")
  private UUID terminalFactEventId;

  @Column(name = "terminal_fact_occurred_at")
  private OffsetDateTime terminalFactOccurredAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "released_at")
  private OffsetDateTime releasedAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected InventoryPublicationSuccessor() {}

  public static InventoryPublicationSuccessor waiting(
      InventoryPublicationSourceId id, UUID predecessorRepairId, UUID successorRepairId) {
    if (id == null || predecessorRepairId == null || successorRepairId == null
        || predecessorRepairId.equals(successorRepairId)) {
      throw new IllegalArgumentException("Inventory publication successor identity is incomplete");
    }
    InventoryPublicationSuccessor value = new InventoryPublicationSuccessor();
    value.id = id;
    value.predecessorRepairId = predecessorRepairId;
    value.successorRepairId = successorRepairId;
    value.state = "WAITING_PREDECESSOR";
    return value;
  }

  /** Marks the successor eligible for the normal idempotent queue reconciliation exactly once. */
  public boolean release(String terminalFact, UUID terminalFactEventId, OffsetDateTime occurredAt) {
    if (!"WAITING_PREDECESSOR".equals(state)) {
      return false;
    }
    if (!("TASK_BOARD_COMPLETION".equals(terminalFact)
            || "REPAIR_ACCEPTANCE".equals(terminalFact))
        || terminalFactEventId == null) {
      throw new IllegalArgumentException("Inventory successor terminal fact is incomplete");
    }
    state = "RELEASED";
    this.terminalFact = terminalFact;
    this.terminalFactEventId = terminalFactEventId;
    terminalFactOccurredAt = occurredAt == null
        ? MaintenanceTime.now() : MaintenanceTime.postgresPrecision(occurredAt);
    releasedAt = MaintenanceTime.now();
    updatedAt = releasedAt;
    return true;
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

  public InventoryPublicationSourceId getId() { return id; }
  public long getVersion() { return version; }
  public UUID getPredecessorRepairId() { return predecessorRepairId; }
  public UUID getSuccessorRepairId() { return successorRepairId; }
  public String getState() { return state; }
  public String getTerminalFact() { return terminalFact; }
  public UUID getTerminalFactEventId() { return terminalFactEventId; }
  public OffsetDateTime getTerminalFactOccurredAt() { return terminalFactOccurredAt; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getReleasedAt() { return releasedAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
