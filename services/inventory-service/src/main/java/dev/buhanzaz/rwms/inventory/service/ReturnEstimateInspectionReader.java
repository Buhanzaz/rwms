package dev.buhanzaz.rwms.inventory.service;

import dev.buhanzaz.rwms.inventory.api.ReturnEstimateInspectionResponse;
import dev.buhanzaz.rwms.inventory.api.ReturnEstimateInspectionResponse.State;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventoryReturnInspectionImport;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway.CompletedReturnEstimateProof;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryReturnInspectionImportRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Resolves receipt-backed return inspection status without holding a transaction across remote reads. */
@Service
@RequiredArgsConstructor
public class ReturnEstimateInspectionReader {
  private final InventoryReturnInspectionImportRepository imports;
  private final InventoryFindingRepository findings;
  private final InventorySessionRepository sessions;
  private final InventoryDependencyGateway dependencies;

  public ReturnEstimateInspectionResponse get(UUID estimateId, UUID warehouseId) {
    Optional<InventoryReturnInspectionImport> imported =
        imports.findByEstimateIdAndWarehouseId(estimateId, warehouseId);
    if (imported.isPresent()) {
      return confirmed(estimateId, warehouseId, imported.orElseThrow());
    }

    Optional<CompletedReturnEstimateProof> proof =
        dependencies.completedReturnEstimate(estimateId);
    if (proof.isEmpty()) {
      return empty(State.NOT_REQUIRED);
    }
    CompletedReturnEstimateProof completed = proof.orElseThrow();
    if (!estimateId.equals(completed.estimateId()) || completed.completedAt() == null) {
      throw InventoryException.dependency("Maintenance returned malformed return-estimate proof");
    }
    if (!warehouseId.equals(completed.warehouseId())) {
      throw InventoryException.notFound("Return estimate not found in the requested warehouse");
    }

    Optional<InventorySession> active =
        sessions.findByWarehouseIdAndLifecycle(warehouseId, SessionLifecycle.ACTIVE);
    InventorySession activeSession = active.orElse(null);
    if (activeSession != null
        && activeSession.getStartedAt() != null
        && !activeSession.getStartedAt().isAfter(completed.completedAt())) {
      return empty(State.PENDING);
    }
    Optional<InventoryReturnInspectionImport> importedAfterSessionLookup =
        imports.findByEstimateIdAndWarehouseId(estimateId, warehouseId);
    if (importedAfterSessionLookup.isPresent()) {
      return confirmed(estimateId, warehouseId, importedAfterSessionLookup.orElseThrow());
    }
    return empty(State.NOT_REQUIRED);
  }

  private ReturnEstimateInspectionResponse confirmed(
      UUID estimateId, UUID warehouseId, InventoryReturnInspectionImport receipt) {
    if (!estimateId.equals(receipt.getEstimateId())
        || !warehouseId.equals(receipt.getWarehouseId())
        || receipt.getInventoryId() == null
        || receipt.getFindingId() == null) {
      throw InventoryException.conflict("Return inspection receipt is inconsistent");
    }
    InventoryFinding finding =
        findings
            .findByIdAndInventoryId(receipt.getFindingId(), receipt.getInventoryId())
            .orElseThrow(
                () -> InventoryException.conflict("Return inspection receipt finding is missing"));
    String cabinNumber = finding.getDisplayCanonicalNumber();
    if (cabinNumber == null || cabinNumber.isBlank()) {
      throw InventoryException.conflict("Return inspection receipt cabin number is missing");
    }
    return new ReturnEstimateInspectionResponse(
        State.CONFIRMED, receipt.getInventoryId(), receipt.getFindingId(), cabinNumber);
  }

  private static ReturnEstimateInspectionResponse empty(State state) {
    return new ReturnEstimateInspectionResponse(state, null, null, null);
  }
}
