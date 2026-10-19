package dev.buhanzaz.rwms.logistics.integration;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Technical private-HTTP boundary shared by the logistics dependency clients.
 *
 * <p>It owns only client-credentials authorization and the already-established response failure
 * mapping. Remote-owner clients supply their approved registration, one exact scope, and wire
 * payload; this type neither interprets domain responses nor selects a remote endpoint.
 */
final class LogisticsOAuthHttpTransport {
  /** Selects the established generic, order-command, or resumable cabin-search failure mapping. */
  enum FailurePolicy {
    DEFAULT,
    ORDER,
    CABIN_SEARCH
  }

  private final RestClient client;
  private final OAuth2AuthorizedClientManager authorizedClients;

  LogisticsOAuthHttpTransport(
      RestClient client, OAuth2AuthorizedClientManager authorizedClients) {
    this.client = client;
    this.authorizedClients = authorizedClients;
  }

  <T> T get(
      String uri,
      Class<T> type,
      String registration,
      String scope,
      String emptyResponseMessage,
      FailurePolicy failurePolicy) {
    try {
      T response =
          client
              .get()
              .uri(uri)
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .retrieve()
              .body(type);
      if (response == null) throw malformed(emptyResponseMessage);
      return response;
    } catch (RuntimeException exception) {
      throw failure(failurePolicy, exception);
    }
  }

  <T> List<T> getList(
      String uri,
      ParameterizedTypeReference<List<T>> type,
      String registration,
      String scope,
      String emptyResponseMessage,
      FailurePolicy failurePolicy) {
    try {
      List<T> response =
          client
              .get()
              .uri(uri)
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .retrieve()
              .body(type);
      if (response == null) throw malformed(emptyResponseMessage);
      return response;
    } catch (RuntimeException exception) {
      throw failure(failurePolicy, exception);
    }
  }

  <T> T post(
      String uri,
      UUID key,
      Object body,
      Class<T> type,
      String registration,
      String scope,
      String emptyResponseMessage,
      FailurePolicy failurePolicy) {
    try {
      T response =
          client
              .post()
              .uri(uri)
              .header("Idempotency-Key", key.toString())
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .body(body)
              .retrieve()
              .body(type);
      if (response == null) throw malformed(emptyResponseMessage);
      return response;
    } catch (RuntimeException exception) {
      throw failure(failurePolicy, exception);
    }
  }

  <T> List<T> postList(
      String uri,
      UUID key,
      Object body,
      ParameterizedTypeReference<List<T>> type,
      String registration,
      String scope,
      String emptyResponseMessage,
      FailurePolicy failurePolicy) {
    try {
      List<T> response =
          client
              .post()
              .uri(uri)
              .header("Idempotency-Key", key.toString())
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .body(body)
              .retrieve()
              .body(type);
      if (response == null) throw malformed(emptyResponseMessage);
      return response;
    } catch (RuntimeException exception) {
      throw failure(failurePolicy, exception);
    }
  }

  <T> T postWithoutIdempotency(
      String uri,
      Object body,
      Class<T> type,
      String registration,
      String scope,
      String emptyResponseMessage,
      FailurePolicy failurePolicy) {
    try {
      T response =
          client
              .post()
              .uri(uri)
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .body(body)
              .retrieve()
              .body(type);
      if (response == null) throw malformed(emptyResponseMessage);
      return response;
    } catch (RuntimeException exception) {
      throw failure(failurePolicy, exception);
    }
  }

  /**
   * Posts already-canonical JSON without rebuilding it, preserving prepared command bytes and the
   * downstream idempotency key across every retry.
   */
  <T> T postExactJson(
      String uri,
      UUID key,
      String exactBody,
      Class<T> type,
      String registration,
      String scope,
      String emptyResponseMessage,
      FailurePolicy failurePolicy) {
    try {
      T response =
          client
              .post()
              .uri(uri)
              .header("Idempotency-Key", key.toString())
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .contentType(MediaType.APPLICATION_JSON)
              .body(exactBody)
              .retrieve()
              .body(type);
      if (response == null) throw malformed(emptyResponseMessage);
      return response;
    } catch (RuntimeException exception) {
      throw failure(failurePolicy, exception);
    }
  }

  <T> T put(
      String uri,
      UUID key,
      Object body,
      Class<T> type,
      String registration,
      String scope,
      String emptyResponseMessage,
      FailurePolicy failurePolicy) {
    try {
      T response =
          client
              .put()
              .uri(uri)
              .header("Idempotency-Key", key.toString())
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .body(body)
              .retrieve()
              .body(type);
      if (response == null) throw malformed(emptyResponseMessage);
      return response;
    } catch (RuntimeException exception) {
      throw failure(failurePolicy, exception);
    }
  }

