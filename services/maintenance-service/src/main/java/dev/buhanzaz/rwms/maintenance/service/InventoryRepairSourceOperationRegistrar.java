package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.domain.InventoryRepairSourceOperation;
import dev.buhanzaz.rwms.maintenance.domain.InventoryRepairSourceOperationId;
import dev.buhanzaz.rwms.maintenance.repository.InventoryRepairSourceOperationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Registers an immutable source key before the caller takes its pessimistic JPA lock. */
@Service
public class InventoryRepairSourceOperationRegistrar {
  private final InventoryRepairSourceOperationRepository operations;

  public InventoryRepairSourceOperationRegistrar(
      InventoryRepairSourceOperationRepository operations) {
    this.operations = operations;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void register(InventoryRepairSourceOperationId id, String requestSha256) {
    if (!operations.existsById(id)) {
      operations.saveAndFlush(InventoryRepairSourceOperation.register(id, requestSha256));
    }
  }
}
