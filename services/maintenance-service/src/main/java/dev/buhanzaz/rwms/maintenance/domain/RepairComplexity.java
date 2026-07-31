package dev.buhanzaz.rwms.maintenance.domain;

/** Calculated repair complexity; it is never entered by an operator. */
public enum RepairComplexity {
  LIGHT("Лёгкий ремонт"),
  MEDIUM("Средний ремонт"),
  COMPLEX("Тяжёлый ремонт"),
  CAPITAL("Капитальный ремонт");

  private final String displayName;

  RepairComplexity(String displayName) {
    this.displayName = displayName;
  }

  public String displayName() {
    return displayName;
  }
}
