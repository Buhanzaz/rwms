package dev.buhanzaz.rwms.logistics.customer.routing;

import dev.buhanzaz.rwms.logistics.customer.config.CustomerDeliveryProperties;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerTravelTimeMatrix.GeoPoint;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Bounded Valhalla matrix adapter using truck costing and the same private graph as the standalone
 * logistics planner. It reuses one HTTP connection pool, expires cached graph results, assembles
 * larger exact directed matrices from provider-bounded blocks and never falls back to
 * straight-line or car estimates.
 */
@Component
public class ValhallaCustomerTravelTimeClient {
  private static final int MAX_CACHE_ENTRIES = 512;
  private static final int MAX_MATRIX_POINTS = 128;
  private static final int MAX_SINGLE_REQUEST_POINTS = 32;
  private static final int MATRIX_BLOCK_POINTS = 32;
  private final ObjectMapper json;
  private final HttpClient httpClient;
  private final int maxCacheEntries;
  private final LongSupplier nanoTime;
  private final Map<MatrixCacheKey, MatrixCacheEntry> cache =
      new LinkedHashMap<>(16, 0.75f, true);
  private final Map<MatrixCacheKey, CompletableFuture<CustomerTravelTimeMatrix>> pendingRequests =
      new ConcurrentHashMap<>();

  /** Creates the process-lifetime adapter and its reusable HTTP connection pool. */
  @Autowired
  public ValhallaCustomerTravelTimeClient(
      ObjectMapper json, CustomerDeliveryProperties properties) {
    this(json, buildHttpClient(properties), MAX_CACHE_ENTRIES, System::nanoTime);
  }

  /** Creates an adapter with deterministic cache bounds and time for focused routing tests. */
  static ValhallaCustomerTravelTimeClient createForTesting(
      ObjectMapper json, HttpClient httpClient, int maxCacheEntries, LongSupplier nanoTime) {
    return new ValhallaCustomerTravelTimeClient(json, httpClient, maxCacheEntries, nanoTime);
  }

  private ValhallaCustomerTravelTimeClient(
      ObjectMapper json, HttpClient httpClient, int maxCacheEntries, LongSupplier nanoTime) {
    this.json = Objects.requireNonNull(json, "json");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    if (maxCacheEntries < 1) throw new IllegalArgumentException("Cache size must be positive");
    this.maxCacheEntries = maxCacheEntries;
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
  }

  /** Requests or reuses a departure-bucketed truck matrix for one exact vehicle profile. */
  public CustomerTravelTimeMatrix matrix(
      List<GeoPoint> points,
      LocalDate date,
      LocalTime departureTime,
      CustomerDeliveryProperties.Validated configuration,
      CustomerVehicleRouteProfile profile) {
    if (points == null
        || points.isEmpty()
        || points.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalArgumentException("Customer route points are invalid");
    }
    if (points.size() > MAX_MATRIX_POINTS) throw workloadLimit();
    if (configuration == null || date == null || departureTime == null || profile == null) {
      throw unavailable();
    }
    if (points.size() == 1) {
      return new CustomerTravelTimeMatrix(List.copyOf(points), List.of(List.of(0L)));
    }
    LocalTime departureBucket =
        departureTime.withMinute((departureTime.getMinute() / 15) * 15).withSecond(0).withNano(0);
    MatrixCacheKey cacheKey =
        new MatrixCacheKey(
            List.copyOf(points),
            date,
            departureBucket,
            profile.routingProfileHash(),
            configuration.valhallaBaseUrl().toString(),
            configuration.routingDataVersion());
    CustomerTravelTimeMatrix cached = cached(cacheKey);
    if (cached != null) return cached;
    CompletableFuture<CustomerTravelTimeMatrix> pending = new CompletableFuture<>();
    CompletableFuture<CustomerTravelTimeMatrix> existing =
        pendingRequests.putIfAbsent(cacheKey, pending);
    if (existing != null) return await(existing);
    try {
      cached = cached(cacheKey);
      if (cached != null) {
        pending.complete(cached);
        return cached;
      }
      CustomerTravelTimeMatrix requested =
          requestMatrix(points, date, departureBucket, configuration, profile);
      cache(cacheKey, requested, configuration.routingCacheTtl());
      pending.complete(requested);
      return requested;
    } catch (RuntimeException | Error failure) {
      pending.completeExceptionally(failure);
      throw failure;
    } finally {
      pendingRequests.remove(cacheKey, pending);
    }
  }

