package dev.buhanzaz.rwms.auth.eventing;

import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaOutboundEventPublisher;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class AuthOutboxRelay {

    private final AuthOutboxStore store;
    private final AuthOutboxProperties properties;
    private final RwmsKafkaOutboundEventPublisher publisher;
    private final AuthEventingMetrics metrics;

    @Scheduled(
            fixedDelayString = "${rwms.auth.eventing.outbox.relay-delay:1s}",
            initialDelayString = "${rwms.auth.eventing.outbox.relay-initial-delay:1s}")
    public void scheduledRelay() {
        relayOne();
    }

    public boolean relayOne() {
        var claimed = store.claim(properties.instanceId(), properties.leaseDuration());
        if (claimed.isEmpty()) {
            return false;
        }
        var claim = claimed.get();
        byte[] body = claim.envelopeBody().getBytes(StandardCharsets.UTF_8);
        if (!AuthEventStore.sha256(body).equals(claim.envelopeSha256())) {
            store.quarantine(claim, "CHECKSUM_MISMATCH");
            metrics.outboxPublishFailed();
            return false;
        }
        if (!store.hasAuthoritativeEnvelope(claim)) {
            store.quarantine(claim, "AUTHORITATIVE_EVENT_MISMATCH");
            metrics.outboxPublishFailed();
            return false;
        }
        if (!store.hasValidPredecessor(claim)) {
            store.quarantine(claim, "AGGREGATE_VERSION_GAP");
            metrics.outboxPublishFailed();
            return false;
        }
        try {
            AuthAggregateType.requireTopic(claim.topic());
            publisher.publishSerializedV2(claim.topic(), body);
            boolean published = store.published(claim.eventId(), claim.leaseToken());
            if (published) {
                metrics.outboxPublished();
            } else {
                metrics.outboxPublishFailed();
            }
            return published;
        } catch (IllegalArgumentException exception) {
            store.validationFailure(claim);
            metrics.outboxPublishFailed();
            return false;
        } catch (RuntimeException exception) {
            log.warn(
                    "Auth Kafka outbox publish failed safely "
                            + "[failureType={}, rootCauseType={}, safeDetail={}]",
                    exception.getClass().getName(),
                    rootCauseType(exception),
                    safeRootCauseDetail(exception));
            store.transientFailure(claim);
            metrics.outboxPublishFailed();
            return false;
        }
    }

    private static String rootCauseType(RuntimeException exception) {
        return rootCause(exception).getClass().getName();
    }

    private static String safeRootCauseDetail(RuntimeException exception) {
        Throwable rootCause = rootCause(exception);
        if (!(rootCause instanceof ClassCastException)
                && !"org.springframework.integration.MessageDispatchingException"
                        .equals(rootCause.getClass().getName())) {
            return "redacted";
        }
        String detail = rootCause.getMessage();
        if (detail == null || detail.isBlank()) {
            return "none";
        }
        return detail.replace('\r', ' ').replace('\n', ' ').substring(0, Math.min(detail.length(), 240));
    }

    private static Throwable rootCause(RuntimeException exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
