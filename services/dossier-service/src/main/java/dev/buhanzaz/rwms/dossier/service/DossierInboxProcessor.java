package dev.buhanzaz.rwms.dossier.service;

import dev.buhanzaz.rwms.dossier.domain.DossierAggregateCheckpoint;
import dev.buhanzaz.rwms.dossier.domain.DossierAggregateBlockReason;
import dev.buhanzaz.rwms.dossier.domain.DossierDltFailureCode;
import dev.buhanzaz.rwms.dossier.domain.DossierInbox;
import dev.buhanzaz.rwms.dossier.domain.DossierInboxDecision;
import dev.buhanzaz.rwms.dossier.domain.DossierPartitionCheckpoint;
import dev.buhanzaz.rwms.dossier.domain.DossierProducer;
import dev.buhanzaz.rwms.dossier.domain.DossierSourceFact;
import dev.buhanzaz.rwms.dossier.domain.DossierUnlinkedFact;
import dev.buhanzaz.rwms.dossier.domain.DossierUnlinkedReason;
import dev.buhanzaz.rwms.dossier.eventing.DossierDeadLetterService;
import dev.buhanzaz.rwms.dossier.eventing.DossierSourceTopics;
import dev.buhanzaz.rwms.dossier.eventing.DossierEnvelopeValidator;
import dev.buhanzaz.rwms.dossier.eventing.DossierValidatedEvent;
import dev.buhanzaz.rwms.dossier.repository.DossierAggregateCheckpointRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierInboxRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierPartitionCheckpointRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierSourceFactRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierUnlinkedFactRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** One local JPA transaction for journal, dedupe, ordering, projection and checkpoints. */
@Service
public class DossierInboxProcessor {
  private final DossierInboxRepository inboxes;
  private final DossierSourceFactRepository sourceFacts;
  private final DossierPartitionCheckpointRepository partitions;
  private final DossierAggregateCheckpointRepository aggregates;
  private final DossierUnlinkedFactRepository unlinked;
  private final DossierProjectionService projections;
  private final DossierDeadLetterService deadLetters;
  private final DossierEnvelopeValidator validator;
  private final TransactionTemplate transactions;

  public DossierInboxProcessor(
      DossierInboxRepository inboxes,
      DossierSourceFactRepository sourceFacts,
      DossierPartitionCheckpointRepository partitions,
      DossierAggregateCheckpointRepository aggregates,
      DossierUnlinkedFactRepository unlinked,
      DossierProjectionService projections,
      DossierDeadLetterService deadLetters,
      DossierEnvelopeValidator validator,
      PlatformTransactionManager transactionManager) {
    this.inboxes = inboxes;
    this.sourceFacts = sourceFacts;
    this.partitions = partitions;
    this.aggregates = aggregates;
    this.unlinked = unlinked;
    this.projections = projections;
    this.deadLetters = deadLetters;
    this.validator = validator;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  public Outcome process(DossierValidatedEvent event) {
    for (int attempt = 0; attempt < 4; attempt++) {
      try {
        Outcome outcome = transactions.execute(status -> processOnce(event));
        if (outcome == null) throw new IllegalStateException("DOSSIER_PROCESSING_OUTCOME_MISSING");
        return outcome;
      } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException failure) {
        if (attempt == 3) throw failure;
      }
    }
    throw new IllegalStateException("DOSSIER_PROCESSING_RETRY_EXHAUSTED");
  }

  private Outcome processOnce(DossierValidatedEvent event) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    DossierProducer producer = producer(event.producerCode());
    DossierPartitionCheckpoint partition = partition(event, now);

    Optional<DossierInbox> existingInbox = inboxes.findById(event.eventId());
    DossierInbox inbox;
    if (existingInbox.isPresent()) {
      inbox = existingInbox.orElseThrow();
      if (!inbox.hasSamePayload(event.payloadSha256())) {
        deadLetters.processingFailure(
            event, event.aggregateId(), DossierDltFailureCode.EVENT_IDENTITY_CONFLICT);
        aggregate(event, producer, now)
            .blockConflict(DossierAggregateBlockReason.EVENT_IDENTITY_CONFLICT, now);
        partition.advance(event.offset(), now);
        return Outcome.EVENT_IDENTITY_CONFLICT;
      }
      if (inbox.getDecision() != DossierInboxDecision.RECEIVED
          && inbox.getDecision() != DossierInboxDecision.DLT) {
        partition.advance(event.offset(), now);
        return Outcome.DUPLICATE;
      }
      inbox.retry();
    } else {
      inbox = inboxes.save(DossierInbox.receive(event.eventId(), event.payloadSha256(), now));
    }

