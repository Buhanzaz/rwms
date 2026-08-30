package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.config.WarehouseLifecycleClientProperties;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** OAuth-protected client for warehouse-service's generic internal identity contract. */
@Component
public class HttpWarehouseIdentityGateway implements WarehouseIdentityGateway {
  static final String REGISTRATION = "warehouse-identity-read";
  static final String SCOPE = "warehouse.identity.read";
  private final RestClient client;
  private final OAuth2AuthorizedClientManager authorizedClients;
  private final String baseUrl;

  public HttpWarehouseIdentityGateway(
      @Qualifier("warehouseLifecycleRestClient") RestClient client,
      OAuth2AuthorizedClientManager authorizedClients,
      WarehouseLifecycleClientProperties properties) {
    this.client = client;
    this.authorizedClients = authorizedClients;
    baseUrl = HttpWarehouseLifecycleGateway.normalizeBaseUrl(properties.baseUrl());
  }

  @Override
  public WarehouseIdentity identity(UUID warehouseId) {
    try {
      WarehouseIdentity response =
          client
              .get()
              .uri(baseUrl + "/api/internal/warehouse/v1/warehouses/" + warehouseId + "/identity")
              .header(HttpHeaders.AUTHORIZATION, bearer())
              .retrieve()
              .body(WarehouseIdentity.class);
      if (response == null
          || !warehouseId.equals(response.id())
          || response.timeZone() == null
          || response.timeZone().isBlank())
        throw new IllegalArgumentException("Malformed warehouse identity");
      return response;
    } catch (RuntimeException exception) {
      throw new ExternalServiceException("Warehouse identity dependency is unavailable", exception);
    }
  }

  private String bearer() {
    var request =
        OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION)
            .principal("task-board-service:" + REGISTRATION)
            .build();
    var authorized = authorizedClients.authorize(request);
    if (authorized == null
        || authorized.getAccessToken() == null
        || !authorized.getAccessToken().getScopes().equals(Set.of(SCOPE)))
      throw new ExternalServiceException(
          "Warehouse identity token does not have its exact approved scope", null);
    return "Bearer " + authorized.getAccessToken().getTokenValue();
  }
}
