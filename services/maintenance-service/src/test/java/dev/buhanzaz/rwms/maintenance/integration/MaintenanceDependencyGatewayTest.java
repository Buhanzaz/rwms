package dev.buhanzaz.rwms.maintenance.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceDependencyException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MaintenanceDependencyGatewayTest {
  private OAuth2AuthorizedClientManager authorizedClients;
  private MockRestServiceServer server;
  private HttpMaintenanceDependencyGateway gateway;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    authorizedClients = mock(OAuth2AuthorizedClientManager.class);
    OAuth2AuthorizedClient authorized = mock(OAuth2AuthorizedClient.class);
    when(authorized.getAccessToken()).thenReturn(new OAuth2AccessToken(
        OAuth2AccessToken.TokenType.BEARER,
        "task-token",
        Instant.now(),
        Instant.now().plusSeconds(3600),
        Set.of("task-board.task-sync")));
    when(authorizedClients.authorize(any())).thenReturn(authorized);
    gateway = new HttpMaintenanceDependencyGateway(
        builder.build(),
        authorizedClients,
        new MaintenanceDependencyProperties.Validated(
            URI.create("http://auth.test/token"),
            "maintenance",
            "secret",
            URI.create("http://asset.test"),
            URI.create("http://task.test"),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2)));
  }

  @Test
  void registerSendsExplicitNullWhenNoStageHasDeadline() {
    UUID externalTaskId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID queueId = UUID.randomUUID();
    server.expect(requestTo("http://task.test/api/internal/task-board/v1/tasks"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(containsString("\"deadlineAt\":null")))
        .andRespond(withSuccess(
            taskResponse(externalTaskId, warehouseId, null, List.of(queueId)),
            MediaType.APPLICATION_JSON));

    var response = gateway.registerTask(
        UUID.randomUUID(), externalTaskId, warehouseId, rentalItemId,
        List.of(taskStage(0, queueId, null)));

    assertThat(response.state()).isEqualTo("ACTIVE");
    server.verify();
  }

  @Test
  void preStartUpdateSendsTheOneIdenticalExplicitDeadline() {
    UUID externalTaskId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID firstQueue = UUID.randomUUID();
    UUID secondQueue = UUID.randomUUID();
    OffsetDateTime deadline = OffsetDateTime.parse("2026-07-18T10:15:30+03:00");
    server.expect(requestTo(
        "http://task.test/api/internal/task-board/v1/tasks/" + externalTaskId))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(content().string(containsString(
            "\"deadlineAt\":\"2026-07-18T10:15:30+03:00\"")))
        .andRespond(withSuccess(
            taskResponse(
                externalTaskId, warehouseId, deadline, List.of(firstQueue, secondQueue)),
            MediaType.APPLICATION_JSON));

    var response = gateway.updatePreStartTask(
        UUID.randomUUID(), externalTaskId, 0,
        List.of(
            taskStage(0, firstQueue, deadline),
            taskStage(1, secondQueue, deadline)));

    assertThat(response.version()).isOne();
    server.verify();
  }

  @Test
  void conflictingStageDeadlinesAreRejectedBeforeAnyHttpRequest() {
    OffsetDateTime first = OffsetDateTime.parse("2026-07-18T10:00:00+03:00");
    OffsetDateTime second = first.plusMinutes(1);

    assertThatThrownBy(() -> gateway.registerTask(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        List.of(
            taskStage(0, UUID.randomUUID(), first),
            taskStage(1, UUID.randomUUID(), second))))
        .isInstanceOfSatisfying(MaintenanceDependencyException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    server.verify();
  }

  @Test
  void noOpFixtureReturnsTheCanonicalActiveTaskState() {
    UUID externalTaskId = UUID.randomUUID();
    var response = new NoOpMaintenanceDependencyGateway().registerTask(
        UUID.randomUUID(), externalTaskId, UUID.randomUUID(), UUID.randomUUID(),
        List.of(taskStage(0, UUID.randomUUID(), null)));

    assertThat(response.externalTaskId()).isEqualTo(externalTaskId);
    assertThat(response.state()).isEqualTo("ACTIVE");
    assertThat(response.stages()).singleElement()
        .extracting(MaintenanceDependencyGateway.TaskStageSnapshot::routeIndex)
        .isEqualTo(0);
  }

  private static MaintenanceDependencyGateway.TaskStage taskStage(
      int order, UUID queueId, OffsetDateTime deadline) {
    return new MaintenanceDependencyGateway.TaskStage(
        UUID.randomUUID(), order, RepairStageKind.REPAIR_WORK,
        "Repair stage " + order, queueId.toString(), deadline);
  }

  private static String taskResponse(
      UUID externalTaskId,
      UUID warehouseId,
      OffsetDateTime deadline,
      List<UUID> queues) {
    StringBuilder route = new StringBuilder();
    for (int index = 0; index < queues.size(); index++) {
      if (index > 0) route.append(',');
      route.append("""
          {"entryId":"%s","entryVersion":0,"queueId":"%s",
           "queueCode":"Q%s","routeIndex":%s}
          """.formatted(UUID.randomUUID(), queues.get(index), index, index));
    }
    String deadlineJson = deadline == null ? "null" : "\"" + deadline + "\"";
    return """
        {"taskId":"%s","taskVersion":1,"warehouseId":"%s",
         "externalTaskId":"%s","title":"Maintenance repair","unitNumber":null,
         "description":null,"status":"ACTIVE","plannedDurationMinutes":null,
         "deadlineAt":%s,"doneAt":null,"route":[%s]}
        """.formatted(
        UUID.randomUUID(), warehouseId, externalTaskId, deadlineJson, route);
  }
}
