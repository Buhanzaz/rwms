package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.DailyBrigadeActivityDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.DailyBrigadeActivityIntervalDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.TaskSourceReferenceDto;

import dev.buhanzaz.rwms.taskboard.domain.AssignmentStatus;
import dev.buhanzaz.rwms.taskboard.domain.TaskSourceType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only projection of actual brigade task execution during the current warehouse-local day.
 *
 * <p>The projection reads task-board-owned assignment timestamps directly. It deliberately does
 * not substitute configured shift bounds for take or completion time. Legacy duplicate route rows
 * and one-assignment-per-joined-worker rows are coalesced only when they describe overlapping work
 * by the same brigade on the same task and physical queue.
 */
@Service
public class DailyBrigadeActivityService {
  private static final Comparator<AssignmentInterval> REPRESENTATIVE_ORDER =
      Comparator.comparingInt(AssignmentInterval::routeIndex)
          .thenComparing(AssignmentInterval::entryId);
  private static final Comparator<DailyBrigadeActivityIntervalDto> RESPONSE_ORDER =
      Comparator.comparing(
              DailyBrigadeActivityIntervalDto::workerGroupName,
              String.CASE_INSENSITIVE_ORDER)
          .thenComparing(DailyBrigadeActivityIntervalDto::workerGroupName)
          .thenComparing(DailyBrigadeActivityIntervalDto::workerGroupId)
          .thenComparing(value -> value.startedAt().toInstant())
          .thenComparing(DailyBrigadeActivityIntervalDto::taskId)
          .thenComparing(DailyBrigadeActivityIntervalDto::queueId)
          .thenComparing(DailyBrigadeActivityIntervalDto::entryId);
  private static final String ACTIVITY_SQL =
      """
      select assignment.id as assignment_id,
             assignment.worker_group_id,
             coalesce(nullif(btrim(assignment.group_name_snapshot), ''), worker_group.name)
               as worker_group_name,
             task.id as task_id,
             entry.id as entry_id,
             entry.route_index,
             queue.id as queue_id,
             definition.name as queue_name,
             task.title,
             task.unit_number,
             entry.task_text,
             task.priority,
             assignment.started_at,
             assignment.finished_at,
             assignment.status as assignment_status,
             source.source_type,
             source.source_id
        from task_assignment assignment
        join queue_entry entry on entry.id = assignment.queue_entry_id
        join board_task task on task.id = entry.task_id
        join work_queue queue on queue.id = entry.queue_id
        join queue_definition definition on definition.id = queue.definition_id
        join worker_group on worker_group.id = assignment.worker_group_id
        left join task_sync_source source on source.board_task_id = task.id
       where task.warehouse_id = ?
         and queue.warehouse_id = ?
         and worker_group.warehouse_id = ?
         and task.status <> 'CANCELLED'
         and assignment.status in ('ACTIVE', 'PAUSED', 'DONE')
         and assignment.started_at is not null
         and assignment.started_at < ?
         and (assignment.finished_at is null or assignment.finished_at > ?)
       order by worker_group.name,
                worker_group.id,
                task.id,
                queue.id,
                assignment.started_at,
                entry.route_index,
                entry.id,
                assignment.id
      """;

  private final JdbcTemplate jdbc;
  private final WarehouseTimeZoneGateway timeZones;
  private final Clock clock;

