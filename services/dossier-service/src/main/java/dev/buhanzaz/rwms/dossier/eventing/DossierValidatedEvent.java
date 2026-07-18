package dev.buhanzaz.rwms.dossier.eventing;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Strictly validated, sanitized source fact ready for one local JPA transaction. */
public record DossierValidatedEvent(
    String topic,
    int partition,
    long offset,
    UUID eventId,
    String eventType,
    int eventVersion,
    Instant occurredAt,
    Instant recordedAt,
    String producer,
    String producerCode,
    String aggregateType,
    UUID aggregateId,
    long aggregateVersion,
    UUID correlationId,
    UUID causationId,
    UUID actorSubjectId,
    String actorPrincipalType,
    String actorProfileRevision,
    String payloadSha256,
    String canonicalEnvelope,
    JsonNode payload,
    UUID cabinId,
    UUID warehouseId,
    UUID secondaryId,
    String activityCode,
    boolean subjectCapable) {}
