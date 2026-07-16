package dev.buhanzaz.rwms.warehouse.eventing;

public enum WarehouseEventType {
  CREATED("warehouse.warehouse.created.v1"),
  CHANGED("warehouse.warehouse.changed.v1"),
  DEACTIVATED("warehouse.warehouse.deactivated.v1");

  private final String value;

  WarehouseEventType(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  public static WarehouseEventType require(String value) {
    for (WarehouseEventType type : values()) if (type.value.equals(value)) return type;
    throw new IllegalArgumentException("Unsupported warehouse event type");
  }
}
