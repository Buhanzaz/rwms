package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.MediaReferenceInput;
import dev.buhanzaz.rwms.maintenance.domain.LogisticsReturnShortage;
import dev.buhanzaz.rwms.maintenance.repository.LogisticsReturnShortageRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses the estimate-source primary key as the concurrent first-write arbiter. */
@Service
public class LogisticsReturnShortageRegistrar {
  private final LogisticsReturnShortageRepository sources;
  private final MaintenanceApplicationService maintenance;
  private final WarehouseLifecycleOperations warehouseLifecycle;
  private final EstimateCreationWindowPolicy creationWindow;
  private final TransactionTemplate transactions;

  public LogisticsReturnShortageRegistrar(
      LogisticsReturnShortageRepository sources,
      MaintenanceApplicationService maintenance,
      WarehouseLifecycleOperations warehouseLifecycle,
      EstimateCreationWindowPolicy creationWindow,
      PlatformTransactionManager transactionManager) {
    this.sources = sources;
    this.maintenance = maintenance;
    this.warehouseLifecycle = warehouseLifecycle;
    this.creationWindow = creationWindow;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * Performs warehouse admission between two short local transactions. The final transaction
   * deliberately retains the source primary-key insert as the concurrent first-write arbiter.
   */
  public boolean register(
      LogisticsReturnShortage candidate,
      LocalDate dispatchDate,
      List<MediaReferenceInput> mediaReferences) {
    requireNoCallerTransaction();
    Boolean alreadyRegistered =
        transactions.execute(status -> sources.existsById(candidate.getId()));
    if (Boolean.TRUE.equals(alreadyRegistered)) {
      return false;
    }
    warehouseLifecycle.requireIncoming(candidate.getWarehouseId());
    creationWindow.requireTrustedCreationOpen(
        candidate.getWarehouseId(), candidate.getArrivedAt());
    Boolean registered =
        transactions.execute(
            status -> registerAfterAdmission(candidate, dispatchDate, mediaReferences));
    if (registered == null) {
      throw new IllegalStateException("Logistics return estimate-source transaction was empty");
    }
    return registered;
  }

  private boolean registerAfterAdmission(
      LogisticsReturnShortage candidate,
      LocalDate dispatchDate,
      List<MediaReferenceInput> mediaReferences) {
    if (sources.existsById(candidate.getId())) {
      return false;
    }
    candidate.bindEstimate(
        maintenance.createLogisticsReturnEstimate(
            candidate.getWarehouseId(),
            candidate.getRentalItemId(),
            candidate.getRentalItemVersionSnapshot(),
            dispatchDate,
            mediaReferences));
    sources.saveAndFlush(candidate);
    return true;
  }

  private static void requireNoCallerTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Logistics return estimate-source registration cannot run inside a caller transaction");
    }
  }
}
