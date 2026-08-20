package dev.buhanzaz.rwms.dossier.service;

import dev.buhanzaz.rwms.dossier.domain.DossierActiveGeneration;
import dev.buhanzaz.rwms.dossier.domain.DossierActivity;
import dev.buhanzaz.rwms.dossier.domain.DossierActivityCode;
import dev.buhanzaz.rwms.dossier.domain.DossierCabinPublicationHead;
import dev.buhanzaz.rwms.dossier.domain.DossierMediaProjection;
import dev.buhanzaz.rwms.dossier.domain.DossierMediaState;
import dev.buhanzaz.rwms.dossier.domain.DossierOutboxEvent;
import dev.buhanzaz.rwms.dossier.domain.DossierProducer;
import dev.buhanzaz.rwms.dossier.domain.DossierSubjectAssociation;
import dev.buhanzaz.rwms.dossier.domain.DossierUnlinkedFact;
import dev.buhanzaz.rwms.dossier.domain.DossierUnlinkedReason;
import dev.buhanzaz.rwms.dossier.eventing.DossierEnvelopeValidator;
import dev.buhanzaz.rwms.dossier.eventing.DossierEventHash;
import dev.buhanzaz.rwms.dossier.eventing.DossierValidatedEvent;
import dev.buhanzaz.rwms.dossier.eventing.DossierOutboundSchemaValidator;
import dev.buhanzaz.rwms.dossier.repository.DossierActiveGenerationRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierActivityRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierCabinPublicationHeadRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierMediaProjectionRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierOutboxEventRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierSourceFactRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierInboxRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierSubjectAssociationRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierUnlinkedFactRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/** Applies a validated fact to one projection generation without source-service calls. */
@Service
public class DossierProjectionService {
  private final DossierActiveGenerationRepository activeGenerations;
  private final DossierSubjectAssociationRepository associations;
  private final DossierActivityRepository activities;
  private final DossierMediaProjectionRepository media;
  private final DossierUnlinkedFactRepository unlinked;
  private final DossierCabinPublicationHeadRepository publicationHeads;
  private final DossierOutboxEventRepository outbox;
  private final DossierSourceFactRepository sourceFacts;
  private final DossierInboxRepository inboxes;
  private final DossierEnvelopeValidator validator;
  private final ObjectMapper mapper;
  private final DossierOutboundSchemaValidator outboundSchemas;
  private final DossierDeferredConflictService deferredConflicts;

  public DossierProjectionService(
      DossierActiveGenerationRepository activeGenerations,
      DossierSubjectAssociationRepository associations,
      DossierActivityRepository activities,
      DossierMediaProjectionRepository media,
      DossierUnlinkedFactRepository unlinked,
      DossierCabinPublicationHeadRepository publicationHeads,
      DossierOutboxEventRepository outbox,
      DossierSourceFactRepository sourceFacts,
      DossierInboxRepository inboxes,
      DossierEnvelopeValidator validator,
      DossierOutboundSchemaValidator outboundSchemas,
      DossierDeferredConflictService deferredConflicts,
      ObjectMapper mapper) {
    this.activeGenerations = activeGenerations;
    this.associations = associations;
    this.activities = activities;
    this.media = media;
    this.unlinked = unlinked;
    this.publicationHeads = publicationHeads;
    this.outbox = outbox;
    this.sourceFacts = sourceFacts;
    this.inboxes = inboxes;
    this.validator = validator;
    this.outboundSchemas = outboundSchemas;
    this.deferredConflicts = deferredConflicts;
    this.mapper = mapper;
  }

  public UUID activeGeneration(OffsetDateTime now) {
    return activeGenerations
        .findSharedByPointerName(DossierActiveGeneration.POINTER_NAME)
        .map(DossierActiveGeneration::getGenerationId)
        .orElseThrow(() -> new IllegalStateException("DOSSIER_ACTIVE_GENERATION_MISSING"));
  }

  public ProjectionOutcome apply(DossierValidatedEvent event, UUID generationId, OffsetDateTime now) {
    return apply(event, generationId, now, true, true);
  }