    Optional<DossierSourceFact> offsetFact =
        sourceFacts.findBySourceTopicAndSourcePartitionAndSourceOffset(
            event.topic(), event.partition(), event.offset());
    if (offsetFact.isPresent() && !offsetFact.orElseThrow().getEventId().equals(event.eventId())) {
      deadLetters.processingFailure(
          event, event.aggregateId(), DossierDltFailureCode.EVENT_IDENTITY_CONFLICT);
      aggregate(event, producer, now)
          .blockConflict(DossierAggregateBlockReason.EVENT_IDENTITY_CONFLICT, now);
      decide(event.eventId(), DossierInboxDecision.DLT, now);
      partition.advance(event.offset(), now);
      return Outcome.EVENT_IDENTITY_CONFLICT;
    }
    Optional<DossierSourceFact> persistedFact = sourceFacts.findByEventId(event.eventId());
    if (persistedFact.isEmpty() && inbox.getDecision() == DossierInboxDecision.RECEIVED) {
      persistedFact = Optional.of(sourceFacts.save(sourceFact(event, producer, now)));
    }

    DossierAggregateCheckpoint aggregate = aggregate(event, producer, now);
    UUID generationId = projections.activeGeneration(now);
    if (inbox.getDecision() == DossierInboxDecision.DLT) {
      if (persistedFact.isPresent()
          && !hasSameSourceIdentity(persistedFact.orElseThrow(), event, producer)) {
        deadLetters.processingFailure(
            event, event.aggregateId(), DossierDltFailureCode.EVENT_IDENTITY_CONFLICT);
        aggregate.blockConflict(DossierAggregateBlockReason.EVENT_IDENTITY_CONFLICT, now);
        partition.advance(event.offset(), now);
        return Outcome.EVENT_IDENTITY_CONFLICT;
      }
      if (persistedFact.isPresent()
          && canRetryPublicMediaProcessingFailure(
              event, producer, aggregate, generationId)) {
        projections.apply(event, generationId, now);
        aggregate.recoverProcessingFailure(event.aggregateVersion(), now);
        unlinked
            .findBySourceEventIdAndGenerationId(event.eventId(), generationId)
            .ifPresent(value -> value.resolve(now));
        inbox.recoverProcessingFailure(now);
        drainQuarantined(event, producer, aggregate, generationId, now);
        partition.advance(event.offset(), now);
        return Outcome.PROCESSED;
      }
      partition.advance(event.offset(), now);
      return Outcome.DUPLICATE;
    }
    boolean firstPublicMediaBaseline =
        isFirstPublicMediaBaseline(event, producer, aggregate);
    if (!aggregate.isBlocked() && firstPublicMediaBaseline) {
      aggregate.blockGap(event.aggregateVersion(), now);
    }
    if (aggregate.isBlocked()) {
      if (aggregate.getBlockedReason() == DossierAggregateBlockReason.MISSING_PREFIX
          && (event.aggregateVersion() == aggregate.getExpectedVersion()
              || firstPublicMediaBaseline)) {
        projections.apply(event, generationId, now);
        aggregate.reconcile(event.aggregateVersion(), now);
        decide(event.eventId(), DossierInboxDecision.PROCESSED, now);
        drainQuarantined(event, producer, aggregate, generationId, now);
        partition.advance(event.offset(), now);
        return Outcome.PROCESSED;
      }
      recordUnlinked(event, producer, generationId, DossierUnlinkedReason.AGGREGATE_QUARANTINED);
      decide(event.eventId(), DossierInboxDecision.QUARANTINED, now);
      partition.advance(event.offset(), now);
      return Outcome.BLOCKED;
    }
    long expected = aggregate.nextExpectedVersion();
    if (event.aggregateVersion() < expected) {
      decide(event.eventId(), DossierInboxDecision.DUPLICATE, now);
      partition.advance(event.offset(), now);
      return Outcome.DUPLICATE;
    }
    if (event.aggregateVersion() > expected) {
      aggregate.blockGap(event.aggregateVersion(), now);
      recordUnlinked(event, producer, generationId, DossierUnlinkedReason.MISSING_PREFIX);
      decide(event.eventId(), DossierInboxDecision.QUARANTINED, now);
      partition.advance(event.offset(), now);
      return Outcome.VERSION_GAP;
    }

