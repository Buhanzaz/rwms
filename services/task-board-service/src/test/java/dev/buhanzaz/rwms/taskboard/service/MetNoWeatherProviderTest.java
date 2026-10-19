package dev.buhanzaz.rwms.taskboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import dev.buhanzaz.rwms.taskboard.config.DriverShiftProperties;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Ensures an unconfigured/free weather dependency cannot block Driver Shift startup. */
class MetNoWeatherProviderTest {
  @Test
  void missingRequiredUserAgentReturnsExplicitUnavailableBriefing() {
    DriverShiftProperties properties =
        new DriverShiftProperties(
            true,
            LocalTime.of(6, 0),
            1500,
            new DriverShiftProperties.Weather(
                URI.create("https://api.met.no/weatherapi/locationforecast/2.0/complete"),
                "",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofMinutes(15),
                2),
            new DriverShiftProperties.Hazards(13, 10, 30));
    MetNoWeatherProvider provider =
        new MetNoWeatherProvider(
            mock(RestClient.class), properties, new WeatherHazardRules(properties));
    var result = provider.briefing(BigDecimal.valueOf(59.9), BigDecimal.valueOf(30.3));
    assertThat(result.available()).isFalse();
    assertThat(result.attribution()).isEqualTo("Данные MET Norway");
    assertThat(result.hazards()).isEmpty();
  }

  @Test
  void expiredCoordinateCacheRevalidatesWithProviderEtag() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    AtomicReference<String> conditionalEtag = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/complete",
        exchange -> {
          int request = requests.incrementAndGet();
          conditionalEtag.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
          exchange.getResponseHeaders().set("ETag", "\"forecast-v1\"");
          exchange.getResponseHeaders().set("Cache-Control", "max-age=0");
          if (request > 1) {
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
            return;
          }
          byte[] body =
              """
{"properties":{"timeseries":[{"time":"2026-08-30T09:00:00Z","data":{
  "instant":{"details":{"air_temperature":3,"wind_speed":4,"wind_speed_of_gust":7}},
  "next_1_hours":{"summary":{"symbol_code":"rain"},"details":{"precipitation_amount":1,"probability_of_precipitation":60}}
}}]}}
"""
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      DriverShiftProperties properties =
          properties(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/complete"),
              "driver-up-tests test@example.invalid");
      MetNoWeatherProvider provider =
          new MetNoWeatherProvider(
              RestClient.builder().build(), properties, new WeatherHazardRules(properties));

      var first = provider.briefing(BigDecimal.valueOf(59.9), BigDecimal.valueOf(30.3));
      var revalidated = provider.briefing(BigDecimal.valueOf(59.9001), BigDecimal.valueOf(30.3001));

      assertThat(first.available()).isTrue();
      assertThat(revalidated).isEqualTo(first);
      assertThat(requests).hasValue(2);
      assertThat(conditionalEtag).hasValue("\"forecast-v1\"");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void normalizesCurrentConditionAndDerivesHazardsAcrossTheNextTwentyFourHours()
      throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/complete",
        exchange -> {
          byte[] body =
              """
{"properties":{"timeseries":[
 {"time":"2026-08-30T09:00:00Z","data":{"instant":{"details":{"air_temperature":-10,"wind_speed":5,"wind_speed_of_gust":7,"fog_area_fraction":0}},"next_1_hours":{"summary":{"symbol_code":"clearsky_day"},"details":{"precipitation_amount":0,"probability_of_precipitation":5}}}},
 {"time":"2026-08-30T16:00:00Z","data":{"instant":{"details":{"air_temperature":2,"wind_speed":8,"wind_speed_of_gust":15,"fog_area_fraction":45}},"next_1_hours":{"summary":{"symbol_code":"heavyfreezingrain"},"details":{"precipitation_amount":12,"probability_of_precipitation":90}}}},
 {"time":"2026-08-31T09:00:00Z","data":{"instant":{"details":{"air_temperature":1,"wind_speed":4,"wind_speed_of_gust":8,"fog_area_fraction":0}},"next_1_hours":{"summary":{"symbol_code":"snowandthunder"},"details":{"precipitation_amount":1,"probability_of_precipitation":40}}}},
 {"time":"2026-08-31T10:00:00Z","data":{"instant":{"details":{"air_temperature":5,"wind_speed":4,"wind_speed_of_gust":30,"fog_area_fraction":100}},"next_1_hours":{"summary":{"symbol_code":"hail"},"details":{"precipitation_amount":30,"probability_of_precipitation":100}}}}
]}}
"""
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      DriverShiftProperties properties =
          properties(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/complete"),
              "driver-up-tests test@example.invalid");
      MetNoWeatherProvider provider =
          new MetNoWeatherProvider(
              RestClient.builder().build(), properties, new WeatherHazardRules(properties));

      var result = provider.briefing(BigDecimal.valueOf(59.9), BigDecimal.valueOf(30.3));

      assertThat(result.available()).isTrue();
      assertThat(result.currentTempC()).isEqualByComparingTo("-10");
      assertThat(result.feelsLikeC()).isEqualByComparingTo("-17.4");
      assertThat(result.minTempC()).isEqualByComparingTo("-10");
      assertThat(result.maxTempC()).isEqualByComparingTo("2");
      assertThat(result.condition()).isEqualTo("Ясно");
      assertThat(result.icon()).isEqualTo("clearsky_day");
      assertThat(result.precipitationProbabilityPercent()).isEqualTo(90);
      assertThat(result.precipitationType()).isEqualTo("FREEZING_RAIN");
      assertThat(result.windGustMetersPerSecond()).isEqualByComparingTo("15");
      assertThat(result.hazards())
          .extracting(hazard -> hazard.type())
          .containsExactly(
              "HIGH_WIND",
              "HEAVY_RAIN",
              "FREEZING_RAIN",
              "SNOW",
              "LOW_VISIBILITY",
              "THUNDERSTORM")
          .doesNotContain("HAIL");
    } finally {
      server.stop(0);
    }
  }

  private DriverShiftProperties properties(URI baseUrl, String userAgent) {
    return new DriverShiftProperties(
        true,
        LocalTime.of(6, 0),
        1500,
        new DriverShiftProperties.Weather(
            baseUrl,
            userAgent,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofMinutes(15),
            1),
        new DriverShiftProperties.Hazards(13, 10, 30));
  }
}
