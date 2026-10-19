package dev.buhanzaz.rwms.logistics.mapper;

import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventPayload;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventSource;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps only a safe read projection into the external event payload. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface LogisticsEventPayloadMapper {
  @Mapping(target = "documentId", source = "document.id")
  @Mapping(target = "documentType", source = "document.documentType")
  @Mapping(target = "state", source = "document.state")
  @Mapping(target = "warehouseId", source = "document.warehouseId")
  @Mapping(target = "destinationWarehouseId", source = "document.destinationWarehouseId")
  @Mapping(target = "lineCount", source = "lineCount")
  @Mapping(target = "resultCode", source = "resultCode")
  LogisticsEventPayload toPayload(LogisticsEventSource source);
}
