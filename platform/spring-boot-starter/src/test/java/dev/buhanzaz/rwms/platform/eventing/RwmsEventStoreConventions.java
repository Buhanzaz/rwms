package dev.buhanzaz.rwms.platform.eventing;

import java.util.Objects;
import java.util.Set;

/** Technical names only; every owning service implements its own SQL and JPA mappings. */
public final class RwmsEventStoreConventions {

    public static final int SNAPSHOT_THRESHOLD = 100;
    public static final Set<String> REQUIRED_TABLES = Set.of(
            "event_stream_head",
            "domain_event",
            "aggregate_snapshot",
            "projection_checkpoint",
            "outbox_event",
            "inbox_message",
            "consumer_aggregate_checkpoint");

    private RwmsEventStoreConventions() {}

    public static void requireExactTableSet(Set<String> tableNames) {
        Objects.requireNonNull(tableNames, "table names are required");
        if (!REQUIRED_TABLES.equals(Set.copyOf(tableNames))) {
            throw new IllegalArgumentException("schema must contain exactly the required service-local event-store tables");
        }
    }
}