  public ProjectionOutcome replay(
      DossierValidatedEvent event, UUID generationId, OffsetDateTime now) {
    return apply(event, generationId, now, false, false);
  }

  private ProjectionOutcome apply(
      DossierValidatedEvent event,
      UUID generationId,
      OffsetDateTime now,
      boolean publish,
      boolean relinkDeferred) {
    DossierProducer producer = producer(event.producerCode());
    if ("inventory.finding.owner-proof.v1".equals(event.eventType())) {
      return ProjectionOutcome.JOURNALED;
    }
    if (!event.subjectCapable()) {
      recordUnlinked(event, generationId, producer, DossierUnlinkedReason.SUBJECT_NOT_PROVIDED);
      return ProjectionOutcome.UNLINKED;
    }

    ResolvedSubject subject = resolve(event, generationId, producer, now);
    if (subject == null) {
      recordUnlinked(event, generationId, producer, DossierUnlinkedReason.SUBJECT_NOT_YET_PROVEN);
      return ProjectionOutcome.DEFERRED;
    }

    if (isMediaLifecycle(event, producer)) {
      applyMedia(event, generationId, subject, now);
    }
    boolean projected = createActivity(event, generationId, producer, subject, now, publish);
    if (relinkDeferred
        && event.cabinId() != null
        && event.warehouseId() != null
        && (producer == DossierProducer.ASSET
            || (producer == DossierProducer.INVENTORY
                && "FINDING".equals(event.aggregateType())))) {
      projectDeferredDirect(
          producer,
          event.aggregateType(),
          event.aggregateId(),
          generationId,
          now,
          publish);
    }
    if (relinkDeferred
        && producer == DossierProducer.ASSET
        && event.cabinId() != null
        && event.warehouseId() != null) {
      projectDeferredCabinMedia(event.cabinId(), generationId, now, publish);
    }
    if (relinkDeferred
        && producer == DossierProducer.INVENTORY
        && "FINDING".equals(event.aggregateType())) {
      projectDeferredFacts(event.secondaryId(), generationId, now, publish);
    }
    return projected ? ProjectionOutcome.PROJECTED : ProjectionOutcome.JOURNALED;
  }

  private ResolvedSubject resolve(
      DossierValidatedEvent event,
      UUID generationId,
      DossierProducer producer,
      OffsetDateTime now) {
    if (producer == DossierProducer.MEDIA && event.cabinId() != null) {
      return findOwned(
          generationId,
          DossierProducer.ASSET,
          "RENTAL_ITEM",
          event.cabinId(),
          event.warehouseId());
    }
    if (event.cabinId() != null && event.warehouseId() != null) {
      String sourceType =
          producer == DossierProducer.INVENTORY ? "FINDING" : event.aggregateType();
      UUID sourceId =
          producer == DossierProducer.INVENTORY ? event.secondaryId() : event.aggregateId();
      reprove(generationId, producer, sourceType, sourceId, event.cabinId(), event.warehouseId(), event.eventId(), now);
      return new ResolvedSubject(event.cabinId(), event.warehouseId());
    }

    if (producer == DossierProducer.ASSET) {
      ResolvedSubject subject =
          find(generationId, DossierProducer.ASSET, "RENTAL_ITEM", event.aggregateId());
      if (subject == null
          || event.cabinId() == null
          || !subject.cabinId().equals(event.cabinId())) {
        throw new IllegalStateException("EVENT_IDENTITY_CONFLICT");
      }
      return subject;
    }
    if (producer == DossierProducer.INVENTORY) {
      return "PUBLICATION".equals(event.aggregateType())
          ? findOwned(
              generationId,
              DossierProducer.INVENTORY,
              "FINDING",
              event.secondaryId(),
              event.warehouseId())
          : find(generationId, DossierProducer.INVENTORY, "FINDING", event.secondaryId());
    }
    if (producer == DossierProducer.MEDIA) {
      return findOwned(
          generationId,
          DossierProducer.INVENTORY,
          "FINDING",
          event.secondaryId(),
          event.warehouseId());
    }
    return event.cabinId() == null || event.warehouseId() == null
        ? null
        : new ResolvedSubject(event.cabinId(), event.warehouseId());
  }

