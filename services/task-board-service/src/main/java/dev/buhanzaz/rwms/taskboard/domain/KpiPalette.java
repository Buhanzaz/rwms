package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Versioned installation-wide KPI display thresholds and semantic status colors. */
@Entity
@Table(name = "kpi_palette")
public class KpiPalette extends AbstractVersionedEntity {
  @Column(name = "overdue_color", nullable = false, length = 7)
  private String overdueColor;

  @Column(name = "problem_color", nullable = false, length = 7)
  private String problemColor = "#FF3B30";

  @Column(name = "completed_color", nullable = false, length = 7)
  private String completedColor = "#238636";

  public String getProblemColor() { return problemColor; }
  public void changeProblemColor(String value) { problemColor = value; }

  public String getCompletedColor() { return completedColor; }
  public void changeCompletedColor(String value) { completedColor = value; }

  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "palette_id", nullable = false)
  @OrderBy("fromPercent ASC")
  private final List<KpiPaletteRange> ranges = new ArrayList<>();

  protected KpiPalette() {}

  public KpiPalette(String overdueColor, List<KpiPaletteRange> ranges) {
    replace(overdueColor, ranges);
  }

  public void replace(String overdueColor, List<KpiPaletteRange> ranges) {
    this.overdueColor = overdueColor;
    clearRanges();
    this.ranges.addAll(ranges);
  }

  /**
   * Removes every current range before a replacement is flushed under the range uniqueness fence.
   */
  public void clearRanges() {
    ranges.clear();
  }

  public String getOverdueColor() {
    return overdueColor;
  }

  public List<KpiPaletteRange> getRanges() {
    return Collections.unmodifiableList(ranges);
  }
}
