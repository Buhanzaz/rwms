package dev.buhanzaz.rwms.taskboard.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Loads authoritative route package coordinates and ready-evidence counts for one bounded
 * worker-feed page.
 *
 * <p>The projection reads every persisted route step for each selected task while returning one
 * row per visible entry. Consecutive maintenance rows in one physical queue form one execution
 * package; a queue that recurs after another queue starts a new package. Other task sources keep
 * one package per persisted row. Coordinates and counts are batched in one database round trip.
 */
@Component
class WorkerFeedCountProjection {
  private final JdbcTemplate jdbc;

  WorkerFeedCountProjection(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Returns counts keyed by selected entry identity. */
  Map<UUID, Counts> load(Map<UUID, UUID> taskIdsByEntry) {
    if (taskIdsByEntry.isEmpty()) return Map.of();
    String valueRows =
        String.join(",", Collections.nCopies(taskIdsByEntry.size(), "(?::uuid,?::uuid)"));
    List<Object> parameters = new ArrayList<>(taskIdsByEntry.size() * 2);
    taskIdsByEntry.forEach(
        (entryId, taskId) -> {
          parameters.add(entryId);
          parameters.add(taskId);
        });
    List<Counts> rows =
        jdbc.query(
            """
            with selected(entry_id,task_id) as (values %s),
            selected_tasks as (
              select distinct task_id from selected
            ),
            task_sources as (
              select selected_tasks.task_id,
                     coalesce(source.source_type='MAINTENANCE_REPAIR',false) maintenance_repair
                from selected_tasks
                left join task_sync_source source
                  on source.board_task_id=selected_tasks.task_id
            ),
            route_boundaries as (
              select route.id entry_id,
                     route.task_id,
                     route.route_index,
                     case
                       when not task_sources.maintenance_repair then 1
                       when row_number() over (
                              partition by route.task_id
                              order by route.route_index,route.id)=1 then 1
                       when lag(route.queue_id) over (
                              partition by route.task_id
                              order by route.route_index,route.id)
                            is distinct from route.queue_id then 1
                       else 0
                     end package_start
                from queue_entry route
                join selected_tasks on selected_tasks.task_id=route.task_id
                join task_sources on task_sources.task_id=route.task_id
            ),
            route_packages as (
              select entry_id,
                     task_id,
                     (sum(package_start) over (
                        partition by task_id
                        order by route_index,entry_id
                        rows between unbounded preceding and current row)-1)::integer
                       route_step_index
                from route_boundaries
            ),
            route_counts as (
              select task_id,
                     (max(route_step_index)+1)::integer route_step_count
                from route_packages
               group by task_id
            ),
            evidence_counts as (
              select evidence.entry_id,
                     (count(*) filter (where evidence.state='READY'))::integer ready_evidence_count
                from worker_task_evidence evidence
                join selected on selected.entry_id=evidence.entry_id
               where evidence.problem_report_id is null
               group by evidence.entry_id
            )
            select selected.entry_id,
                   route_packages.route_step_index,
                   route_counts.route_step_count,
                   coalesce(evidence_counts.ready_evidence_count,0) ready_evidence_count
              from selected
              join route_packages
                on route_packages.entry_id=selected.entry_id
               and route_packages.task_id=selected.task_id
              join route_counts on route_counts.task_id=selected.task_id
              left join evidence_counts on evidence_counts.entry_id=selected.entry_id
            """
                .formatted(valueRows),
            (result, row) ->
                new Counts(
                    result.getObject("entry_id", UUID.class),
                    result.getInt("route_step_index"),
                    result.getInt("route_step_count"),
                    result.getInt("ready_evidence_count")),
            parameters.toArray());
    Map<UUID, Counts> byEntry = new LinkedHashMap<>();
    rows.forEach(row -> byEntry.put(row.entryId(), row));
    return Map.copyOf(byEntry);
  }

  /** Package coordinates and evidence count required to render one selected native entry. */
  record Counts(
      UUID entryId, int routeStepIndex, int routeStepCount, int readyEvidenceCount) {}
}
