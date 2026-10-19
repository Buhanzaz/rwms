package dev.buhanzaz.rwms.logistics.maintenance.service;

import dev.buhanzaz.rwms.logistics.maintenance.api.MaintenanceReturnArrivalResponse;
import dev.buhanzaz.rwms.logistics.maintenance.repository.MaintenanceReturnArrivalRepository;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.service.LogisticsNotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns maintenance-facing reads of logistics return-arrival evidence. */
@Service
@Transactional(readOnly = true)
public class MaintenanceReturnArrivalService {
  private final MaintenanceReturnArrivalRepository repository;

  public MaintenanceReturnArrivalService(MaintenanceReturnArrivalRepository repository) {
    this.repository = repository;
  }

  /** Returns the newest physical arrival for one warehouse-scoped rental item. */
  public MaintenanceReturnArrivalResponse latest(UUID warehouseId, UUID rentalItemId) {
    return response(
        repository
            .findLatestRows(warehouseId, rentalItemId, PageRequest.of(0, 1))
            .stream()
            .findFirst()
            .orElseThrow(LogisticsNotFoundException::new));
  }

  /** Returns the arrival used by the automatic estimate source for one exact return document. */
  public Optional<MaintenanceReturnArrivalResponse> forReturn(
      UUID returnDocumentId, UUID warehouseId, UUID rentalItemId) {
    return repository
        .findForReturn(returnDocumentId, warehouseId, rentalItemId)
        .map(MaintenanceReturnArrivalService::response);
  }

  private static MaintenanceReturnArrivalResponse response(LogisticsDocumentLine line) {
    return new MaintenanceReturnArrivalResponse(
        line.getDocument().getWarehouseId(),
        line.getAssetId(),
        line.getDocument().getId(),
        line.getDocument().getReturnArrivedAt());
  }
}
