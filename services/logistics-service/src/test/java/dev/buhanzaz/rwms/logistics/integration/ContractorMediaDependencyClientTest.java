package dev.buhanzaz.rwms.logistics.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
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

/** Verifies exact private contractor evidence bytes and immutable media reads. */
class ContractorMediaDependencyClientTest {
  private static final UUID WAREHOUSE_ID = UUID.fromString("21000000-0000-0000-0000-000000000001");
  private static final UUID WORKER_ID = UUID.fromString("21000000-0000-0000-0000-000000000002");
  private static final UUID ENTRY_ID = UUID.fromString("21000000-0000-0000-0000-000000000003");
  private static final UUID EVIDENCE_ID = UUID.fromString("21000000-0000-0000-0000-000000000004");
  private static final UUID MEDIA_ID = UUID.fromString("21000000-0000-0000-0000-000000000005");
  private static final byte[] IMAGE = new byte[] {1, 2, 3, 4};
  private static final String SHA256 =
      "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a";

  private MockRestServiceServer server;
  private LogisticsMediaDependencyClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    OAuth2AuthorizedClientManager authorizedClients = mock(OAuth2AuthorizedClientManager.class);
    OAuth2AuthorizedClient authorizedClient = authorizedClient();
    when(authorizedClients.authorize(any())).thenReturn(authorizedClient);
    client =
        new LogisticsMediaDependencyClient(
            new LogisticsOAuthHttpTransport(builder.build(), authorizedClients),
            "http://media.test/api/internal/media/v1");
  }

  @Test
  void uploadsExactBytesWithEvidenceReplayAndActualLengthHeaders() {
    server
        .expect(requestTo(uploadPath()))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer media-token"))
        .andExpect(header("Idempotency-Key", EVIDENCE_ID.toString()))
        .andExpect(header("X-Content-SHA256", SHA256))
        .andExpect(header("Content-Length", Integer.toString(IMAGE.length)))
        .andExpect(content().contentType(MediaType.IMAGE_JPEG))
        .andExpect(content().bytes(IMAGE))
        .andRespond(
            withSuccess(
                """
                {"mediaId":"%s","generation":0,"status":"PROCESSING"}
                """
                    .formatted(MEDIA_ID),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.ContractorEvidenceMediaReceipt receipt =
        client.uploadContractorTaskEvidence(
            WAREHOUSE_ID, WORKER_ID, ENTRY_ID, EVIDENCE_ID, "image/jpeg", SHA256, IMAGE);

    assertThat(receipt.mediaId()).isEqualTo(MEDIA_ID);
    assertThat(receipt.generation()).isZero();
    assertThat(receipt.status()).isEqualTo("PROCESSING");
    server.verify();
  }

  @Test
  void readsOnlyTheExactGenerationAndVariantAsWebp() {
    server
        .expect(requestTo(readPath("SMALL")))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer media-token"))
        .andRespond(withSuccess(IMAGE, MediaType.parseMediaType("image/webp")));

    LogisticsDependencyGateway.MediaContent content =
        client.readContractorTaskMedia(WAREHOUSE_ID, WORKER_ID, ENTRY_ID, MEDIA_ID, 2, "SMALL");

    assertThat(content.bytes()).containsExactly(IMAGE);
    assertThat(content.contentType()).isEqualTo("image/webp");
    server.verify();
  }

  private static String uploadPath() {
    return "http://media.test/api/internal/media/v1/logistics/contractor-task-executions/"
        + ENTRY_ID
        + "/workers/"
        + WORKER_ID
        + "/evidence/"
        + EVIDENCE_ID
        + "?warehouseId="
        + WAREHOUSE_ID;
  }

  private static String readPath(String variant) {
    return "http://media.test/api/internal/media/v1/logistics/contractor-task-executions/"
        + ENTRY_ID
        + "/workers/"
        + WORKER_ID
        + "/assets/"
        + MEDIA_ID
        + "/generations/2/variants/"
        + variant
        + "/content?warehouseId="
        + WAREHOUSE_ID;
  }

  private static OAuth2AuthorizedClient authorizedClient() {
    OAuth2AuthorizedClient authorized = mock(OAuth2AuthorizedClient.class);
    when(authorized.getAccessToken())
        .thenReturn(
            new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "media-token",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Set.of("media.logistics")));
    return authorized;
  }
}
