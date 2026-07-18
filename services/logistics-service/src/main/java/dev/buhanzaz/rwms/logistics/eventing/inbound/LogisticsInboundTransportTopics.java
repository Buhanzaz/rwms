package dev.buhanzaz.rwms.logistics.eventing.inbound;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact source-owned topic and schema allowlist for the logistics inbox. */
public final class LogisticsInboundTransportTopics {
  public static final String RENTAL_ITEM = "rwms.asset.rental-item.v1";
  public static final String OPERATION_LEASE = "rwms.asset.operation-lease.v1";
  public static final String EQUIPMENT_ALLOCATION_HOLD = "rwms.asset.equipment-allocation-hold.v1";
  public static final String BOARD_TASK = "rwms.task-board.board-task.v1";
  public static final String MAINTENANCE_ESTIMATE = "rwms.maintenance.estimate.v1";
  public static final String MEDIA = "rwms.media.media.v1";
  public static final String SANITIZED_DLT = "rwms.logistics.inbound.v1.dlt";
  public static final String CONSUMER_GROUP = "logistics-service-inbox-v1";

  public static final List<String> INPUTS =
      List.of(
          RENTAL_ITEM,
          OPERATION_LEASE,
          EQUIPMENT_ALLOCATION_HOLD,
          BOARD_TASK,
          MAINTENANCE_ESTIMATE,
          MEDIA);

  private static final Map<String, TopicPolicy> POLICIES =
      Map.of(
          RENTAL_ITEM,
          new TopicPolicy(
              "asset-service",
              "RENTAL_ITEM",
              PayloadKind.RENTAL_ITEM,
              Set.of(
                  "asset.rental-item.created.v1",
                  "asset.rental-item.passport-changed.v1",
                  "asset.rental-item.status-changed.v1",
                  "asset.rental-item.warehouse-changed.v1",
                  "asset.rental-item.logistics-effect-applied.v1")),
          OPERATION_LEASE,
          new TopicPolicy(
              "asset-service",
              "OPERATION_LEASE",
              PayloadKind.OPERATION_LEASE,
              Set.of(
                  "asset.operation-lease.acquired.v1",
                  "asset.operation-lease.renewed.v1",
                  "asset.operation-lease.released.v1",
                  "asset.operation-lease.expired.v1")),
          EQUIPMENT_ALLOCATION_HOLD,
          new TopicPolicy(
              "asset-service",
              "EQUIPMENT_ALLOCATION_HOLD",
              PayloadKind.EQUIPMENT_ALLOCATION_HOLD,
              Set.of(
                  "asset.equipment-allocation-hold.acquired.v1",
                  "asset.equipment-allocation-hold.renewed.v1",
                  "asset.equipment-allocation-hold.committed.v1",
                  "asset.equipment-allocation-hold.released.v1",
                  "asset.equipment-allocation-hold.expired.v1")),
          BOARD_TASK,
          new TopicPolicy(
              "task-board-service",
              "BOARD_TASK",
              PayloadKind.BOARD_TASK,
              Set.of(
                  "task-board.board-task.created.v1",
                  "task-board.board-task.changed.v1",
                  "task-board.board-task.completed.v1",
                  "task-board.board-task.cancelled.v1")),
          MAINTENANCE_ESTIMATE,
          new TopicPolicy(
              "maintenance-service",
              "ESTIMATE",
              PayloadKind.MAINTENANCE_ESTIMATE,
              Set.of(
                  "maintenance.estimate.created.v1",
                  "maintenance.estimate.draft-changed.v1",
                  "maintenance.estimate.completed.v1",
                  "maintenance.estimate.amended.v1")),
          MEDIA,
          new TopicPolicy(
              "media-service",
              "MEDIA",
              PayloadKind.MEDIA,
              Set.of(
                  "media.media.uploaded.v1",
                  "media.media.ready.v1",
                  "media.media.failed.v1",
                  "media.media.rotated.v1",
                  "media.media.deleted.v1")));

  private LogisticsInboundTransportTopics() {}

  public static TopicPolicy requireInput(String topic) {
    TopicPolicy policy = POLICIES.get(topic);
    if (policy == null) {
      throw new LogisticsInboundValidationException("Unsupported logistics input topic");
    }
    return policy;
  }

  public record TopicPolicy(
      String producer,
      String aggregateType,
      PayloadKind payloadKind,
      Set<String> acceptedEventTypes) {}

  enum PayloadKind {
    RENTAL_ITEM,
    OPERATION_LEASE,
    EQUIPMENT_ALLOCATION_HOLD,
    BOARD_TASK,
    MAINTENANCE_ESTIMATE,
    MEDIA
  }
}
