package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.domain.GroupKpiDayState;
import dev.buhanzaz.rwms.taskboard.repository.GroupKpiDayStateRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerGroupRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically advances due KPI accounting transitions and publishes refreshed daily evidence. */
@Component
public class GroupKpiEvidenceScheduler {
  private static final Logger log = LoggerFactory.getLogger(GroupKpiEvidenceScheduler.class);

  private final GroupKpiDayStateRepository days;
  private final WorkerGroupRepository groups;
  private final GroupKpiEvidenceService evidence;
  private final WarehouseKpiClock clock;

  public GroupKpiEvidenceScheduler(
      GroupKpiDayStateRepository days,
      WorkerGroupRepository groups,
      GroupKpiEvidenceService evidence,
      WarehouseKpiClock clock) {
    this.days = days;
    this.groups = groups;
    this.evidence = evidence;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${rwms.kpi.evidence-refresh-delay:PT15S}")
  public void refreshDueEvidence() {
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    var targets = new LinkedHashSet<GroupRef>();
    for (GroupKpiDayState day :
        days.findAllByNextTransitionAtLessThanEqualOrderByNextTransitionAtAsc(at)) {
      targets.add(new GroupRef(day.getWarehouseId(), day.getWorkerGroupId()));
    }
    var activeGroups = groups.findAll().stream().filter(group -> group.isActive()).toList();
    var configuredWarehouses =
        activeGroups.stream()
            .map(group -> group.getWarehouseId())
            .distinct()
            .filter(this::hasKpiHistory)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    activeGroups.stream()
        .filter(group -> configuredWarehouses.contains(group.getWarehouseId()))
        .filter(group -> !evidence.hasDay(group.getWarehouseId(), group.getId(), at))
        .map(group -> new GroupRef(group.getWarehouseId(), group.getId()))
        .forEach(targets::add);

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

  private boolean hasKpiHistory(UUID warehouseId) {
    try {
      return clock.dataAvailableFrom(warehouseId).isPresent();
    } catch (RuntimeException exception) {
      log.warn("Could not resolve KPI settings for warehouse {}", warehouseId, exception);
      return false;
    }
  }

  private record GroupRef(UUID warehouseId, UUID groupId) {}
}
