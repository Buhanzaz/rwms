package dev.buhanzaz.rwms.taskboard.service;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Bounded private read using the task-board service identity and a dedicated scope. */
@Component
public class HttpMaintenanceTaskRequirementsGateway implements MaintenanceTaskRequirementsGateway {
  private final RestClient client;
  private final OAuth2AuthorizedClientManager clients;
  private final String baseUrl;

  public HttpMaintenanceTaskRequirementsGateway(
      @Qualifier("warehouseLifecycleRestClient") RestClient client,
      OAuth2AuthorizedClientManager clients,
      @Value("${rwms.maintenance.base-url:}") String baseUrl) {
    this.client = client;
    this.clients = clients;
    this.baseUrl = baseUrl.replaceAll("/+$", "");
  }

  @Override
  public Requirements read(UUID warehouseId, UUID repairId) {
    if (baseUrl.isBlank()) {
      throw new IllegalStateException("MAINTENANCE_SERVICE_URL is required to read task requirements");
    }
    var authorized = clients.authorize(OAuth2AuthorizeRequest
        .withClientRegistrationId("maintenance-task-requirements")
        .principal("task-board-service").build());
    if (authorized == null || authorized.getAccessToken() == null) {
      throw new IllegalStateException("Maintenance task requirements credential is unavailable");
    }
    Requirements result = client.get()
        .uri(baseUrl + "/api/internal/maintenance/v1/repairs/{repairId}/task-requirements?warehouseId={warehouseId}",
            repairId, warehouseId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authorized.getAccessToken().getTokenValue())
        .retrieve().body(Requirements.class);
    if (result == null || !repairId.equals(result.repairId())
        || !warehouseId.equals(result.warehouseId()) || result.items() == null) {
      throw new IllegalStateException("Maintenance returned invalid task requirements");
    }
    return result;
  }
}
