package dev.buhanzaz.rwms.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.analytics.domain.GroupKpiDayEvidence;
import dev.buhanzaz.rwms.analytics.eventing.AnalyticsEnvelopeValidator;
import dev.buhanzaz.rwms.analytics.eventing.AnalyticsEnvelopeValidatorTest;
import dev.buhanzaz.rwms.analytics.eventing.AnalyticsTopics;
import dev.buhanzaz.rwms.analytics.eventing.AnalyticsValidatedEvent;
import dev.buhanzaz.rwms.analytics.repository.GroupKpiDayEvidenceRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class AnalyticsProjectionServiceTest {
  @Test
  void createsThenReplacesTheSingleDailySnapshot() {
    UUID evidenceId = UUID.randomUUID();
    AnalyticsEnvelopeValidator validator = new AnalyticsEnvelopeValidator(new ObjectMapper());
    String envelope = AnalyticsEnvelopeValidatorTest.envelope(evidenceId, 0, "");
    AnalyticsValidatedEvent first =
        validator.validate(
            AnalyticsTopics.INPUT, 0, 0, evidenceId, envelope.getBytes(StandardCharsets.UTF_8));
    AnalyticsValidatedEvent second =
        validator.validate(
            AnalyticsTopics.INPUT,
            0,
            1,
            evidenceId,
            envelope
                .replace("\"aggregateVersion\":0", "\"aggregateVersion\":1")
                .replace("\"activeSeconds\":1200", "\"activeSeconds\":2400")
                .getBytes(StandardCharsets.UTF_8));
    GroupKpiDayEvidenceRepository repository = mock(GroupKpiDayEvidenceRepository.class);
    when(repository.findById(evidenceId)).thenReturn(Optional.empty());
    AnalyticsProjectionService service = new AnalyticsProjectionService(repository);
    OffsetDateTime now = OffsetDateTime.parse("2026-07-30T08:10:00Z");

    service.apply(first, now);

    ArgumentCaptor<GroupKpiDayEvidence> captor =
        ArgumentCaptor.forClass(GroupKpiDayEvidence.class);
    verify(repository).save(captor.capture());
    GroupKpiDayEvidence persisted = captor.getValue();
    when(repository.findById(evidenceId)).thenReturn(Optional.of(persisted));
    service.apply(second, now.plusMinutes(1));

    assertThat(persisted.getActiveSeconds()).isEqualTo(2400);
    assertThat(persisted.getSourceAggregateVersion()).isEqualTo(1);
  }
}
