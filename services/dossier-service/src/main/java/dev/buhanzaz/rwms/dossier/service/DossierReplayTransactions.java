package dev.buhanzaz.rwms.dossier.service;

import dev.buhanzaz.rwms.dossier.domain.DossierActiveGeneration;
import dev.buhanzaz.rwms.dossier.domain.DossierActivity;
import dev.buhanzaz.rwms.dossier.domain.DossierGenerationState;
import dev.buhanzaz.rwms.dossier.domain.DossierInboxDecision;
import dev.buhanzaz.rwms.dossier.domain.DossierMediaProjection;
import dev.buhanzaz.rwms.dossier.domain.DossierProjectionGeneration;
import dev.buhanzaz.rwms.dossier.domain.DossierReplayRun;
import dev.buhanzaz.rwms.dossier.domain.DossierReplayState;
import dev.buhanzaz.rwms.dossier.eventing.DossierEnvelopeValidator;
import dev.buhanzaz.rwms.dossier.eventing.DossierEventHash;
import dev.buhanzaz.rwms.dossier.repository.DossierActiveGenerationRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierActivityRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierInboxRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierMediaProjectionRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierPartitionCheckpointRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierProjectionGenerationRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierReplayRunRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierReplayPartitionHighWaterRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierSanitizedDeadLetterRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierSourceFactRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/** Owns transactional claim, build, tail verification and atomic activation steps for a dossier generation replay. */
@Service
public class DossierReplayTransactions {
  private final DossierActiveGenerationRepository activePointers;
  private final DossierProjectionGenerationRepository generations;
  private final DossierReplayRunRepository runs;
  private final DossierSourceFactRepository facts;
  private final DossierInboxRepository inboxes;
  private final DossierPartitionCheckpointRepository partitions;
  private final DossierReplayPartitionHighWaterRepository highWaters;
  private final DossierActivityRepository activities;
  private final DossierMediaProjectionRepository media;
  private final DossierSanitizedDeadLetterRepository deadLetters;
  private final DossierProjectionService projections;
  private final DossierEnvelopeValidator validator;
  private final ObjectMapper mapper;

  public DossierReplayTransactions(
      DossierActiveGenerationRepository activePointers,
      DossierProjectionGenerationRepository generations,
      DossierReplayRunRepository runs,
      DossierSourceFactRepository facts,
      DossierInboxRepository inboxes,
      DossierPartitionCheckpointRepository partitions,
      DossierReplayPartitionHighWaterRepository highWaters,
      DossierActivityRepository activities,
      DossierMediaProjectionRepository media,
      DossierSanitizedDeadLetterRepository deadLetters,
      DossierProjectionService projections,
      DossierEnvelopeValidator validator,
      ObjectMapper mapper) {
    this.activePointers = activePointers;
    this.generations = generations;
    this.runs = runs;
    this.facts = facts;
    this.inboxes = inboxes;
    this.partitions = partitions;
    this.highWaters = highWaters;
    this.activities = activities;
    this.media = media;
    this.deadLetters = deadLetters;
    this.projections = projections;
    this.validator = validator;
    this.mapper = mapper;
  }

