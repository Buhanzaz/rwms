package dev.buhanzaz.rwms.taskboard;

import dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueBindingRequest;
import java.util.List;
import java.util.UUID;

/** Test-only inputs for creating current global queue registry fixtures. */
final class QueueFixtureModels {
  private QueueFixtureModels() {}

  record QueueFixtureRequest(
      long version,
      UUID definitionId,
      boolean active,
      boolean hidden,
      boolean collapsed,
      Integer holdingPeriodMinutes,
      Integer notificationThreshold,
      boolean notifyWhenThresholdReached,
      Integer resultPhotoMinCount,
      List<QueueBindingRequest> bindings) {}

  record QueueFixtureOrderItem(UUID queueId, long expectedVersion) {}

  record QueueFixtureOrderRequest(List<QueueFixtureOrderItem> queues) {}
}
