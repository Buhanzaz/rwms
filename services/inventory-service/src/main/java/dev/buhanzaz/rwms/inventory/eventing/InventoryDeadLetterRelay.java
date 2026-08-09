package dev.buhanzaz.rwms.inventory.eventing;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MimeTypeUtils;

/**
 * inventory boundary for sanitized terminal event-processing failures.
 */
@Component
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class InventoryDeadLetterRelay {
  private final JdbcTemplate jdbc;
  private final InventoryOutboxProperties properties;
  private final StreamBridge bridge;

  public InventoryDeadLetterRelay(
      JdbcTemplate jdbc, InventoryOutboxProperties properties, StreamBridge bridge) {
    this.jdbc = jdbc;
    this.properties = properties;
    this.bridge = bridge;
  }

  @Scheduled(fixedDelayString = "${rwms.inventory.eventing.outbox.relay-delay:1s}")
  public void relayOne() {
    claim(properties.instanceId(), properties.leaseDuration())
        .ifPresent(
            claim -> {
              try {
                if (!InventoryEventChecksum.sha256(claim.body()).equals(claim.hash())) {
                  failed(claim, true);
                  return;
                }
                boolean acknowledged =
                    bridge.send(
                        "rwms.inventory.dlt.v1",
                        MessageBuilder.withPayload(claim.body().getBytes(StandardCharsets.UTF_8))
                            .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON)
                            .build());
                if (!acknowledged)
                  throw new IllegalStateException("DLT acknowledgement is missing");
                published(claim);
              } catch (RuntimeException exception) {
                failed(claim, false);
              }
            });
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  Optional<Claim> claim(String owner, Duration leaseDuration) {
    UUID token = UUID.randomUUID();
    return jdbc
        .query(
            """
            with candidate as (
              select dlt_id from sanitized_dead_letter
               where (status='PENDING' and next_attempt_at<=clock_timestamp())
                  or (status='IN_FLIGHT' and lease_until<clock_timestamp())
               order by created_at,dlt_id for update skip locked limit 1
            )
            update sanitized_dead_letter letter set status='IN_FLIGHT',lease_owner=?,lease_token=?,
              lease_until=clock_timestamp()+(?*interval '1 millisecond') from candidate
             where letter.dlt_id=candidate.dlt_id
            returning letter.dlt_id,letter.safe_body::text,letter.body_sha256,
              letter.attempt_count
            """,
            (resultSet, rowNumber) ->
                new Claim(
                    resultSet.getObject("dlt_id", UUID.class),
                    resultSet.getString("safe_body"),
                    resultSet.getString("body_sha256").trim(),
                    resultSet.getInt("attempt_count"),
                    token),
            owner,
            token,
            leaseDuration.toMillis())
        .stream()
        .findFirst();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void published(Claim claim) {
    jdbc.update(
        """
        update sanitized_dead_letter set status='PUBLISHED',published_at=clock_timestamp(),
          lease_owner=null,lease_token=null,lease_until=null,last_error_code=null
         where dlt_id=? and status='IN_FLIGHT' and lease_token=?
        """,
        claim.id(),
        claim.token());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void failed(Claim claim, boolean validation) {
    int attempts = claim.attempts() + 1;
    if (validation) {
      jdbc.update(
          """
          update sanitized_dead_letter set status='FAILED',attempt_count=?,
            lease_owner=null,lease_token=null,lease_until=null,last_error_code=?
           where dlt_id=? and status='IN_FLIGHT' and lease_token=?
          """,
          attempts,
          "CHECKSUM_MISMATCH",
          claim.id(),
          claim.token());
      return;
    }
    long delaySeconds = 1L << Math.min(attempts - 1, 6);
    jdbc.update(
        """
        update sanitized_dead_letter set status='PENDING',attempt_count=?,
          next_attempt_at=clock_timestamp()+(?*interval '1 second'),
          lease_owner=null,lease_token=null,lease_until=null,last_error_code='PUBLISH_FAILED'
         where dlt_id=? and status='IN_FLIGHT' and lease_token=?
        """,
        attempts,
        delaySeconds,
        claim.id(),
        claim.token());
  }

  record Claim(UUID id, String body, String hash, int attempts, UUID token) {}
}
