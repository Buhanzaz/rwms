package dev.buhanzaz.rwms.logistics.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Verifies the frozen exact-contractor task-board paths, payload and response identity fences. */
class ContractorTaskBoardDependencyClientTest {
  private static final UUID WORKER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
  private static final UUID EXTERNAL_TASK_ID =
      UUID.fromString("20000000-0000-0000-0000-000000000002");
  private static final UUID TASK_ID = UUID.fromString("20000000-0000-0000-0000-000000000003");
  private static final UUID WAREHOUSE_ID = UUID.fromString("20000000-0000-0000-0000-000000000004");
  private static final UUID SOURCE_ID = UUID.fromString("20000000-0000-0000-0000-000000000005");
  private static final UUID ENTRY_ID = UUID.fromString("20000000-0000-0000-0000-000000000006");
  private static final UUID WORK_ID = UUID.fromString("20000000-0000-0000-0000-000000000007");
  private static final UUID MATERIAL_ID = UUID.fromString("20000000-0000-0000-0000-000000000008");
  private static final UUID COMMENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000009");
  private static final UUID MEDIA_ID = UUID.fromString("20000000-0000-0000-0000-000000000010");
  private static final UUID EVIDENCE_ID = UUID.fromString("20000000-0000-0000-0000-000000000011");

