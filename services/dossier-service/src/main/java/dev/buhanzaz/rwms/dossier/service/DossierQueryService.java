package dev.buhanzaz.rwms.dossier.service;

import dev.buhanzaz.rwms.dossier.api.DossierApiModels;
import dev.buhanzaz.rwms.dossier.domain.DossierActiveGeneration;
import dev.buhanzaz.rwms.dossier.domain.DossierActivity;
import dev.buhanzaz.rwms.dossier.domain.DossierActivityCode;
import dev.buhanzaz.rwms.dossier.domain.DossierMediaProjection;
import dev.buhanzaz.rwms.dossier.domain.DossierProducer;
import dev.buhanzaz.rwms.dossier.mapper.DossierMediaProjectionMapper;
import dev.buhanzaz.rwms.dossier.repository.DossierActiveGenerationRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierActivityRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierMediaProjectionRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierSanitizedDeadLetterRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierUnlinkedFactRepository;
import dev.buhanzaz.rwms.dossier.security.DossierAuthorizer;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds the read-only, warehouse-scoped cabin dossier from the active local projection generation. */
@Service
public class DossierQueryService {
  private final DossierActiveGenerationRepository activeGenerations;
  private final DossierActivityRepository activities;
  private final DossierMediaProjectionRepository media;
  private final DossierUnlinkedFactRepository unlinked;
  private final DossierSanitizedDeadLetterRepository deadLetters;
  private final DossierAuthorizer authorizer;
  private final DossierCursorCodec cursors;
  private final DossierMediaProjectionMapper mediaMapper;

  public DossierQueryService(
      DossierActiveGenerationRepository activeGenerations,
      DossierActivityRepository activities,
      DossierMediaProjectionRepository media,
      DossierUnlinkedFactRepository unlinked,
      DossierSanitizedDeadLetterRepository deadLetters,
      DossierAuthorizer authorizer,
      DossierCursorCodec cursors,
      DossierMediaProjectionMapper mediaMapper) {
    this.activeGenerations = activeGenerations;
    this.activities = activities;
    this.media = media;
    this.unlinked = unlinked;
    this.deadLetters = deadLetters;
    this.authorizer = authorizer;
    this.cursors = cursors;
    this.mediaMapper = mediaMapper;
  }

