package dev.buhanzaz.rwms.platform.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.stream.binder.ProducerProperties;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaBindingProperties;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaExtendedBindingProperties;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaProducerProperties;
import org.springframework.cloud.stream.binding.NewDestinationBindingCallback;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.integration.channel.DirectChannel;

class RwmsKafkaBinderAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RwmsKafkaBinderAutoConfiguration.class));

    @Test
    void remainsDisabledUnlessKafkaPlatformIsEnabled() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(NewDestinationBindingCallback.class));
    }

    @Test
    void makesDynamicKafkaPublishingSynchronousDurableIdempotentAndCompressed() {
        contextRunner
                .withPropertyValues("rwms.platform.kafka.enabled=true")
                .run(context -> {
                    @SuppressWarnings("unchecked")
                    NewDestinationBindingCallback<KafkaProducerProperties> callback =
                            context.getBean(NewDestinationBindingCallback.class);
                    KafkaProducerProperties kafkaProperties = new KafkaProducerProperties();

                    callback.configure(
                            "rwms.task-board.board-task.v1",
                            new DirectChannel(),
                            new ProducerProperties(),
                            kafkaProperties);

                    assertThat(kafkaProperties.isSync()).isTrue();
                    assertThat(kafkaProperties.getCompressionType())
                            .isEqualTo(KafkaProducerProperties.CompressionType.zstd);
                    assertThat(kafkaProperties.getConfiguration())
                            .containsEntry("acks", "all")
                            .containsEntry("enable.idempotence", "true")
                            .containsEntry(
                                    "key.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
                });
    }

    @Test
    void wrapsServiceOwnedDestinationCallbackAndRestoresMandatoryProducerMinimums() {
        AtomicBoolean serviceCallbackInvoked = new AtomicBoolean();
        NewDestinationBindingCallback<KafkaProducerProperties> serviceCallback =
                (destination, channel, producer, kafka) -> {
                    serviceCallbackInvoked.set(true);
                    kafka.setSync(false);
                    kafka.setCompressionType(KafkaProducerProperties.CompressionType.none);
                    kafka.setConfiguration(Map.of("acks", "1", "enable.idempotence", "false"));
                };

        contextRunner
                .withPropertyValues("rwms.platform.kafka.enabled=true")
                .withBean(NewDestinationBindingCallback.class, () -> serviceCallback)
                .run(context -> {
                    @SuppressWarnings("unchecked")
                    NewDestinationBindingCallback<KafkaProducerProperties> callback =
                            context.getBean(NewDestinationBindingCallback.class);
                    KafkaProducerProperties kafkaProperties = new KafkaProducerProperties();

                    callback.configure(
                            "rwms.task-board.board-task.v1",
                            new DirectChannel(),
                            new ProducerProperties(),
                            kafkaProperties);

                    assertThat(serviceCallbackInvoked).isTrue();
                    assertThat(callback).isNotSameAs(serviceCallback);
                    assertThat(kafkaProperties.isSync()).isTrue();
                    assertThat(kafkaProperties.getCompressionType())
                            .isEqualTo(KafkaProducerProperties.CompressionType.zstd);
                    assertThat(kafkaProperties.getConfiguration())
                            .containsEntry("acks", "all")
                            .containsEntry("enable.idempotence", "true");
                });
    }

    @Test
    void restoresMandatoryMinimumsForDeclaredProducerBindings() {
        BindingProperties declaredBinding = new BindingProperties();
        declaredBinding.setProducer(new ProducerProperties());
        BindingServiceProperties bindingServiceProperties = mock(BindingServiceProperties.class);
        when(bindingServiceProperties.getBindings()).thenReturn(Map.of("audit-out-0", declaredBinding));

        KafkaProducerProperties maliciousOverrides = new KafkaProducerProperties();
        maliciousOverrides.setSync(false);
        maliciousOverrides.setCompressionType(KafkaProducerProperties.CompressionType.none);
        maliciousOverrides.setConfiguration(Map.of("acks", "1", "enable.idempotence", "false"));
        KafkaBindingProperties kafkaBinding = new KafkaBindingProperties();
        kafkaBinding.setProducer(maliciousOverrides);
        KafkaExtendedBindingProperties kafkaBindings = new KafkaExtendedBindingProperties();
        kafkaBindings.setBindings(Map.of("audit-out-0", kafkaBinding));

        contextRunner
                .withPropertyValues("rwms.platform.kafka.enabled=true")
                .withBean(BindingServiceProperties.class, () -> bindingServiceProperties)
                .withBean(KafkaExtendedBindingProperties.class, () -> kafkaBindings)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    KafkaProducerProperties enforced =
                            kafkaBindings.getExtendedProducerProperties("audit-out-0");
                    assertThat(enforced.isSync()).isTrue();
                    assertThat(enforced.getCompressionType())
                            .isEqualTo(KafkaProducerProperties.CompressionType.zstd);
                    assertThat(enforced.getConfiguration())
                            .containsEntry("acks", "all")
                            .containsEntry("enable.idempotence", "true")
                            .containsEntry(
                                    "key.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
                });
    }
}
