package dev.buhanzaz.rwms.platform.kafka;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds the explicit allow-list and enablement flags that constrain platform Kafka publishing. */
@ConfigurationProperties("rwms.platform.kafka")
public class RwmsKafkaProperties {

    private static final Pattern DESTINATION = Pattern.compile(
            "^rwms\\.[a-z0-9]+(?:-[a-z0-9]+)*\\.[a-z0-9]+(?:-[a-z0-9]+)*(?:\\.events)?\\.v[1-9][0-9]*$");

    private boolean enabled;
    private List<String> destinations = List.of();

    public RwmsKafkaProperties() {}

    public RwmsKafkaProperties(boolean enabled, List<String> destinations) {
        this.enabled = enabled;
        setDestinations(destinations);
        validate();
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> destinations() {
        return destinations;
    }

    public void setDestinations(List<String> destinations) {
        this.destinations = destinations == null ? List.of() : List.copyOf(destinations);
    }

    public void validate() {
        if (!enabled) {
            return;
        }
        if (destinations.isEmpty()) {
            throw new IllegalStateException("Kafka requires at least one exact destination");
        }
        Set<String> unique = new HashSet<>();
        for (String destination : destinations) {
            if (destination == null || !DESTINATION.matcher(destination).matches()) {
                throw new IllegalStateException(
                        "Kafka destination must be an exact versioned aggregate-family topic: " + destination);
            }
            if (!unique.add(destination)) {
                throw new IllegalStateException("Kafka destinations must not contain duplicates: " + destination);
            }
        }
    }

    public void requireAllowedDestination(String destination) {
        if (destination == null || !destinations.contains(destination)) {
            throw new IllegalArgumentException("Kafka destination is not configured exactly: " + destination);
        }
    }
}
