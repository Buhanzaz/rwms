package dev.buhanzaz.rwms.logistics.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpLogisticsDependencyGatewayTest {
  private OAuth2AuthorizedClientManager authorizedClients;
  private MockRestServiceServer server;
  private HttpLogisticsDependencyGateway gateway;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    authorizedClients = mock(OAuth2AuthorizedClientManager.class);
    when(authorizedClients.authorize(any())).thenAnswer(invocation -> {
      OAuth2AuthorizeRequest request = invocation.getArgument(0);
      String scope =
          switch (request.getClientRegistrationId()) {
            case "logistics-asset" -> "asset.logistics";
            case "logistics-warehouse" -> "warehouse.logistics";
            case "logistics-maintenance" -> "maintenance.logistics";
            case "logistics-media" -> "media.logistics";
            case "logistics-task-board" -> "task-board.logistics";
            default -> throw new IllegalArgumentException("unexpected registration");
          };
      OAuth2AuthorizedClient authorized = mock(OAuth2AuthorizedClient.class);
      when(authorized.getAccessToken())
          .thenReturn(
              new OAuth2AccessToken(
                  OAuth2AccessToken.TokenType.BEARER,
                  "test-" + scope,
                  Instant.now(),
                  Instant.now().plusSeconds(300),
                  Set.of(scope)));
      return authorized;
    });
    gateway =
        new HttpLogisticsDependencyGateway(
            builder.build(),
            authorizedClients,
            new LogisticsDependencyProperties.Validated(
                URI.create("http://auth.test/oauth2/token"),
                "logistics-service",
                "secret",
                URI.create("http://asset.test"),
                URI.create("http://warehouse.test"),
                URI.create("http://task-board.test"),
                URI.create("http://maintenance.test"),
                URI.create("http://media.test"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2)));
  }

  @Test
  void usesTheExactAssetScopeAndTypedReturnLeasePayload() {
    UUID key = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    server
        .expect(requestTo("http://asset.test/api/internal/asset/v1/logistics/operation-leases"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-asset.logistics"))
        .andExpect(header("Idempotency-Key", key.toString()))
        .andExpect(jsonPath("$.rentalItemId").value(assetId.toString()))
        .andExpect(jsonPath("$.ownerType").value("LOGISTICS_RETURN"))
        .andExpect(jsonPath("$.documentId").value(documentId.toString()))
        .andExpect(jsonPath("$.lineId").value(lineId.toString()))
        .andExpect(jsonPath("$.expectedRentalItemVersion").value(7))
        .andRespond(
            withSuccess(
                """
                {
                  "leaseId":"00000000-0000-0000-0000-000000000501",
                  "version":3,
                  "rentalItemId":"%s",
                  "fencingToken":11,
                  "state":"ACTIVE",
                  "expiresAt":"2026-07-17T12:00:00Z"
                }
                """
                    .formatted(assetId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.OperationLease response =
        gateway.acquireReturnLease(key, assetId, 7, documentId, lineId);

    assertThat(response.rentalItemId()).isEqualTo(assetId);
    assertThat(response.fencingToken()).isEqualTo(11);
    server.verify();
  }

  @Test
  void usesTheExactWarehouseScopeForTheNarrowIdentityRoute() {
    UUID warehouseId = UUID.randomUUID();
    server
        .expect(
            requestTo(
                "http://warehouse.test/api/internal/warehouse/v1/warehouses/logistics/"
                    + warehouseId
                    + "/identity"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer test-warehouse.logistics"))
        .andRespond(
            withSuccess(
                """
                {
                  "id":"%s",
                  "version":4,
                  "active":true,
                  "timeZone":"Europe/Moscow"
                }
                """
                    .formatted(warehouseId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.WarehouseIdentity identity = gateway.readWarehouseIdentity(warehouseId);

    assertThat(identity).isEqualTo(
        new LogisticsDependencyGateway.WarehouseIdentity(
            warehouseId, 4, true, "Europe/Moscow"));
    server.verify();
  }

  @Test
  void rejectsACombinedOrBroaderClientTokenBeforeSendingARequest() {
    OAuth2AuthorizedClient combined = mock(OAuth2AuthorizedClient.class);
    when(combined.getAccessToken())
        .thenReturn(
            new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "combined",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Set.of("asset.logistics", "warehouse.logistics")));
    doReturn(combined).when(authorizedClients).authorize(any());

    assertThatThrownBy(() -> gateway.readRentalItemSnapshot(UUID.randomUUID()))
        .isInstanceOf(LogisticsDependencyException.class)
        .extracting(exception -> ((LogisticsDependencyException) exception).kind())
        .isEqualTo(LogisticsDependencyException.FailureKind.CONFIGURATION);
    server.verify();
  }
}