  /** Closes the adapter-owned connection pool after Spring stops routing work. */
  @PreDestroy
  public void close() {
    httpClient.close();
  }

  private CustomerTravelTimeMatrix requestMatrix(
      List<GeoPoint> points,
      LocalDate date,
      LocalTime departureTime,
      CustomerDeliveryProperties.Validated configuration,
      CustomerVehicleRouteProfile profile) {
    if (points.size() <= MAX_SINGLE_REQUEST_POINTS) {
      return new CustomerTravelTimeMatrix(
          List.copyOf(points),
          requestMatrixBlock(points, points, date, departureTime, configuration, profile));
    }

    long[][] complete = new long[points.size()][points.size()];
    for (int sourceStart = 0; sourceStart < points.size(); sourceStart += MATRIX_BLOCK_POINTS) {
      int sourceEnd = Math.min(points.size(), sourceStart + MATRIX_BLOCK_POINTS);
      List<GeoPoint> sources = points.subList(sourceStart, sourceEnd);
      for (int targetStart = 0; targetStart < points.size(); targetStart += MATRIX_BLOCK_POINTS) {
        int targetEnd = Math.min(points.size(), targetStart + MATRIX_BLOCK_POINTS);
        List<List<Long>> block =
            requestMatrixBlock(
                sources,
                points.subList(targetStart, targetEnd),
                date,
                departureTime,
                configuration,
                profile);
        for (int source = 0; source < block.size(); source++) {
          for (int target = 0; target < block.get(source).size(); target++) {
            complete[sourceStart + source][targetStart + target] = block.get(source).get(target);
          }
        }
      }
    }
    List<List<Long>> rows = new ArrayList<>(complete.length);
    for (long[] completeRow : complete) {
      List<Long> row = new ArrayList<>(completeRow.length);
      for (long seconds : completeRow) row.add(seconds);
      rows.add(List.copyOf(row));
    }
    return new CustomerTravelTimeMatrix(List.copyOf(points), List.copyOf(rows));
  }

