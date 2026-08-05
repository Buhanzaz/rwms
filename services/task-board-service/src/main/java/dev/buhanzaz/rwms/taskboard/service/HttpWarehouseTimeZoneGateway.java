package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.config.WarehouseLifecycleClientProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Fail-closed client for warehouse-service's historical timezone boundary.
 *
 * <p>The private API returns one effective-dated fact at a time. A bounded reverse walk from a
 * far-future point reconstructs only the pieces needed by KPI calendar calculations; no mutable
 * timezone history is replicated into task-board storage.
 */
@Component
public class HttpWarehouseTimeZoneGateway implements WarehouseTimeZoneGateway {
  static final String REGISTRATION = "warehouse-timezone-read";
  static final String SCOPE = "warehouse.timezone.read";
  private static final int MAX_TIMELINE_DECISIONS = 100;
  private static final int MAX_CACHE_ENTRIES = 256;
  private static final Duration CACHE_TTL = Duration.ofSeconds(30);

  private final RestClient client;
  private final OAuth2AuthorizedClientManager authorizedClients;
  private final String warehouseBaseUrl;
  private final Map<LookupKey, CachedDecision> decisions =
      new LinkedHashMap<>(MAX_CACHE_ENTRIES, 0.75f, true);

  public HttpWarehouseTimeZoneGateway(
      @Qualifier("warehouseLifecycleRestClient") RestClient client,
      OAuth2AuthorizedClientManager authorizedClients,
      WarehouseLifecycleClientProperties properties) {
    this.client = client;
    this.authorizedClients = authorizedClients;
    this.warehouseBaseUrl = HttpWarehouseLifecycleGateway.normalizeBaseUrl(properties.baseUrl());
  }

  @Override
  public TimeZoneDecision timeZoneAt(UUID warehouseId, Instant at) {
    if (warehouseId == null || at == null) {
      throw new IllegalArgumentException("Warehouse timezone lookup requires warehouse and timestamp");
    }
    LookupKey key = new LookupKey(warehouseId, at);
    TimeZoneDecision cached = cached(key);
    if (cached != null) {
      return cached;
    }

    TimeZoneDecision resolved = request(warehouseId, at);
    cache(key, resolved);
    return resolved;
  }

  @Override
  public List<TimeZoneSegment> timeline(
      UUID warehouseId, Instant fromInclusive, Instant toExclusive) {
    if (warehouseId == null || fromInclusive == null || toExclusive == null) {
      throw new IllegalArgumentException("Warehouse timezone timeline is incomplete");
    }
    if (!fromInclusive.isBefore(toExclusive)) {
      return List.of();
    }
    if (toExclusive.isAfter(FAR_FUTURE)) {
      throw unavailable("Warehouse timezone interval exceeds the supported historical bound", null);
    }

    List<TimeZoneDecision> descending = new ArrayList<>();
    Instant cursor = FAR_FUTURE;
    for (int attempt = 0; attempt < MAX_TIMELINE_DECISIONS; attempt++) {
      TimeZoneDecision current = timeZoneAt(warehouseId, cursor);
      if (current.effectiveFrom().isAfter(cursor)) {
        throw unavailable("Warehouse-service returned a future-effective timezone fact", null);
      }
      if (!descending.isEmpty()
          && !current.effectiveFrom().isBefore(descending.getLast().effectiveFrom())) {
        throw unavailable("Warehouse-service returned a repeated timezone boundary", null);
      }
      descending.add(current);
      if (!current.effectiveFrom().isAfter(fromInclusive)) {
        return segments(descending, fromInclusive, toExclusive);
      }
      if (current.effectiveFrom().equals(Instant.MIN)) {
        break;
      }
      cursor = current.effectiveFrom().minusNanos(1);
    }
    throw unavailable("Warehouse timezone history exceeds the bounded reconstruction limit", null);
  }

  @Override
  public void invalidate(UUID warehouseId) {
    if (warehouseId == null) {
      return;
    }
    synchronized (decisions) {
      Iterator<LookupKey> iterator = decisions.keySet().iterator();
      while (iterator.hasNext()) {
        if (warehouseId.equals(iterator.next().warehouseId())) {
          iterator.remove();
        }
      }
    }
  }

