package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "inventory_completion_statistics")
public class InventoryCompletionStatistics {
  @Id @Column(name = "inventory_id", nullable = false) private UUID inventoryId;
  @Column(name = "expected_count", nullable = false) private int expectedCount;
  @Column(name = "inspected_count", nullable = false) private int inspectedCount;
  @Column(name = "missing_count", nullable = false) private int missingCount;
  @Column(name = "ready_count", nullable = false) private int readyCount;
  @Column(name = "with_work_count", nullable = false) private int withWorkCount;
  @Column(name = "added_count", nullable = false) private int addedCount;
  @Column(name = "unexpected_existing_count", nullable = false) private int unexpectedExistingCount;
  @Column(name = "conflict_count", nullable = false) private int conflictCount;
  @Column(name = "work_line_count", nullable = false) private int workLineCount;
  @Column(name = "material_line_count", nullable = false) private int materialLineCount;
  @Column(name = "work_total_minor", nullable = false) private long workTotalMinor;
  @Column(name = "material_total_minor", nullable = false) private long materialTotalMinor;
  @Column(name = "grand_total_minor", nullable = false) private long grandTotalMinor;
  @Column(name = "rounding_adjustment_minor", nullable = false) private short roundingAdjustmentMinor;
  @Column(name = "normative_minutes", nullable = false, precision = 22, scale = 3) private BigDecimal normativeMinutes;
  @Column(name = "duration_seconds", nullable = false) private long durationSeconds;
  @Column(name = "frozen_at", nullable = false) private OffsetDateTime frozenAt;

  protected InventoryCompletionStatistics() {}
  public InventoryCompletionStatistics(UUID id, int expected, int inspected, int missing,
      int ready, int withWork, int added, int unexpected, int conflicts, int workLines,
      int materialLines, long work, long material, long grand, int adjustment,
      BigDecimal normative, long duration) {
    inventoryId = id; expectedCount = expected; inspectedCount = inspected; missingCount = missing;
    readyCount = ready; withWorkCount = withWork; addedCount = added;
    unexpectedExistingCount = unexpected; conflictCount = conflicts; workLineCount = workLines;
    materialLineCount = materialLines; workTotalMinor = work; materialTotalMinor = material;
    grandTotalMinor = grand;
    if (adjustment < Short.MIN_VALUE || adjustment > Short.MAX_VALUE) {
      throw new IllegalArgumentException("Rounding adjustment exceeds smallint");
    }
    roundingAdjustmentMinor = (short) adjustment; normativeMinutes = normative;
    durationSeconds = duration; frozenAt = OffsetDateTime.now(ZoneOffset.UTC);
  }
  public int getExpectedCount() { return expectedCount; } public int getInspectedCount() { return inspectedCount; }
  public int getMissingCount() { return missingCount; } public int getReadyCount() { return readyCount; }
  public int getWithWorkCount() { return withWorkCount; } public int getAddedCount() { return addedCount; }
  public int getUnexpectedExistingCount() { return unexpectedExistingCount; } public int getConflictCount() { return conflictCount; }
  public int getWorkLineCount() { return workLineCount; } public int getMaterialLineCount() { return materialLineCount; }
  public long getWorkTotalMinor() { return workTotalMinor; } public long getMaterialTotalMinor() { return materialTotalMinor; }
  public long getGrandTotalMinor() { return grandTotalMinor; } public int getRoundingAdjustmentMinor() { return roundingAdjustmentMinor; }
  public BigDecimal getNormativeMinutes() { return normativeMinutes; } public long getDurationSeconds() { return durationSeconds; }
}
