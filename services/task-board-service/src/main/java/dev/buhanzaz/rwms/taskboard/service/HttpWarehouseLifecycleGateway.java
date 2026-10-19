package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.config.WarehouseLifecycleClientProperties;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/** HTTP implementation of the two exact warehouse lifecycle service scopes. */
@Component
public class HttpWarehouseLifecycleGateway implements WarehouseLifecycleGateway {
  static final String READ_REGISTRATION = "warehouse-lifecycle-read";
  static final String CONFIRM_REGISTRATION = "warehouse-lifecycle-confirm";
  static final String READ_SCOPE = "warehouse.lifecycle.read";
  static final String CONFIRM_SCOPE = "warehouse.lifecycle.confirm";

  private final RestClient client;
  private final OAuth2AuthorizedClientManager authorizedClients;
  private final String warehouseBaseUrl;

  public HttpWarehouseLifecycleGateway(
      @Qualifier("warehouseLifecycleRestClient") RestClient client,
      OAuth2AuthorizedClientManager authorizedClients,
      WarehouseLifecycleClientProperties properties) {
    this.client = client;
    this.authorizedClients = authorizedClients;
    this.warehouseBaseUrl = normalizeBaseUrl(properties.baseUrl());
  }

  @Override
  public void requireAdmission(UUID warehouseId, OperationDirection direction) {
    if (warehouseId == null || direction == null) {
      throw new IllegalArgumentException("Warehouse lifecycle admission is incomplete");
    }
    String uri =
        UriComponentsBuilder.fromUriString(
                warehouseBaseUrl
                    + "/api/internal/warehouse/v1/warehouses/"
                    + warehouseId
                    + "/admission")
            .queryParam("direction", direction.name())
            .build()
            .encode()
            .toUriString();
    AdmissionResponse response = get(uri, AdmissionResponse.class, READ_REGISTRATION, READ_SCOPE);
    LifecycleState state = validateAdmission(warehouseId, direction, response);
    boolean expectedAdmission =
        switch (state) {
          case ACTIVE -> true;
          case DRAINING -> direction == OperationDirection.OUTGOING;
          case INACTIVE -> false;
        };
    if (response.admitted() != expectedAdmission) {
      throw unavailable("Warehouse-service returned an inconsistent lifecycle admission", null);
    }
    if (!response.admitted()) {
      throw new ConflictException("Склад не принимает " + admissionLabel(direction) + " операции");
    }
  }

  @Override
  public ReadinessWorkPage readinessWork(UUID after, int limit) {
    if (limit < 1 || limit > 500) {
      throw new IllegalArgumentException("Warehouse lifecycle page size must be from 1 to 500");
    }
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromUriString(
                warehouseBaseUrl + "/api/internal/warehouse/v1/lifecycle/readiness-work")
            .queryParam("limit", limit);
    if (after != null) {
      uri.queryParam("after", after);
    }
    ReadinessWorkPage page =
        get(uri.build().encode().toUriString(), ReadinessWorkPage.class, READ_REGISTRATION, READ_SCOPE);
    validateReadinessPage(page);
    return page;
  }

  @Override
  public void confirmReadiness(UUID warehouseId, long expectedVersion) {
    if (warehouseId == null || expectedVersion < 0) {
      throw new IllegalArgumentException("Warehouse lifecycle readiness fence is invalid");
    }
    ConfirmationResponse response;
    try {
      response =
          client
              .post()
              .uri(
                  warehouseBaseUrl
                      + "/api/internal/warehouse/v1/warehouses/"
                      + warehouseId
                      + "/lifecycle-readiness")
              .header(HttpHeaders.AUTHORIZATION, bearer(CONFIRM_REGISTRATION, CONFIRM_SCOPE))
              .body(new ReadinessRequest(expectedVersion))
              .retrieve()
              .body(ConfirmationResponse.class);
    } catch (RestClientResponseException exception) {
      if (exception.getStatusCode() == HttpStatus.CONFLICT) {
        throw new WarehouseLifecycleReadinessConflictException(
            "Warehouse lifecycle version changed before task-board readiness confirmation", exception);
      }
      throw unavailable("Warehouse lifecycle readiness confirmation is unavailable", exception);
    } catch (RuntimeException exception) {
      throw unavailable("Warehouse lifecycle readiness confirmation is unavailable", exception);
    }
    if (response == null
        || !warehouseId.equals(response.warehouseId())
        || response.warehouseVersion() < 0
        || !"DRAINING".equals(response.lifecycleState())
        || !"TASK_BOARD".equals(response.readinessOwner())
        || response.confirmedAt() == null) {
      throw unavailable("Warehouse-service returned malformed task-board readiness confirmation", null);
    }
  }

