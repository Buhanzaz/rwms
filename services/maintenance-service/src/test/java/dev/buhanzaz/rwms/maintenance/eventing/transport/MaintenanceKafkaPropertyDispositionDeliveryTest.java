package dev.buhanzaz.rwms.maintenance.eventing.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.service.MaintenanceChecksum;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaOutboundEventPublisher;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.stream.binding.BindingService;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.MessageChannel;

/**
 * Proves canonical property-disposition relay validation, channel creation, and ordered binding.
 */
class MaintenanceKafkaPropertyDispositionDeliveryTest {
  @Test
  void canonicalOutputsMatchTheFiveContractChannelsInOrder() {
    assertThat(MaintenanceTransportTopics.OUTPUTS)
        .containsExactly(
            "rwms.maintenance.catalog-version.v1",
            "rwms.maintenance.estimate.v1",
            "rwms.maintenance.repair.v1",
            "rwms.maintenance.property-disposition.v1",
            "rwms.maintenance.dlt.v1");
  }

  @Test
  void propertyDispositionClaimPublishesOnlyOnItsCanonicalTopic() {
    MaintenanceKafkaOutboxStore store = mock(MaintenanceKafkaOutboxStore.class);
    MaintenanceKafkaOutboxStore.Claim claim =
        propertyDispositionClaim(MaintenanceTransportTopics.PROPERTY_DISPOSITION);
    when(store.claim(anyString(), any())).thenReturn(Optional.of(claim));
    when(store.markPublished(claim.eventId(), claim.leaseToken())).thenReturn(true);
    RwmsKafkaOutboundEventPublisher publisher = mock(RwmsKafkaOutboundEventPublisher.class);
    MaintenanceSanitizedDltPublisher deadLetters = mock(MaintenanceSanitizedDltPublisher.class);

    boolean published = relay(store, publisher, deadLetters).relayOne();

    assertThat(published).isTrue();
    verify(publisher)
        .publishSerializedV2(
            MaintenanceTransportTopics.PROPERTY_DISPOSITION,
            claim.envelopeBody().getBytes(StandardCharsets.UTF_8));
    verify(store).markPublished(claim.eventId(), claim.leaseToken());
  }

  @Test
  void propertyDispositionClaimWithWrongTopicIsQuarantinedBeforePublishing() {
    MaintenanceKafkaOutboxStore store = mock(MaintenanceKafkaOutboxStore.class);
    MaintenanceKafkaOutboxStore.Claim claim =
        propertyDispositionClaim(MaintenanceTransportTopics.REPAIR);
    when(store.claim(anyString(), any())).thenReturn(Optional.of(claim));
    RwmsKafkaOutboundEventPublisher publisher = mock(RwmsKafkaOutboundEventPublisher.class);
    MaintenanceSanitizedDltPublisher deadLetters = mock(MaintenanceSanitizedDltPublisher.class);

    boolean published = relay(store, publisher, deadLetters).relayOne();

    assertThat(published).isFalse();
    verify(store).validationFailure(claim, "TOPIC_MISMATCH");
    verify(deadLetters)
        .publishHash(claim.envelopeSha256(), "VALIDATION_REJECTED", null, null);
    verify(publisher, never()).publishSerializedV2(anyString(), any(byte[].class));
  }

  @Test
  void enabledOutputConfigurationCreatesPropertyDispositionChannel() {
    new ApplicationContextRunner()
        .withPropertyValues("rwms.platform.kafka.enabled=true")
        .withUserConfiguration(MaintenanceKafkaOutputChannelsConfiguration.class)
        .run(
            context -> {
              assertThat(context.containsBean(MaintenanceTransportTopics.PROPERTY_DISPOSITION))
                  .isTrue();
              assertThat(
                      context.getBean(
                          MaintenanceTransportTopics.PROPERTY_DISPOSITION, MessageChannel.class))
                  .isNotNull();
            });
  }

  @Test
  void outputInitializerBindsAllFiveCanonicalTopicsInOrder() {
    BindingService bindings = mock(BindingService.class);
    ApplicationContext context = mock(ApplicationContext.class);
    Map<String, MessageChannel> channels = new LinkedHashMap<>();
    MaintenanceTransportTopics.OUTPUTS.forEach(
        topic -> {
          MessageChannel channel = mock(MessageChannel.class);
          channels.put(topic, channel);
          when(context.getBean(topic, MessageChannel.class)).thenReturn(channel);
        });

    new MaintenanceKafkaOutputBindingInitializer(bindings, context)
        .afterSingletonsInstantiated();

    InOrder ordered = inOrder(bindings);
    MaintenanceTransportTopics.OUTPUTS.forEach(
        topic -> ordered.verify(bindings).bindProducer(channels.get(topic), topic));
  }

  private static MaintenanceKafkaOutboxRelay relay(
      MaintenanceKafkaOutboxStore store,
      RwmsKafkaOutboundEventPublisher publisher,
      MaintenanceSanitizedDltPublisher deadLetters) {
    return new MaintenanceKafkaOutboxRelay(
        store, new MaintenanceOutboxProperties(), publisher, deadLetters);
  }

  private static MaintenanceKafkaOutboxStore.Claim propertyDispositionClaim(String topic) {
    String body = "{}";
    return new MaintenanceKafkaOutboxStore.Claim(
        UUID.randomUUID(),
        "PROPERTY_DISPOSITION",
        UUID.randomUUID().toString(),
        3,
        "maintenance.property-disposition.approved.v1",
        topic,
        body,
        MaintenanceChecksum.sha256(body.getBytes(StandardCharsets.UTF_8)),
        0,
        UUID.randomUUID());
  }
}
