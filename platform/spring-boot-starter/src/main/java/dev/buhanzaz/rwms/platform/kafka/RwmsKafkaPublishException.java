package dev.buhanzaz.rwms.platform.kafka;

/** Signals that a broker publication was not acknowledged so the owning outbox relay can retain its pending row. */
public class RwmsKafkaPublishException extends RuntimeException {

    public RwmsKafkaPublishException(String message) {
        super(message);
    }

    public RwmsKafkaPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
