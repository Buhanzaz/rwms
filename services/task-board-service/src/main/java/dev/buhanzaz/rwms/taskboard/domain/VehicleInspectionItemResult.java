package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One ordered inspection-template snapshot and independently resumable driver result. */
@Entity
@Table(
    name = "vehicle_inspection_item_result",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_vehicle_inspection_item_code",
            columnNames = {"inspection_id", "template_item_code"}),
    indexes =
        @Index(name = "idx_vehicle_inspection_item_shift", columnList = "shift_id,sort_order"))
public class VehicleInspectionItemResult extends AbstractVersionedEntity {
  @Column(name = "inspection_id", nullable = false)
  private UUID inspectionId;

  @Column(name = "shift_id", nullable = false)
  private UUID shiftId;

  @Column(name = "template_item_code", nullable = false, length = 96)
  private String templateItemCode;

  @Column(name = "section_name", nullable = false, length = 128)
  private String section;

  @Column(name = "item_label", nullable = false, length = 256)
  private String label;

  @Column(name = "required", nullable = false)
  private boolean required;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Enumerated(EnumType.STRING)
  @Column(name = "result_state", nullable = false, length = 24)
  private InspectionItemState state = InspectionItemState.NOT_CHECKED;

  @Column(name = "defect_id")
  private UUID defectId;

  @Column(name = "checked_at")
  private OffsetDateTime checkedAt;

  /** Copies one owner-configured template item into this historical inspection. */
  public void initialize(
      UUID inspectionId,
      UUID shiftId,
      String code,
      String section,
      String label,
      boolean required,
      int sortOrder) {
    this.inspectionId = inspectionId;
    this.shiftId = shiftId;
    templateItemCode = code;
    this.section = section;
    this.label = label;
    this.required = required;
    this.sortOrder = sortOrder;
  }

  /** Records an explicit OK or DEFECT result and its stable defect reference. */
  public void answer(InspectionItemState result, UUID defectId, OffsetDateTime now) {
    if (result == null || result == InspectionItemState.NOT_CHECKED)
      throw new IllegalArgumentException("Inspection result must be OK or DEFECT");
    state = result;
    this.defectId = defectId;
    checkedAt = now;
  }

  public UUID getInspectionId() {
    return inspectionId;
  }

  public UUID getShiftId() {
    return shiftId;
  }

  public String getTemplateItemCode() {
    return templateItemCode;
  }

  public String getSection() {
    return section;
  }

  public String getLabel() {
    return label;
  }

  public boolean isRequired() {
    return required;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public InspectionItemState getState() {
    return state;
  }

  public UUID getDefectId() {
    return defectId;
  }

  public OffsetDateTime getCheckedAt() {
    return checkedAt;
  }
}
