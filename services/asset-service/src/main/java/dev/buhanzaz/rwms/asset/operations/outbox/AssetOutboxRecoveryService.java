package dev.buhanzaz.rwms.asset.operations.outbox;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.eventing.AssetEventPayloadPolicy;
import dev.buhanzaz.rwms.asset.eventing.AssetKafkaOutboxStore;
import dev.buhanzaz.rwms.asset.service.AssetChecksum;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.AssetNotFoundException;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A narrowly-scoped recovery command for one terminal outbox head. It never reconstructs or
 * changes the envelope: an administrator can only release an already-verified stored fact.
 */
@Service
public class AssetOutboxRecoveryService {
  private static final String ASSET_PRODUCER = "asset-service";
  private final AssetKafkaOutboxStore outbox;
  private final ObjectMapper strictMapper;
  private final AssetEventPayloadPolicy payloads;

  public AssetOutboxRecoveryService(
      AssetKafkaOutboxStore outbox, ObjectMapper mapper, AssetEventPayloadPolicy payloads) {
    this.outbox = outbox;
    this.strictMapper = mapper.rebuild()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();
    this.payloads = payloads;
  }

  @Transactional
  public AssetOutboxRequeueResponse requeue(
      UUID eventId, Long expectedReviewVersion, UUID reviewerSubjectId, String reason) {
    if (eventId == null || expectedReviewVersion == null || expectedReviewVersion < 0 || reviewerSubjectId == null) {
      throw new IllegalArgumentException("Outbox recovery request is invalid");
    }
    String reviewReason = normalizeReason(reason);
    String fingerprint = requestFingerprint(eventId, expectedReviewVersion, reviewerSubjectId, reviewReason);
    AssetKafkaOutboxStore.RecoveryCandidate candidate = outbox.lockForRecovery(eventId)
        .orElseThrow(() -> new AssetNotFoundException("Asset outbox event was not found"));

    var priorReview = outbox.recoveryFingerprint(eventId, expectedReviewVersion);
    if (priorReview.isPresent()) {
      if (!priorReview.get().equals(fingerprint)) {
        throw new AssetConflictException("Outbox recovery review version is already bound to another request");
      }
      return response(candidate);
    }
    if (candidate.reviewVersion() != expectedReviewVersion) {
      throw new AssetConflictException("Outbox recovery review version conflict");
    }
    if (!isTerminal(candidate.status())) {
      throw new AssetConflictException("Only terminal outbox events can be requeued");
    }
    if (!outbox.isCurrentOrderedHead(candidate)) {
      throw new AssetConflictException("Only the first unpublished outbox event of an aggregate can be requeued");
    }
    verifyStoredEnvelope(candidate);
    AssetKafkaOutboxStore.RecoveryTruth truth = outbox.requeueAfterReview(
            candidate, expectedReviewVersion, reviewerSubjectId, reviewReason, fingerprint)
        .orElseThrow(() -> new AssetConflictException("Outbox recovery changed concurrently"));
    return response(truth);
  }

  static String normalizeReason(String reason) {
    if (reason == null) {
      throw new IllegalArgumentException("Outbox recovery reason is required");
    }
    String normalized = reason.strip();
    int length = normalized.codePointCount(0, normalized.length());
    if (length < 1 || length > 2000 || normalized.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("Outbox recovery reason must contain 1 to 2000 trimmed characters");
    }
    return normalized;
  }

  static String requestFingerprint(
      UUID eventId, long expectedReviewVersion, UUID reviewerSubjectId, String normalizedReason) {
    String source = "asset-outbox-requeue-v1\n"
        + eventId + '\n'
        + expectedReviewVersion + '\n'
        + reviewerSubjectId + '\n'
        + normalizedReason;
    return AssetChecksum.sha256(source.getBytes(StandardCharsets.UTF_8));
  }

  private void verifyStoredEnvelope(AssetKafkaOutboxStore.RecoveryCandidate candidate) {
    if (!AssetChecksum.sha256(candidate.envelopeBody().getBytes(StandardCharsets.UTF_8))
        .equals(candidate.envelopeSha256())) {
      throw new AssetConflictException("Stored outbox envelope checksum is invalid");
    }
    try {
      JsonNode root = strictMapper.readTree(candidate.envelopeBody());
      requireEnvelopeShape(root);
      DomainEventEnvelopeV2<Map<String, Object>> envelope = strictMapper
          .readerFor(new TypeReference<DomainEventEnvelopeV2<Map<String, Object>>>() {})
          .readValue(root);
      AssetAggregateType topicType = AssetAggregateType.requireTopic(candidate.topic());
      AssetAggregateType aggregateType = AssetAggregateType.valueOf(candidate.aggregateType());
      UUID aggregateId = UUID.fromString(candidate.aggregateId());
      if (topicType != aggregateType
          || !candidate.eventId().equals(envelope.eventId())
          || !candidate.eventType().equals(envelope.eventType())
          || !ASSET_PRODUCER.equals(envelope.producer())
          || !candidate.aggregateType().equals(envelope.aggregateType())
          || !candidate.aggregateId().equals(envelope.aggregateId())
          || candidate.aggregateVersion() != envelope.aggregateVersion()) {
        throw new IllegalArgumentException("Stored outbox envelope does not match its immutable metadata");
      }
      payloads.validateNode(envelope.eventType(), aggregateType, aggregateId, root.get("payload"));
    } catch (RuntimeException exception) {
      if (exception instanceof AssetConflictException conflict) {
        throw conflict;
      }
      throw new AssetConflictException("Stored outbox envelope is not safe to requeue");
    }
  }

  private static void requireEnvelopeShape(JsonNode root) {
    requireObjectWithFields(root,
        "envelopeVersion", "eventId", "eventType", "eventVersion", "occurredAt", "recordedAt",
        "producer", "aggregateType", "aggregateId", "aggregateVersion", "correlation", "actorRef", "payload");
    requireObjectWithFields(root.get("correlation"), "correlationId", "causationId");
    JsonNode actor = root.get("actorRef");
    if (actor != null && !actor.isNull()) {
      requireObjectWithFields(actor, "subjectId", "principalType", "profileRevision");
    }
    if (root.get("payload") == null || !root.get("payload").isObject()) {
      throw new IllegalArgumentException("Stored outbox payload is not an object");
    }
  }

  private static void requireObjectWithFields(JsonNode node, String... fields) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("Stored outbox envelope has an invalid object");
    }
    for (String field : fields) {
      if (!node.has(field)) {
        throw new IllegalArgumentException("Stored outbox envelope is missing a required field");
      }
    }
  }

  private static boolean isTerminal(String status) {
    return "DLT".equals(status) || "QUARANTINED".equals(status);
  }

  private static AssetOutboxRequeueResponse response(AssetKafkaOutboxStore.RecoveryCandidate candidate) {
    return new AssetOutboxRequeueResponse(
        candidate.eventId(), candidate.reviewVersion(), candidate.status(), candidate.attemptCount(),
        candidate.lastErrorCode(), candidate.reviewedAt());
  }

  private static AssetOutboxRequeueResponse response(AssetKafkaOutboxStore.RecoveryTruth truth) {
    return new AssetOutboxRequeueResponse(
        truth.eventId(), truth.reviewVersion(), truth.status(), truth.attemptCount(),
        truth.lastErrorCode(), truth.reviewedAt());
  }
}
