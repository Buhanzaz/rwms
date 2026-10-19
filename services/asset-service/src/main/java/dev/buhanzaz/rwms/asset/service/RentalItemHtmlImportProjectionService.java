package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.RentalItemHtmlImportApiModels.*;

import dev.buhanzaz.rwms.asset.domain.CabinCatalogItem;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImport;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImportRow;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImportRowAction;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient;
import dev.buhanzaz.rwms.asset.mapper.RentalItemHtmlImportMapper;
import dev.buhanzaz.rwms.asset.repository.CabinCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.EquipmentCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemHtmlImportRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemHtmlImportRowRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import dev.buhanzaz.rwms.asset.service.RentalItemHtmlParser.ParsedImport;
import dev.buhanzaz.rwms.asset.service.RentalItemHtmlParser.ParsedRow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Creates bounded normalized HTML-import intake state and projects import, row, and target-cabin
 * reads.
 *
 * <p>It invokes the plan service only for candidate/read mapping. Durable plan mutation, commit
 * recovery, and media transitions remain with their dedicated owners.
 */
@Service
public class RentalItemHtmlImportProjectionService {
  private final RentalItemHtmlParser parser;
  private final RentalItemHtmlImportRepository imports;
  private final RentalItemHtmlImportRowRepository rows;
  private final RentalItemRepository rentalItems;
  private final CabinCatalogItemRepository catalog;
  private final EquipmentCatalogItemRepository equipment;
  private final WarehouseRegistryClient warehouses;
  private final RentalItemHtmlImportMapper importMapper;
  private final RentalItemHtmlImportPlanService plans;
  private final RentalItemHtmlImportCodec codec;

  public RentalItemHtmlImportProjectionService(
      RentalItemHtmlParser parser,
      RentalItemHtmlImportRepository imports,
      RentalItemHtmlImportRowRepository rows,
      RentalItemRepository rentalItems,
      CabinCatalogItemRepository catalog,
      EquipmentCatalogItemRepository equipment,
      WarehouseRegistryClient warehouses,
      RentalItemHtmlImportMapper importMapper,
      RentalItemHtmlImportPlanService plans,
      RentalItemHtmlImportCodec codec) {
    this.parser = parser;
    this.imports = imports;
    this.rows = rows;
    this.rentalItems = rentalItems;
    this.catalog = catalog;
    this.equipment = equipment;
    this.warehouses = warehouses;
    this.importMapper = importMapper;
    this.plans = plans;
    this.codec = codec;
  }

  HtmlImportDetailResponse create(
      UUID actorSubjectId, UUID idempotencyKey, UUID warehouseId, byte[] html) {
    if (actorSubjectId == null
        || idempotencyKey == null
        || warehouseId == null) {
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
        throw new AssetConflictException("Idempotency-Key is already bound to another HTML import");
      }
      return detail(existing);
    }

    warehouses.requireIncoming(warehouseId);
    ParsedImport parsed = parser.parse(html);
    List<CabinCatalogItem> catalogItems = catalog.findAll();
    List<HtmlImportSourceCandidate> candidates =
        plans.sourceCandidates(parsed.rows(), catalogItems, equipment.findAllByOrderByNameAscIdAsc());
    int parserInvalid =
        (int) parsed.rows().stream().filter(ParsedRow::requiresManualReview).count();
    int unresolved =
        parserInvalid
            + (int)
                candidates.stream()
                    .filter(RentalItemHtmlImportPlanService::candidateNeedsDecision)
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
              codec.write(parsedRow),
              codec.write(
                  parsedRow.diagnostics().stream()
                      .map(RentalItemHtmlParser.ParserDiagnostic::code)
                      .toList())));
    }
    rows.saveAll(persistedRows);
    rows.flush();
    return detail(value);
  }

  List<HtmlImportSummaryResponse> list(UUID warehouseId) {
    return imports.findAllByWarehouseIdOrderByUpdatedAtDescIdAsc(warehouseId).stream()
        .map(importMapper::toSummary)
        .toList();
  }

  HtmlImportDetailResponse get(UUID id) {
    return detail(requireImport(id));
  }

  UUID warehouseId(UUID id) {
    return requireImport(id).getWarehouseId();
  }

  HtmlImportRowPage rowPage(UUID id, int page, int size) {
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

  HtmlImportDetailResponse detail(RentalItemHtmlImport value) {
    List<ParsedRow> parsedRows =
        rows.findAllByImportIdOrderBySourcePositionAscIdAsc(value.getId()).stream()
            .map(codec::parsed)
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
            .filter(item -> item.isActive() && item.getCategory() == EquipmentCategory.FURNITURE)
            .map(item -> new HtmlImportEquipmentTarget(item.getId(), item.getName(), item.isActive()))
            .toList(),
        plans.sourceCandidates(parsedRows, catalogItems, equipmentItems),
        codec.readPlan(value.getPlanJson()));
  }

  private HtmlImportRowResponse rowResponse(
      RentalItemHtmlImportRow row,
      RentalItem target,
      Map<UUID, CabinCatalogItem> catalogById) {
    ParsedRow source = codec.parsed(row);
    HtmlImportRowDecision decision = codec.readDecision(row.getDecisionJson());
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
        codec.readMap(target.getPassportJson()));
  }

  private RentalItemHtmlImport requireImport(UUID id) {
    return imports
        .findById(id)
        .orElseThrow(() -> new AssetNotFoundException("HTML import was not found"));
  }

  private static String catalogName(Map<UUID, CabinCatalogItem> catalogById, UUID id) {
    if (id == null) return null;
    CabinCatalogItem item = catalogById.get(id);
    return item == null ? null : item.getName();
  }

  private static void add(java.util.Collection<UUID> values, UUID value) {
    if (value != null) values.add(value);
  }
}