  private ResolvedSubject find(
      UUID generationId, DossierProducer producer, String type, UUID sourceId) {
    if (sourceId == null) return null;
    return associations
        .findByProducerAndSourceTypeAndSourceIdAndGenerationId(
            producer, type, sourceId, generationId)
        .map(value -> new ResolvedSubject(value.getCabinId(), value.getWarehouseId()))
        .orElse(null);
  }

  private ResolvedSubject findOwned(
      UUID generationId,
      DossierProducer producer,
      String type,
      UUID sourceId,
      UUID expectedWarehouseId) {
    ResolvedSubject subject = find(generationId, producer, type, sourceId);
    if (subject != null && !subject.warehouseId().equals(expectedWarehouseId)) {
      throw new IllegalStateException("EVENT_IDENTITY_CONFLICT");
    }
    return subject;
  }

  private void reprove(
      UUID generationId,
      DossierProducer producer,
      String type,
      UUID sourceId,
      UUID cabinId,
      UUID warehouseId,
      UUID eventId,
      OffsetDateTime now) {
    Optional<DossierSubjectAssociation> existing =
        associations.findForUpdateByProducerAndSourceTypeAndSourceIdAndGenerationId(
            producer, type, sourceId, generationId);
    if (existing.isPresent()) {
      DossierSubjectAssociation association = existing.orElseThrow();
      if (!association.getCabinId().equals(cabinId)) {
        throw new IllegalStateException("EVENT_IDENTITY_CONFLICT");
      }
      association.reprove(cabinId, warehouseId, eventId, now);
    } else {
      associations.save(
          DossierSubjectAssociation.prove(
              generationId, producer, type, sourceId, cabinId, warehouseId, eventId, now));
    }
  }

  private void applyMedia(
      DossierValidatedEvent event,
      UUID generationId,
      ResolvedSubject subject,
      OffsetDateTime now) {
    Optional<DossierMediaProjection> current =
        media.findForUpdateByCabinIdAndMediaIdAndGenerationId(
            subject.cabinId(), event.aggregateId(), generationId);
    UUID folderId =
        event.payload().has("folderId")
            ? UUID.fromString(event.payload().required("folderId").stringValue())
            : current.map(DossierMediaProjection::getFolderId).orElse(event.aggregateId());
    long mediaGeneration = event.payload().required("generation").longValue();
    DossierMediaState state =
        DossierMediaState.valueOf(event.payload().required("status").stringValue());
    if (current.isPresent()) {
      current
          .orElseThrow()
          .apply(
              folderId,
              mediaGeneration,
              event.aggregateVersion(),
              state,
              event.eventId(),
              now);
    } else {
      media.save(
          DossierMediaProjection.project(
              generationId,
              subject.cabinId(),
              subject.warehouseId(),
              event.aggregateId(),
              folderId,
              event.secondaryId(),
              mediaGeneration,
              event.aggregateVersion(),
              state,
              event.eventId(),
              now));
    }
  }

  private boolean createActivity(
      DossierValidatedEvent event,
      UUID generationId,
      DossierProducer producer,
      ResolvedSubject subject,
      OffsetDateTime now,
      boolean publish) {
    if (event.activityCode() == null
        || isDirectCabinCoverChange(event)
        || activities.existsBySourceEventIdAndCabinIdAndGenerationId(
            event.eventId(), subject.cabinId(), generationId)) {
      return false;
    }
    UUID activityId = DossierStableIdentity.activity(event.eventId(), subject.cabinId());
    DossierActivityCode code = DossierActivityCode.valueOf(event.activityCode());
    ActivityActor activityActor = activityActor(event, producer);
    DossierActivity activity =
        DossierActivity.project(
            activityId,
            generationId,
            event.eventId(),
            subject.cabinId(),
            subject.warehouseId(),
            code,
            producer,
            event.aggregateType(),
            event.aggregateId(),
            event.secondaryId(),
            offset(event.occurredAt()),
            offset(event.recordedAt()),
            activityActor.subjectId(),
            activityActor.principalType(),
            activityActor.profileRevision(),
            event.correlationId(),
            event.causationId(),
            now);
    activities.save(activity);
    if (publish) enqueue(activity, event, now);
    return true;
  }

