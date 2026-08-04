package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSourceId;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSourceOperation;
import dev.buhanzaz.rwms.maintenance.repository.InventoryPublicationSourceOperationRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryPublicationSourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Registers an immutable completed-inventory key before the caller takes its lock. */
@Service
public class InventoryPublicationSourceOperationRegistrar {
  private final InventoryPublicationSourceOperationRepository operations;
  private final InventoryPublicationSourceRepository sources;

  public InventoryPublicationSourceOperationRegistrar(
      InventoryPublicationSourceOperationRepository operations,
      InventoryPublicationSourceRepository sources) {
    this.operations = operations;
    this.sources = sources;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void register(InventoryPublicationSourceId id, String requestSha256) {
    if (!operations.existsById(id)) {
      operations.saveAndFlush(InventoryPublicationSourceOperation.register(id, requestSha256));
    }
  }

  /**
   * Releases a key only after its owning publication transaction has rolled back.  A successful
   * publication leaves an immutable source row behind, so it can never be removed here.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void discardIfUnpublished(InventoryPublicationSourceId id, String requestSha256) {
    operations.findByIdForUpdate(id)
        .filter(operation -> requestSha256.equals(operation.getRequestSha256()))
        .ifPresent(operation -> {
          if (!sources.existsById(id)) {
            operations.delete(operation);
          }
        });
  }
}
