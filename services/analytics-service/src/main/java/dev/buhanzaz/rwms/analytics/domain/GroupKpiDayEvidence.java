package dev.buhanzaz.rwms.analytics.domain;

import dev.buhanzaz.rwms.analytics.eventing.AnalyticsValidatedEvent;
import dev.buhanzaz.rwms.analytics.eventing.AnalyticsValidatedEvent.KpiDayPayload;
import dev.buhanzaz.rwms.analytics.eventing.KpiOpenState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Stores one worker-group, warehouse and local-day KPI evidence row with raw components rather than rounded aggregates. */
@Entity
@Table(name = "analytics_group_kpi_day")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupKpiDayEvidence {
  @Id
  @Column(name = "evidence_id", nullable = false)
  private UUID evidenceId;

  @Version
  @Column(name = "row_version", nullable = false)
  private long rowVersion;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "worker_group_id", nullable = false)
  private UUID workerGroupId;

  @Column(name = "local_date", nullable = false)
  private LocalDate localDate;

  @Column(name = "data_available_from", nullable = false)
  private LocalDate dataAvailableFrom;

  @Column(name = "formula_version", nullable = false, length = 32)
  private String formulaVersion;

  @Column(name = "completed_budget_seconds", nullable = false)
  private long completedBudgetSeconds;

  @Column(name = "earned_remaining_seconds", nullable = false)
  private long earnedRemainingSeconds;

  @Column(name = "active_seconds", nullable = false)
  private long activeSeconds;

  @Column(name = "penalized_idle_seconds", nullable = false)
  private long penalizedIdleSeconds;

  @Column(name = "completed_task_count", nullable = false)
  private long completedTaskCount;

  @Enumerated(EnumType.STRING)
  @Column(name = "open_state", length = 24)
  private KpiOpenState openState;

  @Column(name = "open_state_started_at")
  private OffsetDateTime openStateStartedAt;

  @Column(name = "penalty_starts_at")
  private OffsetDateTime penaltyStartsAt;

  @Column(name = "next_transition_at")
  private OffsetDateTime nextTransitionAt;

  @Column(name = "evidence_as_of", nullable = false)
  private OffsetDateTime asOf;

  @Column(name = "source_event_id", nullable = false)
  private UUID sourceEventId;

  @Column(name = "source_aggregate_version", nullable = false)
  private long sourceAggregateVersion;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public static GroupKpiDayEvidence initial(
      AnalyticsValidatedEvent event, OffsetDateTime now) {
    GroupKpiDayEvidence evidence = new GroupKpiDayEvidence();
    evidence.evidenceId = AnalyticsDomainRules.uuid(event.aggregateId(), "aggregateId");
    evidence.apply(event, now, false);
    return evidence;
  }

  public void replace(AnalyticsValidatedEvent event, OffsetDateTime now) {
    apply(event, now, true);
  }

  public long activeSecondsAt(OffsetDateTime queryAsOf) {
    return Math.addExact(
        activeSeconds, openState == KpiOpenState.WORKING ? openElapsed(queryAsOf, asOf) : 0);
  }

  public long penalizedIdleSecondsAt(OffsetDateTime queryAsOf) {
    long elapsed = 0;
    if (openState == KpiOpenState.IDLE_PENALIZED) {
      elapsed = openElapsed(queryAsOf, asOf);
    } else if (openState == KpiOpenState.IDLE_GRACE && penaltyStartsAt != null) {
      elapsed = openElapsed(queryAsOf, penaltyStartsAt.isAfter(asOf) ? penaltyStartsAt : asOf);
    }
    return Math.addExact(penalizedIdleSeconds, elapsed);
  }

  public OffsetDateTime projectedAsOf(OffsetDateTime queryAsOf) {
    if (openState == null || openState == KpiOpenState.EXCLUDED || !queryAsOf.isAfter(asOf)) {
      return asOf;
    }
    return nextTransitionAt == null || queryAsOf.isBefore(nextTransitionAt)
        ? queryAsOf
        : nextTransitionAt;
  }

  private void apply(AnalyticsValidatedEvent event, OffsetDateTime now, boolean replacement) {
    KpiDayPayload payload = AnalyticsDomainRules.require(event.payload(), "payload");
    if (!payload.evidenceId().equals(evidenceId)) {
      throw new IllegalArgumentException("Evidence identity cannot change");
    }
    if (replacement) {
      if (event.aggregateVersion() <= sourceAggregateVersion) {
        throw new IllegalArgumentException("Replacement must move source version forward");
      }
      if (!warehouseId.equals(payload.warehouseId())
          || !workerGroupId.equals(payload.workerGroupId())
          || !localDate.equals(payload.localDate())) {
        throw new IllegalArgumentException("Evidence warehouse, group and date cannot change");
      }
    }
    warehouseId = payload.warehouseId();
    workerGroupId = payload.workerGroupId();
    localDate = payload.localDate();
    dataAvailableFrom = payload.dataAvailableFrom();
    formulaVersion = payload.formulaVersion();
    completedBudgetSeconds = payload.completedBudgetSeconds();
    earnedRemainingSeconds = payload.earnedRemainingSeconds();
    activeSeconds = payload.activeSeconds();
    penalizedIdleSeconds = payload.penalizedIdleSeconds();
    completedTaskCount = payload.completedTaskCount();
    openState = payload.openState();
    openStateStartedAt = payload.openStateStartedAt();
    penaltyStartsAt = payload.penaltyStartsAt();
    nextTransitionAt = payload.nextTransitionAt();
    asOf = payload.asOf();
    sourceEventId = event.eventId();
    sourceAggregateVersion = event.aggregateVersion();
    updatedAt = AnalyticsDomainRules.require(now, "now");
  }

  private long openElapsed(OffsetDateTime queryAsOf, OffsetDateTime start) {
    OffsetDateTime end = projectedAsOf(queryAsOf);
    if (!end.isAfter(start)) return 0;
    return Duration.between(start, end).toSeconds();
  }
}
