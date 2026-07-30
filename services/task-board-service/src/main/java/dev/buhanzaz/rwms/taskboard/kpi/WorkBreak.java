package dev.buhanzaz.rwms.taskboard.kpi;

import java.time.LocalTime;
import java.util.Objects;

public record WorkBreak(LocalTime start, LocalTime end) {
  public WorkBreak {
    Objects.requireNonNull(start, "Break start is required");
    Objects.requireNonNull(end, "Break end is required");
    if (!start.isBefore(end)) {
      throw new IllegalArgumentException("Break start must be before break end");
    }
  }
}
