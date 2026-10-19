package dev.buhanzaz.rwms.taskboard.eventing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Records low-cardinality counters for task-board outbox, inbox, replay, and sanitized DLT work. */
@Component
public class TaskBoardEventingMetrics {
  private final Counter published;
  private final Counter publishFailed;
  private final Counter retried;
  private final Counter dltEnqueued;
  private final Counter dltPublished;
  private final Counter dltFailed;
  private final Counter duplicate;
  private final Counter quarantined;
  private final Counter replayAttempted;
  private final Counter replayFailed;
  private final Counter shadowReconciled;
  private final AtomicLong outboxBacklog = new AtomicLong();

  private final JdbcTemplate jdbc;

  public TaskBoardEventingMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    published = Counter.builder("rwms.task_board.outbox.published").register(registry);
    publishFailed = Counter.builder("rwms.task_board.outbox.publish_failed").register(registry);
    retried = Counter.builder("rwms.task_board.outbox.retried").register(registry);
    dltEnqueued = Counter.builder("rwms.task_board.dlt.enqueued").register(registry);
    dltPublished = Counter.builder("rwms.task_board.dlt.published").register(registry);
    dltFailed = Counter.builder("rwms.task_board.dlt.failed").register(registry);
    duplicate = Counter.builder("rwms.task_board.inbox.duplicate").register(registry);
    quarantined = Counter.builder("rwms.task_board.inbox.quarantined").register(registry);
    replayAttempted = Counter.builder("rwms.task_board.replay.attempted").register(registry);
    replayFailed = Counter.builder("rwms.task_board.replay.failed").register(registry);
    shadowReconciled = Counter.builder("rwms.task_board.shadow.reconciled").register(registry);
    registry.gauge("rwms.task_board.outbox.backlog", outboxBacklog);
    Gauge.builder(
            "rwms.task_board.outbox.oldest.age.seconds",
            this,
            ignored ->
                decimal(
                    "select coalesce(extract(epoch from (clock_timestamp()-min(created_at))),0) "
                        + "from outbox_event where status in ('PENDING','IN_FLIGHT')"))
        .register(registry);
    Gauge.builder(
            "rwms.task_board.outbox.retry.count",
            this,
            ignored -> decimal("select coalesce(sum(attempt_count),0) from outbox_event"))
        .register(registry);
    Gauge.builder(
            "rwms.task_board.dlt.backlog",
            this,
            ignored ->
                decimal(
                    "select count(*) from sanitized_dead_letter "
                        + "where status in ('PENDING','IN_FLIGHT','FAILED')"))
        .register(registry);
    Gauge.builder(
            "rwms.task_board.inbox.quarantined.current",
            this,
            ignored -> decimal("select count(*) from inbox_message where status='QUARANTINED'"))
        .register(registry);
    Gauge.builder(
            "rwms.task_board.version_gap.open",
            this,
            ignored -> decimal("select count(*) from version_gap_quarantine where status='OPEN'"))
        .register(registry);
  }

  public void outboxPublished() { published.increment(); }
  public void outboxPublishFailed() { publishFailed.increment(); }
  public void outboxRetried() { retried.increment(); }
  public void sanitizedDltEnqueued() { dltEnqueued.increment(); }
  public void sanitizedDltPublished() { dltPublished.increment(); }
  public void sanitizedDltFailed() { dltFailed.increment(); }
  public void inboxDuplicate() { duplicate.increment(); }
  public void inboxQuarantined() { quarantined.increment(); }
  public void replayAttempted() { replayAttempted.increment(); }
  public void replayFailed() { replayFailed.increment(); }
  public void shadowReconciled() { shadowReconciled.increment(); }
  public void outboxBacklog(long value) { outboxBacklog.set(value); }

  private double decimal(String sql) {
    Number value = jdbc.queryForObject(sql, Number.class);
    return value == null ? 0 : value.doubleValue();
  }
}
