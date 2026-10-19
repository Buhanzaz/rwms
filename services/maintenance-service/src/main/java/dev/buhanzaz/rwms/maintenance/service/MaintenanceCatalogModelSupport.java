package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.CatalogLink;
import dev.buhanzaz.rwms.maintenance.domain.CatalogNode;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersion;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersionState;
import dev.buhanzaz.rwms.maintenance.mapper.CatalogFurnitureReferenceMapper;
import dev.buhanzaz.rwms.maintenance.mapper.CatalogNodeResponseMapper;
import dev.buhanzaz.rwms.maintenance.repository.CatalogVersionRepository;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Loads and maps catalog aggregates for maintenance workflows without mutating catalog state. */
@Service
final class MaintenanceCatalogModelSupport {
  private final CatalogVersionRepository catalogVersions;
  private final MaintenanceReconciliationStore reconciliations;
  private final CatalogFurnitureReferenceMapper catalogFurnitureMapper;
  private final CatalogNodeResponseMapper catalogNodeResponseMapper;
  private final MaintenanceCommandSupport commandSupport;

  MaintenanceCatalogModelSupport(
      CatalogVersionRepository catalogVersions,
      MaintenanceReconciliationStore reconciliations,
      CatalogFurnitureReferenceMapper catalogFurnitureMapper,
      CatalogNodeResponseMapper catalogNodeResponseMapper,
      MaintenanceCommandSupport commandSupport) {
    this.catalogVersions = catalogVersions;
    this.reconciliations = reconciliations;
    this.catalogFurnitureMapper = catalogFurnitureMapper;
    this.catalogNodeResponseMapper = catalogNodeResponseMapper;
    this.commandSupport = commandSupport;
  }

  protected CatalogVersion requireCatalog(UUID id) {
    return catalogVersions.findById(id).orElseThrow(() -> new MaintenanceNotFoundException("Catalog version not found"));
  }

  protected CatalogVersion requireCatalog(UUID id, UUID warehouseId) {
    Objects.requireNonNull(warehouseId, "Catalog authorization warehouse is required");
    return requireCatalog(id);
  }

  protected CatalogVersion requireActiveCatalog(UUID warehouseId) {
    Objects.requireNonNull(warehouseId, "Catalog authorization warehouse is required");
    return catalogVersions
        .findFirstByStateOrderByActivatedAtDescCreatedAtDesc(CatalogVersionState.ACTIVE)
        .orElseThrow(
            () ->
                new MaintenanceValidationException(
                    "MAINTENANCE_VALIDATION_FAILED",
                    "Global maintenance catalog has no active version"));
  }

  protected CatalogVersionResponse catalogResponse(CatalogVersion value) {
    Map<String, Object> validation = commandSupport.jsonMap(value.getValidationReport());
    CatalogRoutingSyncSnapshot routingSync = reconciliations.catalogRoutingTruth(value.getId())
        .map(truth -> new CatalogRoutingSyncSnapshot(
            DeliveryState.valueOf(truth.state()),
            truth.registrationsRequired(),
            truth.registrationsConfirmed(),
            truth.cleanupRequired(),
            truth.cleanupConfirmed(),
            truth.attempts(),
            truth.updatedAt()))
        .orElse(null);
    return new CatalogVersionResponse(
        value.getId(), value.getWarehouseId(), value.getVersion(), value.getState(),
        value.getSourceSha256(), new CatalogCounts(value.getNodeCount(), value.getLinkCount()),
        new CatalogValidationReport(
            commandSupport.booleanValue(validation, "valid"), commandSupport.intValue(validation, "errorCount"),
            commandSupport.intValue(validation, "warningCount"), commandSupport.stringValue(validation, "reportSha256")),
        value.getCreatedAt(), value.getActivatedAt(), routingSync);
  }

  protected CatalogNodeResponse catalogNodeResponse(CatalogNode value) {
    return catalogNodeResponseMapper.toResponse(
        value,
        CatalogNodeType.valueOf(value.getNodeType()),
        value.getFurnitureEquipmentId() == null
            ? null
            : catalogFurnitureMapper.toReference(value),
        commandSupport.money(value.getPriceMinor()),
        value.getRoutingQueueId() == null
            ? null
            : new RoutingSnapshot(
                value.getRoutingQueueId(), value.getRoutingQueueName(), value.getRoutingQueueType()),
        value.getCharacteristicId() == null
            ? null
            : new CabinCharacteristicReference(
                value.getCharacteristicId(), value.getCharacteristicName()));
  }

  protected CatalogLinkResponse catalogLinkResponse(CatalogLink value) {
    return new CatalogLinkResponse(
        value.getId(), value.getCatalogVersionId(), value.getSourceNodeId(), value.getTargetNodeId(),
        CatalogLinkType.valueOf(value.getLinkType()),
        value.getSourceAnchor() == null
            ? null
            : CatalogLinkAnchor.valueOf(value.getSourceAnchor()),
        value.getTargetAnchor() == null
            ? null
            : CatalogLinkAnchor.valueOf(value.getTargetAnchor()),
        value.getSortOrder());
  }
}
