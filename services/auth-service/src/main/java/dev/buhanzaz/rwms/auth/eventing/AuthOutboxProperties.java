package dev.buhanzaz.rwms.auth.eventing;

import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized timing and ownership settings for auth's transactional-outbox relays.
 *
 * <p>These settings coordinate local claim leases and polling only. Kafka connection and binding
 * configuration remains owned by the shared platform Kafka configuration.
 */
@ConfigurationProperties("rwms.auth.eventing.outbox")
public class AuthOutboxProperties {

    private Duration relayDelay = Duration.ofSeconds(1);
    private Duration leaseDuration = Duration.ofSeconds(30);
    private String instanceId = "auth-" + UUID.randomUUID();

    /**
     * Returns the delay between scheduled relay attempts.
     *
     * @return the configured polling delay
     */
    public Duration relayDelay() {
        return relayDelay;
    }

    /**
     * Sets the delay between scheduled relay attempts.
     *
     * @param relayDelay polling delay configured by Spring Boot
     */
    public void setRelayDelay(Duration relayDelay) {
        this.relayDelay = relayDelay;
    }

    /**
     * Returns how long a claimed outbox or sanitized-DLT record is leased to one relay instance.
     *
     * @return the configured claim lease duration
     */
    public Duration leaseDuration() {
        return leaseDuration;
    }

    /**
     * Sets the duration after which an uncompleted claim may be recovered by another relay.
     *
     * @param leaseDuration claim lease duration configured by Spring Boot
     */
    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    /**
     * Returns the identity written into record leases by this application instance.
     *
     * @return stable instance identifier for the running process
     */
    public String instanceId() {
        return instanceId;
    }

    /**
     * Sets the local relay-owner identity.
     *
     * @param instanceId instance identifier configured by Spring Boot
     */
    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }
}
