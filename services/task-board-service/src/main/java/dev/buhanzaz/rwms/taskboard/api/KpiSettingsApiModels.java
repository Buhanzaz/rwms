package dev.buhanzaz.rwms.taskboard.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.buhanzaz.rwms.taskboard.domain.KpiSettingsStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Transport models for versioned per-warehouse KPI appearance and work-schedule configuration.
 *
 * <p>Schedules are expressed in the warehouse's authoritative timezone and become active only
 * through the dedicated idempotent activation command.
 */
public final class KpiSettingsApiModels {
  private KpiSettingsApiModels() {}

  public record KpiPaletteRangeRequest(
      @Min(0) @Max(100) int fromPercent,
      @Min(0) @Max(100) int toPercent,
      @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color) {}

  /**
   * Version-fenced palette replacement with contiguous percentage ranges and an overdue color.
   *
   * @param expectedVersion observed settings version
   * @param ranges ordered non-overlapping percentage ranges
   * @param overdueColor hexadecimal color used for overdue work
   */
  public record SaveKpiPaletteRequest(
      @Min(0) long expectedVersion,
      @NotEmpty @Size(max = 6) List<@Valid KpiPaletteRangeRequest> ranges,
      @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String overdueColor) {
    public SaveKpiPaletteRequest {
      ranges = ranges == null ? List.of() : List.copyOf(ranges);
    }
  }

  public record KpiWorkBreakRequest(
      @NotNull @JsonFormat(pattern = "HH:mm") LocalTime start,
      @NotNull @JsonFormat(pattern = "HH:mm") LocalTime end) {}

  /**
   * Version-fenced current-day or future-effective warehouse work schedule.
   *
   * @param expectedVersion observed settings version
   * @param effectiveFrom warehouse-local calendar date when the schedule takes effect; the current
   *     date applies to the whole local day after explicit activation
   * @param shiftStart local working-shift start
   * @param shiftEnd local working-shift end
   * @param daysOff ISO weekday numbers excluded from work time
   * @param breaks local break intervals within the shift
   */
  public record SaveWorkScheduleRequest(
      @Min(0) long expectedVersion,
      @NotNull LocalDate effectiveFrom,
      @NotNull @JsonFormat(pattern = "HH:mm") LocalTime shiftStart,
      @NotNull @JsonFormat(pattern = "HH:mm") LocalTime shiftEnd,
      @Size(max = 7) List<@NotNull @Min(1) @Max(7) Integer> daysOff,
      @Size(max = 20) List<@Valid KpiWorkBreakRequest> breaks) {
    public SaveWorkScheduleRequest {
      daysOff = daysOff == null ? List.of() : List.copyOf(daysOff);
      breaks = breaks == null ? List.of() : List.copyOf(breaks);
    }
  }

  /**
   * Version fence for the idempotent pending-schedule activation command.
   *
   * @param expectedVersion observed settings version
   */
  public record ActivateKpiSettingsRequest(@Min(0) long expectedVersion) {}

  public record KpiPaletteRangeDto(int fromPercent, int toPercent, String color) {}

  public record KpiPaletteDto(
      long version, List<KpiPaletteRangeDto> ranges, String overdueColor) {}

  public record KpiWorkBreakDto(
      @JsonFormat(pattern = "HH:mm") LocalTime start,
      @JsonFormat(pattern = "HH:mm") LocalTime end) {}

  public record KpiWorkScheduleDto(
      UUID id,
      long version,
      LocalDate effectiveFrom,
      @JsonFormat(pattern = "HH:mm") LocalTime shiftStart,
      @JsonFormat(pattern = "HH:mm") LocalTime shiftEnd,
      List<Integer> daysOff,
      List<KpiWorkBreakDto> breaks) {}

  /**
   * Active and pending KPI configuration returned for one warehouse.
   *
   * @param warehouseId warehouse that owns the settings
   * @param timeZone authoritative warehouse IANA timezone
   * @param status settings lifecycle state
   * @param version current settings version
   * @param dataAvailableFrom earliest date with KPI data
   * @param palette current display palette
   * @param activeSchedule active working-time schedule, if configured
   * @param pendingSchedule current-day draft or next future-effective schedule, if configured
   */
  public record WarehouseKpiSettingsResponse(
      UUID warehouseId,
      String timeZone,
      KpiSettingsStatus status,
      long version,
      LocalDate dataAvailableFrom,
      KpiPaletteDto palette,
      KpiWorkScheduleDto activeSchedule,
      KpiWorkScheduleDto pendingSchedule) {}
}
