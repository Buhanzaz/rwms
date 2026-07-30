package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.platform.contracts.FieldViolation;
import java.util.List;

public class MaintenanceCatalogValidationException extends RuntimeException {
  private final List<FieldViolation> violations;

  public MaintenanceCatalogValidationException(List<FieldViolation> violations) {
    super("Catalog validation failed");
    this.violations = List.copyOf(violations);
  }

  public List<FieldViolation> violations() {
    return violations;
  }
}
