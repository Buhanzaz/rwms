package dev.buhanzaz.rwms.dossier.eventing;

import java.util.Map;
import java.util.Set;

/** Canonical dossier source-topic allowlist. Unknown topics never enter the journal. */
public final class DossierSourceTopics {
  public static final String CONSUMER_GROUP = "dossier-projection-v1";

  private static final Map<String, TopicPolicy> POLICIES =
      Map.ofEntries(
          Map.entry(
              "rwms.asset.rental-item.v1",
              new TopicPolicy("asset-service", "asset", Set.of("RENTAL_ITEM"))),
          Map.entry(
              "rwms.maintenance.estimate.v1",
              new TopicPolicy("maintenance-service", "maintenance", Set.of("ESTIMATE"))),
          Map.entry(
              "rwms.maintenance.repair.v1",
              new TopicPolicy("maintenance-service", "maintenance", Set.of("REPAIR"))),
          Map.entry(
              "rwms.inventory.session.v1",
              new TopicPolicy(
                  "inventory-service", "inventory", Set.of("SESSION", "FINDING"))),
          Map.entry(
              "rwms.inventory.publication.v1",
              new TopicPolicy("inventory-service", "inventory", Set.of("PUBLICATION"))),
          Map.entry(
              "rwms.media.media.v1",
              new TopicPolicy("media-service", "media", Set.of("MEDIA"))),
          Map.entry(
              "rwms.media.cabin-photo.v1",
              new TopicPolicy("media-service", "media", Set.of("CABIN_PHOTO_LIBRARY"))),
          Map.entry(
              "rwms.logistics.return.v1",
              new TopicPolicy("logistics-service", "logistics", Set.of("RETURN"))),
          Map.entry(
              "rwms.logistics.shipment.v1",
              new TopicPolicy("logistics-service", "logistics", Set.of("SHIPMENT"))),
          Map.entry(
              "rwms.logistics.transfer.v1",
              new TopicPolicy("logistics-service", "logistics", Set.of("TRANSFER"))),
          Map.entry(
              "rwms.task-board.board-task.v1",
              new TopicPolicy("task-board-service", "task-board", Set.of("BOARD_TASK"))),
          Map.entry(
              "rwms.task-board.queue-entry.v1",
              new TopicPolicy("task-board-service", "task-board", Set.of("QUEUE_ENTRY"))));

  private DossierSourceTopics() {}

  public static TopicPolicy require(String topic) {
    TopicPolicy policy = POLICIES.get(topic);
    if (policy == null) {
      throw new DossierValidationException("SOURCE_TOPIC_REJECTED");
    }
    return policy;
  }

  public static Set<String> inputs() {
    return POLICIES.keySet();
  }

  public record TopicPolicy(String producer, String producerCode, Set<String> aggregateTypes) {}
}
