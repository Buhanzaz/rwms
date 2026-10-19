package dev.buhanzaz.rwms.asset.integration.media;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Selects enabled or fail-closed asset media clients from one service-credential configuration. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaAssetImportProperties.class)
class MediaAssetImportConfiguration {
  @Bean
  @ConditionalOnProperty(
      prefix = "rwms.asset.media-import",
      name = "enabled",
      havingValue = "true")
  MediaAssetImportClient oauthMediaAssetImportClient(
      MediaAssetImportProperties properties, ObjectMapper mapper) {
    MediaAssetImportProperties.Validated validated =
        properties.requireEnabledConfiguration();
    return new OAuthMediaAssetImportClient(
        validated,
        mapper,
        OAuthMediaAssetImportClient.httpClient(validated.connectTimeout()));
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "rwms.asset.media-import",
      name = "enabled",
      havingValue = "true")
  MediaCabinCreationSnapshotClient oauthMediaCabinCreationSnapshotClient(
      MediaAssetImportProperties properties, ObjectMapper mapper) {
    MediaAssetImportProperties.Validated validated =
        properties.requireEnabledConfiguration();
    return new OAuthMediaCabinCreationSnapshotClient(
        validated,
        mapper,
        OAuthMediaCabinCreationSnapshotClient.httpClient(
            validated.connectTimeout()));
  }

  @Bean
  @ConditionalOnMissingBean(MediaAssetImportClient.class)
  MediaAssetImportClient disabledMediaAssetImportClient() {
    return new DisabledMediaAssetImportClient();
  }

  @Bean
  @ConditionalOnMissingBean(MediaCabinCreationSnapshotClient.class)
  MediaCabinCreationSnapshotClient disabledMediaCabinCreationSnapshotClient() {
    return new DisabledMediaCabinCreationSnapshotClient();
  }
}
