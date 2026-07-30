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

public final class KpiSettingsApiModels {
  private KpiSettingsApiModels() {}

  public record KpiPaletteRangeRequest(
      @Min(0) @Max(100) int fromPercent,
      @Min(0) @Max(100) int toPercent,
      @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color) {}

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

  public record ActivateKpiSettingsRequest(@Min(0) long expectedVersion) {}

  public record SaveRepairComplexityThresholdsRequest(
      @Min(0) long expectedVersion,
      @Min(1) int lightBoundaryMinutes,
      @Min(1) int mediumBoundaryMinutes,
      @Min(1) int complexBoundaryMinutes) {}

  public record RepairComplexityThresholdsDto(
      int lightBoundaryMinutes,
      int mediumBoundaryMinutes,
      int complexBoundaryMinutes) {}

  public record RepairComplexityThresholdsResponse(
      UUID warehouseId,
      long settingsVersion,
      int lightBoundaryMinutes,
      int mediumBoundaryMinutes,
      int complexBoundaryMinutes) {}

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

  public record WarehouseKpiSettingsResponse(
      UUID warehouseId,
      String timeZone,
      KpiSettingsStatus status,
      long version,
      LocalDate dataAvailableFrom,
      RepairComplexityThresholdsDto repairComplexity,
      KpiPaletteDto palette,
      KpiWorkScheduleDto activeSchedule,
      KpiWorkScheduleDto pendingSchedule) {}
}
