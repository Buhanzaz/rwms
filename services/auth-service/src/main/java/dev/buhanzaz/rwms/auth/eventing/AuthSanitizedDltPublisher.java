package dev.buhanzaz.rwms.auth.eventing;

import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Records a sanitized representation of a rejected auth Kafka message for later DLT delivery.
 *
 * <p>The original bytes are reduced to a checksum. The durable record contains only a failure
 * code, checksum, and timestamp so rejected authorization traffic cannot leak into the DLT.
 */
@Component
@RequiredArgsConstructor
public class AuthSanitizedDltPublisher {

    private final AuthSanitizedDltStore store;
    private final AuthEventingMetrics metrics;

    /**
     * Queues safe rejection metadata for the aggregate family's sanitized DLT.
     *
     * @param aggregateType destination family for the rejected record
     * @param rejectedMessage source bytes used only to calculate a checksum
     * @param failureCode stable, non-sensitive rejection classification
     */
    public void publish(AuthAggregateType aggregateType, byte[] rejectedMessage, String failureCode) {
        store.enqueue(
                aggregateType.sanitizedDltTopic(),
                AuthEventStore.sha256(rejectedMessage),
                failureCode,
                Map.of(
                        "failureCode", failureCode,
                        "messageSha256", AuthEventStore.sha256(rejectedMessage),
                        "recordedAt", Instant.now().toString()));
        metrics.sanitizedDltEnqueued();
    }
}
