package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.domain.GroupKpiDayState;
import dev.buhanzaz.rwms.taskboard.repository.GroupKpiDayStateRepository;
import dev.buhanzaz.rwms.taskboard.repository.WarehouseKpiSettingsRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerGroupRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GroupKpiEvidenceScheduler {
  private static final Logger log = LoggerFactory.getLogger(GroupKpiEvidenceScheduler.class);

  private final GroupKpiDayStateRepository days;
  private final WarehouseKpiSettingsRepository settings;
  private final WorkerGroupRepository groups;
  private final GroupKpiEvidenceService evidence;

  public GroupKpiEvidenceScheduler(
      GroupKpiDayStateRepository days,
      WarehouseKpiSettingsRepository settings,
      WorkerGroupRepository groups,
      GroupKpiEvidenceService evidence) {
    this.days = days;
    this.settings = settings;
    this.groups = groups;
    this.evidence = evidence;
  }

  @Scheduled(fixedDelayString = "${rwms.kpi.evidence-refresh-delay:PT15S}")
  public void refreshDueEvidence() {
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    var targets = new LinkedHashSet<GroupRef>();
    for (GroupKpiDayState day :
        days.findAllByNextTransitionAtLessThanEqualOrderByNextTransitionAtAsc(at)) {
      targets.add(new GroupRef(day.getWarehouseId(), day.getWorkerGroupId()));
    }
    settings.findAll().stream()
        .filter(value -> value.getDataAvailableFrom() != null)
        .forEach(
            warehouse ->
                groups
                    .findAllByWarehouseIdAndActiveTrueOrderByNameAsc(warehouse.getWarehouseId())
                    .stream()
                    .filter(
                        group ->
                            !evidence.hasDay(
                                warehouse.getWarehouseId(), group.getId(), at))
                    .map(
                        group ->
                            new GroupRef(warehouse.getWarehouseId(), group.getId()))
                    .forEach(targets::add));

    for (GroupRef target : targets) {
      try {
        evidence.refreshDueGroup(target.warehouseId(), target.groupId(), at);
      } catch (RuntimeException exception) {
        log.warn(
            "Could not refresh KPI evidence for warehouse {} group {}",
            target.warehouseId(),
            target.groupId(),
            exception);
      }
    }
  }

  private record GroupRef(UUID warehouseId, UUID groupId) {}
}
