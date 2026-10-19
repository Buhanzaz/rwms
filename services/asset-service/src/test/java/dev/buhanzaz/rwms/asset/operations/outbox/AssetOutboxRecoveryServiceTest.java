package dev.buhanzaz.rwms.asset.operations.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.eventing.AssetEventPayloadPolicy;
import dev.buhanzaz.rwms.asset.eventing.AssetKafkaOutboxStore;
import dev.buhanzaz.rwms.asset.service.AssetChecksum;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AssetOutboxRecoveryServiceTest {
  private final AssetKafkaOutboxStore outbox = mock(AssetKafkaOutboxStore.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final AssetOutboxRecoveryService service = new AssetOutboxRecoveryService(
      outbox, mapper, new AssetEventPayloadPolicy(mapper));

  @Test
  void requeuesOnlyAVerifiedTerminalHeadAndRecordsTheNormalizedReview() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    UUID reviewer = UUID.randomUUID();
    AssetKafkaOutboxStore.RecoveryCandidate candidate = candidate(eventId, aggregateId, 7, "DLT", null);
    Instant reviewedAt = Instant.parse("2026-08-05T11:12:13Z");
    when(outbox.lockForRecovery(eventId)).thenReturn(Optional.of(candidate));
    when(outbox.recoveryFingerprint(eventId, 7)).thenReturn(Optional.empty());
    when(outbox.isCurrentOrderedHead(candidate)).thenReturn(true);
    when(outbox.requeueAfterReview(eq(candidate), eq(7L), eq(reviewer), eq("manually verified"), anyString()))
        .thenReturn(Optional.of(new AssetKafkaOutboxStore.RecoveryTruth(
            eventId, 8, "PENDING", 0, null, reviewedAt)));

    AssetOutboxRequeueResponse response = service.requeue(eventId, 7L, reviewer, "  manually verified  ");

    assertThat(response).isEqualTo(new AssetOutboxRequeueResponse(
        eventId, candidate.aggregateType(), candidate.aggregateId(), candidate.aggregateVersion(),
        "PENDING", 8, reviewedAt));
    ArgumentCaptor<String> fingerprint = ArgumentCaptor.forClass(String.class);
    verify(outbox).requeueAfterReview(
        eq(candidate), eq(7L), eq(reviewer), eq("manually verified"), fingerprint.capture());
    assertThat(fingerprint.getValue()).matches("[0-9a-f]{64}");
  }

  @Test
  void returnsTheSameReceiptForAnExactAlreadyAppliedReviewWithoutRequeueingAgain() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    UUID reviewer = UUID.randomUUID();
    AssetKafkaOutboxStore.RecoveryCandidate current = candidate(eventId, aggregateId, 3, "PUBLISHED", null);
    String reason = "verified terminal delivery";
    when(outbox.lockForRecovery(eventId)).thenReturn(Optional.of(current));
    when(outbox.recoveryFingerprint(eventId, 2)).thenReturn(Optional.of(
        AssetOutboxRecoveryService.requestFingerprint(eventId, 2, reviewer, reason)));

    AssetOutboxRequeueResponse response = service.requeue(eventId, 2L, reviewer, reason);

    assertThat(response).isEqualTo(new AssetOutboxRequeueResponse(
        eventId, current.aggregateType(), current.aggregateId(), current.aggregateVersion(),
        "PENDING", current.reviewVersion(), current.reviewedAt()));
    verify(outbox, never()).isCurrentOrderedHead(current);
    verify(outbox, never()).requeueAfterReview(
        eq(current), eq(2L), eq(reviewer), eq(reason), anyString());
  }

  @Test
  void rejectsAStaleReviewVersionWhenItsFingerprintDoesNotMatchTheImmutableAudit() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    UUID reviewer = UUID.randomUUID();
    AssetKafkaOutboxStore.RecoveryCandidate current = candidate(eventId, aggregateId, 1, "PENDING", null);
    when(outbox.lockForRecovery(eventId)).thenReturn(Optional.of(current));
    when(outbox.recoveryFingerprint(eventId, 0)).thenReturn(Optional.of("0".repeat(64)));

    assertThatThrownBy(() -> service.requeue(eventId, 0L, reviewer, "different review"))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("already bound");

    verify(outbox, never()).requeueAfterReview(
        eq(current), eq(0L), eq(reviewer), eq("different review"), anyString());
  }

  @Test
  void rejectsTerminalRowsThatAreNotTheFirstUnpublishedAggregateFact() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    UUID reviewer = UUID.randomUUID();
    AssetKafkaOutboxStore.RecoveryCandidate candidate = candidate(eventId, aggregateId, 0, "QUARANTINED", null);
    when(outbox.lockForRecovery(eventId)).thenReturn(Optional.of(candidate));
    when(outbox.recoveryFingerprint(eventId, 0)).thenReturn(Optional.empty());
    when(outbox.isCurrentOrderedHead(candidate)).thenReturn(false);

    assertThatThrownBy(() -> service.requeue(eventId, 0L, reviewer, "must wait for predecessor"))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("first unpublished");

    verify(outbox, never()).requeueAfterReview(
        eq(candidate), eq(0L), eq(reviewer), eq("must wait for predecessor"), anyString());
  }

  @Test
  void rejectsATamperedEnvelopeBeforeAnyStateMutation() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    UUID reviewer = UUID.randomUUID();
    AssetKafkaOutboxStore.RecoveryCandidate candidate = candidate(eventId, aggregateId, 0, "DLT", "0".repeat(64));
    when(outbox.lockForRecovery(eventId)).thenReturn(Optional.of(candidate));
    when(outbox.recoveryFingerprint(eventId, 0)).thenReturn(Optional.empty());
    when(outbox.isCurrentOrderedHead(candidate)).thenReturn(true);

    assertThatThrownBy(() -> service.requeue(eventId, 0L, reviewer, "checksum failed"))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("checksum");

    verify(outbox, never()).requeueAfterReview(
        eq(candidate), eq(0L), eq(reviewer), eq("checksum failed"), anyString());
  }

  @Test
  void validatesTrimmedReasonBeforeReadingOrChangingTheOutbox() {
    UUID eventId = UUID.randomUUID();
    UUID reviewer = UUID.randomUUID();

    assertThatThrownBy(() -> service.requeue(eventId, 0L, reviewer, " \t "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.requeue(eventId, 0L, reviewer, "x".repeat(2001)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.requeue(eventId, 0L, reviewer, "review\0reason"))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(outbox);
  }

  private AssetKafkaOutboxStore.RecoveryCandidate candidate(
      UUID eventId, UUID aggregateId, long reviewVersion, String status, String overrideHash) throws Exception {
    Instant recordedAt = Instant.parse("2026-08-05T10:00:00Z");
    DomainEventEnvelopeV2<Map<String, Object>> envelope = new DomainEventEnvelopeV2<>(
        2,
        eventId,
        "asset.rental-item.created.v1",
        1,
        recordedAt,
        recordedAt,
        "asset-service",
        AssetAggregateType.RENTAL_ITEM.name(),
        aggregateId.toString(),
        0,
        new CorrelationContext(UUID.randomUUID(), null),
        null,
        Map.of(
            "rentalItemId", aggregateId.toString(),
            "warehouseId", UUID.randomUUID().toString(),
            "status", "FREE",
            "numberSha256", "a".repeat(64)));
    ObjectNode root = (ObjectNode) mapper.valueToTree(envelope);
    root.putNull("actorRef");
    ((ObjectNode) root.get("correlation")).putNull("causationId");
    String body = mapper.writeValueAsString(root);
    return new AssetKafkaOutboxStore.RecoveryCandidate(
        eventId,
        AssetAggregateType.RENTAL_ITEM.name(),
        aggregateId.toString(),
        0,
        "asset.rental-item.created.v1",
        AssetAggregateType.RENTAL_ITEM.topic(),
        body,
        overrideHash == null ? AssetChecksum.sha256(body.getBytes(StandardCharsets.UTF_8)) : overrideHash,
        status,
        4,
        "PUBLISH_FAILED",
        reviewVersion,
        reviewVersion == 0 ? null : Instant.parse("2026-08-05T10:01:00Z"));
  }
}
