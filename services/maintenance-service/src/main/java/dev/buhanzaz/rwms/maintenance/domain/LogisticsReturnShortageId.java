package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

@Embeddable
@Getter
public class LogisticsReturnShortageId implements Serializable {
  @Column(name = "return_id", nullable = false)
  private UUID returnId;

  @Column(name = "line_id", nullable = false)
  private UUID lineId;

  protected LogisticsReturnShortageId() {}

  public LogisticsReturnShortageId(UUID returnId, UUID lineId) {
    if (returnId == null || lineId == null) {
      throw new IllegalArgumentException("Logistics return shortage identity is incomplete");
    }
    this.returnId = returnId;
    this.lineId = lineId;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof LogisticsReturnShortageId value)) {
      return false;
    }
    return Objects.equals(returnId, value.returnId) && Objects.equals(lineId, value.lineId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(returnId, lineId);
  }
}
