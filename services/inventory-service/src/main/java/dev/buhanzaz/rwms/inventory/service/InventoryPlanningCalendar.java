package dev.buhanzaz.rwms.inventory.service;

import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bounded task-board calendar evidence consumed by one inventory planning operation.
 *
 * <p>The calendar is fetched in fixed pages so the persisted fingerprint can be replayed exactly.
 * Inventory adds only its own holiday exception; task-board remains the owner of whether a date is
 * otherwise a common object working day.
 */
final class InventoryPlanningCalendar {
  private static final int PAGE_DAYS = 31;
  private static final int MAX_DAYS = 20_000;

  private final InventoryDependencyGateway dependencies;
  private final UUID warehouseId;
  private final LocalDate from;
  private final Map<LocalDate, InventoryDependencyGateway.WorkCalendarDate> dates =
      new LinkedHashMap<>();
  private final List<InventoryDependencyGateway.WorkCalendarSnapshot> pages = new ArrayList<>();
  private LocalDate through;

  InventoryPlanningCalendar(
      InventoryDependencyGateway dependencies, UUID warehouseId, LocalDate from) {
    if (dependencies == null || warehouseId == null || from == null) {
      throw new IllegalArgumentException("Planning calendar identity is incomplete");
    }
    this.dependencies = dependencies;
    this.warehouseId = warehouseId;
    this.from = from;
    loadNextPage();
  }

  boolean taskBoardWorking(LocalDate date) {
    if (date == null || date.isBefore(from)) {
      throw new IllegalArgumentException("Planning calendar date is outside the requested range");
    }
    while (through.isBefore(date)) {
      loadNextPage();
    }
    InventoryDependencyGateway.WorkCalendarDate result = dates.get(date);
    if (result == null) {
      throw new IllegalStateException("Task-board calendar page is incomplete");
    }
    return result.working();
  }

  Evidence evidence() {
    StringBuilder canonical =
        new StringBuilder("rwms:inventory:task-board-calendar:v1\n")
            .append(warehouseId)
            .append('\n')
            .append(from)
            .append('\n')
            .append(through)
            .append('\n');
    for (InventoryDependencyGateway.WorkCalendarSnapshot page : pages) {
      canonical
          .append(page.from())
          .append('|')
          .append(page.through())
          .append('|')
          .append(page.calendarFingerprint())
          .append('\n');
    }
    List<SnapshotPage> snapshotPages =
        pages.stream()
            .map(
                page ->
                    new SnapshotPage(
                        page.from(),
                        page.through(),
                        page.calendarFingerprint(),
                        page.dates().stream()
                            .map(
                                date ->
                                    new SnapshotDate(
                                        date.date(),
                                        date.working(),
                                        date.timeZone(),
                                        date.timeZoneEffectiveFrom(),
                                        date.scheduleId(),
                                        date.scheduleVersion(),
                                        date.scheduleEffectiveFrom()))
                            .toList()))
            .toList();
    return new Evidence(
        from,
        through,
        sha256(canonical.toString()),
        new Snapshot(warehouseId, from, through, snapshotPages));
  }

  static Evidence currentEvidence(
      InventoryDependencyGateway dependencies, UUID warehouseId, LocalDate from, LocalDate through) {
    if (from == null || through == null || through.isBefore(from)) {
      throw new IllegalArgumentException("Stored planning calendar evidence is invalid");
    }
    InventoryPlanningCalendar calendar = new InventoryPlanningCalendar(dependencies, warehouseId, from);
    calendar.taskBoardWorking(through);
    Evidence evidence = calendar.evidence();
    if (!through.equals(evidence.through())) {
      throw new IllegalStateException("Stored planning calendar page boundary is invalid");
    }
    return evidence;
  }

  private void loadNextPage() {
    LocalDate pageFrom = through == null ? from : through.plusDays(1);
    LocalDate maximum = from.plusDays(MAX_DAYS - 1L);
    if (pageFrom.isAfter(maximum)) {
      throw new IllegalStateException("Planning calendar has no available date");
    }
    LocalDate pageThrough = pageFrom.plusDays(PAGE_DAYS - 1L);
    if (pageThrough.isAfter(maximum)) {
      pageThrough = maximum;
    }
    InventoryDependencyGateway.WorkCalendarSnapshot page =
        dependencies.workCalendarSnapshot(warehouseId, pageFrom, pageThrough);
    if (!warehouseId.equals(page.warehouseId())
        || !pageFrom.equals(page.from())
        || !pageThrough.equals(page.through())) {
      throw InventoryException.dependency("Task-board returned mismatched work-calendar evidence");
    }
    for (InventoryDependencyGateway.WorkCalendarDate date : page.dates()) {
      if (dates.put(date.date(), date) != null) {
        throw InventoryException.dependency("Task-board returned duplicate work-calendar evidence");
      }
    }
    pages.add(page);
    through = pageThrough;
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  /** Immutable task-board calendar fence stored with one final-plan generation. */
  record Evidence(LocalDate from, LocalDate through, String fingerprint, Snapshot snapshot) {
    Evidence {
      if (from == null
          || through == null
          || through.isBefore(from)
          || fingerprint == null
          || !fingerprint.matches("^[0-9a-f]{64}$")
          || snapshot == null) {
        throw new IllegalArgumentException("Planning calendar evidence is invalid");
      }
    }
  }

  /** Persistable immutable upstream evidence, including effective schedule version and timezone. */
  record Snapshot(UUID warehouseId, LocalDate from, LocalDate through, List<SnapshotPage> pages) {}

  record SnapshotPage(
      LocalDate from,
      LocalDate through,
      String calendarFingerprint,
      List<SnapshotDate> dates) {}

  record SnapshotDate(
      LocalDate date,
      boolean working,
      String timeZone,
      java.time.OffsetDateTime timeZoneEffectiveFrom,
      UUID scheduleId,
      Long scheduleVersion,
      LocalDate scheduleEffectiveFrom) {}
}