  /**
   * Locks the active-generation pointer, refuses a concurrent replay and captures partition
   * high-water marks that define the immutable source snapshot for a new target generation.
   */
  @Transactional
  public ReplayClaim start() {
    DossierActiveGeneration pointer =
        activePointers
            .findForUpdateByPointerName(DossierActiveGeneration.POINTER_NAME)
            .orElseThrow(() -> new IllegalStateException("DOSSIER_ACTIVE_GENERATION_MISSING"));
    if (!runs
        .findAllByStateInOrderByStartedAtAsc(
            List.of(
                DossierReplayState.BUILDING,
                DossierReplayState.TAILING,
                DossierReplayState.VERIFYING,
                DossierReplayState.READY))
        .isEmpty()) {
      throw new IllegalStateException("DOSSIER_REPLAY_ALREADY_RUNNING");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    UUID active = pointer.getGenerationId();
    DossierProjectionGeneration target =
        generations.saveAndFlush(DossierProjectionGeneration.building(now));
    List<dev.buhanzaz.rwms.dossier.domain.DossierPartitionCheckpoint> checkpointRows =
        partitions.findAll().stream()
            .sorted(
                Comparator.comparing(
                        dev.buhanzaz.rwms.dossier.domain.DossierPartitionCheckpoint::getSourceTopic)
                    .thenComparingInt(
                        dev.buhanzaz.rwms.dossier.domain.DossierPartitionCheckpoint::getSourcePartition))
            .toList();
    String highWaterHash =
        DossierEventHash.sha256(
            canonical(
                checkpointRows.stream()
                    .map(
                        value ->
                            Map.of(
                                "topic", value.getSourceTopic(),
                                "partition", value.getSourcePartition(),
                                "offset", value.getLastAcceptedOffset()))
                    .toList()));
    DossierReplayRun run =
        runs.saveAndFlush(DossierReplayRun.start(active, target.getId(), now, highWaterHash, now));
    highWaters.saveAll(
        checkpointRows.stream()
            .map(
                value ->
                    dev.buhanzaz.rwms.dossier.domain.DossierReplayPartitionHighWater.capture(
                        run.getId(),
                        value.getSourceTopic(),
                        value.getSourcePartition(),
                        value.getLastAcceptedOffset()))
            .toList());
    return new ReplayClaim(run.getId(), now);
  }

  /**
   * Replays only locally persisted processed facts at or below the claimed high-water marks, then
   * advances the run to tailing without changing the reader-visible generation.
   */
  @Transactional
  public void build(UUID runId) {
    DossierReplayRun run = runs.findById(runId).orElseThrow();
    List<dev.buhanzaz.rwms.dossier.domain.DossierSourceFact> frozen =
        highWaters.findAllByRunIdOrderBySourceTopicAscSourcePartitionAsc(runId).stream()
            .flatMap(
                highWater ->
                    facts
                        .findAllBySourceTopicAndSourcePartitionAndSourceOffsetLessThanEqualOrderBySourceOffsetAscEventIdAsc(
                            highWater.getSourceTopic(),
                            highWater.getSourcePartition(),
                            highWater.getMaxOffset())
                        .stream())
            .distinct()
            .sorted(replayOrder())
            .toList();
    for (var fact : frozen) {
      replayFact(fact, run.getTargetGenerationId());
    }
    run.tailing();
  }

  /**
   * Locks the source pointer, applies post-snapshot facts, compares canonical source and target
   * projections, and switches the active generation only after parity succeeds. Unresolved DLT
   * coverage advances without changing relay state; rejection leaves source coverage untouched.
   */
  @Transactional
  public void tailVerifyAndActivate(UUID runId) {
    DossierActiveGeneration pointer =
        activePointers
            .findForUpdateByPointerName(DossierActiveGeneration.POINTER_NAME)
            .orElseThrow(() -> new IllegalStateException("DOSSIER_ACTIVE_GENERATION_MISSING"));
    DossierReplayRun run = runs.findById(runId).orElseThrow();
    if (!pointer.getGenerationId().equals(run.getSourceGenerationId())) {
      throw new IllegalStateException("DOSSIER_REPLAY_SOURCE_CHANGED");
    }
    Map<String, Long> frozen =
        highWaters.findAllByRunIdOrderBySourceTopicAscSourcePartitionAsc(runId).stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    value -> value.getSourceTopic() + "#" + value.getSourcePartition(),
                    dev.buhanzaz.rwms.dossier.domain.DossierReplayPartitionHighWater::getMaxOffset));
    List<dev.buhanzaz.rwms.dossier.domain.DossierSourceFact> tail =
        facts.findAll().stream()
            .filter(
                fact ->
                    fact.getSourceOffset()
                        > frozen.getOrDefault(
                            fact.getSourceTopic() + "#" + fact.getSourcePartition(), -1L))
            .sorted(replayOrder())
            .toList();
    for (var fact : tail) {
      replayFact(fact, run.getTargetGenerationId());
    }
    run.verifying();
    CanonicalProjection source = canonicalProjection(run.getSourceGenerationId());
    CanonicalProjection target = canonicalProjection(run.getTargetGenerationId());
    run.parity(source.count(), target.count(), source.hash(), target.hash());
    DossierProjectionGeneration targetGeneration =
        generations.findById(run.getTargetGenerationId()).orElseThrow();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    if (run.getState() != DossierReplayState.READY) {
      targetGeneration.reject();
      run.reject(now);
      return;
    }
    DossierProjectionGeneration sourceGeneration =
        generations.findById(run.getSourceGenerationId()).orElseThrow();
    deadLetters.advanceUnresolvedCoverage(
        run.getSourceGenerationId(), run.getTargetGenerationId());
    targetGeneration.ready();
    sourceGeneration.retire(now);
    generations.flush();
    targetGeneration.activate(now);
    pointer.activate(targetGeneration.getId(), now);
    run.activated(now);
  }

  /**
   * Independently rejects a replay target after orchestration failure so a rollback of the caller
   * cannot leave an apparently runnable generation behind.
   */
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
  public void rejectFailed(UUID runId) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    DossierReplayRun run = runs.findById(runId).orElseThrow();
    DossierProjectionGeneration target =
        generations.findById(run.getTargetGenerationId()).orElseThrow();
    if (target.getState() == DossierGenerationState.BUILDING
        || target.getState() == DossierGenerationState.READY) {
      target.reject();
    }
    run.reject(now);
  }

  /** Explicit service-local recovery for a replay abandoned by a process crash. */
  @Transactional
  public int rejectStaleBefore(OffsetDateTime cutoff) {
    List<DossierReplayRun> stale =
        runs.findAllByStateInOrderByStartedAtAsc(
                List.of(
                    DossierReplayState.BUILDING,
                    DossierReplayState.TAILING,
                    DossierReplayState.VERIFYING,
                    DossierReplayState.READY))
            .stream()
            .filter(run -> run.getStartedAt().isBefore(cutoff))
            .toList();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    for (DossierReplayRun run : stale) {
      DossierProjectionGeneration target =
          generations.findById(run.getTargetGenerationId()).orElseThrow();
      if (target.getState() == DossierGenerationState.BUILDING
          || target.getState() == DossierGenerationState.READY) {
        target.reject();
      }
      run.reject(now);
    }
    return stale.size();
  }

  private void replayFact(
      dev.buhanzaz.rwms.dossier.domain.DossierSourceFact fact, UUID generationId) {
    if (inboxes
            .findById(fact.getEventId())
            .map(value -> value.getDecision() != DossierInboxDecision.PROCESSED)
            .orElse(true)) {
      return;
    }
    var event =
        validator.validate(
            fact.getSourceTopic(),
            fact.getSourcePartition(),
            fact.getSourceOffset(),
            fact.getAggregateId().toString(),
            fact.getCanonicalEnvelope().getBytes(StandardCharsets.UTF_8));
    projections.replay(event, generationId, OffsetDateTime.now(ZoneOffset.UTC));
  }

  private static Comparator<dev.buhanzaz.rwms.dossier.domain.DossierSourceFact>
      replayOrder() {
    return Comparator
        .comparingInt(DossierReplayTransactions::dependencyOrder)
        .thenComparing(value -> value.getProducer().name())
        .thenComparing(dev.buhanzaz.rwms.dossier.domain.DossierSourceFact::getSourceTopic)
        .thenComparing(dev.buhanzaz.rwms.dossier.domain.DossierSourceFact::getAggregateType)
        .thenComparing(value -> value.getAggregateId().toString())
        .thenComparingLong(
            dev.buhanzaz.rwms.dossier.domain.DossierSourceFact::getAggregateVersion)
        .thenComparing(value -> value.getEventId().toString());
  }

  private static int dependencyOrder(
      dev.buhanzaz.rwms.dossier.domain.DossierSourceFact fact) {
    if (fact.getSubjectCabinId() != null && fact.getSubjectWarehouseId() != null) {
      return 0;
    }
    if (fact.getProducer() == dev.buhanzaz.rwms.dossier.domain.DossierProducer.MEDIA
        || (fact.getProducer() == dev.buhanzaz.rwms.dossier.domain.DossierProducer.INVENTORY
            && "PUBLICATION".equals(fact.getAggregateType()))) {
      return 2;
    }
    return 1;
  }

  private CanonicalProjection canonicalProjection(UUID generationId) {
    Specification<DossierActivity> activityGeneration =
        (root, query, builder) -> builder.equal(root.get("generationId"), generationId);
    Specification<DossierMediaProjection> mediaGeneration =
        (root, query, builder) -> builder.equal(root.get("generationId"), generationId);
    List<Map<String, Object>> activityRows =
        activities.findAll(activityGeneration).stream()
            .sorted(
                Comparator.comparing(DossierActivity::getSourceEventId)
                    .thenComparing(DossierActivity::getCabinId))
            .map(
                DossierReplayTransactions::activityState)
            .toList();
    List<Map<String, Object>> mediaRows =
        media.findAll(mediaGeneration).stream()
            .sorted(
                Comparator.comparing(DossierMediaProjection::getCabinId)
                    .thenComparing(DossierMediaProjection::getMediaId))
            .map(
                value ->
                    Map.<String, Object>ofEntries(
                        Map.entry("cabinId", value.getCabinId()),
                        Map.entry("warehouseId", value.getWarehouseId()),
                        Map.entry("mediaId", value.getMediaId()),
                        Map.entry("folderId", value.getFolderId()),
                        Map.entry("findingId", value.getInventoryFindingId()),
                        Map.entry("generation", value.getMediaGeneration()),
                        Map.entry("state", value.getState()),
                        Map.entry("sourceEventId", value.getSourceEventId())))
            .toList();
    String canonical = canonical(Map.of("activities", activityRows, "media", mediaRows));
    return new CanonicalProjection(
        activityRows.size() + mediaRows.size(), DossierEventHash.sha256(canonical));
  }

  private String canonical(Object value) {
    return mapper
        .writer()
        .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .writeValueAsString(value);
  }

  private static Map<String, Object> activityState(DossierActivity value) {
    Map<String, Object> state = new java.util.LinkedHashMap<>();
    state.put("activityId", value.getActivityId());
    state.put("sourceEventId", value.getSourceEventId());
    state.put("cabinId", value.getCabinId());
    state.put("warehouseId", value.getWarehouseId());
    state.put("code", value.getActivityCode());
    state.put("occurredAt", value.getOccurredAt());
    state.put("recordedAt", value.getRecordedAt());
    state.put("actorSubjectId", value.getActorSubjectId());
    state.put("actorPrincipalType", value.getActorPrincipalType());
    state.put("actorProfileRevision", value.getActorProfileRevision());
    state.put("sourceProducer", value.getSourceProducer());
    state.put("sourceAggregateType", value.getSourceAggregateType());
    state.put("sourceAggregateId", value.getSourceAggregateId());
    state.put("sourceSecondaryId", value.getSourceSecondaryId());
    return state;
  }

  public record ReplayClaim(UUID runId, OffsetDateTime highWaterAt) {}

  private record CanonicalProjection(long count, String hash) {}
}
