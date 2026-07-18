package dev.buhanzaz.rwms.dossier.eventing;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

@Component
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class DossierOutboxRelay {
  private final DossierRelayTransactions transactions;
  private final StreamBridge bridge;
  private final ObjectMapper mapper;
  private final DossierOutboundSchemaValidator schemas;

  public DossierOutboxRelay(
      DossierRelayTransactions transactions,
      StreamBridge bridge,
      ObjectMapper mapper,
      DossierOutboundSchemaValidator schemas) {
    this.transactions = transactions;
    this.bridge = bridge;
    this.mapper = mapper;
    this.schemas = schemas;
  }

  @Scheduled(fixedDelayString = "${rwms.dossier.outbox.relay-delay:1s}")
  public void relay() {
    transactions.nextOutbox().ifPresent(this::publish);
  }

  private void publish(dev.buhanzaz.rwms.dossier.domain.DossierOutboxEvent event) {
    try {
      String canonical =
          mapper
              .writer()
              .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
              .writeValueAsString(mapper.readValue(event.getCanonicalPayload(), Object.class));
      schemas.activity(canonical);
      byte[] payload = canonical.getBytes(StandardCharsets.UTF_8);
      if (!DossierEventHash.sha256(payload).equals(event.getPayloadSha256())) {
        throw new IllegalStateException("DOSSIER_OUTBOX_PAYLOAD_HASH_MISMATCH");
      }
      boolean acknowledged =
          bridge.send(
              event.getDestination(),
              MessageBuilder.withPayload(payload)
                  .setHeader(
                      KafkaHeaders.KEY,
                      event.getCabinId().toString().getBytes(StandardCharsets.UTF_8))
                  .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON)
                  .build());
      if (!acknowledged) throw new IllegalStateException("DOSSIER_OUTBOX_ACK_MISSING");
      transactions.outboxPublished(event.getEventId());
    } catch (RuntimeException exception) {
      transactions.outboxFailed(event.getEventId());
    }
  }
}
