package dev.buhanzaz.rwms.maintenance.integration;

import dev.buhanzaz.rwms.maintenance.service.MaintenanceDependencyException;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Technical HTTP transport for maintenance's private dependencies.
 *
 * <p>It owns only exact-scope service OAuth, idempotent request mechanics, and the frozen error
 * policy. Remote-owner payloads, URLs, and response validation stay in their owner clients.
 */
final class MaintenanceHttpTransport {
  private final RestClient client;
  private final OAuth2AuthorizedClientManager authorizedClients;

  MaintenanceHttpTransport(
      RestClient client, OAuth2AuthorizedClientManager authorizedClients) {
    this.client = client;
    this.authorizedClients = authorizedClients;
  }

  RestClient client() {
    return client;
  }

  <T> T post(
      String uri, UUID key, Object body, Class<T> type, String registration, String scope) {
    try {
      T response = client.post()
          .uri(uri)
          .header("Idempotency-Key", key.toString())
          .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
          .body(body)
          .retrieve()
          .body(type);
      if (response == null) {
        throw malformed("Dependency returned an empty response");
      }
      return response;
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  <T> T put(
      String uri, UUID key, Object body, Class<T> type, String registration, String scope) {
    try {
      T response = client.put()
          .uri(uri)
          .header("Idempotency-Key", key.toString())
          .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
          .body(body)
          .retrieve()
          .body(type);
      if (response == null) {
        throw malformed("Dependency returned an empty response");
      }
      return response;
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  String bearer(String registration, String requiredScope) {
    var request = OAuth2AuthorizeRequest.withClientRegistrationId(registration)
        .principal("maintenance-service:" + registration)
        .build();
    var authorized = authorizedClients.authorize(request);
    if (authorized == null
        || authorized.getAccessToken() == null
        || !authorized.getAccessToken().getScopes().equals(Set.of(requiredScope))) {
      throw malformed("Dependency token does not have its one exact approved scope");
    }
    return "Bearer " + authorized.getAccessToken().getTokenValue();
  }

  RuntimeException dependencyFailure(RuntimeException exception) {
    if (exception instanceof MaintenanceDependencyException known) {
      return known;
    }
    if (exception instanceof RestClientResponseException response) {
      HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
      return new MaintenanceDependencyException(
          status == null ? HttpStatus.SERVICE_UNAVAILABLE : status,
          "Maintenance dependency rejected the command",
          exception);
    }
    return new MaintenanceDependencyException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Maintenance dependency outcome is unknown",
        exception);
  }

  RuntimeException furnitureDependencyFailure(RuntimeException exception) {
    if (exception instanceof MaintenanceDependencyException known) {
      return known;
    }
    if (exception instanceof RestClientResponseException response
        && response.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
      return new MaintenanceDependencyException(
          HttpStatus.CONFLICT,
          "Asset-service rejected the furniture equipment identity",
          exception);
    }
    return new MaintenanceDependencyException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Furniture equipment synchronization outcome is unknown",
        exception);
  }

  static MaintenanceDependencyException malformed(String message) {
    return new MaintenanceDependencyException(HttpStatus.SERVICE_UNAVAILABLE, message);
  }

  static String strip(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