  private void enqueue(
      DossierActivity activity, DossierValidatedEvent source, OffsetDateTime now) {
    UUID eventId = DossierStableIdentity.outbound(source.eventId(), activity.getCabinId());
    if (outbox.existsById(eventId)) return;
    DossierCabinPublicationHead head =
        publicationHeads
            .findForUpdateByCabinId(activity.getCabinId())
            .orElseGet(
                () ->
                    publicationHeads.save(
                        DossierCabinPublicationHead.start(activity.getCabinId(), now)));
    long aggregateVersion = head.next(now);
    Map<String, Object> sourceRef = new LinkedHashMap<>();
    sourceRef.put("producer", source.producer());
    sourceRef.put("aggregateType", source.aggregateType());
    sourceRef.put("aggregateId", source.aggregateId().toString());
    if (source.secondaryId() != null) sourceRef.put("secondaryId", source.secondaryId().toString());
    Map<String, Object> payload =
        Map.of(
            "activityId", activity.getActivityId().toString(),
            "cabinId", activity.getCabinId().toString(),
            "warehouseId", activity.getWarehouseId().toString(),
            "activityCode", activity.getActivityCode().name(),
            "sourceRef", sourceRef);
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("envelopeVersion", 2);
    envelope.put("eventId", eventId.toString());
    envelope.put("eventType", DossierOutboxEvent.EVENT_TYPE);
    envelope.put("eventVersion", 1);
    envelope.put("occurredAt", source.occurredAt());
    envelope.put("recordedAt", source.recordedAt());
    envelope.put("producer", "dossier-service");
    envelope.put("aggregateType", "CABIN");
    envelope.put("aggregateId", activity.getCabinId().toString());
    envelope.put("aggregateVersion", aggregateVersion);
    Map<String, Object> correlation = new LinkedHashMap<>();
    correlation.put("correlationId", source.correlationId().toString());
    correlation.put(
        "causationId", source.causationId() == null ? null : source.causationId().toString());
    envelope.put("correlation", correlation);
    envelope.put(
        "actorRef",
        activity.getActorSubjectId() == null
            ? null
            : actor(activity));
    envelope.put("payload", payload);
    String canonical = canonical(envelope);
    outboundSchemas.activity(canonical);
    outbox.save(
        DossierOutboxEvent.pending(
            eventId,
            activity.getCabinId(),
            aggregateVersion,
            source.eventId(),
            DossierEventHash.sha256(canonical),
            canonical,
            now));
  }

  private void projectDeferredFacts(
      UUID findingId, UUID generationId, OffsetDateTime now, boolean publish) {
    if (findingId == null) return;
    projectDeferred(
        sourceFacts
            .findAllByProducerAndAggregateTypeAndSubjectSecondaryIdOrderByAggregateVersionAscEventIdAsc(
                DossierProducer.MEDIA, "MEDIA", findingId),
        findingId,
        generationId,
        now,
        publish);
    projectDeferred(
        sourceFacts
            .findAllByProducerAndAggregateTypeAndSubjectSecondaryIdOrderByAggregateVersionAscEventIdAsc(
                DossierProducer.INVENTORY, "PUBLICATION", findingId),
        findingId,
        generationId,
        now,
        publish);
  }

