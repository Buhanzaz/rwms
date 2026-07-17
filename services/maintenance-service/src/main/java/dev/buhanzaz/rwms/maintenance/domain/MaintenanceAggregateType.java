package dev.buhanzaz.rwms.maintenance.domain;

public enum MaintenanceAggregateType {
  CATALOG_VERSION("rwms.maintenance.catalog-version.v1"),
  ESTIMATE("rwms.maintenance.estimate.v1"),
  REPAIR("rwms.maintenance.repair.v1");

  private final String topic;

  MaintenanceAggregateType(String topic) {
    this.topic = topic;
  }

  public String topic() {
    return topic;
  }
}
