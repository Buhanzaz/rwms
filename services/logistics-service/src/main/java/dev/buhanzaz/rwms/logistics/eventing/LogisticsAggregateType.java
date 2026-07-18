package dev.buhanzaz.rwms.logistics.eventing;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;

public enum LogisticsAggregateType {
  RETURN(LogisticsDocumentType.RETURN, "rwms.logistics.return.v1"),
  SHIPMENT(LogisticsDocumentType.SHIPMENT, "rwms.logistics.shipment.v1"),
  TRANSFER(LogisticsDocumentType.TRANSFER, "rwms.logistics.transfer.v1");

  private final LogisticsDocumentType documentType;
  private final String topic;

  LogisticsAggregateType(LogisticsDocumentType documentType, String topic) {
    this.documentType = documentType;
    this.topic = topic;
  }

  public String topic() {
    return topic;
  }

  public String sanitizedDltTopic() {
    return topic + ".logistics-service.dlt";
  }

  public static void requireTopic(String topic) {
    for (LogisticsAggregateType value : values()) {
      if (value.topic.equals(topic)) return;
    }
    throw new IllegalArgumentException("Unsupported logistics aggregate-family topic");
  }

  public static void requireSanitizedDltTopic(String topic) {
    for (LogisticsAggregateType value : values()) {
      if (value.sanitizedDltTopic().equals(topic)) return;
    }
    throw new IllegalArgumentException("Unsupported logistics sanitized DLT topic");
  }

  public static LogisticsAggregateType from(LogisticsDocumentType documentType) {
    for (LogisticsAggregateType value : values()) {
      if (value.documentType == documentType) return value;
    }
    throw new IllegalArgumentException("Unsupported logistics document type");
  }
}