  /**
   * Creates the owner-local read projection, using a supplied clock in deterministic tests and a
   * UTC system clock in production.
   *
   * @param jdbc task-board database reader
   * @param timeZones authoritative warehouse timezone boundary
   * @param clockProvider optional application clock
   */
  public DailyBrigadeActivityService(
      JdbcTemplate jdbc,
      WarehouseTimeZoneGateway timeZones,
      ObjectProvider<Clock> clockProvider) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.timeZones = Objects.requireNonNull(timeZones);
    this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
  }

  /**
   * Returns every assignment interval that actually started and overlaps the current local day.
   *
   * <p>Returned timestamps remain the original take and finish instants; day bounds are used only
   * to select rows. A live assignment has a null finish. Completed overlapping duplicates use the
   * latest finish, while a cluster containing any live assignment remains live.
   *
   * @param warehouseId owning warehouse
   * @return current warehouse-local day and its ordered execution intervals
   */
  @Transactional(readOnly = true)
  public DailyBrigadeActivityDto currentDay(UUID warehouseId) {
    Objects.requireNonNull(warehouseId, "Warehouse is required");
    Instant now = clock.instant();
    ZoneId zone = timeZones.timeZoneAt(warehouseId, now).timeZone();
    LocalDate localDate = LocalDate.ofInstant(now, zone);
    Instant dayStart = localDate.atStartOfDay(zone).toInstant();
    Instant dayEnd = localDate.plusDays(1).atStartOfDay(zone).toInstant();
    List<AssignmentInterval> assignments =
        jdbc.query(
            ACTIVITY_SQL,
            (result, rowNumber) ->
                new AssignmentInterval(
                    result.getObject("assignment_id", UUID.class),
                    result.getObject("worker_group_id", UUID.class),
                    result.getString("worker_group_name"),
                    result.getObject("task_id", UUID.class),
                    result.getObject("entry_id", UUID.class),
                    result.getInt("route_index"),
                    result.getObject("queue_id", UUID.class),
                    result.getString("queue_name"),
                    result.getString("title"),
                    result.getString("unit_number"),
                    result.getString("task_text"),
                    result.getInt("priority"),
                    result.getObject("started_at", OffsetDateTime.class),
                    result.getObject("finished_at", OffsetDateTime.class),
                    AssignmentStatus.valueOf(result.getString("assignment_status")),
                    sourceReference(
                        result.getString("source_type"),
                        result.getObject("source_id", UUID.class))),
            warehouseId,
            warehouseId,
            warehouseId,
            OffsetDateTime.ofInstant(dayEnd, ZoneOffset.UTC),
            OffsetDateTime.ofInstant(dayStart, ZoneOffset.UTC));
    List<DailyBrigadeActivityIntervalDto> intervals = coalesce(assignments);
    intervals.sort(RESPONSE_ORDER);
    return new DailyBrigadeActivityDto(
        warehouseId,
        localDate,
        OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
        intervals);
  }

  /** Reconstructs the optional source pair while rejecting impossible half-populated data. */
  private TaskSourceReferenceDto sourceReference(String type, UUID sourceId) {
    if (type == null && sourceId == null) {
      return null;
    }
    if (type == null || sourceId == null) {
      throw new IllegalStateException("Task source reference is incomplete");
    }
    return new TaskSourceReferenceDto(TaskSourceType.valueOf(type), sourceId);
  }

  /** Coalesces overlapping assignment rows independently inside each execution identity. */
  private List<DailyBrigadeActivityIntervalDto> coalesce(
      List<AssignmentInterval> assignments) {
    Map<ActivityKey, List<AssignmentInterval>> grouped = new LinkedHashMap<>();
    for (AssignmentInterval assignment : assignments) {
      grouped.computeIfAbsent(assignment.key(), ignored -> new ArrayList<>()).add(assignment);
    }

    List<DailyBrigadeActivityIntervalDto> result = new ArrayList<>();
    for (List<AssignmentInterval> group : grouped.values()) {
      group.sort(
          Comparator.comparing((AssignmentInterval value) -> value.startedAt().toInstant())
              .thenComparing(REPRESENTATIVE_ORDER)
              .thenComparing(AssignmentInterval::assignmentId));
      MergedInterval current = null;
      for (AssignmentInterval candidate : group) {
        if (current == null || !current.overlaps(candidate)) {
          if (current != null) {
            result.add(current.toDto());
          }
          current = new MergedInterval(candidate);
        } else {
          current.merge(candidate);
        }
      }
      if (current != null) {
        result.add(current.toDto());
      }
    }
    return result;
  }

  /** Identity whose overlapping legacy and joined-worker rows form one displayed interval. */
  private record ActivityKey(UUID workerGroupId, UUID taskId, UUID queueId) {}

  /** One task-assignment row together with stable task and queue presentation data. */
  private record AssignmentInterval(
      UUID assignmentId,
      UUID workerGroupId,
      String workerGroupName,
      UUID taskId,
      UUID entryId,
      int routeIndex,
      UUID queueId,
      String queueName,
      String title,
      String unitNumber,
      String taskText,
      int priority,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt,
      AssignmentStatus status,
      TaskSourceReferenceDto source) {
    /** Returns the brigade, task and physical-queue identity used for interval coalescing. */
    ActivityKey key() {
      return new ActivityKey(workerGroupId, taskId, queueId);
    }
  }

  /** Mutable union of one transitively overlapping assignment interval cluster. */
  private static final class MergedInterval {
    private AssignmentInterval representative;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
    private boolean live;
    private boolean active;

    MergedInterval(AssignmentInterval initial) {
      representative = initial;
      startedAt = initial.startedAt();
      finishedAt = initial.finishedAt();
      live = initial.finishedAt() == null;
      active = initial.status() == AssignmentStatus.ACTIVE;
    }

    /** Returns true only for a strict interval overlap; touching retakes stay distinct. */
    boolean overlaps(AssignmentInterval candidate) {
      return live || candidate.startedAt().toInstant().isBefore(finishedAt.toInstant());
    }

    /** Extends the cluster while retaining the lowest route-index/entry-id representative. */
    void merge(AssignmentInterval candidate) {
      if (REPRESENTATIVE_ORDER.compare(candidate, representative) < 0) {
        representative = candidate;
      }
      if (candidate.startedAt().toInstant().isBefore(startedAt.toInstant())) {
        startedAt = candidate.startedAt();
      }
      if (candidate.finishedAt() == null) {
        live = true;
        finishedAt = null;
      } else if (!live
          && (finishedAt == null
              || candidate.finishedAt().toInstant().isAfter(finishedAt.toInstant()))) {
        finishedAt = candidate.finishedAt();
      }
      active |= candidate.status() == AssignmentStatus.ACTIVE;
    }

    /** Converts the union to the canonical read contract with a coherent status/end pair. */
    DailyBrigadeActivityIntervalDto toDto() {
      AssignmentStatus status =
          live
              ? (active ? AssignmentStatus.ACTIVE : AssignmentStatus.PAUSED)
              : AssignmentStatus.DONE;
      return new DailyBrigadeActivityIntervalDto(
          representative.workerGroupId(),
          representative.workerGroupName(),
          representative.taskId(),
          representative.entryId(),
          representative.queueId(),
          representative.queueName(),
          representative.title(),
          representative.unitNumber(),
          representative.taskText(),
          representative.priority(),
          startedAt,
          live ? null : finishedAt,
          status,
          representative.source());
    }
  }
}
