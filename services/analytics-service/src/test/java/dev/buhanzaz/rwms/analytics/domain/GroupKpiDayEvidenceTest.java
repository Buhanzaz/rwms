package dev.buhanzaz.rwms.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.analytics.eventing.AnalyticsEnvelopeValidatorTest;
import dev.buhanzaz.rwms.analytics.eventing.AnalyticsTopics;
import dev.buhanzaz.rwms.analytics.eventing.AnalyticsValidatedEvent;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GroupKpiDayEvidenceTest {
  @Test
  void aLaterSnapshotReplacesRawComponentsWithoutChangingIdentity() {
    UUID evidenceId = UUID.randomUUID();
    var validator =
        new dev.buhanzaz.rwms.analytics.eventing.AnalyticsEnvelopeValidator(new ObjectMapper());
    String firstEnvelope = AnalyticsEnvelopeValidatorTest.envelope(evidenceId, 0, "");
    AnalyticsValidatedEvent first =
        validator.validate(
            AnalyticsTopics.INPUT,
            0,
            1,
            evidenceId,
            firstEnvelope.getBytes(StandardCharsets.UTF_8));
    AnalyticsValidatedEvent second =
        validator.validate(
            AnalyticsTopics.INPUT,
            0,
            2,
            evidenceId,
            firstEnvelope
                .replace("\"aggregateVersion\":0", "\"aggregateVersion\":1")
                .replace("\"activeSeconds\":1200", "\"activeSeconds\":2400")
                .getBytes(StandardCharsets.UTF_8));

    GroupKpiDayEvidence evidence =
        GroupKpiDayEvidence.initial(first, OffsetDateTime.parse("2026-07-30T08:01:00Z"));
    evidence.replace(second, OffsetDateTime.parse("2026-07-30T08:02:00Z"));

    assertThat(evidence.getSourceAggregateVersion()).isEqualTo(1);
    assertThat(evidence.getActiveSeconds()).isEqualTo(2400);
    assertThat(evidence.getSourceEventId()).isEqualTo(second.eventId());
  }

  @Test
  void rejectsNonForwardReplacement() {
    UUID evidenceId = UUID.randomUUID();
    var validator =
        new dev.buhanzaz.rwms.analytics.eventing.AnalyticsEnvelopeValidator(new ObjectMapper());
    AnalyticsValidatedEvent event =
        validator.validate(
            AnalyticsTopics.INPUT,
            0,
            1,
            evidenceId,
            AnalyticsEnvelopeValidatorTest.envelope(evidenceId, 0, "")
                .getBytes(StandardCharsets.UTF_8));
    GroupKpiDayEvidence evidence =
        GroupKpiDayEvidence.initial(event, OffsetDateTime.parse("2026-07-30T08:01:00Z"));

    assertThatThrownBy(
            () -> evidence.replace(event, OffsetDateTime.parse("2026-07-30T08:02:00Z")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forward");
  }

  @Test
  void projectsOpenWorkingTimeOnlyUntilTheKnownTransition() {
    UUID evidenceId = UUID.randomUUID();
    var validator =
        new dev.buhanzaz.rwms.analytics.eventing.AnalyticsEnvelopeValidator(new ObjectMapper());
    AnalyticsValidatedEvent event =
        validator.validate(
            AnalyticsTopics.INPUT,
            0,
            1,
            evidenceId,
            AnalyticsEnvelopeValidatorTest.envelope(evidenceId, 0, "")
                .getBytes(StandardCharsets.UTF_8));
    GroupKpiDayEvidence evidence =
        GroupKpiDayEvidence.initial(event, OffsetDateTime.parse("2026-07-30T08:01:00Z"));

    assertThat(evidence.activeSecondsAt(OffsetDateTime.parse("2026-07-30T08:30:00Z")))
        .isEqualTo(3000);
    assertThat(evidence.activeSecondsAt(OffsetDateTime.parse("2026-07-30T10:00:00Z")))
        .isEqualTo(4800);
    assertThat(evidence.projectedAsOf(OffsetDateTime.parse("2026-07-30T10:00:00Z")))
        .isEqualTo(OffsetDateTime.parse("2026-07-30T09:00:00Z"));
  }
}
