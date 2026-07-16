package dev.buhanzaz.rwms.asset.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.ObjectMapper;

class AssetEventingConfigurationTest {
  @Test
  void registersAPayloadValidatorForEveryPublishedAssetEventType() {
    DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
    registry.registerSingleton("assetEventPayloadPolicy", new AssetEventPayloadPolicy(new ObjectMapper()));

    AssetEventingConfiguration.assetPayloadValidators().postProcessBeanDefinitionRegistry(registry);

    for (AssetEventType type : AssetEventType.values()) {
      assertThat(registry.containsBeanDefinition(beanName(type))).isTrue();
    }
  }

  private static String beanName(AssetEventType type) {
    return "assetPayloadValidator_" + type.value().replace('.', '_').replace('-', '_');
  }
}
