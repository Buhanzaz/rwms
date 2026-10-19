package dev.buhanzaz.rwms.dossier.service;

import dev.buhanzaz.rwms.dossier.domain.DossierAggregateBlockReason;
import dev.buhanzaz.rwms.dossier.domain.DossierAggregateCheckpoint;
import dev.buhanzaz.rwms.dossier.domain.DossierDltFailureCode;
import dev.buhanzaz.rwms.dossier.domain.DossierProducer;
import dev.buhanzaz.rwms.dossier.domain.DossierUnlinkedFact;
import dev.buhanzaz.rwms.dossier.domain.DossierUnlinkedReason;
import dev.buhanzaz.rwms.dossier.eventing.DossierDeadLetterService;
import dev.buhanzaz.rwms.dossier.eventing.DossierSourceTopics;
import dev.buhanzaz.rwms.dossier.eventing.DossierValidatedEvent;
import dev.buhanzaz.rwms.dossier.repository.DossierAggregateCheckpointRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierInboxRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierUnlinkedFactRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Terminalizes a previously deferred fact whose warehouse owner proof is contradictory. */
@Service
public class DossierDeferredConflictService {
  private final DossierInboxRepository inboxes;
  private final DossierAggregateCheckpointRepository aggregates;
  private final DossierUnlinkedFactRepository unlinked;
  private final DossierVisibilityCoverageResolver visibilityCoverage;
  private final DossierDeadLetterService deadLetters;

  public DossierDeferredConflictService(
      DossierInboxRepository inboxes,
      DossierAggregateCheckpointRepository aggregates,
      DossierUnlinkedFactRepository unlinked,
      DossierVisibilityCoverageResolver visibilityCoverage,
      DossierDeadLetterService deadLetters) {
    this.inboxes = inboxes;
    this.aggregates = aggregates;
    this.unlinked = unlinked;
    this.visibilityCoverage = visibilityCoverage;
    this.deadLetters = deadLetters;
  }

  /**
   * Terminalizes deferred evidence inside the caller's active-generation shared lock, retaining
   * exact cabin coverage when an existing subject association proves it.
   */
  public void identityConflict(
      DossierValidatedEvent event, UUID generationId, OffsetDateTime now) {
    DossierProducer producer = producer(event.producerCode());
    UUID subjectCabinId = visibilityCoverage.provenSubjectCabin(event, producer, generationId);
    var inbox = inboxes.findById(event.eventId()).orElseThrow();
    if (inbox.getDecision() == dev.buhanzaz.rwms.dossier.domain.DossierInboxDecision.DLT) return;
    inbox.deadLetterProcessedConflict(now);
    DossierAggregateCheckpoint checkpoint =
        aggregates
            .findForUpdateByConsumerGroupAndProducerAndSourceTopicAndAggregateTypeAndAggregateId(
                DossierSourceTopics.CONSUMER_GROUP,
                producer,
                event.topic(),
                event.aggregateType(),
                event.aggregateId())
            .orElseThrow();
    checkpoint.blockConflict(DossierAggregateBlockReason.EVENT_IDENTITY_CONFLICT, now);
    if (unlinked.findBySourceEventIdAndGenerationId(event.eventId(), generationId).isEmpty()) {
      unlinked.save(
          DossierUnlinkedFact.record(
              generationId,
              event.eventId(),
              subjectCabinId,
              DossierUnlinkedReason.AGGREGATE_QUARANTINED,
              producer,
              event.aggregateType(),
              event.aggregateId(),
              event.payloadSha256(),
              OffsetDateTime.ofInstant(event.recordedAt(), ZoneOffset.UTC)));
    }
    deadLetters.processingFailure(
        event,
        event.aggregateId(),
        DossierDltFailureCode.EVENT_IDENTITY_CONFLICT,
        generationId,
        subjectCabinId);
  }

  private static DossierProducer producer(String producerCode) {
    return DossierProducer.valueOf(
        producerCode.replace('-', '_').toUpperCase(Locale.ROOT));
  }
}
