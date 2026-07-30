package dev.buhanzaz.rwms.analytics.service;

import dev.buhanzaz.rwms.analytics.domain.AnalyticsAggregateCheckpoint;
import dev.buhanzaz.rwms.analytics.domain.AnalyticsDltFailureCode;
import dev.buhanzaz.rwms.analytics.domain.AnalyticsInbox;
import dev.buhanzaz.rwms.analytics.domain.AnalyticsInboxDecision;
import dev.buhanzaz.rwms.analytics.domain.AnalyticsPartitionCheckpoint;
import dev.buhanzaz.rwms.analytics.domain.AnalyticsSourceFact;
import dev.buhanzaz.rwms.analytics.eventing.AnalyticsDeadLetterService;
import dev.buhanzaz.rwms.analytics.eventing.AnalyticsEnvelopeValidator;
import dev.buhanzaz.rwms.analytics.eventing.AnalyticsTopics;
import dev.buhanzaz.rwms.analytics.eventing.AnalyticsValidatedEvent;
import dev.buhanzaz.rwms.analytics.repository.AnalyticsAggregateCheckpointRepository;
import dev.buhanzaz.rwms.analytics.repository.AnalyticsInboxRepository;
import dev.buhanzaz.rwms.analytics.repository.AnalyticsPartitionCheckpointRepository;
import dev.buhanzaz.rwms.analytics.repository.AnalyticsSourceFactRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AnalyticsInboxProcessor {
  private final AnalyticsInboxRepository inboxes;
  private final AnalyticsSourceFactRepository sourceFacts;
  private final AnalyticsAggregateCheckpointRepository aggregates;
  private final AnalyticsPartitionCheckpointRepository partitions;
  private final AnalyticsProjectionService projections;
  private final AnalyticsDeadLetterService deadLetters;
  private final AnalyticsEnvelopeValidator validator;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public AnalyticsInboxProcessor(
      AnalyticsInboxRepository inboxes,
      AnalyticsSourceFactRepository sourceFacts,
      AnalyticsAggregateCheckpointRepository aggregates,
      AnalyticsPartitionCheckpointRepository partitions,
      AnalyticsProjectionService projections,
      AnalyticsDeadLetterService deadLetters,
      AnalyticsEnvelopeValidator validator,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.inboxes = inboxes;
    this.sourceFacts = sourceFacts;
    this.aggregates = aggregates;
    this.partitions = partitions;
    this.projections = projections;
    this.deadLetters = deadLetters;
    this.validator = validator;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  public Outcome process(AnalyticsValidatedEvent event) {
    for (int attempt = 0; attempt < 4; attempt++) {
      try {
        Outcome result = transactions.execute(status -> processOnce(event));
        if (result == null) throw new IllegalStateException("ANALYTICS_PROCESSING_OUTCOME_MISSING");
        return result;
      } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException failure) {
        if (attempt == 3) throw failure;
      }
    }
    throw new IllegalStateException("ANALYTICS_PROCESSING_RETRY_EXHAUSTED");
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void deadLetterAfterRetries(AnalyticsValidatedEvent event, Object key) {
    OffsetDateTime now = OffsetDateTime.now(clock);
    AnalyticsPartitionCheckpoint partition = partition(event, now);
    AnalyticsInbox inbox = inbox(event, now);
    if (!inbox.hasSameEnvelope(event.envelopeSha256())) {
      deadLetters.processingFailure(
          event, key, AnalyticsDltFailureCode.EVENT_IDENTITY_CONFLICT);
    } else if (!inbox.isTerminal()) {
      if (sourceFacts.findByEventId(event.eventId()).isEmpty()
          && sourceFacts
              .findBySourceTopicAndSourcePartitionAndSourceOffset(
                  event.topic(), event.partition(), event.offset())
              .isEmpty()) {
        sourceFacts.save(AnalyticsSourceFact.record(event, now));
      }
      inbox.deadLetter(now);
      deadLetters.processingFailure(event, key, AnalyticsDltFailureCode.PROCESSING_FAILED);
    }
    partition.advance(event.offset(), now);
  }

  private Outcome processOnce(AnalyticsValidatedEvent event) {
    OffsetDateTime now = OffsetDateTime.now(clock);
    AnalyticsPartitionCheckpoint partition = partition(event, now);
    AnalyticsInbox inbox = inbox(event, now);
    if (!inbox.hasSameEnvelope(event.envelopeSha256())) {
      deadLetters.processingFailure(
          event, event.aggregateId(), AnalyticsDltFailureCode.EVENT_IDENTITY_CONFLICT);
      if (!inbox.isTerminal()) inbox.deadLetter(now);
      partition.advance(event.offset(), now);
      return Outcome.EVENT_IDENTITY_CONFLICT;
    }
    if (inbox.isTerminal()) {
      partition.advance(event.offset(), now);
      return Outcome.DUPLICATE;
    }

    Optional<AnalyticsSourceFact> coordinate =
        sourceFacts.findBySourceTopicAndSourcePartitionAndSourceOffset(
            event.topic(), event.partition(), event.offset());
    if (coordinate.isPresent() && !coordinate.orElseThrow().getEventId().equals(event.eventId())) {
      deadLetters.processingFailure(
          event, event.aggregateId(), AnalyticsDltFailureCode.SOURCE_COORDINATE_CONFLICT);
      inbox.deadLetter(now);
      partition.advance(event.offset(), now);
      return Outcome.SOURCE_COORDINATE_CONFLICT;
    }

    Optional<AnalyticsSourceFact> sameVersion =
        sourceFacts
            .findAllByAggregateIdAndAggregateVersionOrderByEventIdAsc(
                event.aggregateId(), event.aggregateVersion())
            .stream()
            .findFirst();
    if (sameVersion.isPresent() && !sameVersion.orElseThrow().getEventId().equals(event.eventId())) {
      deadLetters.processingFailure(
          event, event.aggregateId(), AnalyticsDltFailureCode.EVENT_IDENTITY_CONFLICT);
      inbox.deadLetter(now);
      partition.advance(event.offset(), now);
      return Outcome.EVENT_IDENTITY_CONFLICT;
    }
    if (sourceFacts.findByEventId(event.eventId()).isEmpty()) {
      sourceFacts.save(AnalyticsSourceFact.record(event, now));
    }

    AnalyticsAggregateCheckpoint aggregate = aggregate(event, now);
    if (aggregate.isTerminallyBlocked()) {
      deadLetters.processingFailure(
          event, event.aggregateId(), AnalyticsDltFailureCode.MISSING_AGGREGATE_VERSION);
      inbox.deadLetter(now);
      partition.advance(event.offset(), now);
      return Outcome.GAP_DLT;
    }
    long expected = aggregate.nextExpectedVersion();
    if (event.aggregateVersion() < expected) {
      inbox.stale(now);
      partition.advance(event.offset(), now);
      return Outcome.STALE;
    }
    if (event.aggregateVersion() > expected) {
      aggregate.holdGap(event.aggregateVersion(), now);
      inbox.hold(now);
      partition.advance(event.offset(), now);
      return Outcome.VERSION_GAP;
    }

    projections.apply(event, now);
    aggregate.apply(event.aggregateVersion(), now);
    inbox.processed(now);
    drainHeld(aggregate, now);
    partition.advance(event.offset(), now);
    return Outcome.PROCESSED;
  }

  private AnalyticsInbox inbox(AnalyticsValidatedEvent event, OffsetDateTime now) {
    return inboxes
        .findById(event.eventId())
        .map(
            existing -> {
              existing.retry();
              return existing;
            })
        .orElseGet(
            () ->
                inboxes.save(
                    AnalyticsInbox.receive(
                        event.eventId(), event.envelopeSha256(), now)));
  }

  private AnalyticsPartitionCheckpoint partition(
      AnalyticsValidatedEvent event, OffsetDateTime now) {
    return partitions
        .findForUpdateByConsumerGroupAndSourceTopicAndSourcePartition(
            AnalyticsTopics.CONSUMER_GROUP, event.topic(), event.partition())
        .orElseGet(
            () ->
                partitions.save(
                    AnalyticsPartitionCheckpoint.start(
                        AnalyticsTopics.CONSUMER_GROUP,
                        event.topic(),
                        event.partition(),
                        now)));
  }

  private AnalyticsAggregateCheckpoint aggregate(
      AnalyticsValidatedEvent event, OffsetDateTime now) {
    return aggregates
        .findForUpdateByConsumerGroupAndSourceTopicAndAggregateTypeAndAggregateId(
            AnalyticsTopics.CONSUMER_GROUP,
            event.topic(),
            "GROUP_KPI_DAY",
            event.aggregateId())
        .orElseGet(
            () ->
                aggregates.save(
                    AnalyticsAggregateCheckpoint.start(
                        AnalyticsTopics.CONSUMER_GROUP,
                        event.topic(),
                        "GROUP_KPI_DAY",
                        event.aggregateId(),
                        now)));
  }

  private void drainHeld(AnalyticsAggregateCheckpoint checkpoint, OffsetDateTime now) {
    while (!checkpoint.isTerminallyBlocked()) {
      long next = checkpoint.nextExpectedVersion();
      Optional<AnalyticsSourceFact> candidate =
          sourceFacts
              .findAllByAggregateIdAndAggregateVersionOrderByEventIdAsc(
                  checkpoint.getAggregateId(), next)
              .stream()
              .findFirst();
      if (candidate.isEmpty()) return;
      AnalyticsSourceFact fact = candidate.orElseThrow();
      AnalyticsInbox inbox = inboxes.findById(fact.getEventId()).orElseThrow();
      if (inbox.getDecision() != AnalyticsInboxDecision.HELD) return;
      AnalyticsValidatedEvent recovered =
          validator.validate(
              fact.getSourceTopic(),
              fact.getSourcePartition(),
              fact.getSourceOffset(),
              fact.getAggregateId(),
              fact.getCanonicalEnvelope().getBytes(StandardCharsets.UTF_8));
      projections.apply(recovered, now);
      checkpoint.apply(recovered.aggregateVersion(), now);
      inbox.processed(now);
    }
  }

  public enum Outcome {
    PROCESSED,
    DUPLICATE,
    STALE,
    VERSION_GAP,
    GAP_DLT,
    EVENT_IDENTITY_CONFLICT,
    SOURCE_COORDINATE_CONFLICT
  }
}
