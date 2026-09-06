package dev.buhanzaz.rwms.inventory.eventing;

import dev.buhanzaz.rwms.inventory.service.InventoryException;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Releases one intact terminal outbox or sanitized DLT record after a versioned review. */
@Service
public class InventoryEventingRecoveryService {
  private static final Set<String> ENVELOPE_FIELDS =
      Set.of(
          "envelopeVersion",
          "eventId",
          "eventType",
          "eventVersion",
          "occurredAt",
          "recordedAt",
          "producer",
          "aggregateType",
          "aggregateId",
          "aggregateVersion",
          "correlation",
          "actorRef",
          "payload");
  private static final Set<String> DLT_FIELDS =
      Set.of("failureCode", "messageSha256", "recordedAt");

  private final InventoryOutboxStore outbox;
  private final InventoryDeadLetterRelayStore deadLetters;
  private final ObjectMapper strictMapper;

  public InventoryEventingRecoveryService(
      InventoryOutboxStore outbox, InventoryDeadLetterRelayStore deadLetters, ObjectMapper mapper) {
    this.outbox = outbox;
    this.deadLetters = deadLetters;
    this.strictMapper =
        mapper
            .rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
  }

  @Transactional
  public InventoryEventingRecoveryResponse requeueOutbox(
      UUID eventId, Long expectedReviewVersion, UUID reviewerSubjectId, String reason) {
    String reviewReason = validate(eventId, expectedReviewVersion, reviewerSubjectId, reason);
    String fingerprint =
        fingerprint("OUTBOX", eventId, expectedReviewVersion, reviewerSubjectId, reviewReason);
    InventoryOutboxStore.RecoveryCandidate candidate =
        outbox
            .lockForRecovery(eventId)
            .orElseThrow(() -> InventoryException.notFound("Inventory outbox event was not found"));
    var priorReview = outbox.recoveryReview(eventId, expectedReviewVersion);
    if (priorReview.isPresent()) {
      requireFingerprint(priorReview.orElseThrow().requestFingerprint(), fingerprint);
      return response(
          "OUTBOX",
          eventId,
          priorReview.orElseThrow().reviewVersion(),
          priorReview.orElseThrow().reviewedAt());
    }
    if (candidate.reviewVersion() != expectedReviewVersion) {
      throw InventoryException.conflict("Outbox recovery review version conflict");
    }
    if (!Set.of("DLT", "QUARANTINED").contains(candidate.status())) {
      throw InventoryException.conflict("Only a terminal outbox event can be requeued");
    }
    if (!outbox.isCurrentOrderedHead(candidate)) {
      throw InventoryException.conflict(
          "Only the first unpublished outbox event of an aggregate can be requeued");
    }
    verifyOutbox(candidate);
    InventoryOutboxStore.RecoveryTruth truth =
        outbox
            .requeueAfterReview(
                candidate, expectedReviewVersion, reviewerSubjectId, reviewReason, fingerprint)
            .orElseThrow(() -> InventoryException.conflict("Outbox recovery changed concurrently"));
    return response("OUTBOX", truth.eventId(), truth.reviewVersion(), truth.reviewedAt());
  }

  @Transactional
  public InventoryEventingRecoveryResponse requeueDeadLetter(
      UUID dltId, Long expectedReviewVersion, UUID reviewerSubjectId, String reason) {
    String reviewReason = validate(dltId, expectedReviewVersion, reviewerSubjectId, reason);
    String fingerprint =
        fingerprint("DLT", dltId, expectedReviewVersion, reviewerSubjectId, reviewReason);
    InventoryDeadLetterRelayStore.RecoveryCandidate candidate =
        deadLetters
            .lockForRecovery(dltId)
            .orElseThrow(
                () -> InventoryException.notFound("Inventory dead-letter record was not found"));
    var priorReview = deadLetters.recoveryReview(dltId, expectedReviewVersion);
    if (priorReview.isPresent()) {
      requireFingerprint(priorReview.orElseThrow().requestFingerprint(), fingerprint);
      return response(
          "DLT",
          dltId,
          priorReview.orElseThrow().reviewVersion(),
          priorReview.orElseThrow().reviewedAt());
    }
    if (candidate.reviewVersion() != expectedReviewVersion) {
      throw InventoryException.conflict("Dead-letter recovery review version conflict");
    }
    if (!"FAILED".equals(candidate.status())) {
      throw InventoryException.conflict("Only a terminal dead-letter record can be requeued");
    }
    verifyDeadLetter(candidate);
    InventoryDeadLetterRelayStore.RecoveryTruth truth =
        deadLetters
            .requeueAfterReview(
                candidate, expectedReviewVersion, reviewerSubjectId, reviewReason, fingerprint)
            .orElseThrow(
                () -> InventoryException.conflict("Dead-letter recovery changed concurrently"));
    return response("DLT", truth.id(), truth.reviewVersion(), truth.reviewedAt());
  }

