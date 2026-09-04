package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.InventoryWorkCalendarApiModels.*;

import dev.buhanzaz.rwms.taskboard.security.InventoryCalendarAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.WarehouseKpiClock;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Private, least-privilege read of task-board's effective-dated object work calendar. */
@RestController
@Validated
@RequestMapping("/api/internal/task-board/v1/inventory/warehouses/{warehouseId}/work-calendar")
public class InternalInventoryWorkCalendarController {
  private final WarehouseKpiClock calendar;
  private final InventoryCalendarAuthorizer access;

  public InternalInventoryWorkCalendarController(
      WarehouseKpiClock calendar, InventoryCalendarAuthorizer access) {
    this.calendar = calendar;
    this.access = access;
  }

  /** Returns immutable effective calendar evidence for an inclusive warehouse-local date range. */
  @GetMapping
  public WorkCalendarSnapshotResponse get(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @RequestParam @NotNull LocalDate from,
      @RequestParam @NotNull LocalDate through) {
    access.requireCalendarRead(jwt);
    WarehouseKpiClock.WorkCalendarSnapshot snapshot =
        calendar.workCalendarSnapshot(warehouseId, from, through);
    return new WorkCalendarSnapshotResponse(
        snapshot.warehouseId(),
        snapshot.from(),
        snapshot.through(),
        snapshot.calendarFingerprint(),
        snapshot.dates().stream()
            .map(
                date ->
                    new WorkCalendarDateResponse(
                        date.date(),
                        date.working(),
                        date.timeZone(),
                        date.timeZoneEffectiveFrom(),
                        date.scheduleId(),
                        date.scheduleVersion(),
                        date.scheduleEffectiveFrom()))
            .toList());
  }
}
