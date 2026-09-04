package dev.buhanzaz.rwms.logistics.driver.mapper;

import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.DriverTaskResponse;
import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.DriverTripDetailsResponse;
import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.ExpiredTripNoticeResponse;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps driver-task persistence state to driver HTTP responses without changing workflow state. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface DriverTaskResponseMapper {
  /** Copies only the operational fields authorized for every warehouse-scoped rental manager. */
  ExpiredTripNoticeResponse toExpiredTripNotice(DriverLogisticsTask task);

  @Mapping(source = "tripDetails", target = "tripDetails")
  @Mapping(source = "task.scheduledDate", target = "scheduledDate")
  @Mapping(source = "task.comment", target = "comment")
  DriverTaskResponse toResponse(DriverLogisticsTask task, DriverTripDetailsResponse tripDetails);

  /** Maps a non-trip or internal response without an expanded trip projection. */
  default DriverTaskResponse toResponse(DriverLogisticsTask task) {
    return toResponse(task, null);
  }
}
