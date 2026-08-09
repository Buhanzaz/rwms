package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateCabinCatalogItemRequest;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateEquipmentRequest;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateGeneralCommentRequest;
import static dev.buhanzaz.rwms.asset.api.RentalItemHtmlImportApiModels.*;

import dev.buhanzaz.rwms.asset.domain.CabinCatalogItem;
import dev.buhanzaz.rwms.asset.domain.CabinTypeDimension;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImport;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImportRow;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImportRowAction;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImportState;
import dev.buhanzaz.rwms.asset.integration.media.MediaAssetImportClient;
import dev.buhanzaz.rwms.asset.integration.media.MediaAssetImportJob;
import dev.buhanzaz.rwms.asset.integration.media.MediaAssetImportSource;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient;
import dev.buhanzaz.rwms.asset.repository.CabinCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.CabinTypeDimensionRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemHtmlImportRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemHtmlImportRowRepository;
import dev.buhanzaz.rwms.asset.service.RentalItemHtmlImportPlanService.CatalogSelection;
import dev.buhanzaz.rwms.asset.service.RentalItemHtmlImportPlanService.CommitCatalogs;
import dev.buhanzaz.rwms.asset.service.RentalItemHtmlImportPlanService.MappingKey;
import dev.buhanzaz.rwms.asset.service.RentalItemHtmlImportPlanService.PlanEvaluation;
import dev.buhanzaz.rwms.asset.service.RentalItemHtmlImportPlanService.ResolvedRow;
import dev.buhanzaz.rwms.asset.service.RentalItemHtmlParser.ParsedRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns the durable HTML-import commit saga from the locked commit intent through cabin and initial
 * furniture materialization.
 *
 * <p>It deliberately opens and closes local transactions around private warehouse/media calls:
 * durable intent is saved first, remote preflight happens outside the database transaction, and
 * local materialization is fenced again under the aggregate lock. A later media poll may invoke
 * {@link #recoverIfCommitting(UUID)} to replay the same durable intent after a lost remote reply.
 */
@Service
public class RentalItemHtmlImportCommitService {
  private final RentalItemHtmlImportRepository imports;
  private final RentalItemHtmlImportRowRepository rows;
  private final CabinCatalogItemRepository catalog;
  private final CabinTypeDimensionRepository typeDimensions;
  private final WarehouseRegistryClient warehouses;
  private final CabinCompositionService composition;
  private final AssetService assets;
  private final AssetIdempotencyStore idempotency;
  private final MediaAssetImportClient mediaImports;
  private final RentalItemHtmlImportPlanService plans;
  private final RentalItemHtmlImportProjectionService projections;
  private final RentalItemHtmlImportCodec codec;
  private final TransactionTemplate transactions;

  public RentalItemHtmlImportCommitService(
      RentalItemHtmlImportRepository imports,
      RentalItemHtmlImportRowRepository rows,
      CabinCatalogItemRepository catalog,
      CabinTypeDimensionRepository typeDimensions,
      WarehouseRegistryClient warehouses,
      CabinCompositionService composition,
      AssetService assets,
      AssetIdempotencyStore idempotency,
      MediaAssetImportClient mediaImports,
      RentalItemHtmlImportPlanService plans,
      RentalItemHtmlImportProjectionService projections,
      RentalItemHtmlImportCodec codec,
      PlatformTransactionManager transactionManager) {
    this.imports = imports;
    this.rows = rows;
    this.catalog = catalog;
    this.typeDimensions = typeDimensions;
    this.warehouses = warehouses;
    this.composition = composition;
    this.assets = assets;
    this.idempotency = idempotency;
    this.mediaImports = mediaImports;
    this.plans = plans;
    this.projections = projections;
    this.codec = codec;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  HtmlImportDetailResponse commit(
      UUID actorSubjectId, UUID idempotencyKey, UUID id, CommitHtmlImportRequest request) {
    if (actorSubjectId == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("HTML import commit identity is required");
    }
    String operation = "rental-item-html-import.commit." + id;
    String requestHash = codec.hash(Map.of("importId", id, "expectedVersion", request.expectedVersion()));
    CommitOperation command =
        new CommitOperation(actorSubjectId, idempotencyKey, operation, requestHash);
    CommitPreparation prepared =
        inTransaction(() -> beginCommit(id, request.expectedVersion(), command));
    if (prepared.replay() != null) return prepared.replay();
    warehouses.requireIncoming(prepared.intent().warehouseId());
    return continueCommitting(prepared.intent(), command);
  }

  /**
   * Replays a persisted COMMITTING intent without a browser-held command key.
   *
   * @return whether this call owned a durable commit-recovery attempt
   */
  boolean recoverIfCommitting(UUID id) {
    CommitRecoveryPreparation recovery = inTransaction(() -> loadCommitRecovery(id));
    if (recovery.intent() == null) return false;
    continueCommitting(recovery.intent(), null);
    return true;
  }

  private CommitPreparation beginCommit(UUID id, Long expectedVersion, CommitOperation command) {
    Optional<tools.jackson.databind.JsonNode> replay =
        idempotency.replay(
            command.actorSubjectId(),
            command.operation(),
            command.idempotencyKey(),
            command.requestHash());
    if (replay.isPresent()) {
      return new CommitPreparation(codec.read(replay.get(), HtmlImportDetailResponse.class), null);
    }
    RentalItemHtmlImport value = requireImportForUpdate(id);
    if (value.getState() != RentalItemHtmlImportState.READY) {
      if (value.hasCommitIdentity(
          command.actorSubjectId(), command.idempotencyKey(), command.requestHash())) {
        if (value.getState() == RentalItemHtmlImportState.COMMITTING) {
          List<RentalItemHtmlImportRow> importRows =
              rows.findAllByImportIdOrderBySourcePositionAscIdAsc(value.getId());
          return new CommitPreparation(null, commitIntent(value, importRows));
        }
        HtmlImportDetailResponse response = projections.detail(value);
        idempotency.store(
            command.actorSubjectId(),
            command.operation(),
            command.idempotencyKey(),
            command.requestHash(),
            202,
            response);
        return new CommitPreparation(response, null);
      }
      throw new AssetConflictException("HTML import still has unresolved decisions");
    }
    RentalItemHtmlImportPlanService.assertVersion(value, expectedVersion);
    HtmlImportPlan plan = codec.readPlan(value.getPlanJson());
    List<RentalItemHtmlImportRow> importRows =
        rows.findAllByImportIdOrderBySourcePositionAscIdAsc(id);
    plans.rejectMergeRows(importRows);
    PlanEvaluation evaluation = plans.evaluatePlan(value, importRows, plan);
    if (evaluation.invalidCount() != 0 || evaluation.unresolvedCount() != 0) {
      throw new AssetConflictException("HTML import plan is stale or incomplete");
    }
    value.beginCommit(command.actorSubjectId(), command.idempotencyKey(), command.requestHash());
    imports.saveAndFlush(value);
    return new CommitPreparation(null, commitIntent(value, importRows));
  }

  private HtmlImportDetailResponse continueCommitting(CommitIntent intent, CommitOperation command) {
    MediaAssetImportJob mediaJob = null;
    if (!intent.mediaSources().isEmpty()) {
      mediaJob =
          mediaImports.preflight(
              intent.importId(),
              intent.warehouseId(),
              intent.mediaSources(),
              codec.stableKey("media-preflight", intent.importId()));
    }
    MediaAssetImportJob finalMediaJob = mediaJob;
    return inTransaction(() -> finishCommit(intent, finalMediaJob, command));
  }

  private HtmlImportDetailResponse finishCommit(
      CommitIntent intent, MediaAssetImportJob mediaJob, CommitOperation command) {
    if (command != null) {
      Optional<tools.jackson.databind.JsonNode> replay =
          idempotency.replay(
              command.actorSubjectId(),
              command.operation(),
              command.idempotencyKey(),
              command.requestHash());
      if (replay.isPresent()) {
        return codec.read(replay.get(), HtmlImportDetailResponse.class);
      }
    }
    RentalItemHtmlImport value = requireImportForUpdate(intent.importId());
    if (value.getState() != RentalItemHtmlImportState.COMMITTING) {
      if (command != null) {
        if (!value.hasCommitIdentity(
            command.actorSubjectId(), command.idempotencyKey(), command.requestHash())) {
          throw new AssetConflictException("HTML import commit identity is not current");
        }
        HtmlImportDetailResponse response = projections.detail(value);
        idempotency.store(
            command.actorSubjectId(),
            command.operation(),
            command.idempotencyKey(),
            command.requestHash(),
            202,
            response);
        return response;
      }
      return projections.detail(value);
    }
    if (command != null
        && !value.hasCommitIdentity(
            command.actorSubjectId(), command.idempotencyKey(), command.requestHash())) {
      throw new AssetConflictException("HTML import commit identity is not current");
    }
    List<RentalItemHtmlImportRow> importRows =
        rows.findAllByImportIdOrderBySourcePositionAscIdAsc(value.getId());
    plans.rejectMergeRows(importRows);
    List<MediaAssetImportSource> expectedMediaSources = mediaSources(importRows);
    if (!expectedMediaSources.equals(intent.mediaSources())) {
      throw new AssetConflictException("HTML import media intent changed concurrently");
    }
    if (!expectedMediaSources.isEmpty()) {
      requireMediaJobIdentity(value, mediaJob);
      value.attachMediaJob(mediaJob.jobId());
    }
    for (RentalItemHtmlImportRow row : importRows) {
      if (row.hasPhotoLink()) {
        row.removePrivatePhotoKey(codec.write(codec.withoutPhotoPublicKey(codec.parsed(row))));
      }
    }

    HtmlImportPlan plan = codec.readPlan(value.getPlanJson());
    PlanEvaluation evaluation = plans.evaluatePlan(value, importRows, plan);
    if (evaluation.invalidCount() != 0 || evaluation.unresolvedCount() != 0) {
      throw new AssetConflictException("HTML import plan is stale or incomplete");
    }
    UUID commitActorSubjectId =
        value.getCommitActorSubjectId() == null
            ? value.getActorSubjectId()
            : value.getCommitActorSubjectId();
    CommitCatalogs staged = stageCatalogs(commitActorSubjectId, value, plan);
    Map<String, HtmlImportRowDecision> decisions =
        plan.rows().stream()
            .collect(Collectors.toMap(HtmlImportRowDecision::sourceRowId, java.util.function.Function.identity()));
    for (RentalItemHtmlImportRow row : importRows) {
      if (row.getAction() == RentalItemHtmlImportRowAction.EXCLUDE) continue;
      if (row.getAction() != RentalItemHtmlImportRowAction.CREATE) {
        throw new AssetConflictException("HTML import can only commit newly-created cabins");
      }
      ParsedRow source = codec.parsed(row);
      HtmlImportRowDecision decision = decisions.get(source.sourceRowId());
      if (decision == null) decision = plans.effectiveStoredDecision(row);
      ResolvedRow resolved = plans.resolveRow(source, decision, plan, staged);
      if (!RentalItemHtmlImportPlanService.isHtmlImportManualStatus(resolved.status())) {
        throw new AssetConflictException("HTML import cannot synthesize a workflow status");
      }
      ensureTypeDimension(resolved.rentalTypeId(), resolved.dimensionId());
      RentalItemResponse created = createRentalItem(value, resolved);
      row.bindCreatedRentalItem(created.id());
      rows.flush();
      if (!resolved.equipmentQuantities().isEmpty()) {
        assets.recordHtmlImportEquipmentReceipts(
            value.getId(),
            row.getSourceRowId(),
            commitActorSubjectId,
            created.id(),
            resolved.equipmentQuantities());
      }
    }
    rows.flush();
    value.assetsCommitted(!expectedMediaSources.isEmpty());
    imports.saveAndFlush(value);
    HtmlImportDetailResponse response = projections.detail(value);
    if (command != null) {
      idempotency.store(
          command.actorSubjectId(),
          command.operation(),
          command.idempotencyKey(),
          command.requestHash(),
          202,
          response);
    }
    return response;
  }

  private CommitCatalogs stageCatalogs(
      UUID actorSubjectId, RentalItemHtmlImport value, HtmlImportPlan plan) {
    Map<MappingKey, CatalogSelection> catalogMappings = new LinkedHashMap<>();
    for (HtmlImportCatalogMapping mapping : plan.catalogMappings()) {
      MappingKey key =
          new MappingKey(
              mapping.kind(), RentalItemHtmlImportPlanService.normalizedKey(mapping.sourceValue()));
      switch (mapping.action()) {
        case MAP -> {
          CabinCatalogItem target =
              catalog
                  .findById(mapping.targetId())
                  .orElseThrow(() -> new AssetNotFoundException("Catalog target was not found"));
          catalogMappings.put(key, new CatalogSelection(target.getId(), target.getName()));
        }
        case CREATE -> {
          UUID commandKey =
              codec.stableKey(
                  "catalog",
                  value.getId(),
                  mapping.kind().name(),
                  RentalItemHtmlImportPlanService.normalizedKey(mapping.sourceValue()));
          var created =
              composition
                  .createCatalogItem(
                      actorSubjectId,
                      commandKey,
                      new CreateCabinCatalogItemRequest(mapping.kind(), mapping.stagedName()))
                  .response();
          catalogMappings.put(key, new CatalogSelection(created.id(), created.name()));
        }
        case IGNORE -> {
          // Optional source value intentionally has no target.
        }
      }
    }

    Map<String, UUID> equipmentMappings = new LinkedHashMap<>();
    for (HtmlImportEquipmentMapping mapping : plan.equipmentMappings()) {
      String key = RentalItemHtmlImportPlanService.normalizedKey(mapping.sourceValue());
      switch (mapping.action()) {
        case MAP -> equipmentMappings.put(key, mapping.targetId());
        case CREATE -> {
          UUID commandKey = codec.stableKey("equipment", value.getId(), key);
          var created =
              assets
                  .createEquipment(
                      actorSubjectId,
                      commandKey,
                      new CreateEquipmentRequest(
                          mapping.stagedName(),
                          EquipmentCategory.FURNITURE,
                          "Создано при импорте HTML"))
                  .response();
          equipmentMappings.put(key, created.id());
        }
        case IGNORE -> {
          // Explicitly skipped.
        }
      }
    }
    return new CommitCatalogs(Map.copyOf(catalogMappings), Map.copyOf(equipmentMappings));
  }

  private RentalItemResponse createRentalItem(RentalItemHtmlImport value, ResolvedRow resolved) {
    RentalItemResponse result =
        assets.createRentalItemFromHtmlImport(
            value.getWarehouseId(),
            resolved.number(),
            resolved.status(),
            resolved.rentalTypeId(),
            resolved.dimensionId(),
            resolved.finishingId(),
            resolved.categoryId(),
            resolved.categoryName(),
            resolved.characteristicIds(),
            resolved.linoleum() == null ? false : resolved.linoleum(),
            resolved.passport());
    if (resolved.comment() != null) {
      result =
          assets.updateGeneralComment(
              result.id(), new UpdateGeneralCommentRequest(result.version(), resolved.comment()));
    }
    return result;
  }

  private void ensureTypeDimension(UUID rentalTypeId, UUID dimensionId) {
    if (rentalTypeId == null || dimensionId == null) return;
    if (typeDimensions.existsByCabinTypeIdAndDimensionId(rentalTypeId, dimensionId)) return;
    List<CabinTypeDimension> configured =
        typeDimensions.findAllByCabinTypeIdOrderBySortOrderAscIdAsc(rentalTypeId);
    int nextOrder = configured.stream().mapToInt(CabinTypeDimension::getSortOrder).max().orElse(-1) + 1;
    try {
      typeDimensions.saveAndFlush(CabinTypeDimension.create(rentalTypeId, dimensionId, nextOrder));
    } catch (DataIntegrityViolationException exception) {
      if (!typeDimensions.existsByCabinTypeIdAndDimensionId(rentalTypeId, dimensionId)) {
        throw exception;
      }
    }
  }

  private CommitRecoveryPreparation loadCommitRecovery(UUID id) {
    RentalItemHtmlImport value = requireImport(id);
    if (value.getState() != RentalItemHtmlImportState.COMMITTING) {
      return new CommitRecoveryPreparation(null);
    }
    List<RentalItemHtmlImportRow> importRows =
        rows.findAllByImportIdOrderBySourcePositionAscIdAsc(value.getId());
    return new CommitRecoveryPreparation(commitIntent(value, importRows));
  }

  private CommitIntent commitIntent(
      RentalItemHtmlImport value, List<RentalItemHtmlImportRow> importRows) {
    UUID commitActorSubjectId =
        value.getCommitActorSubjectId() == null
            ? value.getActorSubjectId()
            : value.getCommitActorSubjectId();
    return new CommitIntent(
        value.getId(), value.getWarehouseId(), commitActorSubjectId, mediaSources(importRows));
  }

  private List<MediaAssetImportSource> mediaSources(List<RentalItemHtmlImportRow> importRows) {
    List<MediaAssetImportSource> result = new ArrayList<>();
    for (RentalItemHtmlImportRow row : importRows) {
      if (row.getAction() == RentalItemHtmlImportRowAction.EXCLUDE || !row.hasPhotoLink()) continue;
      String publicKey = codec.parsed(row).photoPublicKey();
      if (publicKey != null) {
        result.add(new MediaAssetImportSource(row.getId(), "https://disk.yandex.ru/d/" + publicKey));
      }
    }
    if (result.size() > MediaAssetImportClient.MAX_SOURCES) {
      throw new IllegalArgumentException("HTML import contains more than 500 selected photo sources");
    }
    return List.copyOf(result);
  }

  static void requireMediaJobIdentity(
      RentalItemHtmlImport value, MediaAssetImportJob job) {
    if (job == null
        || !value.getId().equals(job.assetImportId())
        || !value.getWarehouseId().equals(job.warehouseId())) {
      throw wrongMediaJob();
    }
  }

  static AssetDependencyException wrongMediaJob() {
    return new AssetDependencyException(
        org.springframework.http.HttpStatus.BAD_GATEWAY, "Media import returned a different job");
  }

  private RentalItemHtmlImport requireImport(UUID id) {
    return imports
        .findById(id)
        .orElseThrow(() -> new AssetNotFoundException("HTML import was not found"));
  }

  private RentalItemHtmlImport requireImportForUpdate(UUID id) {
    return imports
        .findByIdForUpdate(id)
        .orElseThrow(() -> new AssetNotFoundException("HTML import was not found"));
  }

  private <T> T inTransaction(Supplier<T> operation) {
    return transactions.execute(status -> operation.get());
  }

  /**
   * Actor, transport key, operation name, and request digest needed to read or store one permanent
   * command receipt.
   */
  private record CommitOperation(
      UUID actorSubjectId, UUID idempotencyKey, String operation, String requestHash) {}

  /**
   * Durable local commit result that carries only the media work allowed to cross the transaction
   * boundary.
   */
  private record CommitIntent(
      UUID importId,
      UUID warehouseId,
      UUID actorSubjectId,
      List<MediaAssetImportSource> mediaSources) {}

  /**
   * Mutually exclusive transaction outcome: a completed idempotent replay or new post-commit media
   * intent.
   */
  private record CommitPreparation(HtmlImportDetailResponse replay, CommitIntent intent) {}

  /** Recovery transaction outcome identifying media activation that must resume after commit. */
  private record CommitRecoveryPreparation(CommitIntent intent) {}
}
