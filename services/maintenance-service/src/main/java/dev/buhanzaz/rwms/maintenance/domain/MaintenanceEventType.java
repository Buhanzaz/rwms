package dev.buhanzaz.rwms.maintenance.domain;

public enum MaintenanceEventType {
  CATALOG_IMPORTED("maintenance.catalog-version.imported.v1"),
  CATALOG_CHANGED("maintenance.catalog-version.changed.v1"),
  CATALOG_ACTIVATED("maintenance.catalog-version.activated.v1"),
  CATALOG_SUPERSEDED("maintenance.catalog-version.superseded.v1"),
  ESTIMATE_CREATED("maintenance.estimate.created.v1"),
  ESTIMATE_DRAFT_CHANGED("maintenance.estimate.draft-changed.v1"),
  ESTIMATE_COMPLETED("maintenance.estimate.completed.v1"),
  ESTIMATE_AMENDED("maintenance.estimate.amended.v1"),
  REPAIR_CREATED("maintenance.repair.created.v1"),
  REPAIR_PLAN_CHANGED("maintenance.repair.plan-changed.v1"),
  REPAIR_QUEUED("maintenance.repair.queued.v1"),
  REPAIR_STAGE_COMPLETED("maintenance.repair.stage-completed.v1"),
  REPAIR_PENDING_ACCEPTANCE("maintenance.repair.pending-acceptance.v1"),
  REPAIR_REWORK_CREATED("maintenance.repair.rework-created.v1"),
  REPAIR_ACCEPTED("maintenance.repair.accepted.v1"),
  REPAIR_WRITTEN_OFF("maintenance.repair.written-off.v1");

  private final String value;

  MaintenanceEventType(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }
}
