package dev.buhanzaz.rwms.platform.kafka;

public class RwmsKafkaPublishException extends RuntimeException {

    public RwmsKafkaPublishException(String message) {
        super(message);
    }

    public RwmsKafkaPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
