package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.LogisticsDocumentSummary;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.LogisticsDocumentView;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferPlanView;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.mapper.LogisticsDocumentResponseMapper;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Materializes logistics-document read views and validates document/line identity lookups. It is
 * read-only infrastructure; document state machines retain their own mutation dependencies.
 */
@Service
@RequiredArgsConstructor
class LogisticsDocumentReadProjection {
  private final LogisticsDocumentRepository documentRepository;
  private final LogisticsDocumentLineRepository lineRepository;
  private final LogisticsDocumentResponseMapper responseMapper;
  private final TransferPlanService transferPlanning;

  LogisticsDocumentView view(LogisticsDocument document) {
    return view(document, null);
  }

  /** Materializes a document and an optional server-created reverse transfer association. */
  LogisticsDocumentView view(LogisticsDocument document, UUID linkedReturnTransferId) {
    LogisticsDocumentSummary summary = responseMapper.toSummary(document);
    return new LogisticsDocumentView(
        summary.id(),
        summary.version(),
        summary.documentType(),
        summary.state(),
        summary.warehouseId(),
        summary.destinationWarehouseId(),
        linkedReturnTransferId,
        summary.partySnapshot(),
        summary.driverSnapshot(),
        summary.driverWorkerId(),
        summary.clientId(),
        summary.historicalRentalImport(),
        summary.equipmentMovementTaskId(),
        summary.scheduledDate(),
        summary.rentalOrderId(),
        summary.rentalShipmentId(),
        summary.inventorySourceId(),
        summary.inventorySourceFindingId(),
        summary.inventorySourceDispositionKind(),
        responseMapper.toLineViews(
            lineRepository.findAllByDocument_IdOrderByLineNumber(summary.id())),
        summary.createdAt(),
        summary.updatedAt());
  }

  LogisticsDocument document(UUID id, LogisticsDocumentType type) {
    return documentRepository
        .findByIdAndDocumentType(id, type)
        .orElseThrow(LogisticsNotFoundException::new);
  }

  List<LogisticsDocumentLine> linesRequired(UUID documentId) {
    List<LogisticsDocumentLine> lines =
        lineRepository.findAllByDocument_IdOrderByLineNumber(documentId);
    if (lines.isEmpty()) throw new IllegalStateException("Logistics document has no lines");
    return lines;
  }

  List<LogisticsDocumentLine> lines(UUID documentId) {
    return lineRepository.findAllByDocument_IdOrderByLineNumber(documentId);
  }

  /** Returns planned transfer requirements or a compatible view for a pre-planning transfer. */
  TransferPlanView transferPlan(UUID documentId) {
    LogisticsDocument document = document(documentId, LogisticsDocumentType.TRANSFER);
    List<LogisticsDocumentLine> lines = lines(documentId);
    return transferPlanning.view(document, lines.size());
  }

  LogisticsDocumentLine transferLine(LogisticsDocument document, UUID lineId) {
    LogisticsDocumentLine line =
        lineRepository.findById(lineId).orElseThrow(LogisticsNotFoundException::new);
    if (!document.getId().equals(line.getDocument().getId())) {
      throw new LogisticsNotFoundException();
    }
    return line;
  }

  List<LogisticsDocumentView> list(LogisticsDocumentType type, UUID warehouseId) {
    if (warehouseId == null) throw new IllegalArgumentException("warehouseId is required");
    return documentRepository
        .findAllByDocumentTypeAndWarehouseIdOrderByCreatedAtDescIdDesc(type, warehouseId)
        .stream()
        .map(this::view)
        .toList();
  }

  boolean isRentalShipmentShipped(UUID shipmentId) {
    if (shipmentId == null) return false;
    return documentRepository
        .findByIdAndDocumentType(shipmentId, LogisticsDocumentType.SHIPMENT)
        .map(document -> document.getState() == LogisticsDocumentState.SHIPPED)
        .orElse(false);
  }

  boolean isRentalOrderUnitAssignedToShipment(UUID orderId, UUID unitId) {
    if (orderId == null || unitId == null) return false;
    return !lineRepository.findAssignedRentalShipmentAssetIds(orderId, List.of(unitId)).isEmpty();
  }
}
