package dev.buhanzaz.rwms.dossier.eventing;

import dev.buhanzaz.rwms.dossier.domain.DossierSanitizedDeadLetter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
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

/** Schedules broker publication of locally committed sanitized dossier consumer failures. */
@Component
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class DossierDeadLetterRelay {
  private final DossierRelayTransactions transactions;
  private final StreamBridge bridge;
  private final ObjectMapper mapper;
  private final DossierOutboundSchemaValidator outboundSchemas;

  public DossierDeadLetterRelay(
      DossierRelayTransactions transactions,
      StreamBridge bridge,
      ObjectMapper mapper,
      DossierOutboundSchemaValidator outboundSchemas) {
    this.transactions = transactions;
    this.bridge = bridge;
    this.mapper = mapper;
    this.outboundSchemas = outboundSchemas;
  }

  @Scheduled(fixedDelayString = "${rwms.dossier.dlt.relay-delay:1s}")
  public void relay() {
    transactions.nextDeadLetter().ifPresent(this::publish);
  }

  private void publish(DossierSanitizedDeadLetter failure) {
    try {
      String canonical = canonical(failure);
      outboundSchemas.deadLetter(canonical);
      byte[] payload = canonical.getBytes(StandardCharsets.UTF_8);
      boolean acknowledged =
          bridge.send(
              failure.getDestination(),
              MessageBuilder.withPayload(payload)
                  .setHeader(
                      KafkaHeaders.KEY,
                      dltKey(failure).getBytes(StandardCharsets.UTF_8))
                  .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON)
                  .build());
      if (!acknowledged) throw new IllegalStateException("DOSSIER_DLT_ACK_MISSING");
      transactions.deadLetterPublished(failure.getId());
    } catch (RuntimeException exception) {
      transactions.deadLetterFailed(failure.getId());
    }
  }

  private String canonical(DossierSanitizedDeadLetter failure) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("failureId", failure.getId().toString());
    value.put("failureCode", failure.getFailureCode().name());
    value.put("sourceTopic", failure.getSourceTopic());
    value.put("sourcePartition", failure.getSourcePartition());
    value.put("sourceOffset", failure.getSourceOffset());
    value.put("recordKeySha256", failure.getRecordKeySha256());
    value.put("messageSha256", failure.getMessageSha256());
    value.put(
        "eventId",
        failure.getSourceEventId() == null ? null : failure.getSourceEventId().toString());
    value.put("recordedAt", failure.getFailedAt());
    return mapper
        .writer()
        .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .writeValueAsString(value);
  }

  private static String dltKey(DossierSanitizedDeadLetter failure) {
    if (failure.getSourceAggregateId() != null) {
      return failure.getSourceAggregateId().toString();
    }
    return uuid5Oid(failure.getMessageSha256()).toString();
  }

  private static java.util.UUID uuid5Oid(String name) {
    try {
      java.util.UUID namespace =
          java.util.UUID.fromString("6ba7b812-9dad-11d1-80b4-00c04fd430c8");
      java.nio.ByteBuffer source = java.nio.ByteBuffer.allocate(16);
      source.putLong(namespace.getMostSignificantBits()).putLong(namespace.getLeastSignificantBits());
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
      digest.update(source.array());
      byte[] bytes = digest.digest(name.getBytes(StandardCharsets.UTF_8));
      bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x50);
      bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
      java.nio.ByteBuffer result = java.nio.ByteBuffer.wrap(bytes);
      return new java.util.UUID(result.getLong(), result.getLong());
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-1 is unavailable", exception);
    }
  }
}
