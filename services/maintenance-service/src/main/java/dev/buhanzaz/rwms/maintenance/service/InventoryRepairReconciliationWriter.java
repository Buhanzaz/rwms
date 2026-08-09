package dev.buhanzaz.rwms.maintenance.service;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes durable maintenance reconciliation state without taking ownership from its source service. */
@Service
public class InventoryRepairReconciliationWriter {
  private static final String DEPENDENCY = "ASSET";
  private static final String OPERATION = "QUEUE_REPAIR";

  private final MaintenanceReconciliationStore reconciliations;

  public InventoryRepairReconciliationWriter(MaintenanceReconciliationStore reconciliations) {
    this.reconciliations = reconciliations;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueue(UUID repairId, UUID idempotencyKey) {
    reconciliations.enqueue(
        repairId,
        DEPENDENCY,
        OPERATION,
        idempotencyKey,
        Map.of("repairId", repairId.toString(), "linkedReturn", false));
  }
}
