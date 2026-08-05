package dev.buhanzaz.rwms.taskboard;

import dev.buhanzaz.rwms.taskboard.service.WarehouseTimeZoneGateway;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Deterministic test double; production always uses the private warehouse-service boundary. */
@Component
@Primary
@Profile("test")
class TestWarehouseTimeZoneGateway implements WarehouseTimeZoneGateway {
  private static final TimeZoneDecision DEFAULT_DECISION =
      new TimeZoneDecision(ZoneId.of("Europe/Moscow"), Instant.MIN);

  private final Map<UUID, List<TimeZoneDecision>> decisions = new ConcurrentHashMap<>();
  private final Set<UUID> invalidated = ConcurrentHashMap.newKeySet();

  @Override
  public TimeZoneDecision timeZoneAt(UUID warehouseId, Instant at) {
    return decisionsFor(warehouseId).stream()
        .filter(value -> !value.effectiveFrom().isAfter(at))
        .max(Comparator.comparing(TimeZoneDecision::effectiveFrom))
        .orElse(DEFAULT_DECISION);
  }

  @Override
  public List<TimeZoneSegment> timeline(
      UUID warehouseId, Instant fromInclusive, Instant toExclusive) {
    if (!fromInclusive.isBefore(toExclusive)) {
      return List.of();
    }
    List<TimeZoneDecision> relevant = new ArrayList<>();
    relevant.add(timeZoneAt(warehouseId, fromInclusive));
    decisionsFor(warehouseId).stream()
        .filter(value -> value.effectiveFrom().isAfter(fromInclusive))
        .filter(value -> value.effectiveFrom().isBefore(toExclusive))
        .forEach(relevant::add);
    relevant.sort(Comparator.comparing(TimeZoneDecision::effectiveFrom));

    List<TimeZoneSegment> result = new ArrayList<>();
    for (int index = 0; index < relevant.size(); index++) {
      TimeZoneDecision current = relevant.get(index);
      Instant end =
          index + 1 < relevant.size()
              ? relevant.get(index + 1).effectiveFrom()
              : toExclusive;
      Instant start = index == 0 ? fromInclusive : current.effectiveFrom();
      if (start.isBefore(end)) {
        result.add(new TimeZoneSegment(current.timeZone(), start, end));
      }
    }
    return List.copyOf(result);
  }

  @Override
  public void invalidate(UUID warehouseId) {
    invalidated.add(warehouseId);
  }

  void setTimeline(UUID warehouseId, List<TimeZoneDecision> values) {
    List<TimeZoneDecision> normalized =
        values.stream().sorted(Comparator.comparing(TimeZoneDecision::effectiveFrom)).toList();
    if (normalized.stream()
        .map(TimeZoneDecision::effectiveFrom)
        .collect(java.util.stream.Collectors.toSet())
        .size()
        != normalized.size()) {
      throw new IllegalArgumentException("Test timezone decisions must have unique effective timestamps");
    }
    decisions.put(warehouseId, normalized);
  }

  void reset() {
    decisions.clear();
    invalidated.clear();
  }

  Set<UUID> invalidatedWarehouses() {
    return Set.copyOf(new LinkedHashSet<>(invalidated));
  }

  private List<TimeZoneDecision> decisionsFor(UUID warehouseId) {
    return decisions.getOrDefault(warehouseId, List.of(DEFAULT_DECISION));
  }
}
