package dev.buhanzaz.rwms.platform.kafka;

/** Names the non-PII technical Kafka headers emitted by the platform publisher. */
public final class RwmsKafkaHeaders {

    public static final String ENVELOPE_VERSION = "rwms-envelope-version";
    public static final String EVENT_ID = "rwms-event-id";
    public static final String EVENT_TYPE = "rwms-event-type";
    public static final String EVENT_VERSION = "rwms-event-version";
    public static final String PRODUCER = "rwms-producer";
    public static final String AGGREGATE_TYPE = "rwms-aggregate-type";
    public static final String AGGREGATE_ID = "rwms-aggregate-id";
    public static final String AGGREGATE_VERSION = "rwms-aggregate-version";
    public static final String OCCURRED_AT = "rwms-occurred-at";
    public static final String RECORDED_AT = "rwms-recorded-at";
    public static final String CORRELATION_ID = "rwms-correlation-id";
    public static final String CAUSATION_ID = "rwms-causation-id";

    private RwmsKafkaHeaders() {}
}
