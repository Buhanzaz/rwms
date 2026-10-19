package dev.buhanzaz.rwms.taskboard.config;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Configures the bounded private client used only for worker profile-avatar owner proofs. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerProfileMediaProperties.class)
public class WorkerProfileMediaClientConfiguration {
  @Bean("workerProfileMediaRestClient")
  RestClient workerProfileMediaRestClient(TaskBoardClientProperties properties) {
    HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
    var requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.readTimeout());
    return RestClient.builder().requestFactory(requestFactory).build();
  }
}
