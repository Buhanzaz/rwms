package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.api.DriverShiftApiModels.DailyWeatherBriefing;
import dev.buhanzaz.rwms.taskboard.config.DriverShiftProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Cached MET Norway Locationforecast 2.0 client with bounded retries and provider-neutral output.
 */
@Component
public class MetNoWeatherProvider implements DriverWeatherProvider {
  private static final String ATTRIBUTION = "Данные MET Norway";
  private static final Pattern MAX_AGE = Pattern.compile("(?:^|,)\\s*max-age=(\\d+)(?:,|$)");

  private final RestClient client;
  private final DriverShiftProperties.Weather properties;
  private final WeatherHazardRules hazards;
  private final Map<CoordinateKey, CachedBriefing> cache = new LinkedHashMap<>(64, 0.75f, true);

  public MetNoWeatherProvider(
      @Qualifier("metNoRestClient") RestClient client,
      DriverShiftProperties properties,
      WeatherHazardRules hazards) {
    this.client = client;
    this.properties = properties.weather();
    this.hazards = hazards;
  }

  @Override
  public DailyWeatherBriefing briefing(BigDecimal latitude, BigDecimal longitude) {
    if (latitude == null
        || longitude == null
        || properties.userAgent() == null
        || properties.userAgent().isBlank()) {
      return unavailable();
    }
    CoordinateKey key =
        new CoordinateKey(
            latitude.setScale(3, RoundingMode.HALF_UP),
            longitude.setScale(3, RoundingMode.HALF_UP));
    CachedBriefing stale;
    synchronized (cache) {
      stale = cache.get(key);
      if (stale != null && stale.expiresAt().isAfter(Instant.now())) {
        return stale.value();
      }
    }
    for (int attempt = 1; attempt <= Math.max(1, properties.maxAttempts()); attempt++) {
      try {
        RestClient.RequestHeadersSpec<?> request =
            client
                .get()
                .uri(properties.baseUrl() + "?lat=" + key.latitude() + "&lon=" + key.longitude())
                .header(HttpHeaders.USER_AGENT, properties.userAgent());
        if (stale != null && stale.etag() != null) {
          request.header(HttpHeaders.IF_NONE_MATCH, stale.etag());
        }
        if (stale != null && stale.lastModified() != null) {
          request.header(HttpHeaders.IF_MODIFIED_SINCE, stale.lastModified());
        }
        ResponseEntity<JsonNode> response = request.retrieve().toEntity(JsonNode.class);
        if (response.getStatusCode() == HttpStatus.NOT_MODIFIED && stale != null) {
          cache(key, stale.value(), response.getHeaders(), stale);
          return stale.value();
        }
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
          throw new IllegalArgumentException("MET response was not successful");
        }
        DailyWeatherBriefing value = normalize(response.getBody());
        cache(key, value, response.getHeaders(), stale);
        return value;
      } catch (RuntimeException ignored) {
        // Informational dependency: retry within the configured bound, then remain unavailable.
      }
    }
    return unavailable();
  }

  private DailyWeatherBriefing normalize(JsonNode root) {
    JsonNode series = root == null ? null : root.path("properties").path("timeseries");
    if (series == null || !series.isArray() || series.isEmpty()) {
      throw new IllegalArgumentException("MET response has no timeseries");
    }
    JsonNode first = series.get(0);
    JsonNode details = first.path("data").path("instant").path("details");
    BigDecimal current = decimal(details, "air_temperature");
    BigDecimal min = current;
    BigDecimal max = current;
    String symbol = text(first.path("data").path("next_1_hours").path("summary"), "symbol_code");
    JsonNode next = first.path("data").path("next_1_hours").path("details");
    BigDecimal maximumHourlyRain = decimal(next, "precipitation_amount");
    Integer probability = integer(next, "probability_of_precipitation");
    BigDecimal wind = decimal(details, "wind_speed");
    BigDecimal maximumGust = decimal(details, "wind_speed_of_gust");
    BigDecimal maximumFogAreaFraction = decimal(details, "fog_area_fraction");
    Set<String> symbols = new LinkedHashSet<>();
    if (symbol != null) {
      symbols.add(symbol);
    }
    String firstTime = text(first, "time");
    if (firstTime == null) {
      throw new IllegalArgumentException("MET response has no first forecast timestamp");
    }
    OffsetDateTime horizon = OffsetDateTime.parse(firstTime).plusHours(24);
    for (JsonNode item : series) {
      String time = text(item, "time");
      if (time == null) {
        continue;
      }
      if (OffsetDateTime.parse(time).isAfter(horizon)) {
        break;
      }
      JsonNode itemDetails = item.path("data").path("instant").path("details");
      BigDecimal temperature = decimal(itemDetails, "air_temperature");
      if (temperature != null) {
        min = min == null ? temperature : min.min(temperature);
        max = max == null ? temperature : max.max(temperature);
      }
      maximumGust = maximum(maximumGust, decimal(itemDetails, "wind_speed_of_gust"));
      maximumFogAreaFraction =
          maximum(maximumFogAreaFraction, decimal(itemDetails, "fog_area_fraction"));
      JsonNode itemNext = item.path("data").path("next_1_hours");
      String itemSymbol = text(itemNext.path("summary"), "symbol_code");
      if (itemSymbol != null) {
        symbols.add(itemSymbol);
      }
      JsonNode itemNextDetails = itemNext.path("details");
      maximumHourlyRain =
          maximum(maximumHourlyRain, decimal(itemNextDetails, "precipitation_amount"));
      probability = maximum(probability, integer(itemNextDetails, "probability_of_precipitation"));
    }
    return new DailyWeatherBriefing(
        true,
        ATTRIBUTION,
        current,
        windChill(current, wind),
        min,
        max,
        condition(symbol),
        symbol,
        probability,
        precipitationType(symbols),
        wind,
        maximumGust,
        hazards.derive(symbols, maximumGust, maximumHourlyRain, maximumFogAreaFraction));
  }

  private BigDecimal maximum(BigDecimal current, BigDecimal candidate) {
    if (candidate == null) {
      return current;
    }
    return current == null ? candidate : current.max(candidate);
  }

  private Integer maximum(Integer current, Integer candidate) {
    if (candidate == null) {
      return current;
    }
    return current == null ? candidate : Math.max(current, candidate);
  }

  /**
   * Computes the Environment and Climate Change Canada standard wind-chill index only where its
   * documented domain applies: air temperature at or below 0 C and wind of at least 5 km/h.
   */
  private BigDecimal windChill(BigDecimal temperatureCelsius, BigDecimal windMetersPerSecond) {
    if (temperatureCelsius == null || windMetersPerSecond == null) {
      return null;
    }
    double temperature = temperatureCelsius.doubleValue();
    double windKilometersPerHour = windMetersPerSecond.doubleValue() * 3.6d;
    if (temperature > 0d || windKilometersPerHour < 5d) {
      return null;
    }
    double windPower = Math.pow(windKilometersPerHour, 0.16d);
    double value =
        13.12d
            + 0.6215d * temperature
            - 11.37d * windPower
            + 0.3965d * temperature * windPower;
    return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
  }

  private void cache(
      CoordinateKey key,
      DailyWeatherBriefing value,
      HttpHeaders responseHeaders,
      CachedBriefing previous) {
    synchronized (cache) {
      if (!cache.containsKey(key) && cache.size() >= 128) {
        Iterator<CoordinateKey> iterator = cache.keySet().iterator();
        if (iterator.hasNext()) {
          iterator.next();
          iterator.remove();
        }
      }
      String etag = responseHeaders.getETag();
      String lastModified = responseHeaders.getFirst(HttpHeaders.LAST_MODIFIED);
      cache.put(
          key,
          new CachedBriefing(
              value,
              cacheExpiry(responseHeaders),
              etag == null && previous != null ? previous.etag() : etag,
              lastModified == null && previous != null ? previous.lastModified() : lastModified));
    }
  }

  private Instant cacheExpiry(HttpHeaders headers) {
    Instant now = Instant.now();
    Instant configured = now.plus(properties.cacheTtl());
    long expiresAt = headers.getExpires();
    if (expiresAt > now.toEpochMilli()) {
      configured = earlier(configured, Instant.ofEpochMilli(expiresAt));
    }
    Matcher maxAge =
        MAX_AGE.matcher(
            headers.getFirst(HttpHeaders.CACHE_CONTROL) == null
                ? ""
                : headers.getFirst(HttpHeaders.CACHE_CONTROL));
    if (maxAge.find()) {
      try {
        configured = earlier(configured, now.plusSeconds(Long.parseLong(maxAge.group(1))));
      } catch (RuntimeException ignored) {
        // A malformed provider cache directive never widens the configured local TTL.
      }
    }
    return configured;
  }

  private Instant earlier(Instant first, Instant second) {
    return first.isBefore(second) ? first : second;
  }

  private DailyWeatherBriefing unavailable() {
    return new DailyWeatherBriefing(
        false, ATTRIBUTION, null, null, null, null, null, null, null, null, null, null, List.of());
  }

  private BigDecimal decimal(JsonNode node, String name) {
    JsonNode value = node.get(name);
    return value == null || !value.isNumber() ? null : value.decimalValue();
  }

  private Integer integer(JsonNode node, String name) {
    JsonNode value = node.get(name);
    return value == null || !value.isNumber() ? null : value.intValue();
  }

  private String text(JsonNode node, String name) {
    JsonNode value = node.get(name);
    return value == null || !value.isTextual() ? null : value.stringValue();
  }

  private String text(JsonNode node) {
    return node == null || !node.isTextual() ? null : node.stringValue();
  }

  private String precipitationType(Set<String> symbols) {
    if (contains(symbols, "freezingrain")) {
      return "FREEZING_RAIN";
    }
    if (contains(symbols, "sleet")) {
      return "SLEET";
    }
    if (contains(symbols, "snow")) {
      return "SNOW";
    }
    if (contains(symbols, "rain")) {
      return "RAIN";
    }
    return "NONE";
  }

  private boolean contains(Set<String> symbols, String fragment) {
    return symbols.stream()
        .map(value -> value.toLowerCase(Locale.ROOT))
        .anyMatch(value -> value.contains(fragment));
  }

  private String condition(String symbol) {
    if (symbol == null) {
      return null;
    }
    String value = symbol.toLowerCase(Locale.ROOT);
    if (value.contains("thunder") && value.contains("snow")) return "Снег с грозой";
    if (value.contains("thunder") && value.contains("sleet")) return "Мокрый снег с грозой";
    if (value.contains("thunder") && value.contains("rain")) return "Дождь с грозой";
    if (value.contains("thunder")) return "Гроза";
    if (value.contains("freezingrain")) return "Ледяной дождь";
    if (value.contains("sleet")) return "Мокрый снег";
    if (value.contains("snow")) {
      if (value.contains("heavy")) return "Сильный снег";
      if (value.contains("light")) return "Небольшой снег";
      return "Снег";
    }
    if (value.contains("rain")) {
      if (value.contains("heavy")) return "Сильный дождь";
      if (value.contains("light")) return "Небольшой дождь";
      return "Дождь";
    }
    if (value.contains("fog")) return "Туман";
    if (value.contains("clearsky")) return "Ясно";
    if (value.contains("partlycloudy")) return "Переменная облачность";
    if (value.contains("fair")) return "Малооблачно";
    if (value.contains("cloudy")) return "Облачно";
    return "Погодные условия уточняются";
  }

  /** Cache key with provider coordinates rounded to the configured sharing granularity. */
  private record CoordinateKey(BigDecimal latitude, BigDecimal longitude) {}

  /** Cached normalized briefing and its local eviction deadline. */
  private record CachedBriefing(
      DailyWeatherBriefing value, Instant expiresAt, String etag, String lastModified) {}
}
