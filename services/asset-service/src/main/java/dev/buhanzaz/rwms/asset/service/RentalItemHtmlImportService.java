package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateCabinCatalogItemRequest;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateEquipmentRequest;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateGeneralCommentRequest;
import static dev.buhanzaz.rwms.asset.api.RentalItemHtmlImportApiModels.*;

import dev.buhanzaz.rwms.asset.domain.CabinCatalogItem;
import dev.buhanzaz.rwms.asset.domain.CabinCatalogKind;
import dev.buhanzaz.rwms.asset.domain.CabinTypeDimension;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImport;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImportRow;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImportRowAction;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImportState;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.integration.media.MediaAssetImportClient;
import dev.buhanzaz.rwms.asset.integration.media.MediaAssetImportJob;
import dev.buhanzaz.rwms.asset.integration.media.MediaAssetImportSource;
import dev.buhanzaz.rwms.asset.integration.media.MediaAssetImportBinding;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient;
import dev.buhanzaz.rwms.asset.mapper.RentalItemHtmlImportMapper;
import dev.buhanzaz.rwms.asset.repository.CabinCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.CabinTypeDimensionRepository;
import dev.buhanzaz.rwms.asset.repository.EquipmentCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemHtmlImportRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemHtmlImportRowRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import dev.buhanzaz.rwms.asset.service.RentalItemHtmlParser.DiagnosticSeverity;
import dev.buhanzaz.rwms.asset.service.RentalItemHtmlParser.FurnitureLine;
import dev.buhanzaz.rwms.asset.service.RentalItemHtmlParser.ParsedImport;
import dev.buhanzaz.rwms.asset.service.RentalItemHtmlParser.ParsedRow;
import dev.buhanzaz.rwms.asset.service.RentalItemHtmlParser.SourceValue;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Durable import review and commit saga. Raw HTML exists only for the duration
 * of {@link #create}; all later work uses bounded normalized parser output.
 */
@Service
public class RentalItemHtmlImportService {
  private static final String UNSPECIFIED_CATALOG_VALUE = "—";
  private static final double AUTOMATIC_WORD_MATCH_THRESHOLD = 0.60d;
  private static final double AUTOMATIC_WORD_MATCH_MARGIN = 0.10d;

  private final RentalItemHtmlParser parser;
  private final RentalItemHtmlImportRepository imports;
  private final RentalItemHtmlImportRowRepository rows;
  private final RentalItemRepository rentalItems;
  private final CabinCatalogItemRepository catalog;
  private final CabinTypeDimensionRepository typeDimensions;
  private final EquipmentCatalogItemRepository equipment;
  private final WarehouseRegistryClient warehouses;
  private final RentalItemHtmlImportMapper importMapper;
  private final CabinCompositionService composition;
  private final AssetService assets;
  private final AssetIdempotencyStore idempotency;
  private final MediaAssetImportClient mediaImports;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transactions;

  public RentalItemHtmlImportService(
      RentalItemHtmlParser parser,
      RentalItemHtmlImportRepository imports,
      RentalItemHtmlImportRowRepository rows,
      RentalItemRepository rentalItems,
      CabinCatalogItemRepository catalog,
      CabinTypeDimensionRepository typeDimensions,
      EquipmentCatalogItemRepository equipment,
      WarehouseRegistryClient warehouses,
      RentalItemHtmlImportMapper importMapper,
      CabinCompositionService composition,
      AssetService assets,
      AssetIdempotencyStore idempotency,
      MediaAssetImportClient mediaImports,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager) {
    this.parser = parser;
    this.imports = imports;
    this.rows = rows;
    this.rentalItems = rentalItems;
    this.catalog = catalog;
    this.typeDimensions = typeDimensions;
    this.equipment = equipment;
    this.warehouses = warehouses;
    this.importMapper = importMapper;
    this.composition = composition;
    this.assets = assets;
    this.idempotency = idempotency;
    this.mediaImports = mediaImports;
    this.objectMapper = objectMapper;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Transactional
  public HtmlImportDetailResponse create(
      UUID actorSubjectId, UUID idempotencyKey, UUID warehouseId, byte[] html) {
    if (actorSubjectId == null || idempotencyKey == null || warehouseId == null) {
      throw new IllegalArgumentException("HTML import identity is required");
    }
    if (html == null) throw new IllegalArgumentException("HTML source is required");
    String sourceSha256 = AssetChecksum.sha256(html);
    Optional<RentalItemHtmlImport> replay =
        imports.findByActorSubjectIdAndIdempotencyKey(actorSubjectId, idempotencyKey);
    if (replay.isPresent()) {
      RentalItemHtmlImport existing = replay.get();
      if (!existing.getWarehouseId().equals(warehouseId)
          || !existing.getSourceSha256().equals(sourceSha256)) {
        throw new AssetConflictException(
            "Idempotency-Key is already bound to another HTML import");
      }
      return detail(existing);
    }

    warehouses.requireIncoming(warehouseId);
    ParsedImport parsed = parser.parse(html);
    List<CabinCatalogItem> catalogItems = catalog.findAll();
    List<HtmlImportSourceCandidate> candidates =
        sourceCandidates(parsed.rows(), catalogItems, equipment.findAllByOrderByNameAscIdAsc());
    int parserInvalid =
        (int) parsed.rows().stream().filter(ParsedRow::requiresManualReview).count();
    int unresolved =
        parserInvalid
            + (int)
                candidates.stream()
                    .filter(RentalItemHtmlImportService::candidateNeedsDecision)
                    .count();
    int mediaLinks =
        (int) parsed.rows().stream().filter(row -> row.photoPublicKey() != null).count();
    int warnings = parsed.rows().stream().mapToInt(ParsedRow::warningCount).sum();

    Map<String, RentalItem> existingByIdentity =
        rentalItems.findAllByWarehouseIdOrderByNumber(warehouseId).stream()
            .collect(
                Collectors.toMap(
                    RentalItem::getIdentityMatchKey,
                    Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new));
    int existingCabinMatches =
        (int)
            parsed.rows().stream()
                .filter(row -> !row.requiresManualReview())
                .filter(
                    row ->
                        row.identityMatchKey() != null
                            && existingByIdentity.containsKey(row.identityMatchKey()))
                .count();
    RentalItemHtmlImport value =
        imports.saveAndFlush(
            RentalItemHtmlImport.create(
                warehouseId,
                actorSubjectId,
                idempotencyKey,
                sourceSha256,
                parsed.rows().size(),
                parserInvalid,
                unresolved + existingCabinMatches,
                mediaLinks,
                warnings));
    List<RentalItemHtmlImportRow> persistedRows = new ArrayList<>(parsed.rows().size());
    for (ParsedRow parsedRow : parsed.rows()) {
      RentalItem target =
          parsedRow.identityMatchKey() == null
              ? null
              : existingByIdentity.get(parsedRow.identityMatchKey());
      RentalItemHtmlImportRowAction action =
          parsedRow.requiresManualReview()
              ? RentalItemHtmlImportRowAction.REVIEW
              : target == null
                  ? RentalItemHtmlImportRowAction.CREATE
                  : RentalItemHtmlImportRowAction.REVIEW;
      persistedRows.add(
          RentalItemHtmlImportRow.create(
              value.getId(),
              parsedRow.sourceRowId(),
              parsedRow.sourcePosition(),
              parsedRow.sourceNumber(),
              parsedRow.proposedNumber(),
              parsedRow.identityMatchKey(),
              target == null ? null : target.getId(),
              action,
              parsedRow.photoPublicKey() != null,
              write(parsedRow),
              write(
                  parsedRow.diagnostics().stream()
                      .map(RentalItemHtmlParser.ParserDiagnostic::code)
                      .toList())));
    }
    rows.saveAll(persistedRows);
    rows.flush();
    return detail(value);
  }

  @Transactional(readOnly = true)
  public List<HtmlImportSummaryResponse> list(UUID warehouseId) {
    return imports.findAllByWarehouseIdOrderByUpdatedAtDescIdAsc(warehouseId).stream()
        .map(importMapper::toSummary)
        .toList();
  }

  @Transactional(readOnly = true)
  public HtmlImportDetailResponse get(UUID id) {
    return detail(requireImport(id));
  }

  @Transactional(readOnly = true)
  public UUID warehouseId(UUID id) {
    return requireImport(id).getWarehouseId();
  }

  @Transactional(readOnly = true)
  public HtmlImportRowPage rowPage(UUID id, int page, int size) {
    if (page < 0 || size < 1 || size > 200) {
      throw new IllegalArgumentException("Invalid HTML import row page");
    }
    requireImport(id);
    Page<RentalItemHtmlImportRow> result =
        rows.findAllByImportIdOrderBySourcePositionAscIdAsc(id, PageRequest.of(page, size));
    Set<UUID> targetIds =
        result.getContent().stream()
            .map(RentalItemHtmlImportRow::getTargetRentalItemId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
    Map<UUID, RentalItem> targets =
        targetIds.isEmpty()
            ? Map.of()
            : rentalItems.findAllById(targetIds).stream()
                .collect(Collectors.toMap(RentalItem::getId, Function.identity()));
    Set<UUID> catalogIds = new LinkedHashSet<>();
    targets.values().forEach(
        item -> {
          add(catalogIds, item.getRentalTypeId());
          add(catalogIds, item.getDimensionId());
          add(catalogIds, item.getFinishingId());
          add(catalogIds, item.getCategoryId());
        });
    Map<UUID, CabinCatalogItem> catalogById =
        catalogIds.isEmpty()
            ? Map.of()
            : catalog.findAllByIdIn(catalogIds).stream()
                .collect(Collectors.toMap(CabinCatalogItem::getId, Function.identity()));
    List<HtmlImportRowResponse> content =
        result.getContent().stream()
            .map(
                row ->
                    rowResponse(
                        row,
                        row.getTargetRentalItemId() == null
                            ? null
                            : targets.get(row.getTargetRentalItemId()),
                        catalogById))
            .toList();
    return new HtmlImportRowPage(
        content,
        result.getNumber(),
        result.getSize(),
        result.getTotalElements(),
        result.getTotalPages());
  }

  @Transactional
  public HtmlImportDetailResponse updatePlan(UUID id, UpdateHtmlImportPlanRequest request) {
    RentalItemHtmlImport value = requireImportForUpdate(id);
    assertVersion(value, request.expectedVersion());
    if (value.getState() != RentalItemHtmlImportState.REVIEW_REQUIRED
        && value.getState() != RentalItemHtmlImportState.READY) {
      throw new AssetConflictException("HTML import plan can no longer be edited");
    }

    HtmlImportPlan plan = request.plan();
    validateMappingUniqueness(plan);
    List<RentalItemHtmlImportRow> importRows =
        rows.findAllByImportIdOrderBySourcePositionAscIdAsc(id);
    Map<String, RentalItemHtmlImportRow> bySource =
        importRows.stream()
            .collect(Collectors.toMap(RentalItemHtmlImportRow::getSourceRowId, Function.identity()));
    Map<String, HtmlImportRowDecision> decisions =
        plan.rows().stream()
            .collect(
                Collectors.toMap(
                    HtmlImportRowDecision::sourceRowId,
                    Function.identity(),
                    (left, right) -> {
                      throw new IllegalArgumentException(
                          "HTML import row decisions must be unique");
                    },
                    LinkedHashMap::new));
    for (Map.Entry<String, HtmlImportRowDecision> entry : decisions.entrySet()) {
      RentalItemHtmlImportRow row = bySource.get(entry.getKey());
      if (row == null) throw new IllegalArgumentException("HTML import row was not found");
      HtmlImportRowDecision decision = entry.getValue();
      validateRowDecision(value.getWarehouseId(), row, decision);
      row.decide(
          decision.action(),
          decision.proposedNumber() == null
              ? row.getProposedNumber()
              : RentalItem.canonicalNumber(decision.proposedNumber()),
          decision.targetRentalItemId(),
          write(decision));
    }
    rows.flush();

    PlanEvaluation evaluation = evaluatePlan(value, importRows, plan);
    HtmlImportPlan durablePlan =
        new HtmlImportPlan(
            plan.catalogMappings(),
            plan.equipmentMappings(),
            plan.statusMappings(),
            List.of());
    value.replacePlan(
        write(durablePlan),
        evaluation.selectedCount(),
        evaluation.invalidCount(),
        evaluation.unresolvedCount());
    imports.saveAndFlush(value);
    return detail(value);
  }

  @Transactional
  public void cancel(UUID id, long expectedVersion) {
    RentalItemHtmlImport value = requireImportForUpdate(id);
    assertVersion(value, expectedVersion);
    if (value.getState() != RentalItemHtmlImportState.DRAFT
        && value.getState() != RentalItemHtmlImportState.REVIEW_REQUIRED
        && value.getState() != RentalItemHtmlImportState.READY) {
      throw new AssetConflictException(
          "HTML import can no longer be cancelled because processing has started");
    }
    imports.delete(value);
    imports.flush();
  }

  public HtmlImportDetailResponse commit(
      UUID actorSubjectId,
      UUID idempotencyKey,
      UUID id,
      CommitHtmlImportRequest request) {
    if (actorSubjectId == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("HTML import commit identity is required");
    }
    String operation = "rental-item-html-import.commit." + id;
    String requestHash = hash(Map.of("importId", id, "expectedVersion", request.expectedVersion()));
    CommitOperation command =
        new CommitOperation(actorSubjectId, idempotencyKey, operation, requestHash);
    CommitPreparation prepared =
        inTransaction(() -> beginCommit(id, request.expectedVersion(), command));
    if (prepared.replay() != null) return prepared.replay();
    warehouses.requireIncoming(prepared.intent().warehouseId());
    return continueCommitting(prepared.intent(), command);
  }

  public HtmlImportDetailResponse retryMedia(
      UUID actorSubjectId,
      UUID idempotencyKey,
      UUID id,
      RetryHtmlImportMediaRequest request) {
    if (actorSubjectId == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("HTML import media retry identity is required");
    }
    String operation = "rental-item-html-import.retry-media." + id;
    String requestHash =
        hash(Map.of("importId", id, "expectedVersion", request.expectedVersion()));
    CommitOperation command =
        new CommitOperation(actorSubjectId, idempotencyKey, operation, requestHash);
    RetryMediaPreparation prepared =
        inTransaction(() -> prepareRetryMedia(id, request.expectedVersion(), command));
    if (prepared.replay() != null) return prepared.replay();
    if (prepared.synchronize()) {
      synchronizeMedia(id);
      return inTransaction(() -> storeMediaCommandResponse(id, command));
    }
    MediaAssetImportJob job =
        mediaImports.retry(
            prepared.mediaJobId(),
            stableKey("media-retry", id, idempotencyKey.toString()));
    return inTransaction(() -> finishRetryMedia(id, prepared.mediaJobId(), job, command));
  }

  public HtmlImportDetailResponse replaceMedia(
      UUID actorSubjectId,
      UUID idempotencyKey,
      UUID id,
      ReplaceHtmlImportMediaRequest request) {
    if (actorSubjectId == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("HTML import media replacement identity is required");
    }
    String operation = "rental-item-html-import.replace-media." + id;
    String requestHash =
        hash(
            Map.of(
                "importId", id,
                "expectedVersion", request.expectedVersion(),
                "replacements", request.replacements()));
    CommitOperation command =
        new CommitOperation(actorSubjectId, idempotencyKey, operation, requestHash);
    ReplaceMediaPreparation prepared =
        inTransaction(
            () -> prepareReplaceMedia(id, request.expectedVersion(), request.replacements(), command));
    if (prepared.replay() != null) return prepared.replay();
    MediaAssetImportJob job =
        mediaImports.replacePreflightSources(
            prepared.mediaJobId(),
            prepared.replacements(),
            stableKey("media-replace", id, idempotencyKey.toString()));
    return inTransaction(() -> finishReplaceMedia(id, prepared.mediaJobId(), job, command));
  }

  @Transactional
  public HtmlImportDetailResponse skipMedia(
      UUID actorSubjectId,
      UUID idempotencyKey,
      UUID id,
      SkipHtmlImportMediaRequest request) {
    if (actorSubjectId == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("HTML import media skip identity is required");
    }
    String operation = "rental-item-html-import.skip-media." + id;
    String requestHash =
        hash(Map.of("importId", id, "expectedVersion", request.expectedVersion()));
    Optional<tools.jackson.databind.JsonNode> replay =
        idempotency.replay(actorSubjectId, operation, idempotencyKey, requestHash);
    if (replay.isPresent()) return read(replay.get(), HtmlImportDetailResponse.class);

    RentalItemHtmlImport value = requireImportForUpdate(id);
    assertVersion(value, request.expectedVersion());
    value.mediaSkipped();
    imports.saveAndFlush(value);
    HtmlImportDetailResponse response = detail(value);
    idempotency.store(actorSubjectId, operation, idempotencyKey, requestHash, 202, response);
    return response;
  }

  public void synchronizeMedia(UUID id) {
    CommitRecoveryPreparation recovery = inTransaction(() -> loadCommitRecovery(id));
    if (recovery.intent() != null) {
      continueCommitting(recovery.intent(), null);
      return;
    }
    MediaSyncPreparation prepared = inTransaction(() -> prepareMediaSync(id));
    if (prepared.mediaJobId() == null) return;
    MediaAssetImportJob job;
    try {
      job = mediaImports.get(prepared.mediaJobId());
    } catch (AssetNotFoundException ignored) {
      inTransaction(() -> markMediaJobMissing(id, prepared.mediaJobId()));
      return;
    }
    MediaActivationIntent activation =
        inTransaction(() -> applyMediaObservation(id, job));
    if (activation == null) return;
    try {
      MediaAssetImportJob activated =
          mediaImports.activate(
              activation.mediaJobId(),
              activation.bindings(),
              stableKey("media-activate", activation.importId()));
      inTransaction(() -> applyMediaObservation(id, activated));
    } catch (AssetConflictException ignored) {
      // The CABIN owner proof is delivered asynchronously through Kafka.
      // The next bounded poll repeats the stable activation command.
    }
  }

  private CommitPreparation beginCommit(
      UUID id, Long expectedVersion, CommitOperation command) {
    Optional<tools.jackson.databind.JsonNode> replay =
        idempotency.replay(
            command.actorSubjectId(), command.operation(), command.idempotencyKey(), command.requestHash());
    if (replay.isPresent()) {
      return new CommitPreparation(read(replay.get(), HtmlImportDetailResponse.class), null);
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
        HtmlImportDetailResponse response = detail(value);
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
    assertVersion(value, expectedVersion);
    HtmlImportPlan plan = readPlan(value.getPlanJson());
    List<RentalItemHtmlImportRow> importRows =
        rows.findAllByImportIdOrderBySourcePositionAscIdAsc(id);
    rejectMergeRows(importRows);
    PlanEvaluation evaluation = evaluatePlan(value, importRows, plan);
    if (evaluation.invalidCount() != 0 || evaluation.unresolvedCount() != 0) {
      throw new AssetConflictException("HTML import plan is stale or incomplete");
    }
    value.beginCommit(command.actorSubjectId(), command.idempotencyKey(), command.requestHash());
    imports.saveAndFlush(value);
    return new CommitPreparation(null, commitIntent(value, importRows));
  }

  private HtmlImportDetailResponse continueCommitting(
      CommitIntent intent, CommitOperation command) {
    MediaAssetImportJob mediaJob = null;
    if (!intent.mediaSources().isEmpty()) {
      mediaJob =
          mediaImports.preflight(
              intent.importId(),
              intent.warehouseId(),
              intent.mediaSources(),
              stableKey("media-preflight", intent.importId()));
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
        return read(replay.get(), HtmlImportDetailResponse.class);
      }
    }
    RentalItemHtmlImport value = requireImportForUpdate(intent.importId());
    if (value.getState() != RentalItemHtmlImportState.COMMITTING) {
      if (command != null) {
        if (!value.hasCommitIdentity(
            command.actorSubjectId(), command.idempotencyKey(), command.requestHash())) {
          throw new AssetConflictException("HTML import commit identity is not current");
        }
        HtmlImportDetailResponse response = detail(value);
        idempotency.store(
            command.actorSubjectId(),
            command.operation(),
            command.idempotencyKey(),
            command.requestHash(),
            202,
            response);
        return response;
      }
      return detail(value);
    }
    if (command != null
        && !value.hasCommitIdentity(
            command.actorSubjectId(), command.idempotencyKey(), command.requestHash())) {
      throw new AssetConflictException("HTML import commit identity is not current");
    }
    List<RentalItemHtmlImportRow> importRows =
        rows.findAllByImportIdOrderBySourcePositionAscIdAsc(value.getId());
    rejectMergeRows(importRows);
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
        row.removePrivatePhotoKey(write(withoutPhotoPublicKey(parsed(row))));
      }
    }

    HtmlImportPlan plan = readPlan(value.getPlanJson());
    PlanEvaluation evaluation = evaluatePlan(value, importRows, plan);
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
            .collect(Collectors.toMap(HtmlImportRowDecision::sourceRowId, Function.identity()));
    for (RentalItemHtmlImportRow row : importRows) {
      if (row.getAction() == RentalItemHtmlImportRowAction.EXCLUDE) continue;
      if (row.getAction() != RentalItemHtmlImportRowAction.CREATE) {
        throw new AssetConflictException("HTML import can only commit newly-created cabins");
      }
      ParsedRow source = parsed(row);
      HtmlImportRowDecision decision = decisions.get(source.sourceRowId());
      if (decision == null) decision = effectiveStoredDecision(row);
      ResolvedRow resolved = resolveRow(source, decision, plan, staged);
      if (!isHtmlImportManualStatus(resolved.status())) {
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
    HtmlImportDetailResponse response = detail(value);
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

  private RetryMediaPreparation prepareRetryMedia(
      UUID id, Long expectedVersion, CommitOperation command) {
    Optional<tools.jackson.databind.JsonNode> replay =
        idempotency.replay(
            command.actorSubjectId(), command.operation(), command.idempotencyKey(), command.requestHash());
    if (replay.isPresent()) {
      return new RetryMediaPreparation(
          read(replay.get(), HtmlImportDetailResponse.class), null, false);
    }
    RentalItemHtmlImport value = requireImportForUpdate(id);
    assertVersion(value, expectedVersion);
    if (value.getMediaLinkCount() == 0) {
      HtmlImportDetailResponse response = detail(value);
      idempotency.store(
          command.actorSubjectId(),
          command.operation(),
          command.idempotencyKey(),
          command.requestHash(),
          202,
          response);
      return new RetryMediaPreparation(response, null, false);
    }
    if (value.getState() == RentalItemHtmlImportState.ASSETS_COMMITTED
        || value.getState() == RentalItemHtmlImportState.MEDIA_IMPORTING) {
      return new RetryMediaPreparation(null, value.getMediaJobId(), true);
    }
    if (value.getState() == RentalItemHtmlImportState.FAILED && value.getMediaJobId() != null) {
      return new RetryMediaPreparation(null, value.getMediaJobId(), false);
    }
    throw new AssetConflictException("HTML import media cannot be retried in its current state");
  }

  private HtmlImportDetailResponse finishRetryMedia(
      UUID id, UUID expectedJobId, MediaAssetImportJob job, CommitOperation command) {
    HtmlImportDetailResponse replay = replayCommand(command);
    if (replay != null) return replay;
    RentalItemHtmlImport value = requireImportForUpdate(id);
    if (value.getState() != RentalItemHtmlImportState.FAILED
        || !expectedJobId.equals(value.getMediaJobId())) {
      throw new AssetConflictException("HTML import media retry is no longer current");
    }
    requireMediaJobIdentity(value, job);
    value.mediaPending();
    applyMediaJob(value, job);
    imports.saveAndFlush(value);
    HtmlImportDetailResponse response = detail(value);
    idempotency.store(
        command.actorSubjectId(),
        command.operation(),
        command.idempotencyKey(),
        command.requestHash(),
        202,
        response);
    return response;
  }

  private ReplaceMediaPreparation prepareReplaceMedia(
      UUID id,
      Long expectedVersion,
      List<HtmlImportMediaReplacement> replacements,
      CommitOperation command) {
    Optional<tools.jackson.databind.JsonNode> replay =
        idempotency.replay(
            command.actorSubjectId(), command.operation(), command.idempotencyKey(), command.requestHash());
    if (replay.isPresent()) {
      return new ReplaceMediaPreparation(
          read(replay.get(), HtmlImportDetailResponse.class), null, List.of());
    }
    RentalItemHtmlImport value = requireImportForUpdate(id);
    assertVersion(value, expectedVersion);
    if (value.getState() != RentalItemHtmlImportState.FAILED
        || value.getMediaJobId() == null) {
      throw new AssetConflictException("HTML import media links cannot be replaced in its current state");
    }
    return new ReplaceMediaPreparation(
        null, value.getMediaJobId(), mediaReplacementSources(value, replacements));
  }

  private HtmlImportDetailResponse finishReplaceMedia(
      UUID id, UUID expectedJobId, MediaAssetImportJob job, CommitOperation command) {
    HtmlImportDetailResponse replay = replayCommand(command);
    if (replay != null) return replay;
    RentalItemHtmlImport value = requireImportForUpdate(id);
    if (value.getState() != RentalItemHtmlImportState.FAILED
        || !expectedJobId.equals(value.getMediaJobId())) {
      throw new AssetConflictException("HTML import media replacement is no longer current");
    }
    requireMediaJobIdentity(value, job);
    value.mediaPending();
    applyMediaJob(value, job);
    imports.saveAndFlush(value);
    HtmlImportDetailResponse response = detail(value);
    idempotency.store(
        command.actorSubjectId(),
        command.operation(),
        command.idempotencyKey(),
        command.requestHash(),
        202,
        response);
    return response;
  }

  private HtmlImportDetailResponse storeMediaCommandResponse(UUID id, CommitOperation command) {
    HtmlImportDetailResponse replay = replayCommand(command);
    if (replay != null) return replay;
    RentalItemHtmlImport value = requireImportForUpdate(id);
    HtmlImportDetailResponse response = detail(value);
    idempotency.store(
        command.actorSubjectId(),
        command.operation(),
        command.idempotencyKey(),
        command.requestHash(),
        202,
        response);
    return response;
  }

  private HtmlImportDetailResponse replayCommand(CommitOperation command) {
    return idempotency
        .replay(
            command.actorSubjectId(),
            command.operation(),
            command.idempotencyKey(),
            command.requestHash())
        .map(node -> read(node, HtmlImportDetailResponse.class))
        .orElse(null);
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

  private MediaSyncPreparation prepareMediaSync(UUID id) {
    RentalItemHtmlImport value = requireImportForUpdate(id);
    if (value.getState() != RentalItemHtmlImportState.ASSETS_COMMITTED
        && value.getState() != RentalItemHtmlImportState.MEDIA_IMPORTING) {
      return new MediaSyncPreparation(null);
    }
    if (value.getMediaJobId() == null) {
      value.fail("MEDIA_JOB_MISSING");
      imports.saveAndFlush(value);
      return new MediaSyncPreparation(null);
    }
    return new MediaSyncPreparation(value.getMediaJobId());
  }

  private MediaActivationIntent applyMediaObservation(UUID id, MediaAssetImportJob job) {
    RentalItemHtmlImport value = requireImportForUpdate(id);
    if (value.getState() != RentalItemHtmlImportState.ASSETS_COMMITTED
        && value.getState() != RentalItemHtmlImportState.MEDIA_IMPORTING) {
      return null;
    }
    requireMediaJobIdentity(value, job);
    if (!value.getMediaJobId().equals(job.jobId())) throw wrongMediaJob();
    MediaActivationIntent activation = applyMediaJob(value, job);
    imports.saveAndFlush(value);
    return activation;
  }

  private Void markMediaJobMissing(UUID id, UUID jobId) {
    RentalItemHtmlImport value = requireImportForUpdate(id);
    if ((value.getState() == RentalItemHtmlImportState.ASSETS_COMMITTED
            || value.getState() == RentalItemHtmlImportState.MEDIA_IMPORTING)
        && jobId.equals(value.getMediaJobId())) {
      value.fail("MEDIA_JOB_NOT_FOUND");
      imports.saveAndFlush(value);
    }
    return null;
  }

  private MediaActivationIntent applyMediaJob(
      RentalItemHtmlImport value, MediaAssetImportJob job) {
    requireMediaJobIdentity(value, job);
    if (!value.getMediaJobId().equals(job.jobId())) throw wrongMediaJob();
    return switch (job.status()) {
      case PREFLIGHT_PENDING, PREFLIGHT_RUNNING -> {
        value.mediaPending();
        yield null;
      }
      case PREFLIGHT_READY -> {
        List<MediaAssetImportBinding> bindings = mediaBindings(value);
        if (bindings.isEmpty()) {
          value.mediaCompleted(false);
          yield null;
        }
        yield new MediaActivationIntent(value.getId(), value.getMediaJobId(), bindings);
      }
      case ACTIVATION_PENDING, ACTIVATION_RUNNING -> {
        value.mediaStarted(job.jobId());
        yield null;
      }
      case COMPLETED -> {
        value.mediaStarted(job.jobId());
        value.mediaCompleted(job.hasWarnings());
        yield null;
      }
      case FAILED -> {
        value.fail(safeMediaFailureCode(job.failureCode()));
        yield null;
      }
    };
  }

  private List<MediaAssetImportBinding> mediaBindings(RentalItemHtmlImport value) {
    return
        rows.findAllByImportIdOrderBySourcePositionAscIdAsc(value.getId()).stream()
            .filter(row -> row.getAction() != RentalItemHtmlImportRowAction.EXCLUDE)
            .filter(RentalItemHtmlImportRow::hasPhotoLink)
            .map(
                row -> {
                  if (row.getTargetRentalItemId() == null) {
                    throw new IllegalStateException(
                        "Committed HTML media row has no cabin target");
                  }
                  return new MediaAssetImportBinding(
                      row.getId(), row.getTargetRentalItemId());
                })
            .toList();
  }

  private List<MediaAssetImportSource> mediaSources(
      List<RentalItemHtmlImportRow> importRows) {
    List<MediaAssetImportSource> result = new ArrayList<>();
    for (RentalItemHtmlImportRow row : importRows) {
      if (row.getAction() == RentalItemHtmlImportRowAction.EXCLUDE
          || !row.hasPhotoLink()) continue;
      String publicKey = parsed(row).photoPublicKey();
      if (publicKey != null) {
        result.add(
            new MediaAssetImportSource(
                row.getId(), "https://disk.yandex.ru/d/" + publicKey));
      }
    }
    if (result.size() > MediaAssetImportClient.MAX_SOURCES) {
      throw new IllegalArgumentException(
          "HTML import contains more than 500 selected photo sources");
    }
    return List.copyOf(result);
  }

  private List<MediaAssetImportSource> mediaReplacementSources(
      RentalItemHtmlImport value,
      List<HtmlImportMediaReplacement> replacements) {
    if (replacements == null || replacements.isEmpty()) {
      throw new IllegalArgumentException("At least one media link replacement is required");
    }
    Map<UUID, RentalItemHtmlImportRow> rowsById =
        rows.findAllByImportIdOrderBySourcePositionAscIdAsc(value.getId()).stream()
            .collect(Collectors.toMap(RentalItemHtmlImportRow::getId, Function.identity()));
    List<MediaAssetImportSource> result = new ArrayList<>(replacements.size());
    Set<UUID> seen = new HashSet<>();
    for (HtmlImportMediaReplacement replacement : replacements) {
      if (replacement == null || replacement.rowId() == null || !seen.add(replacement.rowId())) {
        throw new IllegalArgumentException("HTML import media replacement rows must be distinct");
      }
      RentalItemHtmlImportRow row = rowsById.get(replacement.rowId());
      if (row == null
          || !row.hasPhotoLink()
          || row.getAction() == RentalItemHtmlImportRowAction.EXCLUDE
          || row.getTargetRentalItemId() == null) {
        throw new AssetConflictException("HTML import media replacement row is not eligible");
      }
      result.add(new MediaAssetImportSource(row.getId(), replacement.publicUrl()));
    }
    return List.copyOf(result);
  }

  private static void requireMediaJobIdentity(
      RentalItemHtmlImport value, MediaAssetImportJob job) {
    if (job == null
        || !value.getId().equals(job.assetImportId())
        || !value.getWarehouseId().equals(job.warehouseId())) {
      throw wrongMediaJob();
    }
  }

  private static AssetDependencyException wrongMediaJob() {
    return new AssetDependencyException(
        org.springframework.http.HttpStatus.BAD_GATEWAY,
        "Media import returned a different job");
  }

  private static String safeMediaFailureCode(String value) {
    return value != null && value.matches("^[A-Z][A-Z0-9_]{0,63}$")
        ? value
        : "MEDIA_IMPORT_FAILED";
  }

  private HtmlImportDetailResponse detail(RentalItemHtmlImport value) {
    List<ParsedRow> parsedRows =
        rows.findAllByImportIdOrderBySourcePositionAscIdAsc(value.getId()).stream()
            .map(this::parsed)
            .toList();
    List<CabinCatalogItem> catalogItems = catalog.findAll();
    List<dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem> equipmentItems =
        equipment.findAllByOrderByNameAscIdAsc();
    return new HtmlImportDetailResponse(
        value.getId(),
        value.getVersion(),
        value.getWarehouseId(),
        value.getState(),
        value.getRowCount(),
        value.getSelectedCount(),
        value.getInvalidCount(),
        value.getUnresolvedCount(),
        value.getMediaLinkCount(),
        value.getWarningCount(),
        value.getMediaJobId(),
        value.getFailureCode(),
        value.getCreatedAt(),
        value.getUpdatedAt(),
        catalogItems.stream()
            .filter(CabinCatalogItem::isActive)
            .sorted(
                Comparator.comparing((CabinCatalogItem item) -> item.getKind().name())
                    .thenComparing(CabinCatalogItem::getName)
                    .thenComparing(CabinCatalogItem::getId))
            .map(
                item ->
                    new HtmlImportCatalogTarget(
                        item.getId(), item.getKind(), item.getName(), item.isActive()))
            .toList(),
        equipmentItems.stream()
            .filter(
                item ->
                    item.isActive()
                        && item.getCategory() == EquipmentCategory.FURNITURE)
            .map(
                item ->
                    new HtmlImportEquipmentTarget(
                        item.getId(), item.getName(), item.isActive()))
            .toList(),
        sourceCandidates(parsedRows, catalogItems, equipmentItems),
        readPlan(value.getPlanJson()));
  }

  private HtmlImportRowResponse rowResponse(
      RentalItemHtmlImportRow row,
      RentalItem target,
      Map<UUID, CabinCatalogItem> catalogById) {
    ParsedRow source = parsed(row);
    HtmlImportRowDecision decision = readDecision(row.getDecisionJson());
    return new HtmlImportRowResponse(
        row.getId(),
        row.getSourceRowId(),
        row.getSourcePosition(),
        row.getSourceNumber(),
        row.getProposedNumber(),
        row.getAction(),
        row.getTargetRentalItemId(),
        source.rentalType(),
        source.dimension(),
        source.finishing(),
        source.category(),
        source.characteristics(),
        source.linoleum(),
        source.storageState(),
        source.status(),
        source.proposedStatus(),
        source.comment(),
        row.hasPhotoLink(),
        source.furniture(),
        source.shipmentDate(),
        source.tenant(),
        source.price(),
        source.diagnostics(),
        decision,
        existingPreview(target, catalogById));
  }

  private ExistingRentalItemPreview existingPreview(
      RentalItem target, Map<UUID, CabinCatalogItem> catalogById) {
    if (target == null) return null;
    return new ExistingRentalItemPreview(
        target.getId(),
        target.getVersion(),
        target.getNumber(),
        target.getStatus(),
        target.getRentalTypeId(),
        catalogName(catalogById, target.getRentalTypeId()),
        target.getDimensionId(),
        catalogName(catalogById, target.getDimensionId()),
        target.getFinishingId(),
        catalogName(catalogById, target.getFinishingId()),
        target.getCategoryId(),
        target.getCategory(),
        target.getLinoleum(),
        target.getGeneralComment(),
        readMap(target.getPassportJson()));
  }

  private List<HtmlImportSourceCandidate> sourceCandidates(
      List<ParsedRow> parsedRows,
      List<CabinCatalogItem> catalogItems,
      List<dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem> equipmentItems) {
    Map<CandidateKey, CandidateAccumulator> values = new LinkedHashMap<>();
    for (ParsedRow row : parsedRows) {
      candidate(values, HtmlImportCandidateKind.TYPE, row.rentalType(), false);
      candidate(values, HtmlImportCandidateKind.DIMENSION, row.dimension(), false);
      candidate(values, HtmlImportCandidateKind.FINISHING, row.finishing(), true);
      candidate(values, HtmlImportCandidateKind.CATEGORY, row.category(), false);
      row.characteristics()
          .forEach(
              value ->
                  candidate(
                      values, HtmlImportCandidateKind.CHARACTERISTIC, value, false));
      String statusSource = candidateSource(row.status());
      if (statusSource != null) {
        accumulate(
            values,
            new CandidateKey(HtmlImportCandidateKind.STATUS, normalizedKey(statusSource)),
            statusSource,
            row.proposedStatus() == null ? null : row.proposedStatus().name(),
            true);
      }
      for (FurnitureLine line : row.furniture()) {
        accumulate(
            values,
            new CandidateKey(
                HtmlImportCandidateKind.EQUIPMENT, normalizedKey(line.sourceLabel())),
            line.sourceLabel(),
            line.sourceLabel(),
            false);
      }
    }

    Map<CabinCatalogKind, List<CabinCatalogItem>> catalogByKind =
        catalogItems.stream()
            .filter(CabinCatalogItem::isActive)
            .collect(Collectors.groupingBy(CabinCatalogItem::getKind));
    List<dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem> activeFurniture =
        equipmentItems.stream()
            .filter(
                item ->
                    item.isActive()
                        && item.getCategory() == EquipmentCategory.FURNITURE)
            .toList();

    List<HtmlImportSourceCandidate> result = new ArrayList<>(values.size());
    for (Map.Entry<CandidateKey, CandidateAccumulator> entry : values.entrySet()) {
      CandidateAccumulator candidate = entry.getValue();
      UUID suggestedTargetId = null;
      CabinCatalogKind catalogKind = catalogKind(entry.getKey().kind());
      String suggestedValue =
          candidate.suggestedValue == null
              ? candidate.sourceValue
              : candidate.suggestedValue;
      if (catalogKind != null) {
        suggestedTargetId =
            bestAutomaticTarget(
                    suggestedValue,
                    catalogByKind.getOrDefault(catalogKind, List.of()),
                    CabinCatalogItem::getName)
                .map(CabinCatalogItem::getId)
                .orElse(null);
      } else if (entry.getKey().kind() == HtmlImportCandidateKind.EQUIPMENT) {
        suggestedTargetId =
            bestAutomaticTarget(
                    suggestedValue,
                    activeFurniture,
                    dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem::getName)
                .map(dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem::getId)
                .orElse(null);
      }
      result.add(
          new HtmlImportSourceCandidate(
              entry.getKey().kind(),
              candidate.sourceValue,
              candidate.suggestedValue,
              candidate.occurrences,
              suggestedTargetId,
              candidate.required));
    }
    return result.stream()
        .sorted(
            Comparator.comparing((HtmlImportSourceCandidate value) -> value.kind().name())
                .thenComparing(HtmlImportSourceCandidate::sourceValue))
        .toList();
  }

  private static void candidate(
      Map<CandidateKey, CandidateAccumulator> target,
      HtmlImportCandidateKind kind,
      SourceValue source,
      boolean required) {
    String sourceValue = candidateSource(source);
    if (sourceValue == null) return;
    accumulate(
        target,
        new CandidateKey(kind, normalizedKey(sourceValue)),
        sourceValue,
        source.suggestedValue(),
        required);
  }

  private static void accumulate(
      Map<CandidateKey, CandidateAccumulator> target,
      CandidateKey key,
      String sourceValue,
      String suggestedValue,
      boolean required) {
    CandidateAccumulator current = target.get(key);
    if (current == null) {
      target.put(key, new CandidateAccumulator(sourceValue, suggestedValue, 1, required));
    } else {
      current.occurrences += 1;
      current.required = current.required || required;
      if (current.suggestedValue == null) current.suggestedValue = suggestedValue;
    }
  }

  private PlanEvaluation evaluatePlan(
      RentalItemHtmlImport value,
      List<RentalItemHtmlImportRow> importRows,
      HtmlImportPlan plan) {
    List<ParsedRow> parsedRows = importRows.stream().map(this::parsed).toList();
    List<HtmlImportSourceCandidate> candidates =
        sourceCandidates(
            parsedRows, catalog.findAll(), equipment.findAllByOrderByNameAscIdAsc());
    int unresolvedMappings = 0;
    for (HtmlImportSourceCandidate candidate : candidates) {
      if (!candidateResolved(candidate, plan)) unresolvedMappings += 1;
    }

    Map<String, HtmlImportRowDecision> requestDecisions =
        plan.rows().stream()
            .collect(Collectors.toMap(HtmlImportRowDecision::sourceRowId, Function.identity()));
    Set<String> createNumbers = new HashSet<>();
    int selected = 0;
    int invalid = 0;
    int unresolvedRows = 0;
    for (RentalItemHtmlImportRow row : importRows) {
      HtmlImportRowDecision decision = requestDecisions.get(row.getSourceRowId());
      if (decision == null) decision = effectiveStoredDecision(row);
      RentalItemHtmlImportRowAction action =
          decision.action() == null ? row.getAction() : decision.action();
      if (action == RentalItemHtmlImportRowAction.EXCLUDE) continue;
      if (action == RentalItemHtmlImportRowAction.REVIEW) {
        invalid += 1;
        continue;
      }
      selected += 1;
      ParsedRow source = parsed(row);
      if (!rowResolvable(value.getWarehouseId(), source, decision, plan)) {
        unresolvedRows += 1;
      }
      if (action == RentalItemHtmlImportRowAction.MERGE) {
        // A historical import record may still contain MERGE, but the active
        // intake path never mutates a live cabin.
        unresolvedRows += 1;
      } else if (action == RentalItemHtmlImportRowAction.CREATE) {
        String proposedNumber =
            decision.proposedNumber() == null
                ? source.proposedNumber()
                : decision.proposedNumber();
        try {
          String identityMatchKey =
              RentalItem.identityMatchKey(RentalItem.canonicalNumber(proposedNumber));
          if (!createNumbers.add(identityMatchKey)
              || rentalItems.existsByWarehouseIdAndIdentityMatchKey(
                  value.getWarehouseId(), identityMatchKey)) {
            unresolvedRows += 1;
          }
        } catch (IllegalArgumentException exception) {
          unresolvedRows += 1;
        }
      }
    }
    return new PlanEvaluation(
        selected, invalid, unresolvedMappings + unresolvedRows);
  }

  private boolean candidateResolved(
      HtmlImportSourceCandidate candidate, HtmlImportPlan plan) {
    if (candidate.kind() == HtmlImportCandidateKind.STATUS) {
      if (candidate.suggestedValue() != null) {
        try {
          return isHtmlImportManualStatus(RentalItemStatus.valueOf(candidate.suggestedValue()));
        } catch (IllegalArgumentException ignored) {
          return false;
        }
      }
      return plan.statusMappings().stream()
          .anyMatch(
              mapping ->
                  normalizedKey(mapping.sourceValue())
                      .equals(normalizedKey(candidate.sourceValue()))
                      && isHtmlImportManualStatus(mapping.targetStatus()));
    }
    if (candidate.suggestedTargetId() != null) return true;
    if (candidate.kind() == HtmlImportCandidateKind.EQUIPMENT) {
      Optional<HtmlImportEquipmentMapping> mapping =
          plan.equipmentMappings().stream()
              .filter(
                  value ->
                      normalizedKey(value.sourceValue())
                          .equals(normalizedKey(candidate.sourceValue())))
              .findFirst();
      return mapping.isEmpty() || mapping.filter(this::validEquipmentMapping).isPresent();
    }
    CabinCatalogKind kind = catalogKind(candidate.kind());
    Optional<HtmlImportCatalogMapping> mapping =
        plan.catalogMappings().stream()
            .filter(
                value ->
                    value.kind() == kind
                        && normalizedKey(value.sourceValue())
                            .equals(normalizedKey(candidate.sourceValue())))
            .findFirst();
    return mapping.isEmpty() || validCatalogMapping(mapping.get());
  }

  private boolean rowResolvable(
      UUID warehouseId,
      ParsedRow source,
      HtmlImportRowDecision decision,
      HtmlImportPlan plan) {
    try {
      String number =
          decision.proposedNumber() == null
              ? source.proposedNumber()
              : decision.proposedNumber();
      RentalItem.canonicalNumber(number);
      requireResolvableCatalog(
          CabinCatalogKind.TYPE, source.rentalType(), decision.rentalTypeId(), plan, false);
      requireResolvableCatalog(
          CabinCatalogKind.DIMENSION,
          source.dimension(),
          decision.dimensionId(),
          plan,
          false);
      requireResolvableCatalog(
          CabinCatalogKind.FINISHING,
          source.finishing(),
          decision.finishingId(),
          plan,
          true);
      requireResolvableCatalog(
          CabinCatalogKind.CATEGORY,
          source.category(),
          decision.categoryId(),
          plan,
          false);
      for (SourceValue characteristic : source.characteristics()) {
        requireResolvableCatalog(
            CabinCatalogKind.CHARACTERISTIC, characteristic, null, plan, false);
      }
      requireResolvableStatus(source, decision, plan);
      for (FurnitureLine furniture : source.furniture()) {
        requireResolvableEquipment(furniture.sourceLabel(), plan);
      }
      if (decision.action() == RentalItemHtmlImportRowAction.CREATE) {
        if (decision.targetRentalItemId() != null) return false;
      } else if (decision.action() == RentalItemHtmlImportRowAction.MERGE) {
        return false;
      } else {
        return false;
      }
      return true;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private void validateMappingUniqueness(HtmlImportPlan plan) {
    Set<String> catalogKeys = new HashSet<>();
    for (HtmlImportCatalogMapping mapping : plan.catalogMappings()) {
      String key = mapping.kind() + ":" + normalizedKey(mapping.sourceValue());
      if (!catalogKeys.add(key)) {
        throw new IllegalArgumentException("HTML catalog mappings must be unique");
      }
      if (!validCatalogMapping(mapping)) {
        throw new IllegalArgumentException("HTML catalog mapping is invalid");
      }
    }
    Set<String> equipmentKeys = new HashSet<>();
    for (HtmlImportEquipmentMapping mapping : plan.equipmentMappings()) {
      if (!equipmentKeys.add(normalizedKey(mapping.sourceValue()))
          || !validEquipmentMapping(mapping)) {
        throw new IllegalArgumentException("HTML equipment mapping is invalid");
      }
    }
    Set<String> statusKeys = new HashSet<>();
    for (HtmlImportStatusMapping mapping : plan.statusMappings()) {
      if (!statusKeys.add(normalizedKey(mapping.sourceValue()))
          || !isHtmlImportManualStatus(mapping.targetStatus())) {
        throw new IllegalArgumentException("HTML status mapping is invalid");
      }
    }
  }

  private boolean validCatalogMapping(HtmlImportCatalogMapping mapping) {
    if (mapping == null || mapping.kind() == null || mapping.action() == null) return false;
    return switch (mapping.action()) {
      case MAP ->
          mapping.targetId() != null
              && catalog
                  .findById(mapping.targetId())
                  .filter(
                      item -> item.isActive() && item.getKind() == mapping.kind())
                  .isPresent();
      case CREATE ->
          mapping.targetId() == null
              && normalized(mapping.stagedName(), 255) != null;
      case IGNORE -> mapping.targetId() == null;
    };
  }

  private boolean validEquipmentMapping(HtmlImportEquipmentMapping mapping) {
    if (mapping == null || mapping.action() == null) return false;
    return switch (mapping.action()) {
      case MAP ->
          mapping.targetId() != null
              && equipment
                  .findById(mapping.targetId())
                  .filter(
                      item ->
                          item.isActive()
                              && item.getCategory() == EquipmentCategory.FURNITURE)
                  .isPresent();
      case CREATE ->
          mapping.targetId() == null
              && normalized(mapping.stagedName(), 255) != null;
      case IGNORE -> mapping.targetId() == null;
    };
  }

  private void validateRowDecision(
      UUID warehouseId,
      RentalItemHtmlImportRow row,
      HtmlImportRowDecision decision) {
    if (!row.getSourceRowId().equals(decision.sourceRowId())) {
      throw new IllegalArgumentException("HTML import row identity changed");
    }
    if (decision.proposedNumber() != null) {
      RentalItem.canonicalNumber(decision.proposedNumber());
    }
    if (decision.action() == RentalItemHtmlImportRowAction.CREATE
        && decision.targetRentalItemId() != null) {
      throw new IllegalArgumentException("CREATE row cannot have a merge target");
    }
    if (decision.action() == RentalItemHtmlImportRowAction.MERGE) {
      throw new AssetConflictException(
          "HTML import MERGE is retired; existing cabins must use their owning workflow");
    }
    if (decision.action() == RentalItemHtmlImportRowAction.EXCLUDE
        || decision.action() == RentalItemHtmlImportRowAction.REVIEW) return;
    if (decision.status() != null && !isHtmlImportManualStatus(decision.status())) {
      throw new IllegalArgumentException(
          "HTML import can only set SALE, USED_SALE, FREE, WAREHOUSE, or OWN_NEEDS");
    }
  }

  private void requireResolvableCatalog(
      CabinCatalogKind kind,
      SourceValue source,
      UUID overrideId,
      HtmlImportPlan plan,
      boolean required) {
    String reference = catalogRef(kind, source, overrideId, plan, required);
    if (required && reference == null) {
      throw new IllegalArgumentException("Required HTML catalog value is unresolved");
    }
  }

  private String catalogRef(
      CabinCatalogKind kind,
      SourceValue source,
      UUID overrideId,
      HtmlImportPlan plan,
      boolean required) {
    if (overrideId != null) {
      CabinCatalogItem item =
          catalog
              .findById(overrideId)
              .filter(value -> value.isActive() && value.getKind() == kind)
              .orElseThrow(() -> new IllegalArgumentException("Catalog override is invalid"));
      return token(item.getId());
    }
    String sourceValue = candidateSource(source);
    if (sourceValue == null) {
      if (required && supportsUnspecifiedValue(kind)) {
        return token(requireUnspecifiedCatalog(kind).getId());
      }
      if (required) throw new IllegalArgumentException("Required source value is absent");
      return null;
    }
    Optional<HtmlImportCatalogMapping> mapping =
        plan.catalogMappings().stream()
            .filter(
                value ->
                    value.kind() == kind
                        && normalizedKey(value.sourceValue())
                            .equals(normalizedKey(sourceValue)))
            .findFirst();
    if (mapping.isPresent()) {
      return switch (mapping.get().action()) {
        case MAP -> token(mapping.get().targetId());
        case CREATE -> "new:" + normalizedKey(mapping.get().stagedName());
        case IGNORE -> {
          if (required && supportsUnspecifiedValue(kind)) {
            yield token(requireUnspecifiedCatalog(kind).getId());
          }
          if (required) throw new IllegalArgumentException("Required source value cannot be ignored");
          yield null;
        }
      };
    }
    String suggestion =
        source == null || source.suggestedValue() == null
            ? sourceValue
            : source.suggestedValue();
    Optional<CabinCatalogItem> automatic = autoCatalog(kind, suggestion);
    if (automatic.isPresent()) return token(automatic.get().getId());
    if (required && supportsUnspecifiedValue(kind)) {
      return token(requireUnspecifiedCatalog(kind).getId());
    }
    if (required) throw new IllegalArgumentException("Source catalog value requires a mapping");
    return null;
  }

  private void requireResolvableStatus(
      ParsedRow source, HtmlImportRowDecision decision, HtmlImportPlan plan) {
    if (resolvedStatus(source, decision, plan) == null) {
      throw new IllegalArgumentException("Source status requires a mapping");
    }
  }

  private RentalItemStatus resolvedStatus(
      ParsedRow source, HtmlImportRowDecision decision, HtmlImportPlan plan) {
    if (decision.status() != null) {
      return isHtmlImportManualStatus(decision.status()) ? decision.status() : null;
    }
    if (source.proposedStatus() != null) {
      return isHtmlImportManualStatus(source.proposedStatus()) ? source.proposedStatus() : null;
    }
    String sourceValue = candidateSource(source.status());
    if (sourceValue == null) return null;
    RentalItemStatus mapped =
        plan.statusMappings().stream()
        .filter(
            value ->
                normalizedKey(value.sourceValue()).equals(normalizedKey(sourceValue)))
        .map(HtmlImportStatusMapping::targetStatus)
        .findFirst()
        .orElse(null);
    return isHtmlImportManualStatus(mapped) ? mapped : null;
  }

  private void requireResolvableEquipment(String sourceValue, HtmlImportPlan plan) {
    if (autoEquipment(sourceValue).isPresent()) return;
    Optional<HtmlImportEquipmentMapping> mapping =
        plan.equipmentMappings().stream()
            .filter(
                value ->
                    normalizedKey(value.sourceValue())
                        .equals(normalizedKey(sourceValue)))
            .findFirst();
    if (mapping.isPresent() && !validEquipmentMapping(mapping.get())) {
      throw new IllegalArgumentException("Equipment mapping is invalid");
    }
  }

  private CommitCatalogs stageCatalogs(
      UUID actorSubjectId, RentalItemHtmlImport value, HtmlImportPlan plan) {
    Map<MappingKey, CatalogSelection> catalogMappings = new LinkedHashMap<>();
    for (HtmlImportCatalogMapping mapping : plan.catalogMappings()) {
      MappingKey key = new MappingKey(mapping.kind(), normalizedKey(mapping.sourceValue()));
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
              stableKey(
                  "catalog",
                  value.getId(),
                  mapping.kind().name(),
                  normalizedKey(mapping.sourceValue()));
          var created =
              composition
                  .createCatalogItem(
                      actorSubjectId,
                      commandKey,
                      new CreateCabinCatalogItemRequest(
                          mapping.kind(), mapping.stagedName()))
                  .response();
          catalogMappings.put(
              key, new CatalogSelection(created.id(), created.name()));
        }
        case IGNORE -> {
          // Optional source value intentionally has no target.
        }
      }
    }

    Map<String, UUID> equipmentMappings = new LinkedHashMap<>();
    for (HtmlImportEquipmentMapping mapping : plan.equipmentMappings()) {
      String key = normalizedKey(mapping.sourceValue());
      switch (mapping.action()) {
        case MAP -> equipmentMappings.put(key, mapping.targetId());
        case CREATE -> {
          UUID commandKey =
              stableKey("equipment", value.getId(), key);
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

  private ResolvedRow resolveRow(
      ParsedRow source,
      HtmlImportRowDecision decision,
      HtmlImportPlan plan,
      CommitCatalogs staged) {
    String number =
        RentalItem.canonicalNumber(
            decision.proposedNumber() == null
                ? source.proposedNumber()
                : decision.proposedNumber());
    CatalogSelection rentalType =
        resolveCatalog(
            CabinCatalogKind.TYPE,
            source.rentalType(),
            decision.rentalTypeId(),
            plan,
            staged,
            false);
    CatalogSelection dimension =
        resolveCatalog(
            CabinCatalogKind.DIMENSION,
            source.dimension(),
            decision.dimensionId(),
            plan,
            staged,
            false);
    CatalogSelection finishing =
        resolveCatalog(
            CabinCatalogKind.FINISHING,
            source.finishing(),
            decision.finishingId(),
            plan,
            staged,
            true);
    CatalogSelection category =
        resolveCatalog(
            CabinCatalogKind.CATEGORY,
            source.category(),
            decision.categoryId(),
            plan,
            staged,
            false);
    boolean categorySpecified = category != null;

    List<UUID> characteristics;
    if (decision.characteristicIds() != null) {
      characteristics =
          decision.characteristicIds().stream()
              .map(
                  id ->
                      requireCatalogTarget(id, CabinCatalogKind.CHARACTERISTIC)
                          .getId())
              .distinct()
              .toList();
    } else {
      List<UUID> values = new ArrayList<>();
      for (SourceValue characteristic : source.characteristics()) {
        CatalogSelection selected =
            resolveCatalog(
                CabinCatalogKind.CHARACTERISTIC,
                characteristic,
                null,
                plan,
                staged,
                false);
        if (selected != null && !values.contains(selected.id())) values.add(selected.id());
      }
      characteristics = List.copyOf(values);
    }
    boolean characteristicsSpecified =
        decision.characteristicIds() != null || !characteristics.isEmpty();

    Map<UUID, Long> equipmentQuantities = new LinkedHashMap<>();
    for (FurnitureLine line : source.furniture()) {
      UUID target = resolveEquipment(line.sourceLabel(), plan, staged);
      if (target != null) {
        equipmentQuantities.merge(target, (long) line.quantity(), Math::addExact);
      }
    }
    Boolean linoleum =
        decision.linoleum() == null ? source.linoleum() : decision.linoleum();
    String comment = decision.comment() == null ? source.comment() : decision.comment();
    return new ResolvedRow(
        number,
        rentalType == null ? null : rentalType.id(),
        dimension == null ? null : dimension.id(),
        finishing.id(),
        category == null ? null : category.id(),
        category == null ? null : category.name(),
        categorySpecified,
        characteristics,
        characteristicsSpecified,
        resolvedStatus(source, decision, plan),
        linoleum,
        comment,
        sourcePassport(source),
        Map.copyOf(equipmentQuantities));
  }

  private CatalogSelection resolveCatalog(
      CabinCatalogKind kind,
      SourceValue source,
      UUID overrideId,
      HtmlImportPlan plan,
      CommitCatalogs staged,
      boolean required) {
    if (overrideId != null) {
      CabinCatalogItem item = requireCatalogTarget(overrideId, kind);
      return new CatalogSelection(item.getId(), item.getName());
    }
    String sourceValue = candidateSource(source);
    if (sourceValue == null) {
      if (required && supportsUnspecifiedValue(kind)) {
        return unspecifiedSelection(kind);
      }
      if (required) throw new IllegalArgumentException("Required source value is absent");
      return null;
    }
    MappingKey key = new MappingKey(kind, normalizedKey(sourceValue));
    Optional<HtmlImportCatalogMapping> explicit =
        plan.catalogMappings().stream()
            .filter(
                mapping ->
                    mapping.kind() == kind
                        && normalizedKey(mapping.sourceValue())
                            .equals(normalizedKey(sourceValue)))
            .findFirst();
    if (explicit.isPresent()) {
      if (explicit.get().action() == HtmlImportMappingAction.IGNORE) {
        if (required && supportsUnspecifiedValue(kind)) {
          return unspecifiedSelection(kind);
        }
        if (required) throw new IllegalArgumentException("Required source value was ignored");
        return null;
      }
      CatalogSelection selection = staged.catalogMappings().get(key);
      if (selection == null) {
        throw new IllegalStateException("Staged catalog mapping is missing");
      }
      return selection;
    }
    String suggestedValue =
        source.suggestedValue() == null
            ? candidateSource(source)
            : source.suggestedValue();
    Optional<CabinCatalogItem> automatic = autoCatalog(kind, suggestedValue);
    if (automatic.isPresent()) {
      return new CatalogSelection(automatic.get().getId(), automatic.get().getName());
    }
    if (required && supportsUnspecifiedValue(kind)) {
      return unspecifiedSelection(kind);
    }
    if (required) throw new IllegalArgumentException("Source catalog value is unresolved");
    return null;
  }

  private UUID resolveEquipment(
      String sourceValue, HtmlImportPlan plan, CommitCatalogs staged) {
    Optional<HtmlImportEquipmentMapping> explicit =
        plan.equipmentMappings().stream()
            .filter(
                mapping ->
                    normalizedKey(mapping.sourceValue())
                        .equals(normalizedKey(sourceValue)))
            .findFirst();
    if (explicit.isPresent()) {
      if (explicit.get().action() == HtmlImportMappingAction.IGNORE) return null;
      UUID target = staged.equipmentMappings().get(normalizedKey(sourceValue));
      if (target == null) throw new IllegalStateException("Staged equipment mapping is missing");
      return target;
    }
    return autoEquipment(sourceValue)
        .map(dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem::getId)
        .orElse(null);
  }

  private RentalItemResponse createRentalItem(
      RentalItemHtmlImport value, ResolvedRow resolved) {
    RentalItemResponse result =
        assets
            .createRentalItemFromHtmlImport(
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
              result.id(),
              new UpdateGeneralCommentRequest(result.version(), resolved.comment()));
    }
    return result;
  }

  private static Map<String, Object> sourcePassport(ParsedRow source) {
    Map<String, Object> passport = new LinkedHashMap<>();
    if (source.storageState() != null) {
      passport.put("storageState", source.storageState());
    }
    if (source.shipmentDate() != null) {
      passport.put("shipmentDate", source.shipmentDate().toString());
    }
    if (source.tenant() != null) passport.put("tenant", source.tenant());
    if (source.price() != null) passport.put("price", source.price());
    return Map.copyOf(passport);
  }

  private void ensureTypeDimension(UUID rentalTypeId, UUID dimensionId) {
    if (rentalTypeId == null || dimensionId == null) return;
    if (typeDimensions.existsByCabinTypeIdAndDimensionId(rentalTypeId, dimensionId)) return;
    List<CabinTypeDimension> configured =
        typeDimensions.findAllByCabinTypeIdOrderBySortOrderAscIdAsc(rentalTypeId);
    int nextOrder =
        configured.stream()
            .mapToInt(CabinTypeDimension::getSortOrder)
            .max()
            .orElse(-1)
            + 1;
    try {
      typeDimensions.saveAndFlush(
          CabinTypeDimension.create(rentalTypeId, dimensionId, nextOrder));
    } catch (DataIntegrityViolationException exception) {
      if (!typeDimensions.existsByCabinTypeIdAndDimensionId(rentalTypeId, dimensionId)) {
        throw exception;
      }
    }
  }

  private CabinCatalogItem requireCatalogTarget(UUID id, CabinCatalogKind kind) {
    if (id == null) throw new IllegalArgumentException("Catalog target is required");
    return catalog
        .findById(id)
        .filter(item -> item.isActive() && item.getKind() == kind)
        .orElseThrow(() -> new AssetNotFoundException("Catalog target was not found"));
  }

  private CatalogSelection unspecifiedSelection(CabinCatalogKind kind) {
    CabinCatalogItem item = requireUnspecifiedCatalog(kind);
    return new CatalogSelection(item.getId(), item.getName());
  }

  private CabinCatalogItem requireUnspecifiedCatalog(CabinCatalogKind kind) {
    if (!supportsUnspecifiedValue(kind)) {
      throw new IllegalArgumentException("This cabin catalog does not support an unspecified value");
    }
    return catalog
        .findByKindAndNameNormalized(kind, normalizedKey(UNSPECIFIED_CATALOG_VALUE))
        .filter(CabinCatalogItem::isActive)
        .orElseThrow(
            () -> new IllegalStateException("Required HTML import placeholder is missing"));
  }

  private Optional<CabinCatalogItem> autoCatalog(
      CabinCatalogKind kind, String suggestedValue) {
    if (suggestedValue == null) return Optional.empty();
    Optional<CabinCatalogItem> exact =
        catalog
            .findByKindAndNameNormalized(kind, normalizedKey(suggestedValue))
            .filter(CabinCatalogItem::isActive);
    if (exact.isPresent()) return exact;
    return bestAutomaticTarget(
        suggestedValue,
        catalog.findAllByKindAndActiveTrueOrderByNameAscIdAsc(kind),
        CabinCatalogItem::getName);
  }

  private Optional<dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem> autoEquipment(
      String sourceValue) {
    if (sourceValue == null) return Optional.empty();
    return bestAutomaticTarget(
        sourceValue,
        equipment.findAllByOrderByNameAscIdAsc().stream()
            .filter(
                item ->
                    item.isActive()
                        && item.getCategory() == EquipmentCategory.FURNITURE)
            .toList(),
        dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem::getName);
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

  private <T> T inTransaction(java.util.function.Supplier<T> operation) {
    return transactions.execute(status -> operation.get());
  }

  private CommitIntent commitIntent(
      RentalItemHtmlImport value, List<RentalItemHtmlImportRow> importRows) {
    UUID commitActorSubjectId =
        value.getCommitActorSubjectId() == null
            ? value.getActorSubjectId()
            : value.getCommitActorSubjectId();
    return new CommitIntent(
        value.getId(),
        value.getWarehouseId(),
        commitActorSubjectId,
        mediaSources(importRows));
  }

  private void rejectMergeRows(List<RentalItemHtmlImportRow> importRows) {
    for (RentalItemHtmlImportRow row : importRows) {
      HtmlImportRowDecision decision = readDecision(row.getDecisionJson());
      if (row.getAction() == RentalItemHtmlImportRowAction.MERGE
          || (decision != null && decision.action() == RentalItemHtmlImportRowAction.MERGE)) {
        throw new AssetConflictException(
            "HTML import MERGE is retired; existing cabins must use their owning workflow");
      }
    }
  }

  private static void assertVersion(RentalItemHtmlImport value, Long expected) {
    if (expected == null || expected < 0) {
      throw new IllegalArgumentException("expectedVersion is required");
    }
    if (value.getVersion() != expected) {
      throw new AssetConflictException("HTML import changed concurrently");
    }
  }

  private ParsedRow parsed(RentalItemHtmlImportRow row) {
    try {
      return objectMapper.readerFor(ParsedRow.class).readValue(row.getParsedJson());
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored HTML import row is corrupt", exception);
    }
  }

  private static ParsedRow withoutPhotoPublicKey(ParsedRow source) {
    return new ParsedRow(
        source.sourceRowId(),
        source.sourcePosition(),
        source.sourceNumber(),
        source.proposedNumber(),
        source.identityMatchKey(),
        source.rentalType(),
        source.dimension(),
        source.finishing(),
        source.category(),
        source.characteristics(),
        source.linoleum(),
        source.storageState(),
        source.status(),
        source.proposedStatus(),
        source.comment(),
        null,
        source.furniture(),
        source.shipmentDate(),
        source.tenant(),
        source.price(),
        source.diagnostics());
  }

  private HtmlImportPlan readPlan(String json) {
    if (json == null || json.isBlank() || "{}".equals(json.trim())) {
      return new HtmlImportPlan(List.of(), List.of(), List.of(), List.of());
    }
    try {
      return objectMapper.readerFor(HtmlImportPlan.class).readValue(json);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored HTML import plan is corrupt", exception);
    }
  }

  private HtmlImportRowDecision readDecision(String json) {
    if (json == null || json.isBlank() || "{}".equals(json.trim())) return null;
    try {
      return objectMapper.readerFor(HtmlImportRowDecision.class).readValue(json);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored HTML import row decision is corrupt", exception);
    }
  }

  private HtmlImportRowDecision effectiveStoredDecision(RentalItemHtmlImportRow row) {
    HtmlImportRowDecision stored = readDecision(row.getDecisionJson());
    if (stored != null) return stored;
    Long targetVersion =
        row.getTargetRentalItemId() == null
            ? null
            : rentalItems
                .findById(row.getTargetRentalItemId())
                .map(RentalItem::getVersion)
                .orElse(null);
    return new HtmlImportRowDecision(
        row.getSourceRowId(),
        row.getAction(),
        row.getProposedNumber(),
        row.getTargetRentalItemId(),
        targetVersion,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        Map.of());
  }

  private <T> T read(tools.jackson.databind.JsonNode node, Class<T> type) {
    try {
      return objectMapper.readerFor(type).readValue(node);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored idempotent HTML import response is corrupt", exception);
    }
  }

  private Map<String, Object> readMap(String json) {
    try {
      return objectMapper.readValue(
          json, new TypeReference<Map<String, Object>>() {});
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored rental passport is corrupt", exception);
    }
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("HTML import value cannot be serialized", exception);
    }
  }

  private String hash(Object value) {
    try {
      return AssetChecksum.sha256(objectMapper.writeValueAsBytes(value));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("HTML import command cannot be fingerprinted", exception);
    }
  }

  private static UUID stableKey(String namespace, UUID importId, String... values) {
    StringBuilder source = new StringBuilder(namespace).append(':').append(importId);
    for (String value : values) source.append(':').append(value);
    return UUID.nameUUIDFromBytes(source.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static String candidateSource(SourceValue source) {
    if (source == null) return null;
    String label = normalized(source.sourceLabel(), 255);
    if (label != null) return label;
    String suggestion = normalized(source.suggestedValue(), 255);
    if (suggestion != null) return suggestion;
    String sourceId = normalized(source.sourceId(), 255);
    return sourceId == null || "0".equals(sourceId) ? null : sourceId;
  }

  private static CabinCatalogKind catalogKind(HtmlImportCandidateKind kind) {
    return switch (kind) {
      case TYPE -> CabinCatalogKind.TYPE;
      case DIMENSION -> CabinCatalogKind.DIMENSION;
      case FINISHING -> CabinCatalogKind.FINISHING;
      case CATEGORY -> CabinCatalogKind.CATEGORY;
      case CHARACTERISTIC -> CabinCatalogKind.CHARACTERISTIC;
      case STATUS, EQUIPMENT -> null;
    };
  }

  private static boolean candidateNeedsDecision(HtmlImportSourceCandidate candidate) {
    if (candidate.kind() != HtmlImportCandidateKind.STATUS) return false;
    if (candidate.suggestedValue() == null) return true;
    try {
      return !isHtmlImportManualStatus(RentalItemStatus.valueOf(candidate.suggestedValue()));
    } catch (IllegalArgumentException ignored) {
      return true;
    }
  }

  private static boolean isHtmlImportManualStatus(RentalItemStatus status) {
    return status == RentalItemStatus.SALE
        || status == RentalItemStatus.USED_SALE
        || status == RentalItemStatus.FREE
        || status == RentalItemStatus.WAREHOUSE
        || status == RentalItemStatus.OWN_NEEDS;
  }

  private static boolean supportsUnspecifiedValue(CabinCatalogKind kind) {
    return kind == CabinCatalogKind.TYPE
        || kind == CabinCatalogKind.DIMENSION
        || kind == CabinCatalogKind.FINISHING;
  }

  private static String catalogName(Map<UUID, CabinCatalogItem> catalogById, UUID id) {
    if (id == null) return null;
    CabinCatalogItem item = catalogById.get(id);
    return item == null ? null : item.getName();
  }

  private static void add(Collection<UUID> values, UUID value) {
    if (value != null) values.add(value);
  }

  private static String token(UUID value) {
    return value == null ? null : "id:" + value;
  }

  private static String normalizedKey(String value) {
    String normalized = normalized(value, 4000);
    return normalized == null ? "" : normalized.toLowerCase(Locale.ROOT);
  }

  private static <T> Optional<T> bestAutomaticTarget(
      String sourceValue, Collection<T> targets, Function<T, String> label) {
    String sourceKey = normalizedKey(sourceValue);
    if (sourceKey.isEmpty() || targets.isEmpty()) return Optional.empty();

    Optional<T> exact =
        targets.stream()
            .filter(target -> normalizedKey(label.apply(target)).equals(sourceKey))
            .findFirst();
    if (exact.isPresent()) return exact;

    Set<String> sourceWords = semanticWords(sourceValue);
    if (sourceWords.isEmpty()) return Optional.empty();

    ScoredTarget<T> best = null;
    ScoredTarget<T> runnerUp = null;
    for (T target : targets) {
      double score = wordSimilarity(sourceWords, semanticWords(label.apply(target)));
      ScoredTarget<T> candidate = new ScoredTarget<>(target, score);
      if (best == null || score > best.score()) {
        runnerUp = best;
        best = candidate;
      } else if (runnerUp == null || score > runnerUp.score()) {
        runnerUp = candidate;
      }
    }
    if (best == null || best.score() < AUTOMATIC_WORD_MATCH_THRESHOLD) {
      return Optional.empty();
    }
    if (runnerUp != null
        && runnerUp.score() >= AUTOMATIC_WORD_MATCH_THRESHOLD
        && best.score() - runnerUp.score() < AUTOMATIC_WORD_MATCH_MARGIN) {
      return Optional.empty();
    }
    return Optional.of(best.target());
  }

  private static Set<String> semanticWords(String value) {
    if (value == null) return Set.of();
    String normalized =
        value
            .toLowerCase(Locale.ROOT)
            .replace('ё', 'е')
            .replaceAll("(?iuU)блок\\s*[-–—]?\\s*контейнер", "бк")
            .replaceAll("(?iuU)сан\\.?\\s*блок", "санблок")
            .replaceAll(
                "(?iuU)(?<![\\p{L}\\p{N}])эл\\s*[-–—]?\\s*ка(?![\\p{L}\\p{N}])",
                "электрика кк")
            .replaceAll(
                "(?iuU)(?:2|двух)\\s*[-–—]?\\s*ярусн\\p{L}*",
                "двухъярусная")
            .replaceAll(
                "(?<=\\p{L})(?=\\d)|(?<=\\d)(?=\\p{L})",
                " ")
            .replaceAll("[^\\p{L}\\p{N}]+", " ");
    Set<String> result = new LinkedHashSet<>();
    for (String word : normalized.split("\\s+")) {
      if (word.isBlank()
          || Set.of("и", "в", "на", "не", "эконом").contains(word)
          || (word.length() == 1 && !word.chars().allMatch(Character::isDigit))) {
        continue;
      }
      result.add(word);
    }
    return Set.copyOf(result);
  }

  private static double wordSimilarity(Set<String> left, Set<String> right) {
    if (left.isEmpty() || right.isEmpty()) return 0.0d;
    int shared = 0;
    for (String word : left) {
      if (right.contains(word)) shared += 1;
    }
    return (2.0d * shared) / (left.size() + right.size());
  }

  private static String normalized(String value, int maximum) {
    if (value == null) return null;
    String normalized = value.trim().replaceAll("[\\p{Z}\\s]+", " ");
    if (normalized.isEmpty() || normalized.length() > maximum) return null;
    return normalized;
  }

  private record ScoredTarget<T>(T target, double score) {}

  private record CandidateKey(HtmlImportCandidateKind kind, String sourceKey) {}

  private static final class CandidateAccumulator {
    private final String sourceValue;
    private String suggestedValue;
    private long occurrences;
    private boolean required;

    private CandidateAccumulator(
        String sourceValue, String suggestedValue, long occurrences, boolean required) {
      this.sourceValue = sourceValue;
      this.suggestedValue = suggestedValue;
      this.occurrences = occurrences;
      this.required = required;
    }
  }

  private record PlanEvaluation(int selectedCount, int invalidCount, int unresolvedCount) {}

  private record MappingKey(CabinCatalogKind kind, String sourceValue) {}

  private record CatalogSelection(UUID id, String name) {}

  private record CommitCatalogs(
      Map<MappingKey, CatalogSelection> catalogMappings,
      Map<String, UUID> equipmentMappings) {}

  private record CommitOperation(
      UUID actorSubjectId, UUID idempotencyKey, String operation, String requestHash) {}

  private record CommitIntent(
      UUID importId,
      UUID warehouseId,
      UUID actorSubjectId,
      List<MediaAssetImportSource> mediaSources) {}

  private record CommitPreparation(
      HtmlImportDetailResponse replay, CommitIntent intent) {}

  private record CommitRecoveryPreparation(CommitIntent intent) {}

  private record RetryMediaPreparation(
      HtmlImportDetailResponse replay, UUID mediaJobId, boolean synchronize) {}

  private record ReplaceMediaPreparation(
      HtmlImportDetailResponse replay,
      UUID mediaJobId,
      List<MediaAssetImportSource> replacements) {}

  private record MediaSyncPreparation(UUID mediaJobId) {}

  private record MediaActivationIntent(
      UUID importId, UUID mediaJobId, List<MediaAssetImportBinding> bindings) {}

  private record ResolvedRow(
      String number,
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      UUID categoryId,
      String categoryName,
      boolean categorySpecified,
      List<UUID> characteristicIds,
      boolean characteristicsSpecified,
      RentalItemStatus status,
      Boolean linoleum,
      String comment,
      Map<String, Object> passport,
      Map<UUID, Long> equipmentQuantities) {}
}
