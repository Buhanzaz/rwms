package dev.buhanzaz.rwms.assistant.eventing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "rwms.assistant.kafka",
    name = "enabled",
    havingValue = "true")
public class RentalInquiryBookedKafkaConsumer {
  private final RentalInquiryBookedEventParser parser;
  private final RentalInquiryArchiveService archive;

  public RentalInquiryBookedKafkaConsumer(
      RentalInquiryBookedEventParser parser, RentalInquiryArchiveService archive) {
    this.parser = parser;
    this.archive = archive;
  }

  @KafkaListener(
      topics = "${rwms.assistant.kafka.rental-inquiry-topic}",
      groupId = "assistant-service-rental-inquiry-v1")
  public void consume(String rawEvent) {
    archive.archive(parser.parse(rawEvent));
  }
}