  /** Sends an idempotent PUT whose successful owner response intentionally has no required body. */
  void putBodiless(
      String uri,
      UUID key,
      Object body,
      String registration,
      String scope,
      FailurePolicy failurePolicy) {
    try {
      ResponseEntity<Void> response =
          client
              .put()
              .uri(uri)
              .header("Idempotency-Key", key.toString())
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .body(body)
              .retrieve()
              .toBodilessEntity();
      if (response.getStatusCode() != HttpStatus.OK
          && response.getStatusCode() != HttpStatus.CREATED) {
        throw malformed("Dependency returned an unexpected idempotent PUT status");
      }
    } catch (RuntimeException exception) {
      throw failure(failurePolicy, exception);
    }
  }

  /** Sends already-canonical JSON with PUT so durable retries preserve the exact request bytes. */
  <T> T putExactJson(
      String uri,
      UUID key,
      String exactBody,
      Class<T> type,
      String registration,
      String scope,
      String emptyResponseMessage,
      FailurePolicy failurePolicy) {
    try {
      T response =
          client
              .put()
              .uri(uri)
              .header("Idempotency-Key", key.toString())
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .contentType(MediaType.APPLICATION_JSON)
              .body(exactBody)
              .retrieve()
              .body(type);
      if (response == null) throw malformed(emptyResponseMessage);
      return response;
    } catch (RuntimeException exception) {
      throw failure(failurePolicy, exception);
    }
  }

  <T> List<T> putList(
      String uri,
      UUID key,
      Object body,
      ParameterizedTypeReference<List<T>> type,
      String registration,
      String scope,
      String emptyResponseMessage,
      FailurePolicy failurePolicy) {
    try {
      List<T> response =
          client
              .put()
              .uri(uri)
              .header("Idempotency-Key", key.toString())
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .body(body)
              .retrieve()
              .body(type);
      if (response == null) throw malformed(emptyResponseMessage);
      return response;
    } catch (RuntimeException exception) {
      throw failure(failurePolicy, exception);
    }
  }

  <T> T putWithoutIdempotency(
      String uri,
      Object body,
      Class<T> type,
      String registration,
      String scope,
      String emptyResponseMessage,
      FailurePolicy failurePolicy) {
    try {
      T response =
          client
              .put()
              .uri(uri)
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .body(body)
              .retrieve()
              .body(type);
      if (response == null) throw malformed(emptyResponseMessage);
      return response;
    } catch (RuntimeException exception) {
      throw failure(failurePolicy, exception);
    }
  }

  void postWithoutIdempotencyBodiless(
      String uri, Object body, String registration, String scope, FailurePolicy failurePolicy) {
    try {
      client
          .post()
          .uri(uri)
          .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
          .body(body)
          .retrieve()
          .toBodilessEntity();
    } catch (RuntimeException exception) {
      throw failure(failurePolicy, exception);
    }
  }

  /**
   * Streams one bounded immutable binary command with its exact digest and content length. The
   * caller owns domain validation; this transport only preserves the private service credential and
   * replay headers.
   */
  <T> T postBytes(
      String uri,
      UUID key,
      String contentSha256,
      String contentType,
      byte[] bytes,
      Class<T> type,
      String registration,
      String scope,
      String emptyResponseMessage,
      FailurePolicy failurePolicy) {
    try {
      T response =
          client
              .post()
              .uri(uri)
              .header("Idempotency-Key", key.toString())
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .header("X-Content-SHA256", contentSha256)
              .header(HttpHeaders.CONTENT_LENGTH, Integer.toString(bytes.length))
              .contentType(MediaType.parseMediaType(contentType))
              .body(bytes)
              .retrieve()
              .body(type);
      if (response == null) throw malformed(emptyResponseMessage);
      return response;
    } catch (RuntimeException exception) {
      throw failure(failurePolicy, exception);
    }
  }

  ResponseEntity<byte[]> getBytes(
      String uri, String registration, String scope, FailurePolicy failurePolicy) {
    try {
      return client
          .get()
          .uri(uri)
          .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
          .retrieve()
          .toEntity(byte[].class);
    } catch (RuntimeException exception) {
      throw failure(failurePolicy, exception);
    }
  }

