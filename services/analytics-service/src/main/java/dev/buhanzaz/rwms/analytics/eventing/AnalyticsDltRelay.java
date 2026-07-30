package dev.buhanzaz.rwms.analytics.eventing;

import dev.buhanzaz.rwms.analytics.domain.AnalyticsSanitizedDeadLetter;
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

@Component
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class AnalyticsDltRelay {
  private final AnalyticsDltRelayTransactions transactions;
  private final StreamBridge bridge;
  private final ObjectMapper mapper;
  private final AnalyticsOutboundSchemaValidator outboundSchema;

  public AnalyticsDltRelay(
      AnalyticsDltRelayTransactions transactions,
      StreamBridge bridge,
      ObjectMapper mapper,
      AnalyticsOutboundSchemaValidator outboundSchema) {
    this.transactions = transactions;
    this.bridge = bridge;
    this.mapper = mapper;
    this.outboundSchema = outboundSchema;
  }

  @Scheduled(fixedDelayString = "${rwms.analytics.dlt.relay-delay:1s}")
  public void relay() {
    transactions.next().ifPresent(this::publish);
  }

  private void publish(AnalyticsSanitizedDeadLetter failure) {
    try {
      String canonical = canonical(failure);
      outboundSchema.deadLetter(canonical);
      byte[] payload = canonical.getBytes(StandardCharsets.UTF_8);
      boolean acknowledged =
          bridge.send(
              failure.getDestination(),
              MessageBuilder.withPayload(payload)
                  .setHeader(KafkaHeaders.KEY, key(failure).getBytes(StandardCharsets.UTF_8))
                  .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON)
                  .build());
      if (!acknowledged) throw new IllegalStateException("ANALYTICS_DLT_ACK_MISSING");
      transactions.published(failure.getId());
    } catch (RuntimeException exception) {
      transactions.failed(failure.getId());
    }
  }

  private String canonical(AnalyticsSanitizedDeadLetter failure) {
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

  private static String key(AnalyticsSanitizedDeadLetter failure) {
    return failure.getSourceAggregateId() == null
        ? failure.getId().toString()
        : failure.getSourceAggregateId().toString();
  }
}