    try {
      projections.apply(event, generationId, now);
    } catch (IllegalStateException exception) {
      DossierAggregateBlockReason reason = conflictReason(exception);
      if (reason == null) throw exception;
      DossierDltFailureCode failureCode = failureCode(reason);
      aggregate.blockConflict(reason, now);
      recordUnlinked(event, producer, generationId, DossierUnlinkedReason.AGGREGATE_QUARANTINED);
      decide(event.eventId(), DossierInboxDecision.DLT, now);
      deadLetters.processingFailure(event, event.aggregateId(), failureCode);
      partition.advance(event.offset(), now);
      return reason == DossierAggregateBlockReason.MEDIA_GENERATION_CONFLICT
          ? Outcome.MEDIA_GENERATION_CONFLICT
          : Outcome.EVENT_IDENTITY_CONFLICT;
    }
    aggregate.apply(event.aggregateVersion(), now);
    decide(event.eventId(), DossierInboxDecision.PROCESSED, now);
    drainQuarantined(event, producer, aggregate, generationId, now);
    partition.advance(event.offset(), now);
    return Outcome.PROCESSED;
  }

  /**
   * Makes the final bounded-retry outcome durable before Kafka may commit the source offset. Any
   * database failure escapes this method so the fail-closed container error handler stops the
   * consumer and leaves the offset uncommitted.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void deadLetterAfterRetries(DossierValidatedEvent event, Object recordKey) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    DossierProducer producer = producer(event.producerCode());
    DossierPartitionCheckpoint partition = partition(event, now);
    DossierInbox inbox =
        inboxes
            .findById(event.eventId())
            .orElseGet(
                () ->
                    inboxes.save(
                        DossierInbox.receive(event.eventId(), event.payloadSha256(), now)));
    if (!inbox.hasSamePayload(event.payloadSha256())) {
      aggregate(event, producer, now)
          .blockConflict(DossierAggregateBlockReason.EVENT_IDENTITY_CONFLICT, now);
      deadLetters.processingFailure(
          event, recordKey, DossierDltFailureCode.EVENT_IDENTITY_CONFLICT);
      partition.advance(event.offset(), now);
      return;
    }
    if (inbox.getDecision() != DossierInboxDecision.RECEIVED) {
      partition.advance(event.offset(), now);
      return;
    }

    Optional<DossierSourceFact> coordinate =
        sourceFacts.findBySourceTopicAndSourcePartitionAndSourceOffset(
            event.topic(), event.partition(), event.offset());
    if (coordinate.isPresent()
        && !coordinate.orElseThrow().getEventId().equals(event.eventId())) {
      aggregate(event, producer, now)
          .blockConflict(DossierAggregateBlockReason.EVENT_IDENTITY_CONFLICT, now);
      inbox.decide(DossierInboxDecision.DLT, now);
      deadLetters.processingFailure(
          event, recordKey, DossierDltFailureCode.EVENT_IDENTITY_CONFLICT);
      partition.advance(event.offset(), now);
      return;
    }
    if (sourceFacts.findByEventId(event.eventId()).isEmpty()) {
      sourceFacts.save(sourceFact(event, producer, now));
    }

    UUID generationId = projections.activeGeneration(now);
    aggregate(event, producer, now)
        .blockConflict(DossierAggregateBlockReason.PROCESSING_FAILED, now);
    recordUnlinked(event, producer, generationId, DossierUnlinkedReason.AGGREGATE_QUARANTINED);
    inbox.decide(DossierInboxDecision.DLT, now);
    deadLetters.processingFailure(event, recordKey, DossierDltFailureCode.PROCESSING_FAILED);
    partition.advance(event.offset(), now);
  }

  private void drainQuarantined(
      DossierValidatedEvent anchor,
      DossierProducer producer,
      DossierAggregateCheckpoint checkpoint,
      UUID generationId,
      OffsetDateTime now) {
    while (true) {
      long next = checkpoint.nextExpectedVersion();
      var candidates =
          sourceFacts
              .findAllByProducerAndSourceTopicAndAggregateTypeAndAggregateIdAndAggregateVersionOrderByEventIdAsc(
                  producer,
                  anchor.topic(),
                  anchor.aggregateType(),
                  anchor.aggregateId(),
                  next);
      if (candidates.isEmpty()) return;
      DossierSourceFact fact = candidates.getFirst();
      DossierInbox inbox = inboxes.findById(fact.getEventId()).orElseThrow();
      if (inbox.getDecision() != DossierInboxDecision.QUARANTINED) return;
      DossierValidatedEvent recovered =
          validator.validate(
              fact.getSourceTopic(),
              fact.getSourcePartition(),
              fact.getSourceOffset(),
              fact.getAggregateId().toString(),
              fact.getCanonicalEnvelope().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      try {
        projections.apply(recovered, generationId, now);
      } catch (IllegalStateException exception) {
        DossierAggregateBlockReason reason = conflictReason(exception);
        if (reason == null) throw exception;
        inbox.deadLetterQuarantined(now);
        checkpoint.blockConflict(reason, now);
        deadLetters.processingFailure(recovered, recovered.aggregateId(), failureCode(reason));
        return;
      }
      unlinked
          .findBySourceEventIdAndGenerationId(recovered.eventId(), generationId)
          .ifPresent(value -> value.resolve(now));
      checkpoint.apply(recovered.aggregateVersion(), now);
      inbox.recoverProcessed(now);
    }
  }

  private DossierPartitionCheckpoint partition(DossierValidatedEvent event, OffsetDateTime now) {
    return partitions
        .findForUpdateByConsumerGroupAndSourceTopicAndSourcePartition(
            DossierSourceTopics.CONSUMER_GROUP, event.topic(), event.partition())
        .orElseGet(
            () ->
                partitions.save(
                    DossierPartitionCheckpoint.start(
                        DossierSourceTopics.CONSUMER_GROUP,
                        event.topic(),
                        event.partition(),
                        now)));
  }

  private DossierAggregateCheckpoint aggregate(
      DossierValidatedEvent event, DossierProducer producer, OffsetDateTime now) {
    return aggregates
        .findForUpdateByConsumerGroupAndProducerAndSourceTopicAndAggregateTypeAndAggregateId(
            DossierSourceTopics.CONSUMER_GROUP,
            producer,
            event.topic(),
            event.aggregateType(),
            event.aggregateId())
        .orElseGet(
            () ->
                aggregates.save(
                    DossierAggregateCheckpoint.start(
                        DossierSourceTopics.CONSUMER_GROUP,
                        producer,
                        event.topic(),
                        event.aggregateType(),
                        event.aggregateId(),
                        producer == DossierProducer.MEDIA ? 0 : -1,
                        now)));
  }

  private void decide(UUID eventId, DossierInboxDecision decision, OffsetDateTime now) {
    inboxes.findById(eventId).orElseThrow().decide(decision, now);
  }

  private void recordUnlinked(
      DossierValidatedEvent event,
      DossierProducer producer,
      UUID generationId,
      DossierUnlinkedReason reason) {
    if (unlinked.findBySourceEventIdAndGenerationId(event.eventId(), generationId).isEmpty()) {
      unlinked.save(
          DossierUnlinkedFact.record(
              generationId,
              event.eventId(),
              event.cabinId(),
              reason,
              producer,
              event.aggregateType(),
              event.aggregateId(),
              event.payloadSha256(),
              offset(event.recordedAt())));
    }
  }

  private static DossierSourceFact sourceFact(
      DossierValidatedEvent event, DossierProducer producer, OffsetDateTime now) {
    UUID subjectCabinId = event.warehouseId() == null ? null : event.cabinId();
    return DossierSourceFact.record(
        event.eventId(),
        producer,
        event.topic(),
        event.partition(),
        event.offset(),
        event.aggregateId(),
        event.aggregateType(),
        event.aggregateId(),
        event.aggregateVersion(),
        event.eventType(),
        event.eventVersion(),
        event.payloadSha256(),
        event.canonicalEnvelope(),
        offset(event.occurredAt()),
        offset(event.recordedAt()),
        event.actorSubjectId(),
        event.actorPrincipalType(),
        event.actorProfileRevision(),
        event.correlationId(),
        event.causationId(),
        subjectCabinId,
        event.warehouseId(),
        event.secondaryId(),
        event.activityCode() == null
            ? null
            : dev.buhanzaz.rwms.dossier.domain.DossierActivityCode.valueOf(event.activityCode()),
        now);
  }

  private static OffsetDateTime offset(java.time.Instant value) {
    return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
  }

  private static DossierProducer producer(String producerCode) {
    return DossierProducer.valueOf(
        producerCode.replace('-', '_').toUpperCase(java.util.Locale.ROOT));
  }

  private static boolean isFirstPublicMediaBaseline(
      DossierValidatedEvent event,
      DossierProducer producer,
      DossierAggregateCheckpoint aggregate) {
    if (!isPublicMediaBaseline(event, producer, aggregate)) return false;
    return !aggregate.isBlocked()
        || (aggregate.getBlockedReason() == DossierAggregateBlockReason.MISSING_PREFIX
            && Long.valueOf(1L).equals(aggregate.getExpectedVersion())
            && aggregate.getObservedVersion() != null
            && aggregate.getObservedVersion() > event.aggregateVersion());
  }

  private boolean canRetryPublicMediaProcessingFailure(
      DossierValidatedEvent event,
      DossierProducer producer,
      DossierAggregateCheckpoint aggregate,
      UUID generationId) {
    if (!aggregate.isBlocked()
        || aggregate.getBlockedReason() != DossierAggregateBlockReason.PROCESSING_FAILED
        || !isPublicMediaBaseline(event, producer, aggregate)) {
      return false;
    }
    return unlinked
        .findBySourceEventIdAndGenerationId(event.eventId(), generationId)
        .filter(value -> value.getReason() == DossierUnlinkedReason.AGGREGATE_QUARANTINED)
        .filter(value -> value.getResolvedAt() == null)
        .isPresent();
  }

  private static boolean isPublicMediaBaseline(
      DossierValidatedEvent event,
      DossierProducer producer,
      DossierAggregateCheckpoint aggregate) {
    return producer == DossierProducer.MEDIA
        && aggregate.getAppliedVersion() == 0
        && event.aggregateVersion() == 2
        && "MEDIA".equals(event.aggregateType())
        && "media.media.uploaded.v1".equals(event.eventType());
  }

  private static boolean hasSameSourceIdentity(
      DossierSourceFact fact, DossierValidatedEvent event, DossierProducer producer) {
    // Kafka coordinates are intentionally excluded: an operator replay is a new record carrying
    // the exact same canonical domain event.
    return fact.getEventId().equals(event.eventId())
        && fact.getProducer() == producer
        && fact.getSourceTopic().equals(event.topic())
        && fact.getAggregateType().equals(event.aggregateType())
        && fact.getAggregateId().equals(event.aggregateId())
        && fact.getAggregateVersion() == event.aggregateVersion()
        && fact.getEventType().equals(event.eventType())
        && fact.getEventVersion() == event.eventVersion()
        && fact.getPayloadSha256().equals(event.payloadSha256());
  }

  private static DossierAggregateBlockReason conflictReason(IllegalStateException exception) {
    return switch (exception.getMessage()) {
      case "MEDIA_GENERATION_CONFLICT" -> DossierAggregateBlockReason.MEDIA_GENERATION_CONFLICT;
      case "EVENT_IDENTITY_CONFLICT", "DOSSIER_SUBJECT_IDENTITY_CONFLICT" ->
          DossierAggregateBlockReason.EVENT_IDENTITY_CONFLICT;
      default -> null;
    };
  }

  private static DossierDltFailureCode failureCode(DossierAggregateBlockReason reason) {
    return switch (reason) {
      case MEDIA_GENERATION_CONFLICT -> DossierDltFailureCode.MEDIA_GENERATION_CONFLICT;
      case EVENT_IDENTITY_CONFLICT -> DossierDltFailureCode.EVENT_IDENTITY_CONFLICT;
      default -> throw new IllegalArgumentException("Reason is not a terminal source conflict");
    };
  }

  public enum Outcome {
    PROCESSED,
    DUPLICATE,
    VERSION_GAP,
    BLOCKED,
    EVENT_IDENTITY_CONFLICT,
    MEDIA_GENERATION_CONFLICT
  }
}
