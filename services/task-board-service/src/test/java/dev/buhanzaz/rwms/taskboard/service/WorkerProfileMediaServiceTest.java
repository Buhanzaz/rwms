package dev.buhanzaz.rwms.taskboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.buhanzaz.rwms.taskboard.config.WorkerProfileMediaProperties;
import dev.buhanzaz.rwms.taskboard.domain.Worker;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class WorkerProfileMediaServiceTest {
  private static final UUID WORKER_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final UUID WAREHOUSE_ID =
      UUID.fromString("20000000-0000-4000-8000-000000000002");

  @Test
  void establishesOnlyTheAuthenticatedActiveWorkersCanonicalAvatarScope() {
    WorkforceService workforce = mock(WorkforceService.class);
    Worker worker = mock(Worker.class);
    when(worker.isActive()).thenReturn(true);
    when(workforce.requireWorker(WAREHOUSE_ID, WORKER_ID)).thenReturn(worker);

    OAuth2AuthorizedClientManager clients = mock(OAuth2AuthorizedClientManager.class);
    OAuth2AuthorizedClient authorized = mock(OAuth2AuthorizedClient.class);
    when(authorized.getAccessToken())
        .thenReturn(
            new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "media-token",
                Instant.parse("2026-09-03T10:00:00Z"),
                Instant.parse("2026-09-03T10:05:00Z"),
                Set.of("media.task-board")));
    when(clients.authorize(any())).thenReturn(authorized);

    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    UUID proofEventId =
        UUID.nameUUIDFromBytes(
            ("worker-profile-avatar-proof:" + WORKER_ID).getBytes(StandardCharsets.UTF_8));
    server
        .expect(once(), requestTo("http://media.test/api/internal/media/v1/owner-proofs"))
        .andExpect(method(POST))
        .andExpect(header("Authorization", "Bearer media-token"))
        .andRespond(
            withSuccess(
                """
                {
                  "ownerType":"TASK_BOARD_WORKER_PROFILE",
                  "ownerId":"%s",
                  "warehouseId":"%s",
                  "ownerRevision":0,
                  "aggregateVersion":0,
                  "proofEventId":"%s",
                  "active":true
                }
                """
                    .formatted(WORKER_ID, WAREHOUSE_ID, proofEventId),
                MediaType.APPLICATION_JSON));

    var service =
        new WorkerProfileMediaService(
            workforce,
            builder.build(),
            clients,
            new WorkerProfileMediaProperties(URI.create("http://media.test/")));

    var scope = service.prepare(WORKER_ID, WAREHOUSE_ID);

    assertThat(scope.ownerType()).isEqualTo("TASK_BOARD_WORKER_PROFILE");
    assertThat(scope.ownerId()).isEqualTo(WORKER_ID);
    assertThat(scope.warehouseId()).isEqualTo(WAREHOUSE_ID);
    assertThat(scope.context()).isEqualTo("PROFILE_AVATAR");
    server.verify();
  }
}
