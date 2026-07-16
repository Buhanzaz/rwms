package dev.buhanzaz.rwms.taskboard.config;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(TaskBoardClientProperties.class)
public class WorkerCredentialClientConfiguration {
  @Bean
  RestClient workerCredentialRestClient(TaskBoardClientProperties properties) {
    return RestClient.builder().requestFactory(requestFactory(properties)).build();
  }

  @Bean
  OAuth2AuthorizedClientManager taskBoardAuthorizedClientManager(
      ClientRegistrationRepository registrations,
      OAuth2AuthorizedClientService authorizedClients,
      TaskBoardClientProperties properties) {
    RestClient tokenRestClient =
        RestClient.builder()
            .requestFactory(requestFactory(properties))
            .configureMessageConverters(
                converters -> {
                  converters.addCustomConverter(new FormHttpMessageConverter());
                  converters.addCustomConverter(
                      new OAuth2AccessTokenResponseHttpMessageConverter());
                })
            .build();
    var tokenClient = new RestClientClientCredentialsTokenResponseClient();
    tokenClient.setRestClient(tokenRestClient);
    var provider =
        OAuth2AuthorizedClientProviderBuilder.builder()
            .clientCredentials(
                clientCredentials ->
                    clientCredentials.accessTokenResponseClient(tokenClient))
            .build();
    var manager =
        new AuthorizedClientServiceOAuth2AuthorizedClientManager(
            registrations, authorizedClients);
    manager.setAuthorizedClientProvider(provider);
    return manager;
  }

  private JdkClientHttpRequestFactory requestFactory(TaskBoardClientProperties properties) {
    HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
    var requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.readTimeout());
    return requestFactory;
  }
}
