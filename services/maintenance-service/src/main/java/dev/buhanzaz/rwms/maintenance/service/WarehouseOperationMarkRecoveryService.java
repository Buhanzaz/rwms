package dev.buhanzaz.rwms.maintenance.service;

import java.util.UUID;
import org.springframework.stereotype.Service;

/** Administrator-reviewed retry boundary for a quarantined warehouse operation mark. */
@Service
public class WarehouseOperationMarkRecoveryService {
  private final WarehouseOperationMarkStore marks;

  public WarehouseOperationMarkRecoveryService(WarehouseOperationMarkStore marks) {
    this.marks = marks;
  }

  public WarehouseOperationMarkStore.RecoveryResult recover(
      UUID warehouseId,
      UUID operationId,
      long expectedRecoveryVersion,
      UUID reviewSubjectId,
      String reason) {
    return marks.recoverQuarantined(
        warehouseId, operationId, expectedRecoveryVersion, reviewSubjectId, reason);
  }
}
