package dev.buhanzaz.rwms.platform.autoconfigure;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaExtendedBindingProperties;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaProducerProperties;
import org.springframework.cloud.stream.binding.NewDestinationBindingCallback;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.context.annotation.Bean;

/** Enforces synchronous, idempotent and acknowledged Kafka producer minimums without declaring any business destination. */
@AutoConfiguration(after = RwmsKafkaAutoConfiguration.class)
@ConditionalOnClass(KafkaProducerProperties.class)
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class RwmsKafkaBinderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(NewDestinationBindingCallback.class)
    NewDestinationBindingCallback<KafkaProducerProperties> rwmsKafkaProducerDefaults() {
        return (destination, channel, producerProperties, kafkaProperties) ->
                enforceProducerMinimums(kafkaProperties);
    }

    @Bean
    static BeanPostProcessor rwmsKafkaProducerMinimumsEnforcer() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof NewDestinationBindingCallback<?> callback)) {
                    return bean;
                }
                @SuppressWarnings("unchecked")
                NewDestinationBindingCallback<KafkaProducerProperties> delegate =
                        (NewDestinationBindingCallback<KafkaProducerProperties>) callback;
                return (NewDestinationBindingCallback<KafkaProducerProperties>)
                        (destination, channel, producerProperties, kafkaProperties) -> {
                            delegate.configure(destination, channel, producerProperties, kafkaProperties);
                            enforceProducerMinimums(kafkaProperties);
                        };
            }
        };
    }

    @Bean
    static SmartInitializingSingleton rwmsKafkaDeclaredProducerMinimumsEnforcer(
            ObjectProvider<BindingServiceProperties> bindingServicePropertiesProvider,
            ObjectProvider<KafkaExtendedBindingProperties> kafkaExtendedBindingPropertiesProvider) {
        return () -> {
            BindingServiceProperties bindingServiceProperties = bindingServicePropertiesProvider.getIfAvailable();
            var kafkaExtendedBindingProperties = kafkaExtendedBindingPropertiesProvider.getIfAvailable();
            if (bindingServiceProperties == null || kafkaExtendedBindingProperties == null) {
                return;
            }

            Set<String> producerBindings = new LinkedHashSet<>(kafkaExtendedBindingProperties
                    .getBindings()
                    .keySet());
            bindingServiceProperties.getBindings().forEach((bindingName, bindingProperties) -> {
                if (bindingProperties.getProducer() != null) {
                    producerBindings.add(bindingName);
                }
            });
            addExplicitOutputBindings(producerBindings, bindingServiceProperties.getOutputBindings());

            producerBindings.forEach(bindingName -> enforceProducerMinimums(
                    kafkaExtendedBindingProperties.getExtendedProducerProperties(bindingName)));
        };
    }

    private static void addExplicitOutputBindings(Set<String> producerBindings, String outputBindings) {
        if (outputBindings == null || outputBindings.isBlank()) {
            return;
        }
        for (String bindingName : outputBindings.split(";")) {
            if (!bindingName.isBlank()) {
                producerBindings.add(bindingName.trim());
            }
        }
    }

    private static void enforceProducerMinimums(KafkaProducerProperties kafkaProperties) {
        if (kafkaProperties == null) {
            return;
        }
        kafkaProperties.setSync(true);
        kafkaProperties.setCompressionType(KafkaProducerProperties.CompressionType.zstd);
        Map<String, String> configuration = new LinkedHashMap<>(kafkaProperties.getConfiguration());
        configuration.put("acks", "all");
        configuration.put("enable.idempotence", "true");
        // RwmsKafkaOutboundEventPublisher supplies the UTF-8 aggregate key as
        // bytes. Keep the binder serializer aligned so a synchronous broker
        // acknowledgement represents a real publish rather than a local type
        // conversion failure.
        configuration.put("key.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        kafkaProperties.setConfiguration(configuration);
    }
}
