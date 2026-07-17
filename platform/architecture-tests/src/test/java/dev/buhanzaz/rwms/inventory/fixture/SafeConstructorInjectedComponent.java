package dev.buhanzaz.rwms.inventory.fixture;

public final class SafeConstructorInjectedComponent {
  private final InventoryReader inventoryReader;

  public SafeConstructorInjectedComponent(InventoryReader inventoryReader) {
    this.inventoryReader = inventoryReader;
  }

  public InventoryReader inventoryReader() {
    return inventoryReader;
  }

  public interface InventoryReader {}
}
