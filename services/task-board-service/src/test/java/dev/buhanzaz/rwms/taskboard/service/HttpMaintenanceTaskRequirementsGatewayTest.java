package dev.buhanzaz.rwms.taskboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpMaintenanceTaskRequirementsGatewayTest {
  private final UUID warehouse = UUID.randomUUID();
  private final UUID repair = UUID.randomUUID();
  private final OAuth2AuthorizedClientManager clients = mock(OAuth2AuthorizedClientManager.class);

  @Test
  void missingConfigurationFailsBeforeAuthorizationOrHttp() {
    RestClient client = mock(RestClient.class);
    var gateway = new HttpMaintenanceTaskRequirementsGateway(client, clients, "");
    assertThatThrownBy(() -> gateway.read(warehouse, repair))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAINTENANCE_SERVICE_URL");
    verifyNoInteractions(clients, client);
  }

  @Test
  void missingCredentialFailsWithoutIssuingHttp() {
    RestClient client = mock(RestClient.class);
    var gateway = new HttpMaintenanceTaskRequirementsGateway(client, clients, "http://maintenance.test");
    assertThatThrownBy(() -> gateway.read(warehouse, repair))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("credential is unavailable");
    verifyNoInteractions(client);
  }

  @Test
  void configuredPrivateOriginUsesDedicatedCredentialAndChecksReturnedIdentity() {
    OAuth2AuthorizedClient authorized = mock(OAuth2AuthorizedClient.class);
    when(authorized.getAccessToken()).thenReturn(new OAuth2AccessToken(
        OAuth2AccessToken.TokenType.BEARER, "test-token", Instant.now(), Instant.now().plusSeconds(300),
        Set.of("maintenance.task-requirements")));
    when(clients.authorize(any())).thenAnswer(invocation -> {
      OAuth2AuthorizeRequest request = invocation.getArgument(0);
      assertThat(request.getClientRegistrationId()).isEqualTo("maintenance-task-requirements");
      assertThat(request.getPrincipal().getName()).isEqualTo("task-board-service");
      return authorized;
    });
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    String url = "http://maintenance.test/api/internal/maintenance/v1/repairs/" + repair
        + "/task-requirements?warehouseId=" + warehouse;
    server.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer test-token"))
        .andRespond(withSuccess("{\"repairId\":\"" + repair + "\",\"warehouseId\":\"" + warehouse
            + "\",\"items\":[]}", MediaType.APPLICATION_JSON));
    server.expect(requestTo(url)).andRespond(withSuccess("{\"repairId\":\"" + UUID.randomUUID()
        + "\",\"warehouseId\":\"" + warehouse + "\",\"items\":[]}", MediaType.APPLICATION_JSON));
    var gateway = new HttpMaintenanceTaskRequirementsGateway(builder.build(), clients, "http://maintenance.test/");
    assertThat(gateway.read(warehouse, repair).items()).isEmpty();
    assertThatThrownBy(() -> gateway.read(warehouse, repair)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid task requirements");
    server.verify();
  }
}
