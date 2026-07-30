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

@Entity
@Table(name = "kpi_palette")
public class KpiPalette extends AbstractVersionedEntity {
  @Column(name = "overdue_color", nullable = false, length = 7)
  private String overdueColor;

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
    this.ranges.clear();
    this.ranges.addAll(ranges);
  }

  public String getOverdueColor() {
    return overdueColor;
  }

  public List<KpiPaletteRange> getRanges() {
    return Collections.unmodifiableList(ranges);
  }
}
