package dev.buhanzaz.rwms.analytics.eventing;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Immutable normalized result of strict schema, envelope, payload and Kafka-key validation for one KPI source record. */
public record AnalyticsValidatedEvent(
    String topic,
    int partition,
    long offset,
    UUID eventId,
    UUID aggregateId,
    long aggregateVersion,
    OffsetDateTime occurredAt,
    OffsetDateTime recordedAt,
    UUID correlationId,
    UUID causationId,
    String envelopeSha256,
    String canonicalEnvelope,
    KpiDayPayload payload) {
  public record KpiDayPayload(
      UUID evidenceId,
      UUID warehouseId,
      UUID workerGroupId,
      LocalDate localDate,
      LocalDate dataAvailableFrom,
      String formulaVersion,
      long completedBudgetSeconds,
      long earnedRemainingSeconds,
      long activeSeconds,
      long penalizedIdleSeconds,
      long completedTaskCount,
      KpiOpenState openState,
      OffsetDateTime openStateStartedAt,
      OffsetDateTime penaltyStartsAt,
      OffsetDateTime nextTransitionAt,
      OffsetDateTime asOf) {}
}
