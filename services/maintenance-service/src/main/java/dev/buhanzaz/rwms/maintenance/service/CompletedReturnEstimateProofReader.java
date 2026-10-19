package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.api.CompletedReturnEstimateProofResponse;
import dev.buhanzaz.rwms.maintenance.domain.EstimateState;
import dev.buhanzaz.rwms.maintenance.domain.LogisticsReturnShortage;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.mapper.CompletedReturnEstimateProofMapper;
import dev.buhanzaz.rwms.maintenance.repository.EstimateLineRepository;
import dev.buhanzaz.rwms.maintenance.repository.LogisticsReturnShortageRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceEstimateRepository;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads completion proof only for estimates owned by an immutable, physically arrived return line. */
@Service
@RequiredArgsConstructor
public class CompletedReturnEstimateProofReader {
  private final LogisticsReturnShortageRepository returnSources;
  private final MaintenanceEstimateRepository estimates;
  private final EstimateLineRepository lines;
  private final CompletedReturnEstimateProofMapper mapper;

  @Transactional(readOnly = true)
  public CompletedReturnEstimateProofResponse get(UUID estimateId) {
    LogisticsReturnShortage source =
        returnSources
            .findByEstimateId(estimateId)
            .filter(candidate -> candidate.getArrivedAt() != null)
            .orElseThrow(
                () -> new MaintenanceNotFoundException("Completed return estimate not found"));
    MaintenanceEstimate estimate =
        estimates
            .findById(estimateId)
            .orElseThrow(
                () -> new MaintenanceNotFoundException("Completed return estimate not found"));

    if (estimate.getState() != EstimateState.COMPLETED) {
      throw new MaintenanceConflictException(
          "INCOMPLETE_ESTIMATE", "Return estimate is not completed");
    }
    requireConsistent(estimateId, estimate, source);
    long lineCount = lines.countByEstimateIdAndEstimateRevision(estimateId, estimate.getRevision());
    if (lineCount < 0 || (lineCount == 0) != (estimate.getRepairId() == null)) {
      throw conflict("Return estimate completion outcome is inconsistent");
    }
    return mapper.toResponse(estimate, source, lineCount);
  }

  private static void requireConsistent(
      UUID requestedEstimateId,
      MaintenanceEstimate estimate,
      LogisticsReturnShortage source) {
    if (estimate.getId() == null
        || source.getId() == null
        || source.getId().getReturnId() == null
        || source.getId().getLineId() == null
        || !requestedEstimateId.equals(estimate.getId())
        || !requestedEstimateId.equals(source.getEstimateId())
        || estimate.getVersion() < 0
        || estimate.getRevision() < 1
        || source.getRentalItemVersionSnapshot() < 0
        || estimate.getWarehouseId() == null
        || estimate.getRentalItemId() == null
        || source.getWarehouseId() == null
        || source.getRentalItemId() == null
        || !Objects.equals(estimate.getWarehouseId(), source.getWarehouseId())
        || !Objects.equals(estimate.getRentalItemId(), source.getRentalItemId())
        || estimate.getCompletedAt() == null
        || estimate.getCompletedAt().isBefore(source.getArrivedAt())) {
      throw conflict("Return estimate proof facts are inconsistent");
    }
  }

  private static MaintenanceConflictException conflict(String detail) {
    return new MaintenanceConflictException("RETURN_ESTIMATE_PROOF_CONFLICT", detail);
  }
}