  /**
   * Reads the currently active generation only after producing a fail-closed warehouse scope.
   * The cursor is bound to the cabin and filter set, and inaccessible rows resolve as not found
   * instead of disclosing their existence. PARTIAL includes only hidden rows or unresolved
   * evidence scoped to this cabin and generation; global operational evidence is excluded.
   */
  @Transactional(readOnly = true)
  public DossierApiModels.CabinDossierResponse get(
      UUID cabinId, Query query, Jwt jwt) {
    validate(query);
    DossierAuthorizer.WarehouseScope scope = authorizer.requireReadScope(jwt);
    if (!scope.unrestricted() && scope.warehouseIds().isEmpty()) throw notFound();
    UUID generationId =
        activeGenerations
            .findByPointerName(DossierActiveGeneration.POINTER_NAME)
            .map(DossierActiveGeneration::getGenerationId)
            .orElseThrow(() -> new IllegalStateException("DOSSIER_ACTIVE_GENERATION_MISSING"));
    String filterHash = filterHash(query);
    DossierCursorCodec.CursorPosition cursor =
        query.after() == null ? null : cursors.decode(query.after(), cabinId, filterHash);

    Specification<DossierActivity> filtered = base(cabinId, generationId, query, null);
    Specification<DossierActivity> base = base(cabinId, generationId, query, cursor);
    Specification<DossierActivity> visibleScope = visible(scope);
    Specification<DossierActivity> evidence =
        (root, ignored, builder) ->
            builder.and(
                builder.equal(root.get("generationId"), generationId),
                builder.equal(root.get("cabinId"), cabinId));
    if (activities.count(evidence.and(visibleScope)) == 0) throw notFound();
    Specification<DossierActivity> visible = base.and(visibleScope);
    Sort sort =
        Sort.by(
            Sort.Order.desc("occurredAt").nullsLast(),
            Sort.Order.desc("recordedAt"),
            Sort.Order.desc("sourceEventId"));
    List<DossierActivity> page =
        activities.findAll(visible, PageRequest.of(0, query.limit() + 1, sort)).getContent();
    boolean hasNext = page.size() > query.limit();
    List<DossierActivity> selected =
        hasNext ? List.copyOf(page.subList(0, query.limit())) : List.copyOf(page);

    Specification<DossierMediaProjection> baseMedia =
        (root, ignored, builder) -> {
          List<Predicate> predicates = new ArrayList<>();
          predicates.add(builder.equal(root.get("generationId"), generationId));
          predicates.add(builder.equal(root.get("cabinId"), cabinId));
          predicates.add(
              builder.notEqual(
                  root.get("state"),
                  dev.buhanzaz.rwms.dossier.domain.DossierMediaState.DELETED));
          return builder.and(predicates.toArray(Predicate[]::new));
        };
    Specification<DossierMediaProjection> visibleMedia =
        baseMedia.and(
            scope.unrestricted()
                ? (root, ignored, builder) -> builder.conjunction()
                : (root, ignored, builder) ->
                    root.get("warehouseId").in(scope.warehouseIds()));
    List<DossierMediaProjection> mediaRows = media.findAll(visibleMedia);
    List<DossierApiModels.Activity> responseRows =
        selected.stream().map(row -> map(row, mediaRows)).toList();
    String next =
        hasNext
            ? cursors.encode(
                cabinId,
                filterHash,
                new DossierCursorCodec.CursorPosition(
                    instant(selected.getLast().getOccurredAt()),
                    instant(selected.getLast().getRecordedAt()),
                    selected.getLast().getSourceEventId()))
            : null;
    boolean hidden = activities.count(filtered) > activities.count(filtered.and(visibleScope));
    boolean partial =
        hidden
            || media.count(baseMedia) > media.count(visibleMedia)
            || unlinked.countByGenerationIdAndSubjectCabinIdAndResolvedAtIsNull(
                    generationId, cabinId)
                > 0
            || deadLetters
                    .countByCoverageGenerationIdAndCoverageSubjectCabinIdAndCoverageResolvedAtIsNull(
                        generationId, cabinId)
                > 0;
    return new DossierApiModels.CabinDossierResponse(
        cabinId,
        responseRows,
        next,
        partial ? DossierApiModels.Visibility.PARTIAL : DossierApiModels.Visibility.COMPLETE);
  }

  private static Specification<DossierActivity> base(
      UUID cabinId, UUID generationId, Query query, DossierCursorCodec.CursorPosition cursor) {
    return (root, ignored, builder) -> {
      List<Predicate> values = new ArrayList<>();
      values.add(builder.equal(root.get("generationId"), generationId));
      values.add(builder.equal(root.get("cabinId"), cabinId));
      if (!query.activityCodes().isEmpty()) values.add(root.get("activityCode").in(query.activityCodes()));
      if (!query.sourceTypes().isEmpty()) values.add(root.get("sourceProducer").in(query.sourceTypes()));
      if (query.actorSubjectId() != null) values.add(builder.equal(root.get("actorSubjectId"), query.actorSubjectId()));
      if (query.occurredFrom() != null) {
        values.add(builder.isNotNull(root.get("occurredAt")));
        values.add(builder.greaterThanOrEqualTo(root.get("occurredAt"), offset(query.occurredFrom())));
      }
      if (query.occurredBefore() != null) {
        values.add(builder.isNotNull(root.get("occurredAt")));
        values.add(builder.lessThan(root.get("occurredAt"), offset(query.occurredBefore())));
      }
      if (cursor != null) values.add(after(root, builder, cursor));
      return builder.and(values.toArray(Predicate[]::new));
    };
  }

