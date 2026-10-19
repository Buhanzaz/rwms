package dev.buhanzaz.rwms.auth.eventing;

import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

/**
 * Delivers durable, sanitized dead-letter metadata to the appropriate Kafka DLT destination.
 *
 * <p>This relay checks the safe body's checksum before publishing and fences completion with the
 * claim lease. It carries no original message payload, credentials, or private profile data.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class AuthSanitizedDltRelay {

    private final AuthSanitizedDltStore store;
    private final AuthOutboxProperties properties;
    private final StreamBridge streamBridge;
    private final AuthEventingMetrics metrics;

    /** Runs one bounded sanitized-DLT relay attempt on the configured schedule. */
    @Scheduled(
            fixedDelayString = "${rwms.auth.eventing.outbox.relay-delay:1s}",
            initialDelayString = "${rwms.auth.eventing.outbox.relay-initial-delay:1s}")
    public void scheduledRelay() {
        relayOne();
    }

    /**
     * Claims and delivers at most one sanitized DLT record.
     *
     * @return {@code true} only when the broker acknowledges the record and its claim is settled
     */
    public boolean relayOne() {
        var claimed = store.claim(properties.instanceId(), properties.leaseDuration());
        if (claimed.isEmpty()) {
            return false;
        }
        var claim = claimed.get();
        byte[] body = claim.safeBody().getBytes(StandardCharsets.UTF_8);
        if (!AuthEventStore.sha256(body).equals(claim.bodySha256())) {
            store.failed(claim);
            return false;
        }
        try {
            boolean acknowledged = streamBridge.send(
                    claim.destination(),
                    MessageBuilder.withPayload(body)
                            .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON)
                            .build());
            if (!acknowledged) {
                throw new IllegalStateException("Sanitized DLT broker acknowledgement missing");
            }
            boolean published = store.published(claim);
            if (published) {
                metrics.sanitizedDltPublished();
            }
            return published;
        } catch (RuntimeException exception) {
            store.failed(claim);
            return false;
        }
    }
}
