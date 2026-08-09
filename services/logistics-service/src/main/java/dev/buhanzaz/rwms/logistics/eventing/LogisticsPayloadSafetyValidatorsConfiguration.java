package dev.buhanzaz.rwms.logistics.eventing;

import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaPayloadSafetyValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers payload validators that fence invalid logistics event envelopes before transport.
 */
@Configuration(proxyBeanMethods = false)
public class LogisticsPayloadSafetyValidatorsConfiguration {
  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsReturnCreatedPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.RETURN_CREATED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsReturnRegistrationStartedPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.RETURN_REGISTRATION_STARTED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsReturnInspectionRequiredPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.RETURN_INSPECTION_REQUIRED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsReturnAcceptanceStartedPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.RETURN_ACCEPTANCE_STARTED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsReturnAcceptedPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.RETURN_ACCEPTED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsReturnEstimateStartedPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.RETURN_ESTIMATE_STARTED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsReturnEstimateRequestedPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.RETURN_ESTIMATE_REQUESTED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsReturnConflictPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.RETURN_CONFLICT);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsReturnReconciliationPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.RETURN_RECONCILIATION_REQUIRED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsShipmentCreatedPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.SHIPMENT_CREATED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsShipmentDraftUpdatedPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.SHIPMENT_DRAFT_UPDATED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsShipmentPreparationStartedPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.SHIPMENT_PREPARATION_STARTED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsShipmentPlannedPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.SHIPMENT_PLANNED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsShipmentConfirmationStartedPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.SHIPMENT_CONFIRMATION_STARTED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsShipmentPreparationConfirmedPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.SHIPMENT_PREPARATION_CONFIRMED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsShipmentCancellationStartedPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.SHIPMENT_CANCELLATION_STARTED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsShipmentCancelledPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.SHIPMENT_CANCELLED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsShipmentConflictPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.SHIPMENT_CONFLICT);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsShipmentReconciliationPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.SHIPMENT_RECONCILIATION_REQUIRED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsTransferCreatedPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.TRANSFER_CREATED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsTransferDepartureStartedPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.TRANSFER_DEPARTURE_STARTED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsTransferDepartedPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.TRANSFER_DEPARTED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsTransferArrivalStartedPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.TRANSFER_ARRIVAL_STARTED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsTransferLineArrivedPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.TRANSFER_LINE_ARRIVED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsTransferCompletedPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.TRANSFER_COMPLETED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsTransferCancelledPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.TRANSFER_CANCELLED);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsTransferConflictPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.TRANSFER_CONFLICT);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator logisticsTransferReconciliationPayloadSafetyValidator() {
    return new LogisticsPayloadSafetyValidator(LogisticsEventType.TRANSFER_RECONCILIATION_REQUIRED);
  }
}
