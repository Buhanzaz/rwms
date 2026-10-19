package dev.buhanzaz.rwms.taskboard.config;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Infrastructure beans for Driver Shift external informational providers. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DriverShiftProperties.class)
public class DriverShiftConfiguration {
  /**
   * Creates a bounded HTTP client; provider failures are converted to unavailable briefing data.
   */
  @Bean("metNoRestClient")
  RestClient metNoRestClient(DriverShiftProperties properties) {
    var weather = properties.weather();
    HttpClient http = HttpClient.newBuilder().connectTimeout(weather.connectTimeout()).build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(weather.readTimeout());
    return RestClient.builder().requestFactory(factory).build();
  }
}
