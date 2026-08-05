package dev.buhanzaz.rwms.taskboard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

abstract class PostgresIntegrationTestSupport {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  static void cleanTaskBoardFixtures(JdbcTemplate jdbc) {
    jdbc.execute("alter table domain_event disable trigger user");
    jdbc.execute("alter table task_board_kafka_cutover_map disable trigger user");
    jdbc.execute("alter table replay_operation_audit disable trigger user");
    try {
      jdbc.execute(
          """
          truncate table
            task_board_kafka_cutover_map, replay_operation_audit,
            group_kpi_segment_day, group_kpi_responsibility_segment, group_kpi_day_state,
            worker_current_group_interval, worker_group_availability_interval,
            kpi_activation_receipt, warehouse_kpi_settings, kpi_work_break, kpi_work_schedule,
            kpi_palette_range, kpi_palette, warehouse_event_inbox, warehouse_metadata,
            worker_device_registration, worker_media_event_inbox, worker_task_evidence,
            sanitized_dead_letter, version_gap_quarantine,
            inbox_message, consumer_aggregate_checkpoint,
            projection_checkpoint, aggregate_snapshot,
            outbox_event, domain_event, event_stream_head,
            task_board_inbox, task_board_outbox,
            task_auto_interruption, task_time_event, task_assignment,
            task_board_warehouse_lifecycle_intent, task_relocation_receipt,
            task_sync_source, queue_entry, board_task,
            queue_usage_reference,
            queue_definition_class_binding, work_queue_class_binding, worker_deletion_intent,
            worker_group_member, worker_class_assignment,
            worker_group, worker, work_queue, queue_definition, worker_class
          restart identity cascade
          """);
    } finally {
      jdbc.execute("alter table replay_operation_audit enable trigger user");
      jdbc.execute("alter table task_board_kafka_cutover_map enable trigger user");
      jdbc.execute("alter table domain_event enable trigger user");
    }
  }
}
