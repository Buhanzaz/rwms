package dev.buhanzaz.rwms.analytics.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

public class AnalyticsEnvelopeValidatorTest {
  private final AnalyticsEnvelopeValidator validator =
      new AnalyticsEnvelopeValidator(new ObjectMapper());

  @Test
  void acceptsExactKpiDaySnapshot() {
    UUID aggregateId = UUID.randomUUID();
    AnalyticsValidatedEvent event =
        validator.validate(
            AnalyticsTopics.INPUT,
            2,
            17,
            aggregateId.toString(),
            envelope(aggregateId, 0, "").getBytes(StandardCharsets.UTF_8));

    assertThat(event.aggregateId()).isEqualTo(aggregateId);
    assertThat(event.payload().completedBudgetSeconds()).isEqualTo(3600);
    assertThat(event.payload().openState()).isEqualTo(KpiOpenState.WORKING);
  }

  @Test
  void rejectsUnknownPayloadFieldsAndIdentityMismatch() {
    UUID aggregateId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                validator.validate(
                    AnalyticsTopics.INPUT,
                    0,
                    0,
                    aggregateId,
                    envelope(aggregateId, 0, ",\"secret\":\"x\"")
                        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(AnalyticsValidationException.class)
        .hasMessage("SOURCE_SCHEMA_REJECTED");

    assertThatThrownBy(
            () ->
                validator.validate(
                    AnalyticsTopics.INPUT,
                    0,
                    0,
                    UUID.randomUUID(),
                    envelope(aggregateId, 0, "").getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(AnalyticsValidationException.class)
        .hasMessage("SOURCE_RECORD_KEY_MISMATCH");
  }

  @Test
  void rejectsEarnedSecondsAboveBudgetAndInconsistentOpenState() {
    UUID aggregateId = UUID.randomUUID();
    String invalid =
        envelope(aggregateId, 0, "")
            .replace("\"earnedRemainingSeconds\":1800", "\"earnedRemainingSeconds\":3601");

    assertThatThrownBy(
            () ->
                validator.validate(
                    AnalyticsTopics.INPUT,
                    0,
                    0,
                    aggregateId,
                    invalid.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(AnalyticsValidationException.class)
        .hasMessage("SOURCE_PAYLOAD_REJECTED");

    String inconsistent =
        envelope(aggregateId, 0, "").replace("\"openState\":\"WORKING\"", "\"openState\":null");
    assertThatThrownBy(
            () ->
                validator.validate(
                    AnalyticsTopics.INPUT,
                    0,
                    0,
                    aggregateId,
                    inconsistent.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(AnalyticsValidationException.class)
        .hasMessage("SOURCE_PAYLOAD_REJECTED");
  }

  public static String envelope(UUID aggregateId, long version, String extraPayload) {
    return """
        {
          "envelopeVersion":2,
          "eventId":"%s",
          "eventType":"task-board.group-kpi-day.changed.v1",
          "eventVersion":1,
          "occurredAt":"2026-07-30T08:00:00Z",
          "recordedAt":"2026-07-30T08:00:01Z",
          "producer":"task-board-service",
          "aggregateType":"GROUP_KPI_DAY",
          "aggregateId":"%s",
          "aggregateVersion":%d,
          "correlation":{"correlationId":"%s","causationId":null},
          "actorRef":null,
          "payload":{
            "evidenceId":"%s",
            "warehouseId":"%s",
            "workerGroupId":"%s",
            "localDate":"2026-07-30",
            "dataAvailableFrom":"2026-07-01",
            "formulaVersion":"kpi-v1",
            "completedBudgetSeconds":3600,
            "earnedRemainingSeconds":1800,
            "activeSeconds":1200,
            "penalizedIdleSeconds":600,
            "completedTaskCount":1,
            "openState":"WORKING",
            "openStateStartedAt":"2026-07-30T07:30:00Z",
            "penaltyStartsAt":null,
            "nextTransitionAt":"2026-07-30T09:00:00Z",
            "asOf":"2026-07-30T08:00:00Z"%s
          }
        }
        """
        .formatted(
            UUID.randomUUID(),
            aggregateId,
            version,
            UUID.randomUUID(),
            aggregateId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            extraPayload);
  }
}
