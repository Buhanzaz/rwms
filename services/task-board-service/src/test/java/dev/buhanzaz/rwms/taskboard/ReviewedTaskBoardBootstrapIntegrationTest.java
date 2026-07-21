package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.taskboard.service.WorkerCredentialGateway;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ReviewedTaskBoardBootstrapIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID SPB =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID MSK =
      UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final String PATH = "/api/warehouses/{warehouseId}/reviewed-bootstrap";
  private static final String SOURCE_SHA256 =
      "76f2a0c7c2527c61d700e4abfe46a06f314b666099889fb5c27dd8f746d6187e";

  @org.springframework.beans.factory.annotation.Autowired MockMvc mockMvc;
  @org.springframework.beans.factory.annotation.Autowired JdbcTemplate jdbc;
  @MockitoBean WorkerCredentialGateway credentialGateway;

  @BeforeEach
  void clean() {
    cleanTaskBoardFixtures(jdbc);
    reset(credentialGateway);
  }

  @Test
  void createsExactReviewedSaintPetersburgRegistriesWithoutOperationalFixtures()
      throws Exception {
    mockMvc
        .perform(
            post(PATH, SPB)
                .header("Idempotency-Key", "reviewed-spb-first")
                .with(access(SPB, "rwms.write", "MANAGE")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.warehouseId").value(SPB.toString()))
        .andExpect(jsonPath("$.sourceSha256").value(SOURCE_SHA256))
        .andExpect(jsonPath("$.created").value(89))
        .andExpect(jsonPath("$.reused").value(0))
        .andExpect(jsonPath("$.conflicts").value(0))
        .andExpect(jsonPath("$.counts.workerClasses").value(7))
        .andExpect(jsonPath("$.counts.workQueues").value(8))
        .andExpect(jsonPath("$.counts.queueBindings").value(7))
        .andExpect(jsonPath("$.counts.workers").value(18))
        .andExpect(jsonPath("$.counts.qualifications").value(22))
        .andExpect(jsonPath("$.counts.workerGroups").value(9))
        .andExpect(jsonPath("$.counts.memberships").value(18));

    assertThat(count("worker_class")).isEqualTo(7);
    assertThat(count("work_queue")).isEqualTo(8);
    assertThat(count("work_queue_class_binding")).isEqualTo(7);
    assertThat(count("worker")).isEqualTo(18);
    assertThat(count("worker_class_assignment")).isEqualTo(22);
    assertThat(count("worker_group")).isEqualTo(9);
    assertThat(count("worker_group_member")).isEqualTo(18);

    assertThat(
            jdbc.queryForList(
                "select code from worker_class order by sort_order", String.class))
        .containsExactly(
            "DRIVER",
            "GENERAL_WORKER",
            "RIGGER",
            "ELECTRICIAN",
            "PLUMBER",
            "WELDER",
            "SES");
    assertThat(
            jdbc.queryForList(
                "select code from work_queue where warehouse_id=? order by sort_order",
                String.class,
                SPB))
        .containsExactly(
            "MOVEMENT",
            "INTERNAL_WORKS",
            "EXTERNAL_WORKS",
            "ELECTRICS",
            "PLUMBING",
            "WELDING",
            "SANITARY_DISINFECTION",
            "HOLDING");
    assertThat(jdbc.queryForList("select app_login from worker order by id", String.class))
        .containsExactly(
            "a.sokolov",
            "d.orlov",
            "general.1",
            "general.2",
            "general.3",
            "general.4",
            "electrician.1",
            "electrician.2",
            "electrician.3",
            "electrician.4",
            "plumber.1",
            "plumber.2",
            "plumber.3",
            "plumber.4",
            "welder.1",
            "welder.2",
            "ses.1",
            "ses.2");
    assertThat(jdbc.queryForList("select id::text from worker order by id", String.class))
        .containsExactlyElementsOf(reviewedIds("20000000", 18));
    assertThat(
            jdbc.queryForObject(
                "select display_name from worker where id=?",
                String.class,
                UUID.fromString("20000000-0000-0000-0000-000000000001")))
        .isEqualTo("Алексей Соколов");
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from worker
                 where credential_status='NOT_CONFIGURED'
                   and credential_error is null
                   and credential_operation_id is null
                   and credential_operation_type is null
                   and credential_operation_started_at is null
                """,
                Long.class))
        .isEqualTo(18L);

    assertNoOperationalFixtures();
    assertThat(count("domain_event")).isEqualTo(42);
    assertThat(count("outbox_event")).isEqualTo(42);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where aggregate_type in ('BOARD_TASK','QUEUE_ENTRY')",
                Long.class))
        .isZero();
    verifyNoInteractions(credentialGateway);
  }

  @Test
  void importsBothCanonicalWarehousesAndReplaysWithoutChangingAnyRowOrEvent()
      throws Exception {
    mockMvc
        .perform(
            post(PATH, SPB)
                .header("Idempotency-Key", "reviewed-spb-first")
                .with(access(SPB, "rwms.write", "MANAGE")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(89));
    mockMvc
        .perform(
            post(PATH, MSK)
                .header("Idempotency-Key", "reviewed-msk-first")
                .with(access(MSK, "rwms.write", "MANAGE")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(15))
        .andExpect(jsonPath("$.reused").value(7))
        .andExpect(jsonPath("$.counts.workQueues").value(8))
        .andExpect(jsonPath("$.counts.workers").value(0));

    assertThat(
            jdbc.queryForObject(
                "select count(*) from work_queue where warehouse_id=?", Long.class, SPB))
        .isEqualTo(8L);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from work_queue where warehouse_id=?", Long.class, MSK))
        .isEqualTo(8L);
    assertThat(count("work_queue_class_binding")).isEqualTo(14);
    assertThat(count("worker")).isEqualTo(18);
    assertThat(count("worker_group")).isEqualTo(9);
    assertThat(count("domain_event")).isEqualTo(50);
    assertThat(count("outbox_event")).isEqualTo(50);
    String beforeReplay = registrySnapshot();

    mockMvc
        .perform(
            post(PATH, SPB)
                .header("Idempotency-Key", "reviewed-spb-retry-with-another-key")
                .with(access(SPB, "rwms.write", "MANAGE")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(0))
        .andExpect(jsonPath("$.reused").value(89))
        .andExpect(jsonPath("$.conflicts").value(0));
    mockMvc
        .perform(
            post(PATH, MSK)
                .header("Idempotency-Key", "reviewed-msk-retry-with-another-key")
                .with(access(MSK, "rwms.write", "MANAGE")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(0))
        .andExpect(jsonPath("$.reused").value(22))
        .andExpect(jsonPath("$.conflicts").value(0));

    assertThat(registrySnapshot()).isEqualTo(beforeReplay);
    assertThat(count("domain_event")).isEqualTo(50);
    assertThat(count("outbox_event")).isEqualTo(50);
    assertNoOperationalFixtures();
    verifyNoInteractions(credentialGateway);
  }

  @Test
  void rejectsMismatchedReviewedValuesAtomically() throws Exception {
    jdbc.update(
        """
        insert into work_queue(
            id, version, revision_marker, warehouse_id, code, name, description,
            queue_type, sort_order, active, hidden, collapsed, holding_period_minutes,
            notification_threshold, notify_when_threshold_reached)
        values (?, 0, ?, ?, 'MOVEMENT', 'Перемещение', null,
                'REPAIR', 10, true, false, false, null, null, false)
        """,
        UUID.fromString("50000000-0000-0000-0000-000000000101"),
        UUID.randomUUID(),
        SPB);

    mockMvc
        .perform(post(PATH, SPB).with(access(SPB, "rwms.write", "MANAGE")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TASK_BOARD_CONFLICT"));

    assertThat(count("worker_class")).isZero();
    assertThat(count("work_queue")).isEqualTo(1);
    assertThat(count("domain_event")).isZero();
    assertThat(count("outbox_event")).isZero();
    verifyNoInteractions(credentialGateway);
  }

  @Test
  void rejectsNaturalIdentityWithAnotherIdInsteadOfCreatingDuplicate() throws Exception {
    jdbc.update(
        """
        insert into work_queue(
            id, version, revision_marker, warehouse_id, code, name, description,
            queue_type, sort_order, active, hidden, collapsed, holding_period_minutes,
            notification_threshold, notify_when_threshold_reached)
        values (?, 0, ?, ?, 'MOVEMENT', 'Перемещение', null,
                'MOVEMENT', 10, true, false, false, null, null, false)
        """,
        UUID.randomUUID(),
        UUID.randomUUID(),
        SPB);

    mockMvc
        .perform(post(PATH, SPB).with(access(SPB, "rwms.write", "MANAGE")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TASK_BOARD_CONFLICT"));

    assertThat(count("worker_class")).isZero();
    assertThat(count("work_queue")).isEqualTo(1);
    assertThat(count("domain_event")).isZero();
  }

  @Test
  void requiresWriteScopeAndManageAccessAndFailsClosedForUnknownWarehouse()
      throws Exception {
    UUID unknown = UUID.fromString("00000000-0000-0000-0000-000000000099");

    mockMvc.perform(post(PATH, SPB)).andExpect(status().isUnauthorized());
    mockMvc
        .perform(post(PATH, SPB).with(access(SPB, "rwms.write", "EDIT")))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(post(PATH, SPB).with(access(SPB, "rwms.read", "MANAGE")))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(post(PATH, unknown).with(access(unknown, "rwms.write", "MANAGE")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TASK_BOARD_NOT_FOUND"));

    assertThat(count("worker_class")).isZero();
    assertThat(count("work_queue")).isZero();
  }

  private JwtRequestPostProcessor access(UUID warehouseId, String scope, String level) {
    return jwt()
        .jwt(
            token ->
                token
                    .subject("reviewed-bootstrap-test")
                    .claim("principal_type", "USER")
                    .claim("global_role", "WAREHOUSE_MANAGER")
                    .claim("scope", scope)
                    .claim(
                        "warehouse_access",
                        List.of(
                            Map.of(
                                "warehouseId", warehouseId.toString(), "level", level))));
  }

  private List<String> reviewedIds(String prefix, int count) {
    return IntStream.rangeClosed(1, count)
        .mapToObj(index -> "%s-0000-0000-0000-%012d".formatted(prefix, index))
        .toList();
  }

  private long count(String table) {
    Long value = jdbc.queryForObject("select count(*) from " + table, Long.class);
    return value == null ? 0 : value;
  }

  private void assertNoOperationalFixtures() {
    assertThat(count("board_task")).isZero();
    assertThat(count("queue_entry")).isZero();
    assertThat(count("task_assignment")).isZero();
    assertThat(count("task_time_event")).isZero();
    assertThat(count("task_auto_interruption")).isZero();
    assertThat(count("worker_deletion_intent")).isZero();
  }

  private String registrySnapshot() {
    return jdbc.queryForObject(
        """
        select jsonb_build_object(
          'classes', (select coalesce(jsonb_agg(to_jsonb(item) order by item.id), '[]'::jsonb)
                        from worker_class item),
          'queues', (select coalesce(jsonb_agg(to_jsonb(item) order by item.id), '[]'::jsonb)
                       from work_queue item),
          'bindings', (select coalesce(jsonb_agg(to_jsonb(item) order by item.id), '[]'::jsonb)
                         from work_queue_class_binding item),
          'workers', (select coalesce(jsonb_agg(to_jsonb(item) order by item.id), '[]'::jsonb)
                        from worker item),
          'qualifications', (select coalesce(jsonb_agg(to_jsonb(item) order by item.id), '[]'::jsonb)
                               from worker_class_assignment item),
          'groups', (select coalesce(jsonb_agg(to_jsonb(item) order by item.id), '[]'::jsonb)
                       from worker_group item),
          'memberships', (select coalesce(jsonb_agg(to_jsonb(item) order by item.id), '[]'::jsonb)
                            from worker_group_member item)
        )::text
        """,
        String.class);
  }
}
