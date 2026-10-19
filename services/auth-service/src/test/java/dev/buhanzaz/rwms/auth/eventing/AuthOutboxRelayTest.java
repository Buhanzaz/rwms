package dev.buhanzaz.rwms.auth.eventing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaOutboundEventPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;

class AuthOutboxRelayTest {

    @Test
    void transientIntegrityReadFailureLeavesClaimForLeaseRecovery() {
        AuthOutboxStore store = mock(AuthOutboxStore.class);
        AuthOutboxProperties properties = new AuthOutboxProperties();
        properties.setInstanceId("relay-test");
        properties.setLeaseDuration(Duration.ofSeconds(30));
        RwmsKafkaOutboundEventPublisher publisher = mock(RwmsKafkaOutboundEventPublisher.class);
        AuthEventingMetrics metrics = mock(AuthEventingMetrics.class);
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        var claim = new AuthOutboxStore.Claim(
                UUID.randomUUID(),
                AuthAggregateType.USER_AUTHORIZATION.name(),
                UUID.randomUUID().toString(),
                0,
                AuthEventTypes.USER_CREATED,
                AuthAggregateType.USER_AUTHORIZATION.topic(),
                new String(body, StandardCharsets.UTF_8),
                AuthEventStore.sha256(body),
                0,
                UUID.randomUUID());
        when(store.claim("relay-test", Duration.ofSeconds(30))).thenReturn(Optional.of(claim));
        when(store.hasAuthoritativeEnvelope(claim))
                .thenThrow(new TransientDataAccessResourceException("database unavailable"));
        var relay = new AuthOutboxRelay(store, properties, publisher, metrics);

        assertThatThrownBy(relay::relayOne)
                .isInstanceOf(TransientDataAccessResourceException.class)
                .hasMessageContaining("database unavailable");

        verify(store, never()).quarantine(any(), anyString());
        verify(store, never()).transientFailure(any());
        verify(store, never()).validationFailure(any());
        verifyNoInteractions(publisher);
    }
}