  private void projectDeferredCabinMedia(
      UUID cabinId, UUID generationId, OffsetDateTime now, boolean publish) {
    for (var fact :
        sourceFacts
            .findAllByProducerAndSubjectSecondaryIdOrderByRecordedAtAscEventIdAsc(
                DossierProducer.MEDIA, cabinId)
            .stream()
            .filter(
                value ->
                    "MEDIA".equals(value.getAggregateType())
                        || "CABIN_PHOTO_LIBRARY".equals(value.getAggregateType()))
            .sorted(
                Comparator.comparingLong(
                        dev.buhanzaz.rwms.dossier.domain.DossierSourceFact::getAggregateVersion)
                    .thenComparing(
                        dev.buhanzaz.rwms.dossier.domain.DossierSourceFact::getEventId))
            .toList()) {
      if (inboxes
              .findById(fact.getEventId())
              .map(
                  value ->
                      value.getDecision()
                          != dev.buhanzaz.rwms.dossier.domain.DossierInboxDecision.PROCESSED)
              .orElse(true)) {
        continue;
      }
      DossierValidatedEvent deferred =
          validator.validate(
              fact.getSourceTopic(),
              fact.getSourcePartition(),
              fact.getSourceOffset(),
              fact.getAggregateId().toString(),
              fact.getCanonicalEnvelope().getBytes(StandardCharsets.UTF_8));
      if (!cabinId.equals(deferred.cabinId())) {
        continue;
      }
      ResolvedSubject subject =
          find(generationId, DossierProducer.ASSET, "RENTAL_ITEM", cabinId);
      if (subject == null) {
        continue;
      }
      if (!subject.warehouseId().equals(deferred.warehouseId())) {
        deferredConflicts.identityConflict(deferred, generationId, now);
        continue;
      }
      if (isMediaLifecycle(deferred, DossierProducer.MEDIA)) {
        applyMedia(deferred, generationId, subject, now);
      }
      createActivity(deferred, generationId, DossierProducer.MEDIA, subject, now, publish);
      unlinked
          .findBySourceEventIdAndGenerationId(deferred.eventId(), generationId)
          .ifPresent(value -> value.resolve(now));
    }
  }

  private void projectDeferredDirect(
      DossierProducer producer,
      String aggregateType,
      UUID aggregateId,
      UUID generationId,
      OffsetDateTime now,
      boolean publish) {
    for (var fact :
        sourceFacts
            .findAllByProducerAndAggregateTypeAndAggregateIdOrderByAggregateVersionAscEventIdAsc(
                producer, aggregateType, aggregateId)) {
      if (inboxes
              .findById(fact.getEventId())
              .map(value -> value.getDecision() != dev.buhanzaz.rwms.dossier.domain.DossierInboxDecision.PROCESSED)
              .orElse(true)) {
        continue;
      }
      DossierValidatedEvent deferred =
          validator.validate(
              fact.getSourceTopic(),
              fact.getSourcePartition(),
              fact.getSourceOffset(),
              fact.getAggregateId().toString(),
              fact.getCanonicalEnvelope().getBytes(StandardCharsets.UTF_8));
      UUID associationSourceId =
          producer == DossierProducer.INVENTORY
              ? deferred.secondaryId()
              : deferred.aggregateId();
      String associationType =
          producer == DossierProducer.INVENTORY ? "FINDING" : deferred.aggregateType();
      ResolvedSubject subject =
          find(generationId, producer, associationType, associationSourceId);
      if (subject == null) continue;
      createActivity(deferred, generationId, producer, subject, now, publish);
      unlinked
          .findBySourceEventIdAndGenerationId(deferred.eventId(), generationId)
          .ifPresent(value -> value.resolve(now));
    }
  }

  private void projectDeferred(
      java.util.List<dev.buhanzaz.rwms.dossier.domain.DossierSourceFact> facts,
      UUID findingId,
      UUID generationId,
      OffsetDateTime now,
      boolean publish) {
    for (var fact : facts) {
      if (inboxes
              .findById(fact.getEventId())
              .map(value -> value.getDecision() != dev.buhanzaz.rwms.dossier.domain.DossierInboxDecision.PROCESSED)
              .orElse(true)) {
        continue;
      }
      DossierValidatedEvent deferred =
          validator.validate(
              fact.getSourceTopic(),
              fact.getSourcePartition(),
              fact.getSourceOffset(),
              fact.getAggregateId().toString(),
              fact.getCanonicalEnvelope().getBytes(StandardCharsets.UTF_8));
      if (deferred.cabinId() == null && findingId.equals(deferred.secondaryId())) {
        ResolvedSubject subject =
            find(generationId, DossierProducer.INVENTORY, "FINDING", findingId);
        if (subject != null) {
          if (!subject.warehouseId().equals(deferred.warehouseId())) {
            deferredConflicts.identityConflict(deferred, generationId, now);
            continue;
          }
          DossierProducer producer = producer(deferred.producerCode());
          if (isMediaLifecycle(deferred, producer)) {
            applyMedia(deferred, generationId, subject, now);
          }
          createActivity(deferred, generationId, producer, subject, now, publish);
          unlinked
              .findBySourceEventIdAndGenerationId(deferred.eventId(), generationId)
              .ifPresent(value -> value.resolve(now));
        }
      }
    }
  }

