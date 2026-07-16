package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.config.TaskBoardClientProperties;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class OAuthWorkerCredentialGateway implements WorkerCredentialGateway {
  private final RestClient client;
  private final OAuth2AuthorizedClientManager authorizedClients;
  private final String baseUrl;

  public OAuthWorkerCredentialGateway(
      RestClient workerCredentialRestClient,
      OAuth2AuthorizedClientManager authorizedClients,
      TaskBoardClientProperties properties) {
    this.client = workerCredentialRestClient;
    this.authorizedClients = authorizedClients;
    this.baseUrl = properties.workerCredentialsUrl().toString();
  }

  @Override
  public void configure(UUID workerId, UUID warehouseId, String appLogin, String password) {
    client
        .put()
        .uri(baseUrl + "/" + workerId)
        .header(HttpHeaders.AUTHORIZATION, bearer())
        .body(new ConfigureRequest(warehouseId, appLogin, password))
        .retrieve()
        .toBodilessEntity();
  }

  @Override
  public void reset(UUID workerId, String password) {
    post(workerId, "/reset", new PasswordRequest(password));
  }

  @Override
  public void disable(UUID workerId) {
    post(workerId, "/disable", null);
  }

  @Override
  public void delete(UUID workerId) {
    client
        .delete()
        .uri(baseUrl + "/" + workerId)
        .header(HttpHeaders.AUTHORIZATION, bearer())
        .retrieve()
        .toBodilessEntity();
  }

  @Override
  public WorkerCredentialSnapshot status(UUID workerId, UUID expectedWarehouseId) {
    try {
      StatusResponse response =
          client
              .get()
              .uri(baseUrl + "/" + workerId + "/status")
              .header(HttpHeaders.AUTHORIZATION, bearer())
              .retrieve()
              .body(StatusResponse.class);
      if (response == null) throw new IllegalStateException("Auth-service returned empty status");
      UUID returnedWorkerId = UUID.fromString(response.workerId());
      UUID returnedWarehouseId = UUID.fromString(response.warehouseId());
      WorkerCredentialStatus status = WorkerCredentialStatus.valueOf(response.status());
      String appLogin = normalize(response.appLogin());
      if (!workerId.equals(returnedWorkerId)
          || !expectedWarehouseId.equals(returnedWarehouseId)
          || (status == WorkerCredentialStatus.ACTIVE && appLogin == null)) {
        throw new IllegalStateException("Auth-service returned mismatched worker status");
      }
      return new WorkerCredentialSnapshot(
          returnedWorkerId, returnedWarehouseId, appLogin, status);
    } catch (HttpClientErrorException.NotFound missing) {
      return new WorkerCredentialSnapshot(
          workerId, expectedWarehouseId, null, WorkerCredentialStatus.ABSENT);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException("Auth-service returned malformed worker status", malformed);
    }
  }

  private void post(UUID id, String suffix, Object body) {
    var request =
        client.post().uri(baseUrl + "/" + id + suffix).header(HttpHeaders.AUTHORIZATION, bearer());
    if (body != null) request.body(body);
    request.retrieve().toBodilessEntity();
  }

  private String bearer() {
    var request =
        OAuth2AuthorizeRequest.withClientRegistrationId("auth-service")
            .principal("task-board-service")
            .build();
    var authorized = authorizedClients.authorize(request);
    if (authorized == null
        || authorized.getAccessToken() == null
        || !authorized.getAccessToken().getScopes().contains("worker-credentials.manage"))
      throw new IllegalStateException("Auth-service client token unavailable");
    return "Bearer " + authorized.getAccessToken().getTokenValue();
  }

  private String normalize(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private record ConfigureRequest(UUID warehouseId, String appLogin, String password) {
    @Override
    public String toString() {
      return "ConfigureRequest[warehouseId="
          + warehouseId
          + ", appLogin="
          + appLogin
          + ", password=<redacted>]";
    }
  }

  private record PasswordRequest(String password) {
    @Override
    public String toString() {
      return "PasswordRequest[password=<redacted>]";
    }
  }

  private record StatusResponse(
      String workerId, String warehouseId, String appLogin, String status) {}
}
