package dev.buhanzaz.rwms.logistics.eventing;

/**
 * Enumerates durable logistics event types used in the local event stream and outbox.
 */
public enum LogisticsEventType {
  RETURN_CREATED(LogisticsAggregateType.RETURN, "logistics.return.created.v1", "DRAFT"),
  RETURN_REGISTRATION_STARTED(
      LogisticsAggregateType.RETURN, "logistics.return.registration-started.v1", "REGISTERING"),
  RETURN_INSPECTION_REQUIRED(
      LogisticsAggregateType.RETURN, "logistics.return.inspection-required.v1", "INSPECTION_REQUIRED"),
  RETURN_ACCEPTANCE_STARTED(
      LogisticsAggregateType.RETURN, "logistics.return.acceptance-started.v1", "ACCEPTING"),
  RETURN_ACCEPTED(LogisticsAggregateType.RETURN, "logistics.return.accepted.v1", "ACCEPTED"),
  RETURN_ESTIMATE_STARTED(
      LogisticsAggregateType.RETURN, "logistics.return.estimate-started.v1", "ESTIMATE_PENDING"),
  RETURN_ESTIMATE_REQUESTED(
      LogisticsAggregateType.RETURN, "logistics.return.estimate-requested.v1", "ESTIMATE_REQUESTED"),
  RETURN_CONFLICT(LogisticsAggregateType.RETURN, "logistics.return.conflict.v1", "CONFLICT"),
  RETURN_RECONCILIATION_REQUIRED(
      LogisticsAggregateType.RETURN, "logistics.return.reconciliation-required.v1", "RECONCILIATION_REQUIRED"),
  SHIPMENT_CREATED(LogisticsAggregateType.SHIPMENT, "logistics.shipment.created.v1", "DRAFT"),
  SHIPMENT_DRAFT_UPDATED(
      LogisticsAggregateType.SHIPMENT, "logistics.shipment.draft-updated.v1", "DRAFT"),
  SHIPMENT_PREPARATION_STARTED(
      LogisticsAggregateType.SHIPMENT, "logistics.shipment.preparation-started.v1", "PREPARING"),
  SHIPMENT_PLANNED(
      LogisticsAggregateType.SHIPMENT, "logistics.shipment.planned.v1", "AWAITING_CONFIRMATION"),
  SHIPMENT_CONFIRMATION_STARTED(
      LogisticsAggregateType.SHIPMENT,
      "logistics.shipment.confirmation-started.v1",
      "CONFIRMING_PREPARATION"),
  SHIPMENT_PREPARATION_CONFIRMED(
      LogisticsAggregateType.SHIPMENT, "logistics.shipment.preparation-confirmed.v1", "SHIPPED"),
  SHIPMENT_CANCELLATION_STARTED(
      LogisticsAggregateType.SHIPMENT, "logistics.shipment.cancellation-started.v1", "CANCELLING"),
  SHIPMENT_CANCELLED(
      LogisticsAggregateType.SHIPMENT, "logistics.shipment.cancelled.v1", "CANCELLED"),
  SHIPMENT_CONFLICT(
      LogisticsAggregateType.SHIPMENT, "logistics.shipment.conflict.v1", "CONFLICT"),
  SHIPMENT_RECONCILIATION_REQUIRED(
      LogisticsAggregateType.SHIPMENT,
      "logistics.shipment.reconciliation-required.v1",
      "RECONCILIATION_REQUIRED"),
  TRANSFER_CREATED(LogisticsAggregateType.TRANSFER, "logistics.transfer.created.v1", "DRAFT"),
  TRANSFER_PLAN_UPDATED(
      LogisticsAggregateType.TRANSFER, "logistics.transfer.plan-updated.v1", "DRAFT"),
  TRANSFER_CONFIRMED(
      LogisticsAggregateType.TRANSFER, "logistics.transfer.confirmed.v1", "DRAFT"),
  TRANSFER_DEPARTURE_STARTED(
      LogisticsAggregateType.TRANSFER, "logistics.transfer.departure-started.v1", "DEPARTING"),
  TRANSFER_DEPARTED(
      LogisticsAggregateType.TRANSFER, "logistics.transfer.departed.v1", "IN_TRANSIT"),
  TRANSFER_ARRIVAL_STARTED(
      LogisticsAggregateType.TRANSFER, "logistics.transfer.arrival-started.v1", "ARRIVING"),
  TRANSFER_LINE_ARRIVED(
      LogisticsAggregateType.TRANSFER, "logistics.transfer.line-arrived.v1", "ARRIVING"),
  TRANSFER_COMPLETED(
      LogisticsAggregateType.TRANSFER, "logistics.transfer.completed.v1", "COMPLETED"),
  TRANSFER_CANCELLATION_STARTED(
      LogisticsAggregateType.TRANSFER,
      "logistics.transfer.cancellation-started.v1",
      "CANCELLING"),
  TRANSFER_CANCELLED(
      LogisticsAggregateType.TRANSFER, "logistics.transfer.cancelled.v1", "CANCELLED"),
  TRANSFER_CONFLICT(
      LogisticsAggregateType.TRANSFER, "logistics.transfer.conflict.v1", "CONFLICT"),
  TRANSFER_RECONCILIATION_REQUIRED(
      LogisticsAggregateType.TRANSFER,
      "logistics.transfer.reconciliation-required.v1",
      "RECONCILIATION_REQUIRED");

  private final LogisticsAggregateType aggregateType;
  private final String value;
  private final String expectedState;

  LogisticsEventType(LogisticsAggregateType aggregateType, String value, String expectedState) {
    this.aggregateType = aggregateType;
    this.value = value;
    this.expectedState = expectedState;
  }

  public String value() {
    return value;
  }

  public LogisticsAggregateType aggregateType() {
    return aggregateType;
  }

  public String expectedState() {
    return expectedState;
  }

  public static LogisticsEventType createdFor(LogisticsAggregateType aggregateType) {
    for (LogisticsEventType value : values()) {
      if (value.aggregateType == aggregateType) return value;
    }
    throw new IllegalArgumentException("Unsupported logistics aggregate type");
  }
}
