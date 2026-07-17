package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.platform.contracts.FieldViolation;
import java.util.List;

public class MaintenanceCatalogImportValidationException extends RuntimeException {
  private final List<FieldViolation> violations;

  public MaintenanceCatalogImportValidationException(List<FieldViolation> violations) {
    super("Reviewed legacy catalog validation failed");
    this.violations = List.copyOf(violations);
  }

  public List<FieldViolation> violations() {
    return violations;
  }
}