  private static Predicate after(
      jakarta.persistence.criteria.Root<DossierActivity> root,
      jakarta.persistence.criteria.CriteriaBuilder builder,
      DossierCursorCodec.CursorPosition cursor) {
    OffsetDateTime recorded = offset(cursor.recordedAt());
    Predicate recordedTail =
        builder.or(
            builder.lessThan(root.get("recordedAt"), recorded),
            builder.and(
                builder.equal(root.get("recordedAt"), recorded),
                builder.lessThan(root.get("sourceEventId"), cursor.sourceEventId())));
    if (cursor.occurredAt() == null) {
      return builder.and(builder.isNull(root.get("occurredAt")), recordedTail);
    }
    OffsetDateTime occurred = offset(cursor.occurredAt());
    return builder.or(
        builder.isNull(root.get("occurredAt")),
        builder.lessThan(root.get("occurredAt"), occurred),
        builder.and(builder.equal(root.get("occurredAt"), occurred), recordedTail));
  }

  private static Specification<DossierActivity> visible(
      DossierAuthorizer.WarehouseScope scope) {
    if (scope.unrestricted()) return (root, query, builder) -> builder.conjunction();
    return (root, query, builder) -> root.get("warehouseId").in(scope.warehouseIds());
  }

  private DossierApiModels.Activity map(
      DossierActivity value, List<DossierMediaProjection> mediaRows) {
    List<DossierApiModels.MediaProjection> attached =
        mediaRows.stream()
            .filter(
                item ->
                    item.getInventoryFindingId().equals(value.getSourceSecondaryId())
                        || item.getMediaId().equals(value.getSourceAggregateId()))
            .sorted(Comparator.comparing(DossierMediaProjection::getMediaId))
            .map(mediaMapper::toResponse)
            .toList();
    DossierApiModels.ActorReference actor =
        value.getActorSubjectId() == null
            ? null
            : new DossierApiModels.ActorReference(
                value.getActorSubjectId(),
                value.getActorPrincipalType(),
                value.getActorProfileRevision());
    return new DossierApiModels.Activity(
        value.getActivityId(),
        value.getCabinId(),
        value.getWarehouseId(),
        value.getActivityCode().name(),
        value.getOccurredAt(),
        value.getRecordedAt(),
        actor,
        new DossierApiModels.SourceReference(
            producerName(value.getSourceProducer()),
            value.getSourceAggregateType(),
            value.getSourceAggregateId(),
            value.getSourceSecondaryId()),
        attached);
  }

  private static void validate(Query query) {
    if (query.limit() < 1 || query.limit() > 100) throw invalidFilter();
    if (query.occurredFrom() != null
        && query.occurredBefore() != null
        && !query.occurredBefore().isAfter(query.occurredFrom())) {
      throw invalidFilter();
    }
  }

  private static String filterHash(Query query) {
    String value =
        query.limit()
            + "|"
            + sorted(query.activityCodes())
            + "|"
            + sorted(query.sourceTypes())
            + "|"
            + query.occurredFrom()
            + "|"
            + query.occurredBefore()
            + "|"
            + query.actorSubjectId();
    return dev.buhanzaz.rwms.dossier.eventing.DossierEventHash.sha256(value);
  }

  private static String sorted(Collection<? extends Enum<?>> values) {
    return values.stream().map(Enum::name).sorted().toList().toString();
  }

  private static String producerName(DossierProducer producer) {
    return switch (producer) {
      case ASSET -> "asset-service";
      case MAINTENANCE -> "maintenance-service";
      case INVENTORY -> "inventory-service";
      case MEDIA -> "media-service";
      case LOGISTICS -> "logistics-service";
      case TASK_BOARD -> "task-board-service";
    };
  }

  private static OffsetDateTime offset(Instant value) {
    return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  private static DossierQueryException invalidFilter() {
    return new DossierQueryException(
        HttpStatus.BAD_REQUEST, "DOSSIER_INVALID_FILTER", "Dossier filter is invalid");
  }

  private static DossierQueryException notFound() {
    return new DossierQueryException(
        HttpStatus.NOT_FOUND, "DOSSIER_NOT_FOUND", "Dossier was not found");
  }

  public record Query(
      int limit,
      String after,
      Instant occurredFrom,
      Instant occurredBefore,
      Set<DossierActivityCode> activityCodes,
      Set<DossierProducer> sourceTypes,
      UUID actorSubjectId) {
    public Query {
      activityCodes = activityCodes == null ? Set.of() : Set.copyOf(activityCodes);
      sourceTypes = sourceTypes == null ? Set.of() : Set.copyOf(sourceTypes);
    }
  }
}