  /**
   * Requests one rectangular matrix block. Both dimensions retain the original 32-point provider
   * budget while the caller can assemble a larger exact directed matrix without straight-line
   * approximations.
   */
  private List<List<Long>> requestMatrixBlock(
      List<GeoPoint> sources,
      List<GeoPoint> targets,
      LocalDate date,
      LocalTime departureTime,
      CustomerDeliveryProperties.Validated configuration,
      CustomerVehicleRouteProfile profile) {
    List<Map<String, Double>> sourceLocations = locations(sources);
    List<Map<String, Double>> targetLocations = locations(targets);
    Map<String, Object> truck = new LinkedHashMap<>();
    truck.put("height", profile.heightMeters());
    truck.put("width", profile.widthMeters());
    truck.put("length", profile.lengthMeters());
    truck.put("weight", profile.weightTons());
    truck.put("axle_load", profile.axleLoadTons());
    truck.put("axle_count", profile.axleCount());
    truck.put("hgv_no_access_penalty", 43_200);
    truck.put("ignore_restrictions", false);
    truck.put("ignore_access", false);
    truck.put("ignore_closures", false);
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("sources", sourceLocations);
    request.put("targets", targetLocations);
    request.put("costing", "truck");
    request.put("costing_options", Map.of("truck", truck));
    request.put("units", "kilometers");
    request.put("verbose", true);
    request.put("shape_format", "no_shape");
    request.put(
        "date_time",
        Map.of(
            "type",
            1,
            "value",
            date.atTime(departureTime)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))));
    try {
      String body = json.writeValueAsString(request);
      URI endpoint =
          URI.create(strip(configuration.valhallaBaseUrl().toString()) + "/sources_to_targets");
      HttpRequest httpRequest =
          HttpRequest.newBuilder(endpoint)
              .timeout(configuration.readTimeout())
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> response =
          httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) throw unavailable();
      return parse(sources.size(), targets.size(), json.readTree(response.body()));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (OrderProblemException exception) {
      throw exception;
    } catch (Exception exception) {
      throw unavailable();
    }
  }

  private static List<Map<String, Double>> locations(List<GeoPoint> points) {
    return points.stream()
        .map(point -> Map.of("lat", point.latitude(), "lon", point.longitude()))
        .toList();
  }

  private CustomerTravelTimeMatrix cached(MatrixCacheKey key) {
    long now = nanoTime.getAsLong();
    synchronized (cache) {
      MatrixCacheEntry entry = cache.get(key);
      if (entry == null) return null;
      if (entry.expiredAt(now)) {
        cache.remove(key);
        return null;
      }
      return entry.matrix();
    }
  }

  private void cache(
      MatrixCacheKey key, CustomerTravelTimeMatrix matrix, Duration routingCacheTtl) {
    long now = nanoTime.getAsLong();
    MatrixCacheEntry newEntry =
        new MatrixCacheEntry(matrix, now + routingCacheTtl.toNanos());
    synchronized (cache) {
      Iterator<MatrixCacheEntry> entries = cache.values().iterator();
      while (entries.hasNext()) {
        if (entries.next().expiredAt(now)) entries.remove();
      }
      cache.put(key, newEntry);
      while (cache.size() > maxCacheEntries) {
        Iterator<MatrixCacheKey> keys = cache.keySet().iterator();
        keys.next();
        keys.remove();
      }
    }
  }

  private static CustomerTravelTimeMatrix await(
      CompletableFuture<CustomerTravelTimeMatrix> pending) {
    try {
      return pending.get();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (ExecutionException exception) {
      if (exception.getCause() instanceof OrderProblemException problem) throw problem;
      if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
      throw unavailable();
    }
  }

  private static HttpClient buildHttpClient(CustomerDeliveryProperties properties) {
    if (properties == null) {
      throw new IllegalStateException("Customer delivery properties are required");
    }
    Duration connectTimeout = properties.connectTimeout();
    if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
      throw new IllegalStateException("connect-timeout must be positive");
    }
    return HttpClient.newBuilder().connectTimeout(connectTimeout).build();
  }

  private static List<List<Long>> parse(int sourceCount, int targetCount, JsonNode root) {
    JsonNode rows = root == null ? null : root.get("sources_to_targets");
    if (rows == null || !rows.isArray() || rows.size() != sourceCount) throw unavailable();
    List<List<Long>> result = new ArrayList<>(sourceCount);
    for (int from = 0; from < rows.size(); from++) {
      JsonNode cells = rows.get(from);
      if (cells == null || !cells.isArray() || cells.size() != targetCount) throw unavailable();
      List<Long> row = new ArrayList<>(targetCount);
      for (int to = 0; to < cells.size(); to++) {
        JsonNode time = cells.get(to).get("time");
        if (time == null || time.isNull() || !time.canConvertToLong() || time.asLong() < 0) {
          throw noSafeRoute();
        }
        row.add(time.asLong());
      }
      result.add(List.copyOf(row));
    }
    return List.copyOf(result);
  }

  private static String strip(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private static OrderProblemException noSafeRoute() {
    return new OrderProblemException(
        HttpStatus.UNPROCESSABLE_CONTENT,
        "CUSTOMER_DELIVERY_ROUTE_NOT_FOUND",
        "Безопасный грузовой маршрут до адреса не найден");
  }

  private static OrderProblemException unavailable() {
    return new OrderProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CUSTOMER_ROUTING_UNAVAILABLE",
        "Сервис расчёта маршрута временно недоступен");
  }

  private static OrderProblemException workloadLimit() {
    return new OrderProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CUSTOMER_DELIVERY_WORKLOAD_LIMIT",
        "Нагрузка склада слишком велика для онлайн-расчёта слотов; повторите запрос позже");
  }

  /** Complete graph-result identity; direction is retained by the ordered square point list. */
  private record MatrixCacheKey(
      List<GeoPoint> points,
      LocalDate date,
      LocalTime departureBucket,
      String routingProfileHash,
      String valhallaEndpoint,
      String routingDataVersion) {}

  /** One immutable matrix and its monotonic expiry deadline. */
  private record MatrixCacheEntry(CustomerTravelTimeMatrix matrix, long expiresAtNanos) {
    private boolean expiredAt(long nowNanos) {
      return nowNanos - expiresAtNanos >= 0;
    }
  }
}