  private String bearer(String registration, String requiredScope) {
    var request =
        OAuth2AuthorizeRequest.withClientRegistrationId(registration)
            .principal("logistics-service:" + registration)
            .build();
    var authorized = authorizedClients.authorize(request);
    if (authorized == null
        || authorized.getAccessToken() == null
        || !authorized.getAccessToken().getScopes().equals(Set.of(requiredScope))) {
      throw new LogisticsDependencyException(
          LogisticsDependencyException.FailureKind.CONFIGURATION,
          "Dependency token does not have its one exact approved scope");
    }
    return "Bearer " + authorized.getAccessToken().getTokenValue();
  }

  static LogisticsDependencyException malformed(String message) {
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.CONFIGURATION, message);
  }

  private static LogisticsDependencyException failure(
      FailurePolicy failurePolicy, RuntimeException exception) {
    return switch (failurePolicy) {
      case DEFAULT -> defaultFailure(exception);
      case ORDER -> orderDependencyFailure(exception);
      case CABIN_SEARCH -> cabinSearchFailure(exception);
    };
  }

  static LogisticsDependencyException defaultFailure(RuntimeException exception) {
    if (exception instanceof LogisticsDependencyException known) return known;
    if (exception instanceof RestClientResponseException response) {
      HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
      if (status != null
          && status.is4xxClientError()
          && status != HttpStatus.TOO_MANY_REQUESTS) {
        return new LogisticsDependencyException(
            LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
            "Dependency rejected the logistics command",
            exception);
      }
    }
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.TRANSIENT,
        "Dependency outcome is unknown",
        exception);
  }

  private static LogisticsDependencyException orderDependencyFailure(RuntimeException exception) {
    if (exception instanceof LogisticsDependencyException known) return known;
    if (exception instanceof RestClientResponseException response) {
      HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
      if (status != null
          && status.is4xxClientError()
          && status != HttpStatus.TOO_MANY_REQUESTS) {
        return new LogisticsDependencyException(
            LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
            safeOrderDependencyCode(response.getResponseBodyAsString()),
            "Asset-service rejected the order command",
            exception);
      }
    }
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.TRANSIENT,
        "Asset-service order command outcome is unknown",
        exception);
  }

  /**
   * Keeps unknown/authentication/transport outcomes retryable and classifies only asset 400/409
   * responses as safe terminal cabin-search rejections.
   */
  private static LogisticsDependencyException cabinSearchFailure(RuntimeException exception) {
    if (exception instanceof LogisticsDependencyException known) return known;
    if (exception instanceof RestClientResponseException response) {
      HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
      if (status == HttpStatus.BAD_REQUEST || status == HttpStatus.CONFLICT) {
        return new LogisticsDependencyException(
            LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
            safeCabinSearchDependencyCode(status, response.getResponseBodyAsString()),
            "Asset-service rejected the cabin search command",
            exception);
      }
    }
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.TRANSIENT,
        "Asset-service cabin search outcome is unknown",
        exception);
  }

  /** Selects only an allow-listed technical code, otherwise a status-derived sanitized code. */
  private static String safeCabinSearchDependencyCode(HttpStatus status, String body) {
    if (body != null) {
      for (String code :
          List.of(
              "ASSET_INVALID_REQUEST",
              "ASSET_VALIDATION_FAILED",
              "ASSET_CONFLICT",
              "ASSET_PERSISTENCE_CONFLICT",
              "UNIT_NOT_AVAILABLE",
              "UNIT_ORDER_RESERVED",
              "UNIT_PRESENTATION_HELD")) {
        if (body.contains("\"code\":\"" + code + "\"")) return code;
      }
    }
    return status == HttpStatus.BAD_REQUEST
        ? "CABIN_SEARCH_INVALID"
        : "CABIN_SEARCH_CONFLICT";
  }

  private static String safeOrderDependencyCode(String body) {
    if (body == null) return null;
    for (String code :
        List.of(
            "UNIT_ALREADY_RESERVED",
            "UNIT_WAREHOUSE_MISMATCH",
            "UNIT_NOT_AVAILABLE",
            "UNIT_NOT_EDITABLE",
            "EQUIPMENT_QUANTITY_CONFLICT",
            "INSUFFICIENT_STOCK",
            "INSUFFICIENT_EQUIPMENT",
            "INSUFFICIENT_EQUIPMENT_SOURCE",
            "EQUIPMENT_NOT_AVAILABLE",
            "ORDER_RESERVATION_MISMATCH",
            "ORDER_WAREHOUSE_MISMATCH",
            "ASSET_NOT_FOUND")) {
      if (body.contains("\"code\":\"" + code + "\"")) return code;
    }
    return null;
  }
}