  private void verifyOutbox(InventoryOutboxStore.RecoveryCandidate candidate) {
    if (!InventoryEventChecksum.sha256(candidate.envelopeBody())
        .equals(candidate.envelopeSha256())) {
      throw InventoryException.conflict("Stored outbox envelope checksum is invalid");
    }
    try {
      JsonNode root = strictMapper.readTree(candidate.envelopeBody());
      requireExactFields(root, ENVELOPE_FIELDS);
      DomainEventEnvelopeV2<Map<String, Object>> envelope =
          strictMapper
              .readerFor(new TypeReference<DomainEventEnvelopeV2<Map<String, Object>>>() {})
              .readValue(root);
      if (!candidate.eventId().equals(envelope.eventId())
          || !candidate.eventType().equals(envelope.eventType())
          || !"inventory-service".equals(envelope.producer())
          || !candidate.aggregateType().equals(envelope.aggregateType())
          || !candidate.aggregateId().equals(envelope.aggregateId())
          || candidate.aggregateVersion() != envelope.aggregateVersion()) {
        throw new IllegalArgumentException("Stored outbox envelope metadata changed");
      }
      InventoryEventStore.requireFamily(
          candidate.aggregateType(), candidate.eventType(), candidate.topic());
      InventoryEventStore.rejectForbidden(root.path("payload"));
    } catch (RuntimeException exception) {
      if (exception instanceof InventoryException inventoryException) {
        throw inventoryException;
      }
      throw InventoryException.conflict("Stored outbox envelope is not safe to requeue");
    }
  }

  private void verifyDeadLetter(InventoryDeadLetterRelayStore.RecoveryCandidate candidate) {
    if (!"rwms.inventory.dlt.v1".equals(candidate.destination())
        || !InventoryEventChecksum.sha256(candidate.body()).equals(candidate.bodySha256())) {
      throw InventoryException.conflict("Stored dead-letter body checksum is invalid");
    }
    try {
      JsonNode root = strictMapper.readTree(candidate.body());
      requireExactFields(root, DLT_FIELDS);
      if (!candidate.failureCode().equals(root.path("failureCode").stringValue())
          || !candidate.messageSha256().equals(root.path("messageSha256").stringValue())) {
        throw new IllegalArgumentException("Stored dead-letter metadata changed");
      }
      OffsetDateTime.parse(root.path("recordedAt").stringValue());
    } catch (RuntimeException exception) {
      if (exception instanceof InventoryException inventoryException) {
        throw inventoryException;
      }
      throw InventoryException.conflict("Stored dead-letter body is not safe to requeue");
    }
  }

  private static String validate(
      UUID recordId, Long expectedReviewVersion, UUID reviewerSubjectId, String reason) {
    if (recordId == null
        || expectedReviewVersion == null
        || expectedReviewVersion < 0
        || reviewerSubjectId == null
        || reason == null) {
      throw new IllegalArgumentException("Eventing recovery request is invalid");
    }
    if (reason.codePointCount(0, reason.length()) > 2000) {
      throw new IllegalArgumentException(
          "Eventing recovery reason must contain 1 to 2000 trimmed characters");
    }
    String normalized = reason.strip();
    int length = normalized.codePointCount(0, normalized.length());
    if (length < 1 || length > 2000 || normalized.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(
          "Eventing recovery reason must contain 1 to 2000 trimmed characters");
    }
    return normalized;
  }

  private static String fingerprint(
      String kind,
      UUID recordId,
      long expectedReviewVersion,
      UUID reviewerSubjectId,
      String reason) {
    String source =
        "inventory-eventing-requeue-v1\n"
            + kind
            + '\n'
            + recordId
            + '\n'
            + expectedReviewVersion
            + '\n'
            + reviewerSubjectId
            + '\n'
            + reason;
    return InventoryEventChecksum.sha256(source.getBytes(StandardCharsets.UTF_8));
  }

  private static void requireFingerprint(String stored, String submitted) {
    if (!stored.equals(submitted)) {
      throw InventoryException.conflict(
          "Eventing recovery review version is already bound to another request");
    }
  }

  private static void requireExactFields(JsonNode node, Set<String> expected) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("Stored eventing body is not an object");
    }
    Set<String> actual = new LinkedHashSet<>();
    node.propertyNames().forEach(actual::add);
    if (!actual.equals(expected)) {
      throw new IllegalArgumentException("Stored eventing body fields changed");
    }
  }

  private static InventoryEventingRecoveryResponse response(
      String kind, UUID id, long reviewVersion, OffsetDateTime reviewedAt) {
    return new InventoryEventingRecoveryResponse(kind, id, "PENDING", reviewVersion, reviewedAt);
  }
}
