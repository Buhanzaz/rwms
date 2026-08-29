package dev.buhanzaz.rwms.logistics.customer.routing;

import dev.buhanzaz.rwms.logistics.customer.config.CustomerDeliveryProperties;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerTravelTimeMatrix.GeoPoint;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Bounded Valhalla matrix adapter using truck costing and the same private graph as the standalone
 * logistics planner. It never falls back to straight-line or car estimates.
 */
@Component
@RequiredArgsConstructor
public class ValhallaCustomerTravelTimeClient {
  private static final int MAX_CACHE_ENTRIES = 512;
  private final ObjectMapper json;
  private final Map<MatrixCacheKey, CustomerTravelTimeMatrix> cache = new ConcurrentHashMap<>();

  /** Requests or reuses a departure-bucketed truck matrix for one exact vehicle profile. */
  public CustomerTravelTimeMatrix matrix(
      List<GeoPoint> points,
      LocalDate date,
      LocalTime departureTime,
      CustomerDeliveryProperties.Validated configuration,
      CustomerVehicleRouteProfile profile) {
    if (points == null
        || points.isEmpty()
        || points.size() > 32
        || points.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalArgumentException("Customer route points are invalid");
    }
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
            configuration.valhallaBaseUrl().toString());
    CustomerTravelTimeMatrix cached = cache.get(cacheKey);
    if (cached != null) return cached;
    CustomerTravelTimeMatrix requested =
        requestMatrix(points, date, departureBucket, configuration, profile);
    if (cache.size() >= MAX_CACHE_ENTRIES) cache.clear();
    cache.put(cacheKey, requested);
    return requested;
  }

  private CustomerTravelTimeMatrix requestMatrix(
      List<GeoPoint> points,
      LocalDate date,
      LocalTime departureTime,
      CustomerDeliveryProperties.Validated configuration,
      CustomerVehicleRouteProfile profile) {
    List<Map<String, Double>> locations =
        points.stream()
            .map(point -> Map.of("lat", point.latitude(), "lon", point.longitude()))
            .toList();
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
    request.put("sources", locations);
    request.put("targets", locations);
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
      try (HttpClient client =
          HttpClient.newBuilder().connectTimeout(configuration.connectTimeout()).build()) {
        HttpRequest httpRequest =
            HttpRequest.newBuilder(endpoint)
                .timeout(configuration.readTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response =
            client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw unavailable();
        return parse(points, json.readTree(response.body()));
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (OrderProblemException exception) {
      throw exception;
    } catch (Exception exception) {
      throw unavailable();
    }
  }

  private static CustomerTravelTimeMatrix parse(List<GeoPoint> points, JsonNode root) {
    JsonNode rows = root == null ? null : root.get("sources_to_targets");
    if (rows == null || !rows.isArray() || rows.size() != points.size()) throw unavailable();
    List<List<Long>> result = new ArrayList<>(points.size());
    for (int from = 0; from < rows.size(); from++) {
      JsonNode cells = rows.get(from);
      if (cells == null || !cells.isArray() || cells.size() != points.size()) throw unavailable();
      List<Long> row = new ArrayList<>(points.size());
      for (int to = 0; to < cells.size(); to++) {
        JsonNode time = cells.get(to).get("time");
        if (time == null || time.isNull() || !time.canConvertToLong() || time.asLong() < 0) {
          throw noSafeRoute();
        }
        row.add(time.asLong());
      }
      result.add(List.copyOf(row));
    }
    return new CustomerTravelTimeMatrix(List.copyOf(points), List.copyOf(result));
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

  /** Complete cache identity; direction is retained by the ordered square point list. */
  private record MatrixCacheKey(
      List<GeoPoint> points,
      LocalDate date,
      LocalTime departureBucket,
      String routingProfileHash,
      String routingConfigurationVersion) {}
}
