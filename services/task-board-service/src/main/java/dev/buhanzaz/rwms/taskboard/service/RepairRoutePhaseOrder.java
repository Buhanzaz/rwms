package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.domain.QueueDefinition;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * Owns the canonical phase order for ordinary repair routes.
 *
 * <p>Only maintenance-service routes are normalized. GENERAL queues with one of the six reviewed
 * normalized names are placed first in the canonical phase order; unknown legacy stages retain
 * their source order after those recognized phases. Logistics routes and routes owned by another
 * source retain their source order so this policy never invents semantics for another workflow.
 */
final class RepairRoutePhaseOrder {
  private static final String MAINTENANCE_SOURCE_CLIENT_ID = "maintenance-service";
  private static final Map<String, Integer> PHASE_BY_NORMALIZED_NAME =
      Map.of(
          "сэс и санитария", 0,
          "сварка", 1,
          "внешние работы", 2,
          "внутренние работы", 3,
          "электрика", 4,
          "сантехника", 5);
  private static final int UNKNOWN_PHASE = PHASE_BY_NORMALIZED_NAME.size();

  private RepairRoutePhaseOrder() {}

  /**
   * Returns a maintenance-owned route with canonical phases first and stable unknown-stage order
   * after them. Other source ownership and logistics routes are returned in source order.
   *
   * @param source route steps in the producer's requested order
   * @param queueExtractor resolves the physical queue used by one route step
   * @param sourceClientId owning source-service identity
   * @param <T> route-step type
   * @return an immutable route view with canonical phases and stable source-order tie-breakers
   */
  static <T> List<T> ordered(
      List<T> source, Function<T, WorkQueue> queueExtractor, String sourceClientId) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(queueExtractor, "queueExtractor");
    List<T> snapshot = List.copyOf(source);
    if (snapshot.size() < 2
        || !MAINTENANCE_SOURCE_CLIENT_ID.equals(sourceClientId)
        || snapshot.stream().anyMatch(step -> isLogisticsQueue(queueExtractor.apply(step)))) {
      return snapshot;
    }
    return IntStream.range(0, snapshot.size())
        .boxed()
        .sorted(
            Comparator.comparingInt(
                    (Integer index) -> phaseOf(queueExtractor.apply(snapshot.get(index))))
                .thenComparingInt(Integer::intValue))
        .map(snapshot::get)
        .toList();
  }

  /**
   * Orders GENERAL queue definitions with canonical phases first and preserves the source order
   * of custom definitions after them.
   *
   * @param source definitions in their current or requested presentation order
   * @return an immutable presentation order with the reviewed phase sequence enforced
   */
  static List<QueueDefinition> orderedDefinitions(List<QueueDefinition> source) {
    Objects.requireNonNull(source, "source");
    List<QueueDefinition> snapshot = List.copyOf(source);
    return IntStream.range(0, snapshot.size())
        .boxed()
        .sorted(
            Comparator.comparingInt((Integer index) -> phaseOf(snapshot.get(index)))
                .thenComparingInt(Integer::intValue))
        .map(snapshot::get)
        .toList();
  }

  /** Returns whether a physical queue is the canonical SES holding phase. */
  static boolean isSesQueue(WorkQueue queue) {
    return queue != null
        && queue.getDefinition() != null
        && queue.getPurpose() == QueuePurpose.GENERAL
        && "сэс и санитария".equals(queue.getDefinition().getNormalizedName());
  }

  private static boolean isLogisticsQueue(WorkQueue queue) {
    return queue != null
        && queue.getDefinition() != null
        && queue.getPurpose() == QueuePurpose.LOGISTICS_DRIVER;
  }

  private static int phaseOf(WorkQueue queue) {
    return phaseOf(queue == null ? null : queue.getDefinition());
  }

  private static int phaseOf(QueueDefinition definition) {
    if (definition == null || definition.getPurpose() != QueuePurpose.GENERAL) {
      return UNKNOWN_PHASE;
    }
    return PHASE_BY_NORMALIZED_NAME.getOrDefault(definition.getNormalizedName(), UNKNOWN_PHASE);
  }
}