  private <T> T get(String uri, Class<T> responseType, String registration, String scope) {
    try {
      T response =
          client
              .get()
              .uri(uri)
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .retrieve()
              .body(responseType);
      if (response == null) {
        throw unavailable("Warehouse-service returned an empty lifecycle response", null);
      }
      return response;
    } catch (RuntimeException exception) {
      throw unavailable("Warehouse lifecycle dependency is unavailable", exception);
    }
  }

  private String bearer(String registration, String requiredScope) {
    var request =
        OAuth2AuthorizeRequest.withClientRegistrationId(registration)
            .principal("task-board-service:" + registration)
            .build();
    var authorized = authorizedClients.authorize(request);
    if (authorized == null
        || authorized.getAccessToken() == null
        || !authorized.getAccessToken().getScopes().equals(Set.of(requiredScope))) {
      throw unavailable("Warehouse lifecycle token does not have its exact approved scope", null);
    }
    return "Bearer " + authorized.getAccessToken().getTokenValue();
  }

  private LifecycleState validateAdmission(
      UUID warehouseId, OperationDirection direction, AdmissionResponse response) {
    if (response == null
        || !warehouseId.equals(response.warehouseId())
        || response.warehouseVersion() < 0
        || response.direction() != direction) {
      throw unavailable("Warehouse-service returned malformed lifecycle admission", null);
    }
    try {
      return LifecycleState.valueOf(response.lifecycleState());
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw unavailable("Warehouse-service returned an unknown lifecycle state", exception);
    }
  }

  private void validateReadinessPage(ReadinessWorkPage page) {
    if (page == null || page.items() == null) {
      throw unavailable("Warehouse-service returned malformed lifecycle readiness work", null);
    }
    Set<UUID> ids = new HashSet<>();
    for (ReadinessWork item : page.items()) {
      if (item == null
          || item.warehouseId() == null
          || item.warehouseVersion() < 0
          || !"DRAINING".equals(item.lifecycleState())
          || !ids.add(item.warehouseId())) {
        throw unavailable("Warehouse-service returned malformed lifecycle readiness item", null);
      }
    }
    if (page.nextAfter() != null
        && (page.items().isEmpty()
            || !page.nextAfter().equals(page.items().getLast().warehouseId()))) {
      throw unavailable("Warehouse-service returned an invalid lifecycle readiness cursor", null);
    }
  }

  static String normalizeBaseUrl(URI value) {
    if (value == null
        || value.getScheme() == null
        || value.getHost() == null
        || value.getUserInfo() != null
        || value.getQuery() != null
        || value.getFragment() != null) {
      throw new IllegalArgumentException("Warehouse lifecycle base URL must be an absolute origin URI");
    }
    String result = value.toString();
    return result.endsWith("/") ? result.substring(0, result.length() - 1) : result;
  }

  private static ExternalServiceException unavailable(String message, Throwable cause) {
    if (cause instanceof ExternalServiceException exception) {
      return exception;
    }
    return new ExternalServiceException(message, cause);
  }

  private static String admissionLabel(OperationDirection direction) {
    return direction == OperationDirection.INCOMING ? "входящие" : "исходящие";
  }

  private enum LifecycleState {
    ACTIVE,
    DRAINING,
    INACTIVE
  }

  private record AdmissionResponse(
      UUID warehouseId,
      long warehouseVersion,
      String lifecycleState,
      OperationDirection direction,
      boolean admitted) {}

  private record ConfirmationResponse(
      UUID warehouseId,
      long warehouseVersion,
      String lifecycleState,
      String readinessOwner,
      OffsetDateTime confirmedAt) {}

  private record ReadinessRequest(long expectedVersion) {}
}
