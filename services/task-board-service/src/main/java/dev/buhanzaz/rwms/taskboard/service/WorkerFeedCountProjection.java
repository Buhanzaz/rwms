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
 * Loads authoritative route cardinality and ready-evidence counts for one bounded worker-feed
 * page.
 *
 * <p>The projection reads every persisted route step for each selected task while returning one
 * row per visible entry. A maintenance route counts physical queues because one queue is one
 * worker-executable subtask even while a started historical task still retains several persisted
 * content rows for that queue. Other task sources remain entry-counted. Both counts are batched in
 * one database round trip.
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
            route_counts as (
              select route.task_id,
                     case when task_sources.maintenance_repair
                          then count(distinct route.queue_id)::integer
                          else count(*)::integer
                      end route_step_count
                from queue_entry route
                join selected_tasks on selected_tasks.task_id=route.task_id
                join task_sources on task_sources.task_id=route.task_id
               group by route.task_id,task_sources.maintenance_repair
            ),
            evidence_counts as (
              select evidence.entry_id,
                     (count(*) filter (where evidence.state='READY'))::integer ready_evidence_count
                from worker_task_evidence evidence
                join selected on selected.entry_id=evidence.entry_id
               group by evidence.entry_id
            )
            select selected.entry_id,
                   route_counts.route_step_count,
                   coalesce(evidence_counts.ready_evidence_count,0) ready_evidence_count
              from selected
              join route_counts on route_counts.task_id=selected.task_id
              left join evidence_counts on evidence_counts.entry_id=selected.entry_id
            """
                .formatted(valueRows),
            (result, row) ->
                new Counts(
                    result.getObject("entry_id", UUID.class),
                    result.getInt("route_step_count"),
                    result.getInt("ready_evidence_count")),
            parameters.toArray());
    Map<UUID, Counts> byEntry = new LinkedHashMap<>();
    rows.forEach(row -> byEntry.put(row.entryId(), row));
    return Map.copyOf(byEntry);
  }

  /** Counts required to render one selected worker-feed entry. */
  record Counts(UUID entryId, int routeStepCount, int readyEvidenceCount) {}
}