  private List<TimeZoneSegment> segments(
      List<TimeZoneDecision> descending, Instant fromInclusive, Instant toExclusive) {
    List<TimeZoneDecision> ascending = new ArrayList<>(descending);
    java.util.Collections.reverse(ascending);
    List<TimeZoneSegment> result = new ArrayList<>();
    for (int index = 0; index < ascending.size(); index++) {
      TimeZoneDecision current = ascending.get(index);
      Instant nextEffective =
          index + 1 < ascending.size() ? ascending.get(index + 1).effectiveFrom() : FAR_FUTURE;
      Instant start = current.effectiveFrom().isAfter(fromInclusive) ? current.effectiveFrom() : fromInclusive;
      Instant end = nextEffective.isBefore(toExclusive) ? nextEffective : toExclusive;
      if (start.isBefore(end)) {
        result.add(new TimeZoneSegment(current.timeZone(), start, end));
      }
    }
    if (result.isEmpty()
        || !result.getFirst().fromInclusive().equals(fromInclusive)
        || !result.getLast().toExclusive().equals(toExclusive)) {
      throw unavailable("Warehouse-service returned an incomplete timezone timeline", null);
    }
    for (int index = 1; index < result.size(); index++) {
      if (!result.get(index - 1).toExclusive().equals(result.get(index).fromInclusive())) {
        throw unavailable("Warehouse-service returned a discontinuous timezone timeline", null);
      }
    }
    return List.copyOf(result);
  }

  private TimeZoneDecision request(UUID warehouseId, Instant at) {
    String uri =
        UriComponentsBuilder.fromUriString(
                warehouseBaseUrl
                    + "/api/internal/warehouse/v1/warehouses/"
                    + warehouseId
                    + "/time-zone")
            .queryParam("at", at.toString())
            .build()
            .encode()
            .toUriString();
    WarehouseTimeZoneResponse response;
    try {
      response =
          client
              .get()
              .uri(uri)
              .header(HttpHeaders.AUTHORIZATION, bearer())
              .retrieve()
              .body(WarehouseTimeZoneResponse.class);
    } catch (RuntimeException exception) {
      throw unavailable("Warehouse timezone dependency is unavailable", exception);
    }
    if (response == null
        || !warehouseId.equals(response.warehouseId())
        || response.timeZone() == null
        || response.timeZone().isBlank()
        || response.effectiveFrom() == null) {
      throw unavailable("Warehouse-service returned a malformed timezone response", null);
    }
    try {
      ZoneId zone = ZoneId.of(response.timeZone());
      if (!zone.getId().equals(response.timeZone())) {
        throw new IllegalArgumentException("Timezone is not canonical");
      }
      Instant effectiveFrom = response.effectiveFrom().toInstant();
      if (effectiveFrom.isAfter(at)) {
        throw new IllegalArgumentException("Timezone fact begins after lookup timestamp");
      }
      return new TimeZoneDecision(zone, effectiveFrom);
    } catch (RuntimeException exception) {
      throw unavailable("Warehouse-service returned an invalid timezone fact", exception);
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
        || !authorized.getAccessToken().getScopes().equals(Set.of(SCOPE))) {
      throw unavailable("Warehouse timezone token does not have its exact approved scope", null);
    }
    return "Bearer " + authorized.getAccessToken().getTokenValue();
  }

  private TimeZoneDecision cached(LookupKey key) {
    Instant now = Instant.now();
    synchronized (decisions) {
      CachedDecision cached = decisions.get(key);
      if (cached == null) {
        return null;
      }
      if (!cached.expiresAt().isAfter(now)) {
        decisions.remove(key);
        return null;
      }
      return cached.decision();
    }
  }

  private void cache(LookupKey key, TimeZoneDecision decision) {
    synchronized (decisions) {
      if (decisions.size() >= MAX_CACHE_ENTRIES) {
        Iterator<LookupKey> iterator = decisions.keySet().iterator();
        if (iterator.hasNext()) {
          iterator.next();
          iterator.remove();
        }
      }
      decisions.put(key, new CachedDecision(decision, Instant.now().plus(CACHE_TTL)));
    }
  }

  private static ExternalServiceException unavailable(String message, Throwable cause) {
    if (cause instanceof ExternalServiceException exception) {
      return exception;
    }
    return new ExternalServiceException(message, cause);
  }

  private record LookupKey(UUID warehouseId, Instant at) {}

  private record CachedDecision(TimeZoneDecision decision, Instant expiresAt) {}

  private record WarehouseTimeZoneResponse(
      UUID warehouseId, String timeZone, OffsetDateTime effectiveFrom) {}
}
