package dev.buhanzaz.rwms.auth.eventing;

import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthSanitizedDltPublisher {

    private final AuthSanitizedDltStore store;
    private final AuthEventingMetrics metrics;

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