  private void recordUnlinked(
      DossierValidatedEvent event,
      UUID generationId,
      DossierProducer producer,
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

  private String canonical(Object value) {
    return mapper
        .writer()
        .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .writeValueAsString(value);
  }

  private ActivityActor activityActor(DossierValidatedEvent event, DossierProducer producer) {
    if (event.actorSubjectId() != null) {
      return new ActivityActor(
          event.actorSubjectId(), event.actorPrincipalType(), event.actorProfileRevision());
    }
    if (producer != DossierProducer.MEDIA
        || !"media.media.ready.v1".equals(event.eventType())) {
      return ActivityActor.NONE;
    }
    return sourceFacts
        .findAllByProducerAndAggregateTypeAndAggregateIdOrderByAggregateVersionAscEventIdAsc(
            DossierProducer.MEDIA, event.aggregateType(), event.aggregateId())
        .stream()
        .filter(fact -> fact.getAggregateVersion() < event.aggregateVersion())
        .filter(fact -> "media.media.uploaded.v1".equals(fact.getEventType()))
        .filter(fact -> event.correlationId().equals(fact.getCorrelationId()))
        .filter(fact -> fact.getActorSubjectId() != null)
        .filter(
            fact ->
                inboxes
                    .findById(fact.getEventId())
                    .map(
                        inbox ->
                            inbox.getDecision()
                                == dev.buhanzaz.rwms.dossier.domain.DossierInboxDecision.PROCESSED)
                    .orElse(false))
        .max(
            Comparator.comparingLong(
                    dev.buhanzaz.rwms.dossier.domain.DossierSourceFact::getAggregateVersion)
                .thenComparing(
                    dev.buhanzaz.rwms.dossier.domain.DossierSourceFact::getEventId))
        .map(
            fact ->
                new ActivityActor(
                    fact.getActorSubjectId(),
                    fact.getActorPrincipalType(),
                    fact.getActorProfileRevision()))
        .orElse(ActivityActor.NONE);
  }

  private static Map<String, Object> actor(DossierActivity activity) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("subjectId", activity.getActorSubjectId().toString());
    value.put("principalType", activity.getActorPrincipalType());
    value.put("profileRevision", activity.getActorProfileRevision());
    return value;
  }

  private static OffsetDateTime offset(java.time.Instant value) {
    return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
  }

  private static DossierProducer producer(String producerCode) {
    return DossierProducer.valueOf(
        producerCode.replace('-', '_').toUpperCase(java.util.Locale.ROOT));
  }

  /** Returns whether a fact carries the media lifecycle shape consumed by the media projection. */
  private static boolean isMediaLifecycle(DossierValidatedEvent event, DossierProducer producer) {
    return producer == DossierProducer.MEDIA && "MEDIA".equals(event.aggregateType());
  }

  /** Keeps direct cabin-cover changes in the immutable journal without inventing a task history row. */
  private static boolean isDirectCabinCoverChange(DossierValidatedEvent event) {
    return "media.cabin.cover-changed.v1".equals(event.eventType())
        && event.payload().required("taskBoardEntryId").isNull();
  }

  private record ResolvedSubject(UUID cabinId, UUID warehouseId) {}

  private record ActivityActor(UUID subjectId, String principalType, String profileRevision) {
    private static final ActivityActor NONE = new ActivityActor(null, null, null);
  }

  public enum ProjectionOutcome { PROJECTED, JOURNALED, UNLINKED, DEFERRED }
}
