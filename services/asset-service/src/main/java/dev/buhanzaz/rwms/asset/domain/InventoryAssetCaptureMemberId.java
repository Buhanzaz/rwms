package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class InventoryAssetCaptureMemberId implements Serializable {
  @Column(name = "capture_id", nullable = false)
  private UUID captureId;

  @Column(name = "sequence_no", nullable = false)
  private long sequenceNo;

  protected InventoryAssetCaptureMemberId() {}

  public InventoryAssetCaptureMemberId(UUID captureId, long sequenceNo) {
    if (captureId == null || sequenceNo < 0) {
      throw new IllegalArgumentException("Inventory capture member identity is invalid");
    }
    this.captureId = captureId;
    this.sequenceNo = sequenceNo;
  }

  public UUID getCaptureId() {
    return captureId;
  }

  public long getSequenceNo() {
    return sequenceNo;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof InventoryAssetCaptureMemberId value)) {
      return false;
    }
    return sequenceNo == value.sequenceNo && Objects.equals(captureId, value.captureId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(captureId, sequenceNo);
  }
}
