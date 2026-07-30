package dev.buhanzaz.rwms.maintenance.eventing.transport;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MaintenanceTransportTopics {
  public static final String CATALOG = "rwms.maintenance.catalog-version.v1";
  public static final String ESTIMATE = "rwms.maintenance.estimate.v1";
  public static final String REPAIR = "rwms.maintenance.repair.v1";
  public static final String BOARD_TASK = "rwms.task-board.board-task.v1";
  public static final String QUEUE_ENTRY = "rwms.task-board.queue-entry.v1";
  public static final String TASK_EVIDENCE = "rwms.task-board.task-evidence.v1";
  public static final String MEDIA = "rwms.media.media.v1";
  public static final String RENTAL_ITEM = "rwms.asset.rental-item.v1";
  public static final String OPERATION_LEASE = "rwms.asset.operation-lease.v1";
  public static final String SANITIZED_DLT = "rwms.maintenance.dlt.v1";
  public static final String CONSUMER_GROUP = "maintenance-service-inbox-v1";

  public static final List<String> OUTPUTS = List.of(CATALOG, ESTIMATE, REPAIR, SANITIZED_DLT);
  public static final Set<String> INPUTS =
      Set.of(BOARD_TASK, QUEUE_ENTRY, TASK_EVIDENCE, MEDIA, RENTAL_ITEM, OPERATION_LEASE);

  private static final Map<String, TopicPolicy> POLICIES =
      Map.of(
          BOARD_TASK,
          new TopicPolicy(
              "task-board-service",
              "BOARD_TASK",
              Set.of(
                  "task-board.board-task.created.v1",
                  "task-board.board-task.changed.v1",
                  "task-board.board-task.completed.v1",
                  "task-board.board-task.cancelled.v1"),
              Set.of(
                  "task-board.board-task.completed.v1",
                  "task-board.board-task.cancelled.v1")),
          QUEUE_ENTRY,
          new TopicPolicy(
              "task-board-service",
              "QUEUE_ENTRY",
              Set.of(
                  "task-board.queue-entry.created.v1",
                  "task-board.queue-entry.changed.v1",
                  "task-board.queue-entry.taken.v1",
                  "task-board.queue-entry.paused.v1",
                  "task-board.queue-entry.resumed.v1",
                  "task-board.queue-entry.completed.v1",
                  "task-board.queue-entry.moved.v1",
                  "task-board.queue-entry.cancelled.v1",
                  "task-board.queue-entry.interrupted.v1",
                  "task-board.queue-entry.returning.v1"),
              Set.of(
                  "task-board.queue-entry.completed.v1",
                  "task-board.queue-entry.cancelled.v1")),
          TASK_EVIDENCE,
          new TopicPolicy(
              "task-board-service",
              "TASK_EVIDENCE",
              Set.of(
                  "task-board.task-evidence.ready.v1",
                  "task-board.task-evidence.review-required.v1"),
              Set.of(
                  "task-board.task-evidence.ready.v1",
                  "task-board.task-evidence.review-required.v1")),
          MEDIA,
          new TopicPolicy(
              "media-service",
              "MEDIA",
              Set.of(
                  "media.media.uploaded.v1",
                  "media.media.ready.v1",
                  "media.media.failed.v1",
                  "media.media.rotated.v1",
                  "media.media.deleted.v1"),
              Set.of(
                  "media.media.uploaded.v1",
                  "media.media.ready.v1",
                  "media.media.failed.v1",
                  "media.media.rotated.v1",
                  "media.media.deleted.v1")),
          RENTAL_ITEM,
          new TopicPolicy(
              "asset-service",
              "RENTAL_ITEM",
              Set.of(
                  "asset.rental-item.created.v1",
                  "asset.rental-item.passport-changed.v1",
                  "asset.rental-item.status-changed.v1",
                  "asset.rental-item.warehouse-changed.v1",
                  "asset.rental-item.general-comment-changed.v1",
                  "asset.rental-item.manual-note-added.v1"),
              Set.of(
                  "asset.rental-item.created.v1",
                  "asset.rental-item.passport-changed.v1",
                  "asset.rental-item.status-changed.v1",
                  "asset.rental-item.warehouse-changed.v1",
                  "asset.rental-item.general-comment-changed.v1",
                  "asset.rental-item.manual-note-added.v1")),
          OPERATION_LEASE,
          new TopicPolicy(
              "asset-service",
              "OPERATION_LEASE",
              Set.of(
                  "asset.operation-lease.acquired.v1",
                  "asset.operation-lease.renewed.v1",
                  "asset.operation-lease.released.v1",
                  "asset.operation-lease.expired.v1"),
              Set.of(
                  "asset.operation-lease.acquired.v1",
                  "asset.operation-lease.renewed.v1",
                  "asset.operation-lease.released.v1",
                  "asset.operation-lease.expired.v1")));

  private MaintenanceTransportTopics() {}

  public static TopicPolicy requireInput(String topic) {
    TopicPolicy policy = POLICIES.get(topic);
    if (policy == null) {
      throw new MaintenanceInboundValidationException("Unsupported maintenance input topic");
    }
    return policy;
  }

  public record TopicPolicy(
      String producer,
      String aggregateType,
      Set<String> acceptedEventTypes,
      Set<String> actionableEventTypes) {}
}
