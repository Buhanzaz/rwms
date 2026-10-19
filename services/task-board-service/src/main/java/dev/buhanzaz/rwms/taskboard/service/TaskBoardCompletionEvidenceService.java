package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ContractorTaskExecutionApiModels.ContractorTaskEvidence;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Enforces the task-board-owned result-evidence invariant before worker completion.
 *
 * <p>Both native worker commands and the private contractor execution boundary use this service,
 * so a service caller cannot complete logistics work without the same ready, selected evidence
 * required by DriverApp.
 */
@Service
class TaskBoardCompletionEvidenceService {
  private static final int CONTRACTOR_EVIDENCE_LIMIT = 100;

  private final JdbcTemplate jdbc;

  TaskBoardCompletionEvidenceService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Returns the number of finalized, ready evidence objects for one route entry. */
  int readyCount(UUID entryId) {
    Integer ready =
        jdbc.queryForObject(
            """
            select count(*)::integer
              from worker_task_evidence
             where entry_id=? and problem_report_id is null
               and state='READY' and media_id is not null
            """,
            Integer.class,
            entryId);
    return ready == null ? 0 : ready;
  }

  /** Returns the ready evidence count attributed to one exact contractor and route entry. */
  int readyCount(UUID entryId, UUID workerId) {
    Integer ready =
        jdbc.queryForObject(
            """
            select count(*)::integer
              from worker_task_evidence
             where entry_id=? and worker_id=? and problem_report_id is null
               and state='READY' and media_id is not null
            """,
            Integer.class,
            entryId,
            workerId);
    return ready == null ? 0 : ready;
  }

  /**
   * Returns at most the newest one hundred result-evidence facts for one exact contractor entry.
   *
   * <p>The projection deliberately excludes media read and upload paths. All identities and states
   * come from task-board's authoritative evidence table.
   */
  List<ContractorTaskEvidence> contractorEvidence(UUID entryId, UUID workerId) {
    return jdbc.query(
        """
        select evidence_id,version,captured_at,recorded_at,state,media_id,
               media_generation,review_reason,content_type
          from worker_task_evidence
         where entry_id=? and worker_id=? and problem_report_id is null
         order by recorded_at desc,evidence_id desc
         limit ?
        """,
        (result, row) ->
            new ContractorTaskEvidence(
                result.getObject("evidence_id", UUID.class),
                result.getLong("version"),
                result.getObject("captured_at", java.time.OffsetDateTime.class),
                result.getObject("recorded_at", java.time.OffsetDateTime.class),
                result.getString("state"),
                result.getObject("media_id", UUID.class),
                result.getObject("media_generation") == null
                    ? null
                    : result.getLong("media_generation"),
                result.getString("review_reason"),
                result.getString("content_type")),
        entryId,
        workerId,
        CONTRACTOR_EVIDENCE_LIMIT);
  }

  /**
   * Requires the configured ready-evidence count and, for driver work, selects one exact result.
   *
   * <p>The selection and the subsequent completion run in the caller's transaction. A missing or
   * not-yet-finalized selection is a typed conflict rather than an invented successful result.
   */
  void requireAndSelect(
      UUID entryId, int requiredCount, boolean selectedEvidenceRequired, UUID evidenceId) {
    if (readyCount(entryId) < requiredCount) {
      throw new ConflictException("Для завершения не хватает готовых фотографий");
    }
    if (!selectedEvidenceRequired) return;
    if (evidenceId == null) {
      throw new ConflictException(
          "Для завершения логистического задания выберите фотографию результата");
    }
    Integer ready =
        jdbc.queryForObject(
            """
            select count(*)::integer
              from worker_task_evidence
             where entry_id=? and evidence_id=? and problem_report_id is null
               and state='READY' and media_id is not null
            """,
            Integer.class,
            entryId,
            evidenceId);
    if (ready == null || ready != 1) {
      throw new ConflictException("Выбранная фотография результата ещё не готова");
    }
    jdbc.update(
        "update worker_task_evidence set selected_for_completion=false where entry_id=? and problem_report_id is null",
        entryId);
    jdbc.update(
        """
        update worker_task_evidence
           set selected_for_completion=true, updated_at=clock_timestamp()
         where entry_id=? and evidence_id=? and problem_report_id is null
        """,
        entryId,
        evidenceId);
  }

  /**
   * Requires and selects only evidence captured by the exact contractor completing this entry.
   *
   * <p>This is intentionally separate from {@link #requireAndSelect(UUID, int, boolean, UUID)} so
   * native driver and shared-slinger completion keep their existing shared-evidence semantics.
   */
  void requireAndSelectForContractor(
      UUID entryId, UUID workerId, int requiredCount, UUID evidenceId) {
    if (readyCount(entryId, workerId) < requiredCount) {
      throw new ConflictException("Для завершения не хватает готовых фотографий");
    }
    if (evidenceId == null) {
      throw new ConflictException(
          "Для завершения логистического задания выберите фотографию результата");
    }
    Integer ready =
        jdbc.queryForObject(
            """
            select count(*)::integer
              from worker_task_evidence
             where entry_id=? and worker_id=? and evidence_id=? and problem_report_id is null
               and state='READY' and media_id is not null
            """,
            Integer.class,
            entryId,
            workerId,
            evidenceId);
    if (ready == null || ready != 1) {
      throw new ConflictException("Выбранная фотография результата ещё не готова");
    }
    jdbc.update(
        "update worker_task_evidence set selected_for_completion=false where entry_id=? and problem_report_id is null", entryId);
    jdbc.update(
        """
        update worker_task_evidence
           set selected_for_completion=true, updated_at=clock_timestamp()
         where entry_id=? and worker_id=? and evidence_id=? and problem_report_id is null
        """,
        entryId,
        workerId,
        evidenceId);
  }
}
