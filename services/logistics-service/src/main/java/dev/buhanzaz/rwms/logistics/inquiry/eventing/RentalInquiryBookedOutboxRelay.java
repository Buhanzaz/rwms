package dev.buhanzaz.rwms.logistics.inquiry.eventing;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MimeTypeUtils;

@Component
@RequiredArgsConstructor
public class RentalInquiryBookedOutboxRelay {
  private static final String DESTINATION = "rwms.logistics.rental-inquiry.events.v1";

  private final JdbcTemplate jdbc;
  private final StreamBridge streamBridge;

  @Value("${rwms.logistics.rental-inquiry.outbox-enabled:true}")
  private boolean enabled;

  @Scheduled(
      fixedDelayString = "${rwms.logistics.rental-inquiry.outbox-delay:1s}",
      initialDelayString = "${rwms.logistics.rental-inquiry.outbox-initial-delay:1s}")
  @Transactional
  public void relay() {
    if (!enabled) return;
    List<Row> rows =
          jdbc.query(
            """
            select event_id,conversation_id,payload::text
            from rental_inquiry_outbox
            where status='PENDING' and next_attempt_at <= clock_timestamp()
            order by created_at,event_id
            for update skip locked
            limit 50
            """,
            (result, row) ->
                new Row(
                    result.getObject("event_id", UUID.class),
                    result.getObject("conversation_id", UUID.class),
                    result.getString("payload")));
    for (Row row : rows) {
      boolean sent =
          streamBridge.send(
              DESTINATION,
              MessageBuilder.withPayload(row.payload().getBytes(StandardCharsets.UTF_8))
                  .setHeader(
                      KafkaHeaders.KEY,
                      row.conversationId().toString().getBytes(StandardCharsets.UTF_8))
                  .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON)
                  .build());
      if (sent) {
        jdbc.update(
            """
            update rental_inquiry_outbox
            set status='PUBLISHED',published_at=clock_timestamp()
            where event_id=?
            """,
            row.eventId());
      } else {
        jdbc.update(
            """
            update rental_inquiry_outbox
            set attempt_count=attempt_count+1,
                next_attempt_at=clock_timestamp() + interval '5 seconds'
            where event_id=?
            """,
            row.eventId());
      }
    }
  }

  private record Row(UUID eventId, UUID conversationId, String payload) {}
}
