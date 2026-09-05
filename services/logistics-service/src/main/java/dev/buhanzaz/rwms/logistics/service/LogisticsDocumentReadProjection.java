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
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
    return view(document, null, lines(document.getId()));
  }

  /** Materializes a document and an optional server-created reverse transfer association. */
  LogisticsDocumentView view(LogisticsDocument document, UUID linkedReturnTransferId) {
    return view(document, linkedReturnTransferId, lines(document.getId()));
  }

  private LogisticsDocumentView view(
      LogisticsDocument document,
      UUID linkedReturnTransferId,
      List<LogisticsDocumentLine> documentLines) {
    LogisticsDocumentSummary summary = responseMapper.toSummary(document);
    return new LogisticsDocumentView(
        summary.id(),
        summary.version(),
        summary.documentType(),
        summary.customerDeliveryPurpose(),
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
        responseMapper.toLineViews(documentLines),
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

  /**
   * Materializes one deterministic document page and resolves all of its lines with one bounded
   * batch query rather than issuing one line query per document.
   */
  LogisticsDocumentPage page(
      LogisticsDocumentType type, UUID warehouseId, int pageNumber, int pageSize) {
    if (warehouseId == null) throw new IllegalArgumentException("warehouseId is required");
    PageRequest pageRequest = pageRequest(pageNumber, pageSize);
    Page<LogisticsDocument> documents =
        documentRepository.findAllByDocumentTypeAndWarehouseIdOrderByCreatedAtDescIdDesc(
            type, warehouseId, pageRequest);
    return materialize(documents);
  }

  /** Reads an exact cabin's history within the already-authorized document warehouse. */
  LogisticsDocumentPage cabinHistoryPage(
      LogisticsDocumentType type,
      UUID warehouseId,
      UUID assetId,
      LocalDate scheduledDate,
      int pageNumber,
      int pageSize) {
    if (warehouseId == null || assetId == null) {
      throw new IllegalArgumentException("warehouseId and assetId are required");
    }
    if (type != LogisticsDocumentType.RETURN && type != LogisticsDocumentType.SHIPMENT) {
      throw new IllegalArgumentException("Cabin history supports returns and shipments");
    }
    return materialize(
        documentRepository.findCabinHistoryPage(
            type,
            warehouseId,
            assetId,
            scheduledDate == null,
            scheduledDate,
            pageRequest(pageNumber, pageSize)));
  }

  /** Reads only the selected warehouse-local day; transfers are visible in both directions. */
  LogisticsDocumentPage page(
      LogisticsDocumentType type,
      UUID warehouseId,
      LocalDate scheduledDate,
      int pageNumber,
      int pageSize) {
    if (warehouseId == null) throw new IllegalArgumentException("warehouseId is required");
    if (scheduledDate == null) throw new IllegalArgumentException("scheduledDate is required");
    PageRequest pageRequest = pageRequest(pageNumber, pageSize);
    Page<LogisticsDocument> documents =
        type == LogisticsDocumentType.TRANSFER
            ? documentRepository.findTransferPageForWarehouseAndScheduledDate(
                type, warehouseId, scheduledDate, pageRequest)
            : documentRepository
                .findAllByDocumentTypeAndWarehouseIdAndScheduledDateOrderByCreatedAtDescIdDesc(
                    type, warehouseId, scheduledDate, pageRequest);
    return materialize(documents);
  }

  private LogisticsDocumentPage materialize(Page<LogisticsDocument> documents) {
    List<UUID> documentIds = documents.stream().map(LogisticsDocument::getId).toList();
    Map<UUID, List<LogisticsDocumentLine>> linesByDocument =
        documentIds.isEmpty()
            ? Map.of()
            : lineRepository.findAllByDocumentIdIn(documentIds).stream()
                .collect(
                    Collectors.groupingBy(
                        line -> line.getDocument().getId(),
                        LinkedHashMap::new,
                        Collectors.toList()));
    List<LogisticsDocumentView> content =
        documents.stream()
            .map(
                document ->
                    view(
                        document,
                        null,
                        linesByDocument.getOrDefault(document.getId(), List.of())))
            .toList();
    return new LogisticsDocumentPage(
        content,
        documents.getNumber(),
        documents.getSize(),
        documents.getTotalElements(),
        documents.getTotalPages(),
        documents.hasNext());
  }

  private static PageRequest pageRequest(int pageNumber, int pageSize) {
    if (pageNumber < 0 || pageSize < 1 || pageSize > 100) {
      throw new IllegalArgumentException("Logistics document page is invalid");
    }
    return PageRequest.of(pageNumber, pageSize);
  }

  /** Preserves the internal list contract as the same bounded first page used by public reads. */
  List<LogisticsDocumentView> list(LogisticsDocumentType type, UUID warehouseId) {
    return page(type, warehouseId, 0, 50).content();
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
