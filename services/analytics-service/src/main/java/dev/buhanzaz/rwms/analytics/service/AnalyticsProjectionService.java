package dev.buhanzaz.rwms.analytics.service;

import dev.buhanzaz.rwms.analytics.domain.GroupKpiDayEvidence;
import dev.buhanzaz.rwms.analytics.eventing.AnalyticsValidatedEvent;
import dev.buhanzaz.rwms.analytics.repository.GroupKpiDayEvidenceRepository;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;

/** Projects a contiguous validated KPI fact into daily evidence without taking ownership of task-board truth. */
@Service
public class AnalyticsProjectionService {
  private final GroupKpiDayEvidenceRepository evidence;

  public AnalyticsProjectionService(GroupKpiDayEvidenceRepository evidence) {
    this.evidence = evidence;
  }

  public void apply(AnalyticsValidatedEvent event, OffsetDateTime now) {
    evidence
        .findById(event.aggregateId())
        .ifPresentOrElse(
            current -> current.replace(event, now),
            () -> evidence.save(GroupKpiDayEvidence.initial(event, now)));
  }
}
