package dev.buhanzaz.rwms.warehouse.eventing;

public enum WarehouseAggregateType {
  WAREHOUSE("rwms.warehouse.warehouse.v1");

  private final String topic;

  WarehouseAggregateType(String topic) {
    this.topic = topic;
  }

  public String topic() {
    return topic;
  }

  public static WarehouseAggregateType requireTopic(String topic) {
    if (WAREHOUSE.topic.equals(topic)) return WAREHOUSE;
    throw new IllegalArgumentException("Unsupported warehouse aggregate-family topic");
  }
}