  private MockRestServiceServer server;
  private LogisticsTaskBoardDependencyClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    OAuth2AuthorizedClientManager authorizedClients = mock(OAuth2AuthorizedClientManager.class);
    OAuth2AuthorizedClient authorizedClient = authorizedClient();
    when(authorizedClients.authorize(any())).thenReturn(authorizedClient);
    client =
        new LogisticsTaskBoardDependencyClient(
            new LogisticsOAuthHttpTransport(builder.build(), authorizedClients),
            "http://task-board.test");
  }

  @Test
  void readsOnlyTheExactWorkerTaskWithStepContentAndBoundedEvidence() {
    server
        .expect(requestTo(taskPath()))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer task-board-token"))
        .andRespond(withSuccess(taskJson(3, "IN_PROGRESS"), MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.ContractorTaskExecution execution =
        client.readContractorTaskExecution(WORKER_ID, EXTERNAL_TASK_ID);

    assertThat(execution.workerId()).isEqualTo(WORKER_ID);
    assertThat(execution.externalTaskId()).isEqualTo(EXTERNAL_TASK_ID);
    assertThat(execution.route()).hasSize(1);
    var entry = execution.route().getFirst();
    assertThat(entry.works()).extracting(work -> work.id()).containsExactly(WORK_ID);
    assertThat(entry.materials())
        .extracting(material -> material.id())
        .containsExactly(MATERIAL_ID);
    assertThat(entry.comments()).extracting(comment -> comment.id()).containsExactly(COMMENT_ID);
    assertThat(entry.sourceMedia()).extracting(media -> media.mediaId()).containsExactly(MEDIA_ID);
    assertThat(entry.evidence())
        .extracting(evidence -> evidence.evidenceId())
        .containsExactly(EVIDENCE_ID);
    server.verify();
  }

  @Test
  void forwardsOneExactActionWithMatchingOperationAndIdempotencyIdentity() {
    UUID key = UUID.randomUUID();
    server
        .expect(requestTo(taskPath() + "/entries/" + ENTRY_ID + "/actions"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer task-board-token"))
        .andExpect(header("Idempotency-Key", key.toString()))
        .andExpect(jsonPath("$.operationId").value(key.toString()))
        .andExpect(jsonPath("$.action").value("COMPLETE"))
        .andExpect(jsonPath("$.expectedVersion").value(3))
        .andExpect(jsonPath("$.evidenceId").value(EVIDENCE_ID.toString()))
        .andRespond(
            withSuccess(
                """
                {"outcome":"APPLIED","currentVersion":4,"task":%s}
                """
                    .formatted(taskJson(4, "DONE")),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.ContractorTaskActionResult result =
        client.applyContractorTaskAction(
            WORKER_ID, EXTERNAL_TASK_ID, ENTRY_ID, key, "COMPLETE", 3, EVIDENCE_ID);

    assertThat(result.currentVersion()).isEqualTo(4);
    assertThat(result.task().route().getFirst().version()).isEqualTo(4);
    server.verify();
  }

  @Test
  void reservesExactEvidenceWithOneSharedOperationAndIdempotencyIdentity() {
    OffsetDateTime capturedAt = OffsetDateTime.parse("2026-09-01T10:15:30Z");
    String sha256 = "8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4";
    server
        .expect(requestTo(taskPath() + "/entries/" + ENTRY_ID + "/evidence-reservations"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer task-board-token"))
        .andExpect(header("Idempotency-Key", EVIDENCE_ID.toString()))
        .andExpect(jsonPath("$.operationId").value(EVIDENCE_ID.toString()))
        .andExpect(jsonPath("$.evidenceId").value(EVIDENCE_ID.toString()))
        .andExpect(jsonPath("$.capturedAt").value(capturedAt.toString()))
        .andExpect(jsonPath("$.contentType").value("image/jpeg"))
        .andExpect(jsonPath("$.sizeBytes").value(2))
        .andExpect(jsonPath("$.sha256").value(sha256))
        .andRespond(
            withSuccess(
                """
                {
                  "evidenceId":"%s",
                  "version":1,
                  "state":"RESERVED",
                  "entryId":"%s",
                  "ownerType":"TASK_BOARD_ENTRY",
                  "ownerId":"%s",
                  "warehouseId":"%s",
                  "clientReferenceId":"%s",
                  "capturedAt":"%s",
                  "contentType":"image/jpeg",
                  "sizeBytes":2,
                  "sha256":"%s"
                }
                """
                    .formatted(
                        EVIDENCE_ID,
                        ENTRY_ID,
                        ENTRY_ID,
                        WAREHOUSE_ID,
                        EVIDENCE_ID,
                        capturedAt,
                        sha256),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.ContractorEvidenceReservation reservation =
        client.reserveContractorTaskEvidence(
            WORKER_ID,
            EXTERNAL_TASK_ID,
            ENTRY_ID,
            EVIDENCE_ID,
            capturedAt,
            "image/jpeg",
            2,
            sha256);

    assertThat(reservation.evidenceId()).isEqualTo(EVIDENCE_ID);
    assertThat(reservation.entryId()).isEqualTo(ENTRY_ID);
    assertThat(reservation.state()).isEqualTo("RESERVED");
    server.verify();
  }

  @Test
  void rejectsAResponseForAnotherWorkerBeforeItCanReachThePublicBoundary() {
    server
        .expect(requestTo(taskPath()))
        .andRespond(
            withSuccess(
                taskJson(3, "IN_PROGRESS")
                    .replace(WORKER_ID.toString(), UUID.randomUUID().toString()),
                MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.readContractorTaskExecution(WORKER_ID, EXTERNAL_TASK_ID))
        .isInstanceOf(LogisticsDependencyException.class)
        .hasMessageContaining("invalid contractor task execution");
    server.verify();
  }

  @Test
  void rejectsMalformedNestedEvidenceBeforeItCanReachThePublicBoundary() {
    server
        .expect(requestTo(taskPath()))
        .andRespond(
            withSuccess(
                taskJson(3, "IN_PROGRESS").replace("\"state\":\"READY\"", "\"state\":\"RAW\""),
                MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.readContractorTaskExecution(WORKER_ID, EXTERNAL_TASK_ID))
        .isInstanceOf(LogisticsDependencyException.class)
        .hasMessageContaining("invalid contractor task route");
    server.verify();
  }

  private static OAuth2AuthorizedClient authorizedClient() {
    OAuth2AuthorizedClient authorized = mock(OAuth2AuthorizedClient.class);
    when(authorized.getAccessToken())
        .thenReturn(
            new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "task-board-token",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Set.of("task-board.logistics")));
    return authorized;
  }

  private static String taskPath() {
    return "http://task-board.test/api/internal/task-board/v1/logistics/contractor-execution/workers/"
        + WORKER_ID
        + "/tasks/"
        + EXTERNAL_TASK_ID;
  }

  private static String taskJson(long entryVersion, String entryStatus) {
    return """
    {
      "workerId":"%s",
      "externalTaskId":"%s",
      "taskId":"%s",
      "taskVersion":5,
      "warehouseId":"%s",
      "title":"Доставка БК-2",
      "description":"Доставить бытовку",
      "unitNumber":"БК-172",
      "scheduledDate":"2026-09-01",
      "deadlineAt":"2026-09-01T15:00:00Z",
      "priority":2,
      "status":"ACTIVE",
      "source":{"type":"LOGISTICS_DRIVER_TASK","sourceId":"%s"},
      "route":[{
        "entryId":"%s",
        "version":%d,
        "routeIndex":0,
        "routeStepIndex":0,
        "routeStepCount":1,
        "queueName":"Доставка",
        "taskText":"Доставить бытовку",
        "status":"%s",
        "plannedDurationMinutes":45,
        "works":[{"id":"%s","name":"Выгрузить бытовку","quantity":1,"unit":"шт.","durationMinutes":30,"comment":"Проверить номер","sourceMediaIds":["%s"]}],
        "materials":[{"id":"%s","name":"Бытовка БК-2","quantity":1,"unit":"шт."}],
        "comments":[{"id":"%s","text":"Въезд с торца","authorDisplayName":"Логист","createdAt":"2026-08-31T08:00:00Z"}],
        "sourceMedia":[{"mediaId":"%s","generation":2,"contentType":"image/jpeg","capturedAt":"2026-08-31T09:00:00Z","recordedAt":"2026-08-31T09:01:00Z"}],
        "resultPhotoMinCount":1,
        "evidence":[{"evidenceId":"%s","version":1,"capturedAt":"2026-08-31T09:00:00Z","recordedAt":"2026-08-31T09:01:00Z","state":"READY","mediaId":"%s","mediaGeneration":2,"reviewReason":null,"contentType":"image/jpeg"}],
        "completionAllowed":true
      }]
    }
    """
        .formatted(
            WORKER_ID,
            EXTERNAL_TASK_ID,
            TASK_ID,
            WAREHOUSE_ID,
            SOURCE_ID,
            ENTRY_ID,
            entryVersion,
            entryStatus,
            WORK_ID,
            MEDIA_ID,
            MATERIAL_ID,
            COMMENT_ID,
            MEDIA_ID,
            EVIDENCE_ID,
            MEDIA_ID);
  }
}
